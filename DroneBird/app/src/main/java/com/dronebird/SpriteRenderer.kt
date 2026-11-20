package com.dronebird

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** Colour palette sourced from colors.xml and handed to the renderer. */
data class Palette(
    val skyTop: Int,
    val skyBottom: Int,
    val pipe: Int,
    val pipeCap: Int,
    val drone: Int,
    val droneAccent: Int,
    val hudText: Int,
    val hudShadow: Int,
    val rcGreen: Int,
    val crashRed: Int,
    val overlayScrim: Int,
    val buttonFill: Int,
    val buttonText: Int,
    val hudAmber: Int
)

/**
 * All drawing for the game, using only Canvas + Path (no image assets).
 *
 * The renderer also lays out and exposes the interactive button rectangles
 * (pause, retry, menu) so [GameView] can hit-test taps against them.
 */
class SpriteRenderer(private val density: Float, private val palette: Palette) {

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.pipe }
    private val pipeCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.pipeCap }
    private val dronePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.drone }
    private val droneAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.droneAccent }
    private val rotorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = palette.droneAccent
    }
    private val scorerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.hudText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textSize = 56f * density
    }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.hudText
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
        textSize = 18f * density
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.hudShadow
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textSize = 56f * density
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.rcGreen }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.buttonText
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f * density
    }
    private val scrimPaint = Paint().apply { color = palette.overlayScrim }
    private val overlayTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.crashRed
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textSize = 64f * density
    }
    private val overlayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.hudText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 24f * density
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.buttonFill }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.buttonText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 22f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.hudText }
    private val dodgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Interactive regions, laid out in [layout].
    val pauseButton = RectF()
    val retryButton = RectF()
    val menuButton = RectF()

    private var width = 0f
    private var height = 0f

    /** Recomputes the sky shader and button rectangles for a new surface size. */
    fun layout(w: Int, h: Int) {
        width = w.toFloat()
        height = h.toFloat()
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, height,
            palette.skyTop, palette.skyBottom, Shader.TileMode.CLAMP
        )
        val pad = 14f * density
        val btn = 46f * density
        pauseButton.set(width - pad - btn, pad, width - pad, pad + btn)

        val bw = 150f * density
        val bh = 56f * density
        val cx = width / 2f
        val by = height * 0.62f
        retryButton.set(cx - bw - 12f * density, by, cx - 12f * density, by + bh)
        menuButton.set(cx + 12f * density, by, cx + bw + 12f * density, by + bh)
    }

    /** Draws a full frame for the current engine state. */
    fun draw(
        canvas: Canvas,
        engine: GameEngine,
        currentScore: Int,
        highScore: Int,
        controllerConnected: Boolean
    ) {
        canvas.drawRect(0f, 0f, width, height, skyPaint)
        drawPipes(canvas, engine)
        drawDrone(canvas, engine)
        drawHud(canvas, engine, currentScore, highScore, controllerConnected)

        when (engine.state) {
            GameEngine.State.READY -> drawReady(canvas)
            GameEngine.State.PAUSED -> drawPaused(canvas)
            GameEngine.State.CRASHED -> drawCrash(canvas, currentScore, highScore)
            GameEngine.State.RUNNING -> drawPauseButton(canvas)
        }
    }

    private fun drawPipes(canvas: Canvas, engine: GameEngine) {
        val w = engine.pipeWidth
        val capH = 18f * density
        val capOver = 6f * density
        for (p in engine.obstacleList) {
            // Top pipe.
            canvas.drawRect(p.x, 0f, p.x + w, p.gapTop, pipePaint)
            canvas.drawRect(p.x - capOver, p.gapTop - capH, p.x + w + capOver, p.gapTop, pipeCapPaint)
            // Bottom pipe.
            canvas.drawRect(p.x, p.gapBottom, p.x + w, height, pipePaint)
            canvas.drawRect(p.x - capOver, p.gapBottom, p.x + w + capOver, p.gapBottom + capH, pipeCapPaint)
        }
    }

    /** Draws a stylised top-down quadcopter, rotated by the engine's tilt. */
    private fun drawDrone(canvas: Canvas, engine: GameEngine) {
        val r = engine.droneRect()
        val cx = r.centerX()
        val cy = r.centerY()
        canvas.save()
        canvas.rotate(engine.tiltDeg, cx, cy)

        // Flash while invincible (dodge active).
        if (engine.isInvincible) {
            dodgePaint.color = palette.rcGreen
            canvas.drawCircle(cx, cy, r.width() * 0.75f, dodgePaint.apply { alpha = 90 })
            dodgePaint.alpha = 255
        }

        val armLen = r.width() * 0.52f
        val rotorR = r.height() * 0.42f
        // Four arms + rotor guards.
        canvas.drawLine(cx, cy, cx - armLen, cy - armLen * 0.6f, rotorPaint)
        canvas.drawLine(cx, cy, cx + armLen, cy - armLen * 0.6f, rotorPaint)
        canvas.drawLine(cx, cy, cx - armLen, cy + armLen * 0.6f, rotorPaint)
        canvas.drawLine(cx, cy, cx + armLen, cy + armLen * 0.6f, rotorPaint)
        canvas.drawCircle(cx - armLen, cy - armLen * 0.6f, rotorR, rotorPaint)
        canvas.drawCircle(cx + armLen, cy - armLen * 0.6f, rotorR, rotorPaint)
        canvas.drawCircle(cx - armLen, cy + armLen * 0.6f, rotorR, rotorPaint)
        canvas.drawCircle(cx + armLen, cy + armLen * 0.6f, rotorR, rotorPaint)

        // Central body + a forward-facing accent so orientation reads.
        val body = RectF(cx - r.width() * 0.3f, cy - r.height() * 0.3f,
            cx + r.width() * 0.3f, cy + r.height() * 0.3f)
        canvas.drawRoundRect(body, 6f * density, 6f * density, dronePaint)
        canvas.drawCircle(cx + r.width() * 0.18f, cy, r.height() * 0.12f, droneAccentPaint)
        canvas.restore()
    }

    private fun drawHud(
        canvas: Canvas,
        engine: GameEngine,
        currentScore: Int,
        highScore: Int,
        controllerConnected: Boolean
    ) {
        // Score, top-centre, large, with a drop shadow.
        val sy = 64f * density
        canvas.drawText(currentScore.toString(), width / 2f + 2f * density, sy + 2f * density, shadowPaint)
        canvas.drawText(currentScore.toString(), width / 2f, sy, scorerPaint)

        // High score, top-right small (left of the pause button).
        canvas.drawText("HI $highScore", width - 70f * density, 30f * density, hiPaint)

        // RC badge + dodge readiness, top-left.
        if (controllerConnected) {
            val bw = 128f * density
            val bh = 26f * density
            val badge = RectF(14f * density, 14f * density, 14f * density + bw, 14f * density + bh)
            canvas.drawRoundRect(badge, bh / 2f, bh / 2f, badgePaint)
            canvas.drawText("RC CONNECTED", badge.left + 12f * density, badge.centerY() + 5f * density, badgeTextPaint)
        }
        drawDodgeIndicator(canvas, engine)
    }

    /** Small readiness pip/bar for the dodge cooldown, under the top-left badge. */
    private fun drawDodgeIndicator(canvas: Canvas, engine: GameEngine) {
        val x = 14f * density
        val y = 48f * density
        val w = 128f * density
        val h = 8f * density
        val frac = engine.dodgeCooldownFraction
        dodgePaint.color = palette.hudShadow
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, dodgePaint)
        dodgePaint.color = if (frac <= 0f) palette.rcGreen else palette.hudAmber
        val filled = w * (1f - frac)
        canvas.drawRoundRect(RectF(x, y, x + filled, y + h), h / 2f, h / 2f, dodgePaint)
    }

    private fun drawPauseButton(canvas: Canvas) {
        canvas.drawRoundRect(pauseButton, 8f * density, 8f * density, buttonPaint)
        val barW = 5f * density
        val gap = 6f * density
        val cx = pauseButton.centerX()
        val top = pauseButton.top + 12f * density
        val bot = pauseButton.bottom - 12f * density
        canvas.drawRect(cx - gap - barW, top, cx - gap, bot, iconPaint)
        canvas.drawRect(cx + gap, top, cx + gap + barW, bot, iconPaint)
    }

    private fun drawReady(canvas: Canvas) {
        canvas.drawText("TAP / PUSH UP TO FLY",
            width / 2f, height * 0.3f, overlayTextPaint)
    }

    private fun drawPaused(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width, height, scrimPaint)
        canvas.drawText("PAUSED", width / 2f, height * 0.42f, overlayTitlePaint)
        canvas.drawText("Tap the pause button to resume",
            width / 2f, height * 0.52f, overlayTextPaint)
        drawPauseButton(canvas)
    }

    private fun drawCrash(canvas: Canvas, currentScore: Int, highScore: Int) {
        canvas.drawRect(0f, 0f, width, height, scrimPaint)
        canvas.drawText("CRASHED", width / 2f, height * 0.30f, overlayTitlePaint)
        canvas.drawText("Score $currentScore    High $highScore",
            width / 2f, height * 0.44f, overlayTextPaint)
        drawTextButton(canvas, retryButton, "RETRY")
        drawTextButton(canvas, menuButton, "MENU")
    }

    private fun drawTextButton(canvas: Canvas, rect: RectF, label: String) {
        canvas.drawRoundRect(rect, 10f * density, 10f * density, buttonPaint)
        canvas.drawText(label, rect.centerX(), rect.centerY() + 8f * density, buttonTextPaint)
    }
}
