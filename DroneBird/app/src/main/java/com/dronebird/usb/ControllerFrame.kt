package com.dronebird.usb

/**
 * Logical axes exposed by the DJI RC-N1 that this app can decode.
 *
 * Only the two analog sticks and the top dial are reliably decodable from the
 * raw serial stream; physical buttons are NOT represented here on purpose.
 */
enum class ControllerAxis(val label: String) {
    LEFT_X("Left Stick X"),
    LEFT_Y("Left Stick Y"),
    RIGHT_X("Right Stick X"),
    RIGHT_Y("Right Stick Y"),
    DIAL("Top Dial");

    /** True for the four stick axes (normalised to -1f..+1f). The dial uses 0f..1f. */
    val isStick: Boolean get() = this != DIAL
}

/**
 * A single decoded, normalised controller sample.
 *
 * Sticks are normalised to -1f..+1f (centre = 0f). The dial is normalised to
 * 0f..1f. [timestampMs] is the monotonic read time used for gesture timing.
 */
data class ControllerFrame(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val dial: Float,
    val timestampMs: Long
) {
    /** Returns the normalised value for [axis] — convenient for the diagnostic bars. */
    fun value(axis: ControllerAxis): Float = when (axis) {
        ControllerAxis.LEFT_X -> leftX
        ControllerAxis.LEFT_Y -> leftY
        ControllerAxis.RIGHT_X -> rightX
        ControllerAxis.RIGHT_Y -> rightY
        ControllerAxis.DIAL -> dial
    }

    companion object {
        /** A neutral frame: sticks centred, dial mid-travel. Used before first data. */
        val NEUTRAL = ControllerFrame(0f, 0f, 0f, 0f, 0.5f, 0L)
    }
}
