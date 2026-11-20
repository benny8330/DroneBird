package com.dronebird

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dronebird.input.InputMode
import com.dronebird.input.InputRouter
import com.dronebird.usb.FrameParser
import com.dronebird.usb.UsbSerialController

/**
 * Hosts the [GameView], landscape-locked (see manifest). Owns the USB controller,
 * decoder and input router for the session, keeps the screen on, and goes
 * immersive. Flight uses the RC-N1 when connected and the touchscreen otherwise,
 * switching automatically with a toast either way.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var controller: UsbSerialController
    private lateinit var router: InputRouter
    private lateinit var sound: SoundManager
    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        controller = UsbSerialController(this)
        val parser = FrameParser(this)
        router = InputRouter(controller, parser)
        sound = SoundManager()
        val scores = ScoreManager(this)

        router.onModeChanged = { mode -> runOnUiThread { toastMode(mode) } }
        router.start(lifecycleScope)

        gameView = GameView(this).apply {
            attach(router, scores, sound)
            onMenuRequested = { finish() }
        }
        setContentView(gameView)
    }

    private fun toastMode(mode: InputMode) {
        val msg = if (mode == InputMode.CONTROLLER) R.string.toast_rc_connected
        else R.string.toast_rc_disconnected
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onResume() {
        super.onResume()
        gameView.onResumeLoop()
    }

    override fun onPause() {
        super.onPause()
        gameView.onPauseLoop()
    }

    override fun onStop() {
        super.onStop()
        controller.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.dispose()
        sound.release()
    }
}
