package com.dronebird

import android.graphics.RectF
import com.dronebird.input.GameInput
import kotlin.math.max
import kotlin.math.min

/**
 * Result of one simulation step, consumed by the view for audio cues.
 */
data class StepResult(val scoredThisTick: Int, val crashed: Boolean)

/**
 * Owns all game state and physics: the drone, scrolling speed, scoring, the dodge
 * cooldown/invincibility timers, and the READY/RUNNING/PAUSED/CRASHED state
 * machine. Pure logic — no drawing, no Android view code — so it is easy to reason
 * about and tune. All distances are derived from [density] (px per dp).
 */
class GameEngine(
    private val density: Float,
    private val score: ScoreManager
) {

    /** High-level game states. */
    enum class State { READY, RUNNING, PAUSED, CRASHED }

    var state: State = State.READY
        private set

    private val obstacles = ObstacleManager(density)

    // World dimensions, set from the surface.
    private var worldW = 0f
    private var worldH = 0f

    // Drone kinematics.
    private var droneX = 0f
    private var droneY = 0f
    private var velocityY = 0f
    private var tiltDegValue = 0f
    private var readyBobPhase = 0f

    // Dodge timers (seconds).
    private var dodgeCooldownRemaining = 0f
    private var invincibleRemaining = 0f

    // Derived px sizes.
    private val droneW = DRONE_W_DP * density
    private val droneH = DRONE_H_DP * density
    private val gravity = GRAVITY_DP * density
    private val climbAccel = CLIMB_ACCEL_DP * density
    private val thrustAccel = THRUST_ACCEL_DP * density
    private val impulseV = IMPULSE_V_DP * density
    private val maxFall = MAX_FALL_DP * density
    private val maxRise = MAX_RISE_DP * density
    private val startScroll = START_SCROLL_DP * density
    private val scrollStep = SCROLL_STEP_DP * density

    /** Last computed scroll speed (px/s), exposed for optional HUD/debug. */
    var currentScroll = 0f
        private set

    /** Accessors for the renderer. */
    val obstacleList get() = obstacles.obstacles
    val pipeWidth get() = obstacles.pipeWidth
    val worldWidth get() = worldW
    val worldHeight get() = worldH
    val tiltDeg get() = tiltDegValue
    val isInvincible get() = invincibleRemaining > 0f
    /** 0f = dodge ready, 1f = full cooldown remaining. */
    val dodgeCooldownFraction get() = (dodgeCooldownRemaining / DODGE_COOLDOWN).coerceIn(0f, 1f)

    /** Called when the surface size is known or changes. */
    fun onSurfaceSize(w: Int, h: Int) {
        worldW = w.toFloat()
        worldH = h.toFloat()
        droneX = worldW * DRONE_X_FRACTION
        if (state == State.READY) resetPositions()
    }

    /** Full reset to a fresh READY run. */
    fun reset() {
        score.reset()
        state = State.READY
        resetPositions()
    }

    /** Begins a run from READY. */
    fun startRun() {
        if (state == State.READY) state = State.RUNNING
    }

    /** Toggles pause/resume during a run. */
    fun togglePause() {
        state = when (state) {
            State.RUNNING -> State.PAUSED
            State.PAUSED -> State.RUNNING
            else -> state
        }
    }

    /** True if the point (x,y) in view space is a tap that should start/retry. */
    fun isWaiting(): Boolean = state == State.READY || state == State.CRASHED

    /**
     * Advances the simulation by [dtSecRaw] seconds using [input]. Returns a
     * [StepResult] describing scoring and crash events for this tick.
     */
    fun update(dtSecRaw: Float, input: GameInput): StepResult {
        if (worldW <= 0f || worldH <= 0f) return StepResult(0, false)
        val dt = min(dtSecRaw, MAX_DT)

        when (state) {
            State.READY -> {
                // Idle hover bob; any upward intent launches the run.
                readyBobPhase += dt * 4f
                droneY = worldH / 2f + kotlin.math.sin(readyBobPhase) * 8f * density
                val wantsLaunch = input.pollImpulse() || input.thrustHeld() ||
                    input.verticalAxis() > 0.2f
                if (wantsLaunch) {
                    obstacles.reset(worldW, worldH)
                    velocityY = -impulseV * 0.5f
                    state = State.RUNNING
                }
                return StepResult(0, false)
            }
            State.PAUSED, State.CRASHED -> return StepResult(0, false)
            State.RUNNING -> { /* fall through */ }
        }

        // --- Vertical physics ---
        velocityY += gravity * dt
        // Controller continuous climb (verticalAxis: +1 climb => reduce y).
        velocityY -= input.verticalAxis() * climbAccel * dt
        // Touch tap impulse + sustained hold.
        if (input.pollImpulse()) velocityY = -impulseV
        if (input.thrustHeld()) velocityY -= thrustAccel * dt
        velocityY = clamp(velocityY, -maxRise, maxFall)
        droneY += velocityY * dt

        // Cosmetic tilt from vertical velocity plus right-stick-Y feedback.
        val velTilt = clamp(velocityY / maxFall, -1f, 1f) * MAX_TILT_DEG
        tiltDegValue = velTilt + input.tiltAxis() * COSMETIC_TILT_DEG

        // --- Speed: base rises every 10 points, ±20% from left-stick X, dial trim ---
        val level = score.current / 10
        val base = startScroll + level * scrollStep
        val boost = 1f + SPEED_RANGE * input.speedAxis()
        val trimFactor = 1f + (input.trim() - 0.5f) * 2f * TRIM_RANGE
        currentScroll = max(startScroll * 0.5f, base * boost * trimFactor)

        // --- Dodge ---
        if (dodgeCooldownRemaining > 0f) dodgeCooldownRemaining -= dt
        if (invincibleRemaining > 0f) invincibleRemaining -= dt
        if (input.pollDodge() && dodgeCooldownRemaining <= 0f) {
            invincibleRemaining = DODGE_INVINCIBLE
            dodgeCooldownRemaining = DODGE_COOLDOWN
        }

        // --- Obstacles + scoring ---
        val scored = obstacles.update(currentScroll * dt, worldW, worldH, droneX)
        repeat(scored) { score.point() }

        // --- Collision / bounds ---
        val top = droneY - droneH / 2f
        val bottom = droneY + droneH / 2f
        var crashed = false
        if (top < 0f || bottom > worldH) {
            crashed = true // world exit always fatal, even mid-dodge
        } else if (!isInvincible && obstacles.collides(hitRect())) {
            crashed = true
        }
        if (crashed) {
            doCrash()
            return StepResult(scored, true)
        }
        return StepResult(scored, false)
    }

    /** Full drone rectangle (for drawing), centred on the current position. */
    fun droneRect(): RectF = RectF(
        droneX - droneW / 2f, droneY - droneH / 2f,
        droneX + droneW / 2f, droneY + droneH / 2f
    )

    /** Collision rectangle: 10% smaller than the sprite on each axis. */
    fun hitRect(): RectF {
        val w = droneW * 0.9f
        val h = droneH * 0.9f
        return RectF(droneX - w / 2f, droneY - h / 2f, droneX + w / 2f, droneY + h / 2f)
    }

    // ------------------------------------------------------------------

    private fun doCrash() {
        state = State.CRASHED
        velocityY = 0f
        score.commitHighScore()
    }

    private fun resetPositions() {
        droneY = if (worldH > 0f) worldH / 2f else 0f
        velocityY = 0f
        tiltDegValue = 0f
        dodgeCooldownRemaining = 0f
        invincibleRemaining = 0f
        readyBobPhase = 0f
        if (worldW > 0f) obstacles.reset(worldW, worldH)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

    private companion object {
        const val MAX_DT = 0.05f
        const val DRONE_X_FRACTION = 0.28f
        const val DRONE_W_DP = 52f
        const val DRONE_H_DP = 34f
        const val GRAVITY_DP = 1500f
        const val CLIMB_ACCEL_DP = 3200f
        const val THRUST_ACCEL_DP = 2600f
        const val IMPULSE_V_DP = 520f
        const val MAX_FALL_DP = 720f
        const val MAX_RISE_DP = 620f
        const val START_SCROLL_DP = 230f
        const val SCROLL_STEP_DP = 22f      // +22dp/s every 10 points
        const val SPEED_RANGE = 0.20f       // left-stick X -> ±20%
        const val TRIM_RANGE = 0.25f        // dial -> ±25% global trim
        const val MAX_TILT_DEG = 22f
        const val COSMETIC_TILT_DEG = 12f
        const val DODGE_INVINCIBLE = 0.4f
        const val DODGE_COOLDOWN = 5.0f
    }
}
