package com.dronebird

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny programmatic sound: an 880 Hz score blip (~100 ms) and a decaying
 * white-noise crash (~300 ms). PCM is synthesised in code — no asset files, no
 * looping hum (deferred to Phase 2).
 */
class SoundManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val blip: ShortArray = buildSine(880.0, 100)
    private val crash: ShortArray = buildNoise(300)

    /** Plays the score blip. */
    fun playBlip() = play(blip, 150)

    /** Plays the crash tone. */
    fun playCrash() = play(crash, 350)

    /** Releases the scheduling scope. */
    fun release() = scope.cancel()

    /** Fire-and-forget: builds a one-shot static track, plays it, then releases it. */
    private fun play(data: ShortArray, holdMs: Long) {
        scope.launch {
            val track = runCatching { buildTrack(data) }.getOrNull() ?: return@launch
            runCatching {
                track.write(data, 0, data.size)
                track.play()
            }
            delay(holdMs)
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun buildTrack(data: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(data.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    /** Synthesises a short sine tone with a quick linear fade to avoid clicks. */
    private fun buildSine(freqHz: Double, durationMs: Int): ShortArray {
        val n = SAMPLE_RATE * durationMs / 1000
        val out = ShortArray(n)
        val fade = n / 8
        for (i in 0 until n) {
            var amp = 0.6
            if (i < fade) amp *= i.toDouble() / fade
            if (i > n - fade) amp *= (n - i).toDouble() / fade
            val s = sin(2.0 * Math.PI * freqHz * i / SAMPLE_RATE) * amp
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Synthesises exponentially-decaying white noise for the crash. */
    private fun buildNoise(durationMs: Int): ShortArray {
        val n = SAMPLE_RATE * durationMs / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val decay = exp(-5.0 * i / n)
            val s = (Random.nextDouble(-1.0, 1.0)) * decay * 0.7
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}
