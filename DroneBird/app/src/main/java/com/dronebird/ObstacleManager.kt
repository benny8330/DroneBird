package com.dronebird

import android.graphics.RectF
import kotlin.random.Random

/**
 * One pair of pipes with a vertical gap the drone must fly through.
 * [x] is the left edge in pixels; the pair scrolls leftward each tick.
 */
class PipePair(
    var x: Float,
    val gapTop: Float,
    val gapBottom: Float
) {
    /** Set once the drone has passed this pair, so it is scored only one time. */
    var scored: Boolean = false
}

/**
 * Spawns, moves, recycles and collision-tests the endless corridor of pipe pairs.
 * All sizing is derived from device density so gaps read the same on any screen.
 */
class ObstacleManager(private val density: Float) {

    private val pipes = ArrayList<PipePair>()

    /** Pipe width in px. */
    val pipeWidth: Float = PIPE_WIDTH_DP * density

    private val spacing: Float = PIPE_SPACING_DP * density
    private val minGap: Float = MIN_GAP_DP * density
    private val maxGap: Float = MAX_GAP_DP * density
    private val margin: Float = EDGE_MARGIN_DP * density

    /** Read-only view of live pipe pairs for the renderer. */
    val obstacles: List<PipePair> get() = pipes

    /** Clears all pipes and seeds the first few off the right edge. */
    fun reset(worldW: Float, worldH: Float) {
        pipes.clear()
        var x = worldW + spacing
        repeat(INITIAL_PIPES) {
            pipes.add(spawnAt(x, worldH))
            x += spacing
        }
    }

    /**
     * Advances all pipes by [scrollPx], recycles those fully off-screen left,
     * spawns new ones on the right, and returns how many pairs the drone (at
     * [droneX]) newly cleared this tick.
     */
    fun update(scrollPx: Float, worldW: Float, worldH: Float, droneX: Float): Int {
        var scored = 0
        val iterator = pipes.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x -= scrollPx
            if (!p.scored && p.x + pipeWidth < droneX) {
                p.scored = true
                scored++
            }
            if (p.x + pipeWidth < 0f) iterator.remove()
        }
        // Keep the corridor populated.
        val rightmost = pipes.maxOfOrNull { it.x } ?: (worldW - spacing)
        if (rightmost < worldW - spacing) {
            pipes.add(spawnAt(rightmost + spacing, worldH))
        }
        return scored
    }

    /** True if [drone] overlaps any pipe rectangle (AABB). */
    fun collides(drone: RectF): Boolean {
        for (p in pipes) {
            if (drone.right < p.x || drone.left > p.x + pipeWidth) continue
            // Horizontally overlapping this pair: hit if above the gap or below it.
            if (drone.top < p.gapTop || drone.bottom > p.gapBottom) return true
        }
        return false
    }

    /** Builds a pair at [x] with a randomised gap size and vertical position. */
    private fun spawnAt(x: Float, worldH: Float): PipePair {
        val gap = Random.nextFloat() * (maxGap - minGap) + minGap
        val topMax = worldH - margin - gap
        val gapTop = if (topMax <= margin) margin else Random.nextFloat() * (topMax - margin) + margin
        return PipePair(x, gapTop, gapTop + gap)
    }

    private companion object {
        const val PIPE_WIDTH_DP = 70f
        const val PIPE_SPACING_DP = 300f
        const val MIN_GAP_DP = 180f   // gap size randomised 180dp..260dp
        const val MAX_GAP_DP = 260f
        const val EDGE_MARGIN_DP = 24f
        const val INITIAL_PIPES = 4
    }
}
