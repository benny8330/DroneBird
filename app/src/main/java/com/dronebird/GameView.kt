package com.dronebird

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.dronebird.input.InputMode
import com.dronebird.input.InputRouter

/**
 * SurfaceView hosting the dedicated ~60 FPS render/update thread.
 *
 * It wires together the [GameEngine], [SpriteRenderer] and [InputRouter], pumps
 * fixed-ish time steps, plays audio cues from each step's result, and hit-tests
 * taps against the pause / retry / menu buttons.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private val density = resources.displayMetrics.density
    private val renderer = SpriteRenderer(density, buildPalette())

    private lateinit var engine: GameEngine
    private lateinit var input: InputRouter
    private lateinit var scores: ScoreManager
    private lateinit var sound: SoundManager
    private var attached = false

    /** Invoked (on the UI thread) when the player taps MENU on the crash overlay. */
    var onMenuRequested: (() -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastNanos = 0L

    init {
        holder.addCallback(this)
    }

    /** Supplies the dependencies the view cannot build itself. Call before showing. */
    fun attach(router: InputRouter, scoreManager: ScoreManager, soundManager: SoundManager) {
        input = router
        scores = scoreManager
        sound = soundManager
        engine = GameEngine(density, scoreManager)
        attached = true
    }

    // ------------------------------------------------------------------
    // Surface lifecycle
    // ------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!attached) return
        engine.onSurfaceSize(width, height)
        renderer.layout(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun startLoop() {
        if (!attached || running) return
        running = true
        lastNanos = System.nanoTime()
        thread = Thread(this, "DroneBirdLoop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        thread?.let { t ->
            try { t.join(500) } catch (_: InterruptedException) { }
        }
        thread = null
    }

    // ------------------------------------------------------------------
    // Game loop
    // ------------------------------------------------------------------

    override fun run() {
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat()
            lastNanos = now

            val result = engine.update(dt, input)
            if (result.scoredThisTick > 0) sound.playBlip()
            if (result.crashed) sound.playCrash()

            drawFrame()

            val frameMs = (System.nanoTime() - now) / 1_000_000L
            val sleep = TARGET_FRAME_MS - frameMs
            if (sleep > 0) {
                try { Thread.sleep(sleep) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun drawFrame() {
        val surfaceHolder = holder
        if (!surfaceHolder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = surfaceHolder.lockCanvas()
            if (canvas != null) {
                renderer.draw(
                    canvas,
                    engine,
                    scores.current,
                    scores.high,
                    input.mode == InputMode.CONTROLLER
                )
            }
        } finally {
            if (canvas != null) surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!attached) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            when (engine.state) {
                GameEngine.State.RUNNING, GameEngine.State.PAUSED -> {
                    if (renderer.pauseButton.contains(x, y)) {
                        engine.togglePause()
                        return true
                    }
                }
                GameEngine.State.CRASHED -> {
                    if (renderer.retryButton.contains(x, y)) {
                        engine.reset()
                        return true
                    }
                    if (renderer.menuButton.contains(x, y)) {
                        post { onMenuRequested?.invoke() }
                        return true
                    }
                }
                GameEngine.State.READY -> { /* fall through to input */ }
            }
        }
        // Everything else drives flight via the touch fallback.
        input.onTouch(event, width, height)
        return true
    }

    // ------------------------------------------------------------------
    // Palette from colors.xml
    // ------------------------------------------------------------------

    private fun buildPalette() = Palette(
        skyTop = color(R.color.sky_top),
        skyBottom = color(R.color.sky_bottom),
        pipe = color(R.color.pipe_green),
        pipeCap = color(R.color.pipe_cap),
        drone = color(R.color.drone_body),
        droneAccent = color(R.color.drone_accent),
        hudText = color(R.color.hud_text),
        hudShadow = color(R.color.hud_shadow),
        rcGreen = color(R.color.rc_green),
        crashRed = color(R.color.crash_red),
        overlayScrim = color(R.color.overlay_scrim),
        buttonFill = color(R.color.button_fill),
        buttonText = color(R.color.button_text),
        hudAmber = color(R.color.hud_amber)
    )

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    /** Pauses the loop when the host activity is backgrounded. */
    fun onPauseLoop() = stopLoop()

    /** Resumes the loop when the host activity returns. */
    fun onResumeLoop() = startLoop()

    private companion object {
        const val TARGET_FRAME_MS = 16L // ~60 FPS
    }
}
