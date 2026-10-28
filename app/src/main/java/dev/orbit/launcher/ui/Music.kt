package dev.orbit.launcher.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Фонова музика меню: вбудований ембієнт (синтезується, без файлів) або свій трек.
 * На час гри плавно затихає і ставиться на паузу.
 */
class BgMusic(private val context: Context, private val scope: CoroutineScope) {
    private var mode = "off"
    private var fileUri = ""
    private var volume = 0.4f

    private var ambient: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var fadeJob: Job? = null
    private var current = 0f
    private var wantPlaying = false

    fun apply(newMode: String, newFile: String, vol: Int) {
        volume = (vol.coerceIn(0, 10) / 10f) * 0.6f
        if (newMode != mode || newFile != fileUri) {
            stopAll()
            mode = newMode; fileUri = newFile
        }
        if (wantPlaying) resume() else setVolumeNow(volume)
    }

    fun resume() {
        wantPlaying = true
        when (mode) {
            "ambient" -> scope.launch {
                val track = ambient ?: withContext(Dispatchers.Default) { buildAmbient() }.also { ambient = it }
                if (!wantPlaying) return@launch
                runCatching { if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play() }
                fadeTo(volume, 1500)
            }
            "file" -> {
                val p = player ?: runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        setDataSource(context, Uri.parse(fileUri))
                        isLooping = true
                        prepare()
                    }
                }.getOrNull()?.also { player = it }
                if (p != null) {
                    runCatching { if (!p.isPlaying) p.start() }
                    fadeTo(volume, 1500)
                }
            }
            else -> Unit
        }
    }

    /** Плавно приглушити й поставити на паузу (запуск гри, згортання) */
    fun pause(fadeMs: Long = 600) {
        wantPlaying = false
        fadeTo(0f, fadeMs) {
            runCatching { ambient?.pause() }
            runCatching { player?.pause() }
        }
    }

    fun release() {
        fadeJob?.cancel()
        stopAll()
    }

    private fun stopAll() {
        runCatching { ambient?.stop(); ambient?.release() }
        runCatching { player?.stop(); player?.release() }
        ambient = null; player = null; current = 0f
    }

    private fun setVolumeNow(v: Float) {
        current = v
        runCatching { ambient?.setVolume(v) }
        runCatching { player?.setVolume(v, v) }
    }

    private fun fadeTo(target: Float, ms: Long, then: () -> Unit = {}) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val from = current
            val steps = (ms / 30).coerceAtLeast(1)
            for (i in 1..steps) {
                setVolumeNow(from + (target - from) * i / steps)
                delay(30)
            }
            then()
        }
    }

    /** 32 секунди м'якого ембієнту, які зациклюються без шву */
    private fun buildAmbient(): AudioTrack {
        val rate = 22050
        val seconds = 32.0
        val frames = (rate * seconds).toInt()
        val l = DoubleArray(frames)
        val r = DoubleArray(frames)

        // акорди: Cmaj9, Am9, Fmaj7, G6sus — по 8 секунд, з перекриттям
        val chords = listOf(
            listOf(130.81, 196.00, 246.94, 293.66, 329.63),
            listOf(110.00, 164.81, 196.00, 246.94, 261.63),
            listOf(87.31, 174.61, 220.00, 261.63, 329.63),
            listOf(98.00, 146.83, 196.00, 261.63, 293.66),
        )
        val chordLen = seconds / chords.size
        for ((ci, chord) in chords.withIndex()) {
            val start = ci * chordLen
            val len = chordLen + 3.0 // хвіст заходить на наступний акорд
            for (i in 0 until (len * rate).toInt()) {
                val t = i.toDouble() / rate
                val idx = ((start * rate).toInt() + i) % frames // зациклення
                val env = when {
                    t < 2.5 -> sin(t / 2.5 * PI / 2).let { it * it }
                    t > len - 3.5 -> ((len - t) / 3.5).coerceIn(0.0, 1.0).let { it * it }
                    else -> 1.0
                }
                val trem = 0.85 + 0.15 * sin(2 * PI * 0.15 * (start + t))
                var sl = 0.0; var sr = 0.0
                for ((ni, f) in chord.withIndex()) {
                    val amp = if (ni == 0) 0.9 else 0.55 / (1 + ni * 0.15)
                    // легке розстроювання лівого/правого каналу — «ширина»
                    sl += amp * (sin(2 * PI * f * 0.999 * t) + 0.12 * sin(4 * PI * f * t))
                    sr += amp * (sin(2 * PI * f * 1.001 * t + ni) + 0.12 * sin(4 * PI * f * t + 1))
                }
                l[idx] += sl * env * trem
                r[idx] += sr * env * trem
            }
        }
        // рідкі «дзвіночки» з пентатоніки
        val bells = listOf(2.0 to 783.99, 6.5 to 659.25, 11.0 to 1046.5, 15.5 to 880.0, 19.0 to 783.99, 24.5 to 587.33, 28.0 to 659.25)
        for ((at, f) in bells) {
            val s0 = (at * rate).toInt()
            for (i in 0 until (2.5 * rate).toInt()) {
                val t = i.toDouble() / rate
                val idx = (s0 + i) % frames
                val env = (if (t < 0.01) t / 0.01 else 1.0) * exp(-t / 0.6)
                val v = 0.35 * env * (sin(2 * PI * f * t) + 0.2 * sin(2 * PI * f * 2.01 * t))
                l[idx] += v * 0.8; r[idx] += v * 1.0
            }
        }
        var peak = 1e-6
        for (i in 0 until frames) peak = maxOf(peak, kotlin.math.abs(l[i]), kotlin.math.abs(r[i]))
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            pcm[2 * i] = (l[i] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort()
            pcm[2 * i + 1] = (r[i] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also {
                it.write(pcm, 0, pcm.size)
                it.setLoopPoints(0, frames, -1)
                it.setVolume(0f)
            }
    }
}
