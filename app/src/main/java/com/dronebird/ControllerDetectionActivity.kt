package com.dronebird

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dronebird.databinding.ActivityDetectionBinding
import com.dronebird.usb.ConnectionState
import com.dronebird.usb.UsbSerialController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Live USB connect/disconnect screen. Shows the matched device's VID/PID/product
 * and the current connection state in real time, and lets the user jump to the
 * Diagnostic screen or straight into the game. Also the landing point when the OS
 * launches the app via a USB_DEVICE_ATTACHED broadcast.
 */
class ControllerDetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var controller: UsbSerialController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = UsbSerialController(this)

        binding.btnRetry.setOnClickListener { controller.connect() }
        binding.btnDiagnostic.setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
        binding.btnPlay.setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            controller.state.collectLatest { render(it) }
        }
    }

    /** Reflects the current connection state into the UI. */
    private fun render(state: ConnectionState) {
        val green = ContextCompat.getColor(this, R.color.rc_green)
        val red = ContextCompat.getColor(this, R.color.crash_red)
        val amber = ContextCompat.getColor(this, R.color.hud_amber)
        when (state) {
            is ConnectionState.Connected -> {
                binding.txtStatus.setText(R.string.state_connected)
                binding.txtStatus.setTextColor(green)
                binding.txtDevice.text = state.device.label
                binding.btnPlay.isEnabled = true
            }
            ConnectionState.Connecting -> {
                binding.txtStatus.setText(R.string.state_connecting)
                binding.txtStatus.setTextColor(amber)
            }
            ConnectionState.NoDevice -> {
                binding.txtStatus.setText(R.string.state_no_device)
                binding.txtStatus.setTextColor(amber)
                binding.txtDevice.setText(R.string.device_none)
            }
            ConnectionState.PermissionDenied -> {
                binding.txtStatus.setText(R.string.state_permission_denied)
                binding.txtStatus.setTextColor(red)
            }
            ConnectionState.Disconnected -> {
                binding.txtStatus.setText(R.string.state_disconnected)
                binding.txtStatus.setTextColor(red)
                binding.btnPlay.isEnabled = true // touch fallback still playable
            }
            is ConnectionState.Error -> {
                binding.txtStatus.text = getString(R.string.state_error, state.message)
                binding.txtStatus.setTextColor(red)
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
