package com.dronebird.input

import android.view.MotionEvent
import com.dronebird.usb.ConnectionState
import com.dronebird.usb.ControllerFrame
import com.dronebird.usb.FrameParser
import com.dronebird.usb.UsbSerialController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chooses between RC-N1 controller input and the touchscreen fallback, exposing a
 * single [GameInput] to the engine and auto-switching when the controller
 * connects or drops mid-game.
 *
 * It owns a [FrameParser] for the game session, feeds raw serial chunks through
 * it, runs the right-stick flick [GestureDetector] for dodge, and translates
 * touch events into a virtual joystick + tap/hold + right-swipe dodge.
 */
class InputRouter(
    private val controller: UsbSerialController,
    private val parser: FrameParser
) : GameInput {

    /** Invoked on the collecting coroutine whenever the active source changes. */
    var onModeChanged: ((InputMode) -> Unit)? = null

    @Volatile
    private var currentMode: InputMode = InputMode.TOUCH
    override val mode: InputMode get() = currentMode

    // --- Controller state ---
    @Volatile
    private var latestFrame: ControllerFrame = ControllerFrame.NEUTRAL
    private val dodgeGesture = GestureDetector()

    @Volatile
    private var controllerDodgePending = false

    // --- Touch state ---
    private var joystickPointerId = -1
    private var joyOriginX = 0f
    private var joyOriginY = 0f
    @Volatile private var joyX = 0f
    @Volatile private var joyY = 0f

    private var thrustPointerId = -1
    private var thrustStartX = 0f
    private var thrustStartMs = 0L
    @Volatile private var thrustHeld = false
    @Volatile private var touchImpulsePending = false
    @Volatile private var touchDodgePending = false
    private var lastTouchDodgeMs = 0L

    /** Starts collecting connection state and serial frames on [scope]. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            controller.state.collectLatest { state ->
                val newMode = if (state is ConnectionState.Connected) {
                    InputMode.CONTROLLER
                } else {
                    InputMode.TOUCH
                }
                if (newMode != currentMode) {
                    currentMode = newMode
                    if (newMode == InputMode.TOUCH) latestFrame = ControllerFrame.NEUTRAL
                    dodgeGesture.reset()
                    onModeChanged?.invoke(newMode)
                }
            }
        }
        scope.launch {
            controller.frames.collectLatest { chunk ->
                val now = System.currentTimeMillis()
                val frame = parser.feed(chunk, now)
                if (frame != null) {
                    latestFrame = frame
                    if (dodgeGesture.update(frame.rightX, now)) {
                        controllerDodgePending = true
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // GameInput implementation
    // ------------------------------------------------------------------

    override fun verticalAxis(): Float =
        if (currentMode == InputMode.CONTROLLER) latestFrame.leftY else joyY

    override fun speedAxis(): Float =
        if (currentMode == InputMode.CONTROLLER) latestFrame.leftX else joyX

    override fun trim(): Float =
        if (currentMode == InputMode.CONTROLLER) latestFrame.dial else NEUTRAL_TRIM

    override fun tiltAxis(): Float =
        if (currentMode == InputMode.CONTROLLER) latestFrame.rightY else 0f

    override fun thrustHeld(): Boolean =
        currentMode == InputMode.TOUCH && thrustHeld

    override fun pollImpulse(): Boolean {
        if (currentMode != InputMode.TOUCH) return false
        val v = touchImpulsePending
        touchImpulsePending = false
        return v
    }

    override fun pollDodge(): Boolean {
        return if (currentMode == InputMode.CONTROLLER) {
            val v = controllerDodgePending
            controllerDodgePending = false
            v
        } else {
            val v = touchDodgePending
            touchDodgePending = false
            v
        }
    }

    // ------------------------------------------------------------------
    // Touch handling (called from GameView.onTouchEvent)
    // ------------------------------------------------------------------

    /**
     * Routes a raw touch event into the virtual joystick (left zone), tap/hold
     * thrust (right zone) and right-swipe dodge. No-op while a controller is active
     * so a resting thumb cannot fight the sticks.
     */
    fun onTouch(event: MotionEvent, viewW: Int, viewH: Int) {
        if (currentMode == InputMode.CONTROLLER) return
        val radius = viewW * JOY_RADIUS_FRAC
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (x < viewW * JOY_ZONE_FRAC && joystickPointerId == -1) {
                    joystickPointerId = id
                    joyOriginX = x
                    joyOriginY = y
                } else if (thrustPointerId == -1) {
                    thrustPointerId = id
                    thrustStartX = x
                    thrustStartMs = System.currentTimeMillis()
                    thrustHeld = true
                    touchImpulsePending = true // tap = one upward flap
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when (id) {
                        joystickPointerId -> {
                            joyX = clamp((x - joyOriginX) / radius, -1f, 1f)
                            // Up (smaller y) = climb (+1).
                            joyY = clamp((joyOriginY - y) / radius, -1f, 1f)
                        }
                        thrustPointerId -> {
                            val now = System.currentTimeMillis()
                            if (x - thrustStartX > viewW * SWIPE_FRAC &&
                                now - thrustStartMs <= SWIPE_WINDOW_MS &&
                                now - lastTouchDodgeMs >= DODGE_DEBOUNCE_MS
                            ) {
                                touchDodgePending = true
                                lastTouchDodgeMs = now
                                thrustStartX = x // avoid repeat fire on same swipe
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                when (id) {
                    joystickPointerId -> {
                        joystickPointerId = -1; joyX = 0f; joyY = 0f
                    }
                    thrustPointerId -> {
                        thrustPointerId = -1; thrustHeld = false
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> resetTouch()
        }
    }

    /** Clears all touch state (used on pause / reset). */
    fun resetTouch() {
        joystickPointerId = -1; thrustPointerId = -1
        joyX = 0f; joyY = 0f; thrustHeld = false
        touchImpulsePending = false
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = maxOf(lo, minOf(hi, v))

    private companion object {
        const val NEUTRAL_TRIM = 0.5f
        const val JOY_ZONE_FRAC = 0.4f       // left 40% of width is the joystick zone
        const val JOY_RADIUS_FRAC = 0.12f    // joystick travel radius as fraction of width
        const val SWIPE_FRAC = 0.15f         // horizontal distance for a dodge swipe
        const val SWIPE_WINDOW_MS = 400L
        const val DODGE_DEBOUNCE_MS = 200L
    }
}
