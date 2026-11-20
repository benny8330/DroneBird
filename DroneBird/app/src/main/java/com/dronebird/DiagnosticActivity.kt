package com.dronebird

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dronebird.databinding.ActivityDiagnosticBinding
import com.dronebird.usb.ConnectionState
import com.dronebird.usb.ControllerAxis
import com.dronebird.usb.ControllerFrame
import com.dronebird.usb.FrameParser
import com.dronebird.usb.UsbSerialController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Byte-inspector + calibration — the first feature that must work.
 *
 * Shows the live connection, the matched device IDs, the latest raw frame as hex,
 * and a moving bar per axis so the user can see which bytes respond to which
 * stick. A capture flow records raw min/centre/max per axis and persists it so
 * [FrameParser] can normalise cleanly. Only after this is flight control trusted.
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticBinding
    private lateinit var controller: UsbSerialController
    private lateinit var parser: FrameParser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = UsbSerialController(this)
        parser = FrameParser(this)

        binding.btnCapMin.setOnClickListener { capture("min") }
        binding.btnCapCenter.setOnClickListener { capture("center") }
        binding.btnCapMax.setOnClickListener { capture("max") }
        binding.btnReset.setOnClickListener {
            parser.resetCalibration()
            showCalibration(selectedAxis())
            toast(getString(R.string.calib_reset))
        }
        binding.axisGroup.setOnCheckedChangeListener { _, _ -> showCalibration(selectedAxis()) }

        showCalibration(selectedAxis())
        observeState()
        observeFrames()
    }

    private fun observeState() {
        lifecycleScope.launch {
            controller.state.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        binding.txtState.text = getString(R.string.state_connected)
                        binding.txtDevice.text = state.device.label
                    }
                    ConnectionState.Connecting ->
                        binding.txtState.text = getString(R.string.state_connecting)
                    ConnectionState.NoDevice -> {
                        binding.txtState.text = getString(R.string.state_no_device)
                        binding.txtDevice.text = getString(R.string.device_none)
                    }
                    ConnectionState.PermissionDenied ->
                        binding.txtState.text = getString(R.string.state_permission_denied)
                    ConnectionState.Disconnected ->
                        binding.txtState.text = getString(R.string.state_disconnected)
                    is ConnectionState.Error ->
                        binding.txtState.text = getString(R.string.state_error, state.message)
                }
            }
        }
    }

    private fun observeFrames() {
        lifecycleScope.launch {
            controller.frames.collectLatest { chunk ->
                val frame = parser.feed(chunk, System.currentTimeMillis())
                if (frame != null) renderFrame(frame)
            }
        }
    }

    /** Updates the hex dump and all five axis bars from a decoded frame. */
    private fun renderFrame(frame: ControllerFrame) {
        binding.txtHex.text = toHex(parser.lastRawFrame)
        updateAxis(ControllerAxis.LEFT_X, frame, binding.barLeftX, binding.valLeftX)
        updateAxis(ControllerAxis.LEFT_Y, frame, binding.barLeftY, binding.valLeftY)
        updateAxis(ControllerAxis.RIGHT_X, frame, binding.barRightX, binding.valRightX)
        updateAxis(ControllerAxis.RIGHT_Y, frame, binding.barRightY, binding.valRightY)
        updateAxis(ControllerAxis.DIAL, frame, binding.barDial, binding.valDial)
    }

    private fun updateAxis(axis: ControllerAxis, frame: ControllerFrame, bar: ProgressBar, label: TextView) {
        val norm = frame.value(axis)
        val progress = if (axis.isStick) ((norm + 1f) / 2f * 100f).toInt() else (norm * 100f).toInt()
        bar.progress = progress.coerceIn(0, 100)
        val raw = parser.rawAxis(parser.lastRawFrame, axis)
        label.text = getString(R.string.axis_value_fmt, raw, norm)
    }

    /** Captures a calibration endpoint for the selected axis from the latest frame. */
    private fun capture(which: String) {
        val axis = selectedAxis()
        val raw = parser.captureCalibration(axis, which)
        if (raw < 0) {
            toast(getString(R.string.calib_no_frame))
        } else {
            toast(getString(R.string.calib_captured, which, raw))
            showCalibration(axis)
        }
    }

    private fun showCalibration(axis: ControllerAxis) {
        val c = parser.calibrationOf(axis)
        binding.txtCalib.text =
            getString(R.string.calib_fmt, axis.label, c.rawMin, c.rawCenter, c.rawMax)
    }

    private fun selectedAxis(): ControllerAxis = when (binding.axisGroup.checkedRadioButtonId) {
        R.id.rbLeftX -> ControllerAxis.LEFT_X
        R.id.rbLeftY -> ControllerAxis.LEFT_Y
        R.id.rbRightX -> ControllerAxis.RIGHT_X
        R.id.rbRightY -> ControllerAxis.RIGHT_Y
        R.id.rbDial -> ControllerAxis.DIAL
        else -> ControllerAxis.LEFT_Y
    }

    private fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return getString(R.string.no_bytes)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) sb.append(String.format("%02X ", b.toInt() and 0xFF))
        return sb.toString().trim()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onStart() {
        super.onStart()
        parser.reloadCalibration()
        controller.start()
    }

    override fun onStop() {
        super.onStop()
        controller.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.dispose()
    }
}
