package com.dronebird

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dronebird.databinding.ActivityMainBinding

/**
 * Entry menu. Routes to Play (the game), Controller Setup (live detection) and
 * Diagnostic (byte inspector + calibration). Detection is where the app also
 * lands when launched by a USB attach broadcast.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPlay.setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        binding.btnDetection.setOnClickListener {
            startActivity(Intent(this, ControllerDetectionActivity::class.java))
        }
        binding.btnDiagnostic.setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
    }
}
