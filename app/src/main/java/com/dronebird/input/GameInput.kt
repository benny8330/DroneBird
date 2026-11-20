package com.dronebird.input

/**
 * Input source modes the game can be in. Drives the on-screen HUD and toasts.
 */
enum class InputMode { CONTROLLER, TOUCH }

/**
 * The input contract the game engine consumes each tick. Both the RC-N1 serial
 * decoder and the touchscreen fallback are funnelled into this single interface
 * so [com.dronebird.GameEngine] never needs to know which one is active.
 *
 * Continuous axes:
 *  - [verticalAxis]  -1 (dive) .. +1 (climb)
 *  - [speedAxis]     -1 (brake) .. +1 (boost)   (left-stick X)
 *  - [trim]           0 .. 1 difficulty/speed trim (top dial)
 *  - [tiltAxis]      -1 .. +1 cosmetic drone tilt (right-stick Y)
 *
 * Discrete events are one-shot and must be *polled* (reading clears them):
 *  - [pollImpulse]   a touch tap (classic Flappy flap)
 *  - [pollDodge]     a dodge request (stick flick or right-swipe)
 */
interface GameInput {

    /** Active source, for HUD/badge decisions. */
    val mode: InputMode

    /** Continuous vertical control, -1 (dive) .. +1 (climb). */
    fun verticalAxis(): Float

    /** Speed modifier, -1 (brake) .. +1 (boost). */
    fun speedAxis(): Float

    /** Global speed/difficulty trim, 0 .. 1. */
    fun trim(): Float

    /** Cosmetic drone tilt, -1 .. +1 (no gameplay effect). */
    fun tiltAxis(): Float

    /** True while sustained thrust is held (touch hold). */
    fun thrustHeld(): Boolean

    /** Returns true once per discrete upward impulse (touch tap), then clears it. */
    fun pollImpulse(): Boolean

    /** Returns true once per dodge request, then clears it. */
    fun pollDodge(): Boolean
}
