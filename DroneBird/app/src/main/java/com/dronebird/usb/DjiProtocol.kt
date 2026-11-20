package com.dronebird.usb

/**
 * Single source of truth for every UNCERTAIN value in the DJI RC-N1 raw-serial
 * protocol.
 *
 * The RC-N1 enumerates on a USB host as "DJI USB VCOM For Protocol" and streams
 * a DUML-style byte frame when powered on and connected over its bottom USB-C
 * port — no drone required. The exact byte layout is undocumented, so EVERY
 * magic number lives here as a named constant with a // VERIFY VIA DIAGNOSTIC
 * note and a sensible default taken from the open-source references below.
 *
 * Decode references whose framing logic informed these defaults:
 *  - pverhaert/DJI_RCN1_for_drone_simulators
 *  - IvanYaky/DJI_RC-N1_SIMULATOR_FLY_DCL
 *  - Miniontoby/DJI_RC-N1_SIMULATOR_FLY_DCL
 *  - Matsemann/mDjiController
 *
 * Workflow: build → open Diagnostic → watch which hex bytes move with each
 * stick/dial → set the offsets here → run per-axis calibration. Only then is
 * flight control trustworthy.
 */
object DjiProtocol {

    // ---------------------------------------------------------------------
    // USB identity
    // ---------------------------------------------------------------------

    /** DJI vendor id (0x2CA3). VERIFY VIA DIAGNOSTIC — confirm on your unit. */
    const val VENDOR_ID: Int = 0x2CA3 // VERIFY VIA DIAGNOSTIC

    /**
     * Product id of the VCOM interface. -1 means "match any product id for the
     * vendor" (recommended until you read the real one). VERIFY VIA DIAGNOSTIC.
     */
    const val PRODUCT_ID: Int = -1 // VERIFY VIA DIAGNOSTIC (-1 = any)

    // ---------------------------------------------------------------------
    // Serial line parameters (CDC/ACM)
    // ---------------------------------------------------------------------

    /** Baud rate. CDC often ignores this, but set a sane value. VERIFY VIA DIAGNOSTIC. */
    const val BAUD_RATE: Int = 115200 // VERIFY VIA DIAGNOSTIC

    /** Data bits (8). VERIFY VIA DIAGNOSTIC. */
    const val DATA_BITS: Int = 8 // VERIFY VIA DIAGNOSTIC

    /** Stop bits: 1 == UsbSerialPort.STOPBITS_1. VERIFY VIA DIAGNOSTIC. */
    const val STOP_BITS: Int = 1 // VERIFY VIA DIAGNOSTIC

    /** Parity: 0 == UsbSerialPort.PARITY_NONE. VERIFY VIA DIAGNOSTIC. */
    const val PARITY: Int = 0 // VERIFY VIA DIAGNOSTIC

    /**
     * If true, probe the device as a USB CDC/ACM driver (the common case for the
     * DJI VCOM). If the Diagnostic shows no bytes, the guide explains trying the
     * raw-bulk-transfer fallback instead.
     * VERIFY VIA DIAGNOSTIC.
     */
    const val USE_CDC_DRIVER: Boolean = true // VERIFY VIA DIAGNOSTIC

    // ---------------------------------------------------------------------
    // Frame framing heuristic
    // ---------------------------------------------------------------------

    /**
     * DUML frames begin with 0x55. If HEADER_KNOWN is true the parser locates a
     * frame by this header byte; otherwise it falls back to a rolling-window
     * strategy (take the last FRAME_LENGTH bytes seen).
     * VERIFY VIA DIAGNOSTIC.
     */
    const val HEADER_KNOWN: Boolean = true // VERIFY VIA DIAGNOSTIC

    /** DUML start-of-frame byte. VERIFY VIA DIAGNOSTIC. */
    const val FRAME_HEADER: Int = 0x55 // VERIFY VIA DIAGNOSTIC

    /**
     * Expected frame length in bytes. Used both to slice a header-anchored frame
     * and as the rolling-window size when HEADER_KNOWN is false.
     * VERIFY VIA DIAGNOSTIC.
     */
    const val FRAME_LENGTH: Int = 38 // VERIFY VIA DIAGNOSTIC

    // ---------------------------------------------------------------------
    // Per-axis byte layout
    // ---------------------------------------------------------------------

    /**
     * How many bytes encode one axis value. DJI sticks are ~11-bit values packed
     * into 2 little-endian bytes. VERIFY VIA DIAGNOSTIC.
     */
    const val VALUE_BYTES: Int = 2 // VERIFY VIA DIAGNOSTIC

    /** True = little-endian 2-byte read, false = big-endian. VERIFY VIA DIAGNOSTIC. */
    const val LITTLE_ENDIAN: Boolean = true // VERIFY VIA DIAGNOSTIC

    /**
     * Bit mask applied after assembling the raw value (0x7FF keeps 11 bits).
     * VERIFY VIA DIAGNOSTIC.
     */
    const val VALUE_MASK: Int = 0x07FF // VERIFY VIA DIAGNOSTIC

    /**
     * Candidate byte offsets of each axis WITHIN a located frame.
     * These are placeholders ordered the way the reference projects report them;
     * the Diagnostic screen is how you confirm/replace each one.
     * VERIFY VIA DIAGNOSTIC — every entry.
     */
    const val OFFSET_LEFT_X: Int = 13 // VERIFY VIA DIAGNOSTIC
    const val OFFSET_LEFT_Y: Int = 15 // VERIFY VIA DIAGNOSTIC
    const val OFFSET_RIGHT_X: Int = 17 // VERIFY VIA DIAGNOSTIC
    const val OFFSET_RIGHT_Y: Int = 19 // VERIFY VIA DIAGNOSTIC
    const val OFFSET_DIAL: Int = 21 // VERIFY VIA DIAGNOSTIC

    /** Returns the configured frame offset for [axis]. */
    fun offsetOf(axis: ControllerAxis): Int = when (axis) {
        ControllerAxis.LEFT_X -> OFFSET_LEFT_X
        ControllerAxis.LEFT_Y -> OFFSET_LEFT_Y
        ControllerAxis.RIGHT_X -> OFFSET_RIGHT_X
        ControllerAxis.RIGHT_Y -> OFFSET_RIGHT_Y
        ControllerAxis.DIAL -> OFFSET_DIAL
    }

    // ---------------------------------------------------------------------
    // Default raw calibration (used until the user runs calibration)
    // ---------------------------------------------------------------------

    /** Default raw minimum for a DJI 11-bit axis. VERIFY VIA DIAGNOSTIC. */
    const val DEFAULT_RAW_MIN: Int = 364 // VERIFY VIA DIAGNOSTIC

    /** Default raw centre for a DJI 11-bit axis. VERIFY VIA DIAGNOSTIC. */
    const val DEFAULT_RAW_CENTER: Int = 1024 // VERIFY VIA DIAGNOSTIC

    /** Default raw maximum for a DJI 11-bit axis. VERIFY VIA DIAGNOSTIC. */
    const val DEFAULT_RAW_MAX: Int = 1684 // VERIFY VIA DIAGNOSTIC

    // ---------------------------------------------------------------------
    // Read-loop tuning (not protocol-sensitive, safe to leave alone)
    // ---------------------------------------------------------------------

    /** Size of the per-read scratch buffer handed to the serial port. */
    const val READ_BUFFER_SIZE: Int = 256

    /** Blocking read timeout in milliseconds. */
    const val READ_TIMEOUT_MS: Int = 200

    /** Maximum bytes retained in the rolling reassembly buffer. */
    const val MAX_ROLLING_BUFFER: Int = 512
}
