package com.dronebird

import android.content.Context

/**
 * Tracks the current run's score and the persisted all-time high score
 * (SharedPreferences). Call [point] when the drone clears a pipe pair, [reset]
 * on a new run, and [commitHighScore] at crash time.
 */
class ScoreManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Score for the current run. */
    var current: Int = 0
        private set

    /** All-time high score, loaded once and updated as it is beaten. */
    var high: Int = prefs.getInt(KEY_HIGH, 0)
        private set

    /** Adds one point for clearing a pipe pair. */
    fun point() { current++ }

    /** Resets the current run score to zero. */
    fun reset() { current = 0 }

    /** Persists the high score if the current run beat it; returns true if it did. */
    fun commitHighScore(): Boolean {
        if (current > high) {
            high = current
            prefs.edit().putInt(KEY_HIGH, high).apply()
            return true
        }
        return false
    }

    private companion object {
        const val PREFS = "dronebird_scores"
        const val KEY_HIGH = "high_score"
    }
}
