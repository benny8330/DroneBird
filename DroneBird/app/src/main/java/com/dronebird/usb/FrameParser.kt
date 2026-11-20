package com.dronebird.usb

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * Raw per-axis calibration (raw ADC counts captured on-device), one triple per axis.
 */
data class AxisCalibration(
    var rawMin: Int = DjiProtocol.DEFAULT_RAW_MIN,
    var rawCenter: Int = DjiProtocol.DEFAULT_RAW_CENTER,
    var rawMax: Int = DjiProtocol.DEFAULT_RAW_MAX
)

/**
 * Persists per-axis calibration to SharedPreferences so the decoded values survive
 * restarts. Calibration is the thing that turns jumpy raw counts into clean -1..1.
 */
class CalibrationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads the stored calibration for [axis], falling back to protocol defaults. */
    fun load(axis: ControllerAxis): AxisCalibration = AxisCalibration(
        rawMin = prefs.getInt(key(axis, "min"), DjiProtocol.DEFAULT_RAW_MIN),
        rawCenter = prefs.getInt(key(axis, "center"), DjiProtocol.DEFAULT_RAW_CENTER),
        rawMax = prefs.getInt(key(axis, "max"), DjiProtocol.DEFAULT_RAW_MAX)
    )

    /** Persists one captured endpoint ("min" | "center" | "max") for [axis]. */
    fun save(axis: ControllerAxis, which: String, rawValue: Int) {
        prefs.edit().putInt(key(axis, which), rawValue).apply()
    }

    /** True if any axis has been calibrated away from its default. */
    fun hasUserCalibration(): Boolean = prefs.all.isNotEmpty()

    /** Clears all stored calibration (used by the diagnostic "reset" action). */
    fun clear() = prefs.edit().clear().apply()

    private fun key(axis: ControllerAxis, which: String) = "${axis.name}_$which"

    private companion object {
        const val PREFS = "dronebird_calibration"
    }
}

/**
 * Turns a raw serial byte stream into normalised [ControllerFrame]s.
 *
 * This is the ONE place that knows the byte layout. If DJI changes framing, only
 * this file (plus the constants in [DjiProtocol]) needs to change. It keeps a
 * small rolling buffer so frames split across USB reads are reassembled.
 */
class FrameParser(context: Context) {

    private val store = CalibrationStore(context)
    private val calibration: MutableMap<ControllerAxis, AxisCalibration> =
        ControllerAxis.values().associateWith { store.load(it) }.toMutableMap()

    private val rolling = ArrayList<Byte>(DjiProtocol.MAX_ROLLING_BUFFER)

    /** The most recent fully-located raw frame, for the diagnostic hex view. */
    @Volatile
    var lastRawFrame: ByteArray = ByteArray(0)
        private set

    /** Re-reads calibration from storage (call after the user calibrates an axis). */
    fun reloadCalibration() {
        ControllerAxis.values().forEach { calibration[it] = store.load(it) }
    }

    /**
     * Feeds a freshly read chunk and returns a decoded frame if a candidate frame
     * is now available, else null. Also updates [lastRawFrame].
     */
    fun feed(chunk: ByteArray, nowMs: Long): ControllerFrame? {
        for (b in chunk) rolling.add(b)
        trimRolling()
        val frame = locateFrame() ?: return null
        lastRawFrame = frame
        return decode(frame, nowMs)
    }

    /** Decodes a located frame into normalised floats using current calibration. */
    fun decode(frame: ByteArray, nowMs: Long): ControllerFrame = ControllerFrame(
        leftX = normalise(ControllerAxis.LEFT_X, rawAxis(frame, ControllerAxis.LEFT_X)),
        leftY = normalise(ControllerAxis.LEFT_Y, rawAxis(frame, ControllerAxis.LEFT_Y)),
        rightX = normalise(ControllerAxis.RIGHT_X, rawAxis(frame, ControllerAxis.RIGHT_X)),
        rightY = normalise(ControllerAxis.RIGHT_Y, rawAxis(frame, ControllerAxis.RIGHT_Y)),
        dial = normalise(ControllerAxis.DIAL, rawAxis(frame, ControllerAxis.DIAL)),
        timestampMs = nowMs
    )

