package com.dronebird.input

import kotlin.math.abs

/**
 * Turns continuous stick motion into discrete "flick" events.
 *
 * A flick is a fast deflection of an axis past [flickThreshold] followed by a
 * return toward centre (below [returnThreshold]) within [flickWindowMs]. Events
 * are debounced by [debounceMs] so one physical flick fires at most once.
 *
 * Used for the right-stick quick-X-flick -> dodge mapping. No controller buttons
 * are read anywhere; gestures are the only discrete controller actions.
 */
class GestureDetector(
    private val flickThreshold: Float = 0.6f,
    private val returnThreshold: Float = 0.25f,
    private val flickWindowMs: Long = 350L,
    private val debounceMs: Long = 200L
) {

    private enum class Phase { IDLE, DEFLECTED }

    private var phase = Phase.IDLE
    private var deflectStartMs = 0L
    private var lastFireMs = 0L

    /**
     * Feeds the latest axis value and timestamp. Returns true exactly on the tick
     * a valid, debounced flick completes.
     */
    fun update(axis: Float, nowMs: Long): Boolean {
        when (phase) {
            Phase.IDLE -> {
                if (abs(axis) >= flickThreshold) {
                    phase = Phase.DEFLECTED
                    deflectStartMs = nowMs
                }
            }
            Phase.DEFLECTED -> {
                val elapsed = nowMs - deflectStartMs
                if (abs(axis) <= returnThreshold) {
                    // Returned to centre: a flick completes if it was quick + not debounced.
                    phase = Phase.IDLE
                    if (elapsed <= flickWindowMs && nowMs - lastFireMs >= debounceMs) {
                        lastFireMs = nowMs
                        return true
                    }
                } else if (elapsed > flickWindowMs) {
                    // Held too long to count as a flick; reset without firing.
                    phase = Phase.IDLE
                }
            }
        }
        return false
    }

    /** Clears transient state (call on input-source switch or game reset). */
    fun reset() {
        phase = Phase.IDLE
        deflectStartMs = 0L
    }
}
