package dev.orbit.launcher.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * М'які синтезовані звуки інтерфейсу — без жодних аудіофайлів.
 * Теплий тембр (тон + тихий обертон), плавна атака без клацання, ноти з мажорної гами.
 * Грають з медіа-гучністю телефона і мовчать у беззвучному / вібро-режимі.
 */
class Sounds(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    enum class S { Move, Tab, Select, Back, Boot }

    /** Нота: частота, початок і тривалість (с), гучність, атака (с) */
    private data class Note(val f: Double, val start: Double, val len: Double, val amp: Double, val attack: Double = 0.006)

    private val rate = 44100

    // C5=523.25 D5=587.33 E5=659.25 G5=783.99 A5=880 C6=1046.5
    private val tracks: Map<S, AudioTrack> = mapOf(
        // ледь чутний «тук» при переміщенні
        S.Move to synth(listOf(Note(659.25, 0.0, 0.07, 1.0, 0.004)), volume = 0.045),
        // два легкі кроки вгору
        S.Tab to synth(listOf(Note(587.33, 0.0, 0.09, 1.0), Note(783.99, 0.045, 0.12, 0.9)), volume = 0.05),
        // теплий акорд підтвердження
        S.Select to synth(
            listOf(Note(523.25, 0.0, 0.42, 1.0, 0.008), Note(659.25, 0.025, 0.40, 0.8, 0.008), Note(783.99, 0.05, 0.40, 0.7, 0.008)),
            volume = 0.065,
        ),
        // м'який спад
        S.Back to synth(listOf(Note(659.25, 0.0, 0.1, 1.0), Note(523.25, 0.06, 0.16, 0.9)), volume = 0.05),
        // плавне «пробудження»: арпеджіо з повільною атакою, що згасає
        S.Boot to synth(
            listOf(
                Note(261.63, 0.00, 1.9, 0.8, 0.25), Note(392.00, 0.10, 1.8, 0.6, 0.25),
                Note(523.25, 0.25, 1.6, 0.6, 0.12), Note(659.25, 0.45, 1.4, 0.5, 0.10),
                Note(783.99, 0.65, 1.2, 0.45, 0.08), Note(1174.66, 0.9, 1.0, 0.25, 0.06),
            ),
            volume = 0.07, decay = 0.55,
        ),
    )

    /** 0 вимк, 1 тихо, 2 нормально */
    var level = 2

    private fun synth(notes: List<Note>, volume: Double, decay: Double = 0.22): AudioTrack {
        val total = notes.maxOf { it.start + it.len } + 0.02
        val n = (rate * total).toInt()
        val buf = DoubleArray(n)
        for (note in notes) {
            val s0 = (note.start * rate).toInt()
            val len = (note.len * rate).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx >= n) break
                val t = i.toDouble() / rate
                // атака по синусоїді (без клацання), далі експоненційне згасання, в кінці — плавний хвіст у нуль
                val att = if (t < note.attack) sin(t / note.attack * PI / 2) else 1.0
                val dec = exp(-t / (note.len * decay))
                val tail = min(1.0, (len - i).toDouble() / (rate * 0.015))
                val env = att * dec * tail * note.amp
                val ph = 2 * PI * note.f * t
                buf[idx] += env * (sin(ph) + 0.18 * sin(2 * ph) + 0.05 * sin(3 * ph))
            }
        }
        val peak = max(1e-6, buf.maxOf { kotlin.math.abs(it) })
        val pcm = ShortArray(n) { i -> (buf[i] / peak * volume * Short.MAX_VALUE).toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
            .also { it.write(pcm, 0, n) }
    }

    fun play(s: S) {
        if (level <= 0 || muted()) return
        val t = tracks[s] ?: return
        runCatching {
            t.setVolume(if (level == 1) 0.4f else 1f)
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    /** Телефон у беззвучному / вібро-режимі або медіа-гучність на нулі */
    private fun muted(): Boolean = runCatching {
        audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }.getOrDefault(false)

    fun release() = tracks.values.forEach { it.release() }
}