    /**
     * Returns the raw (pre-normalisation) value for [axis] from [frame], or -1 if
     * the configured offset lies outside the frame. Used by the diagnostic bars and
     * by the calibration capture.
     */
    fun rawAxis(frame: ByteArray, axis: ControllerAxis): Int {
        val off = DjiProtocol.offsetOf(axis)
        if (off < 0 || off + DjiProtocol.VALUE_BYTES > frame.size) return -1
        val assembled = if (DjiProtocol.VALUE_BYTES == 1) {
            frame[off].toInt() and 0xFF
        } else {
            val lo: Int
            val hi: Int
            if (DjiProtocol.LITTLE_ENDIAN) {
                lo = frame[off].toInt() and 0xFF
                hi = frame[off + 1].toInt() and 0xFF
            } else {
                hi = frame[off].toInt() and 0xFF
                lo = frame[off + 1].toInt() and 0xFF
            }
            (hi shl 8) or lo
        }
        return assembled and DjiProtocol.VALUE_MASK
    }

    /** Captures a calibration endpoint for [axis] from the latest raw frame, if any. */
    fun captureCalibration(axis: ControllerAxis, which: String): Int {
        val frame = lastRawFrame
        if (frame.isEmpty()) return -1
        val raw = rawAxis(frame, axis)
        if (raw < 0) return -1
        store.save(axis, which, raw)
        calibration[axis] = store.load(axis)
        return raw
    }

    /** Exposes the live calibration for display. */
    fun calibrationOf(axis: ControllerAxis): AxisCalibration =
        calibration[axis] ?: AxisCalibration()

    /** Clears calibration back to defaults. */
    fun resetCalibration() {
        store.clear()
        reloadCalibration()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Normalises a raw count: sticks -> -1f..+1f (centre 0), dial -> 0f..1f. */
    private fun normalise(axis: ControllerAxis, raw: Int): Float {
        if (raw < 0) return if (axis.isStick) 0f else 0.5f
        val c = calibration[axis] ?: return if (axis.isStick) 0f else 0.5f
        return if (axis.isStick) {
            if (raw >= c.rawCenter) {
                val span = max(1, c.rawMax - c.rawCenter)
                clamp((raw - c.rawCenter).toFloat() / span, 0f, 1f)
            } else {
                val span = max(1, c.rawCenter - c.rawMin)
                -clamp((c.rawCenter - raw).toFloat() / span, 0f, 1f)
            }
        } else {
            val span = max(1, c.rawMax - c.rawMin)
            clamp((raw - c.rawMin).toFloat() / span, 0f, 1f)
        }
    }

    /**
     * Locates a candidate frame in the rolling buffer. With a known header it finds
     * the latest header byte that has FRAME_LENGTH bytes behind it; otherwise it
     * returns the most recent FRAME_LENGTH-byte window.
     */
    private fun locateFrame(): ByteArray? {
        val size = rolling.size
        if (size < DjiProtocol.FRAME_LENGTH) return null

        if (DjiProtocol.HEADER_KNOWN) {
            // Scan from the newest possible start backwards for a header with a full frame.
            var start = size - DjiProtocol.FRAME_LENGTH
            while (start >= 0) {
                if ((rolling[start].toInt() and 0xFF) == DjiProtocol.FRAME_HEADER) {
                    val frame = ByteArray(DjiProtocol.FRAME_LENGTH)
                    for (i in frame.indices) frame[i] = rolling[start + i]
                    // Keep unconsumed tail so partial next frame survives.
                    val consumeTo = start + DjiProtocol.FRAME_LENGTH
                    repeat(min(consumeTo, rolling.size)) { if (rolling.isNotEmpty()) rolling.removeAt(0) }
                    return frame
                }
                start--
            }
            return null
        } else {
            // Rolling-window fallback: last FRAME_LENGTH bytes.
            val frame = ByteArray(DjiProtocol.FRAME_LENGTH)
            val base = size - DjiProtocol.FRAME_LENGTH
            for (i in frame.indices) frame[i] = rolling[base + i]
            // Drop everything but a frame's worth to bound memory.
            while (rolling.size > DjiProtocol.FRAME_LENGTH) rolling.removeAt(0)
            return frame
        }
    }

    /** Bounds the rolling buffer so a silent/garbage stream cannot grow it forever. */
    private fun trimRolling() {
        while (rolling.size > DjiProtocol.MAX_ROLLING_BUFFER) rolling.removeAt(0)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
