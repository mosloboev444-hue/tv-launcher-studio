package com.gathin.tvhome.universal.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class Sound { FOCUS, SELECT, START, VPN_ON, VPN_OFF, FAVORITE }

/**
 * Звуки синтезируются в коде в фоновом потоке (интерфейс не ждёт).
 * Частота дискретизации 22 кГц вдвое снижает расчёты и память; для колокольчиков этого достаточно.
 */
object SoundFx {
    private const val SR = 22050
    private val lock = Any()
    @Volatile private var tracks: Map<Sound, AudioTrack> = emptyMap()
    @Volatile private var theme = -1
    @Volatile private var level = 2
    @Volatile private var pendingStart = false
    private val levels = floatArrayOf(0f, 0.25f, 0.55f, 1f)

    private class Timbre(val pitch: Double, val h2: Double, val h3: Double, val decayMul: Double)
    private class Note(val freq: Double, val delayMs: Int, val vol: Float)

    private val timbres = listOf(
        Timbre(1.0, 0.30, 0.08, 1.0),   // колокольчики
        Timbre(0.5, 0.05, 0.00, 0.6),   // мягкие
        Timbre(1.5, 0.50, 0.20, 1.6),   // кристалл
    )

    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val B5 = 987.77
    private const val C6 = 1046.5
    private const val E6 = 1318.5
    private const val G6 = 1568.0

    fun configure(themeIndex: Int, volume: Int) {
        level = volume.coerceIn(0, 3)
        val th = themeIndex.coerceIn(0, timbres.lastIndex)
        if (th == theme) { applyVolume(); return }
        theme = th
        Thread({
            val built = buildAll(timbres[th])
            var stale = false
            synchronized(lock) {
                if (th != theme) {
                    stale = true            // за время сборки тему сменили ещё раз
                } else {
                    val old = tracks
                    tracks = built
                    old.values.forEach { it.release() }
                    applyVolume()
                }
            }
            if (stale) built.values.forEach { it.release() }
            else if (pendingStart) { pendingStart = false; play(Sound.START) }
        }, "sfx-builder").start()
    }

    /** Мелодия запуска: если звуки ещё собираются, сыграет сразу после готовности. */
    fun playStartup() {
        if (tracks.containsKey(Sound.START)) play(Sound.START) else pendingStart = true
    }

    private fun applyVolume() {
        tracks.values.forEach { it.setVolume(levels[level]) }
    }

    fun play(sound: Sound) {
        if (level == 0) return
        val t = tracks[sound] ?: return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: IllegalStateException) {
        }
    }

    fun release() {
        synchronized(lock) {
            tracks.values.forEach { it.release() }
            tracks = emptyMap()
            theme = -1
        }
    }

    private fun buildAll(t: Timbre): Map<Sound, AudioTrack> = mapOf(
        Sound.FOCUS to build(listOf(Note(G6, 0, 0.10f)), 90, 22.0, t),
        Sound.SELECT to build(listOf(Note(C6, 0, 0.30f), Note(E6, 40, 0.25f)), 450, 9.0, t),
        Sound.START to build(
            listOf(Note(C5, 0, 0.30f), Note(E5, 140, 0.30f), Note(G5, 280, 0.30f), Note(C6, 420, 0.32f)),
            1700, 3.2, t,
        ),
        Sound.VPN_ON to build(listOf(Note(E5, 0, 0.30f), Note(B5, 130, 0.32f)), 800, 5.0, t),
        Sound.VPN_OFF to build(listOf(Note(B5, 0, 0.30f), Note(E5, 130, 0.32f)), 800, 5.0, t),
        Sound.FAVORITE to build(listOf(Note(G5, 0, 0.28f), Note(G6, 70, 0.20f)), 600, 7.0, t),
    )

    private fun build(notes: List<Note>, totalMs: Int, decay: Double, tb: Timbre): AudioTrack {
        val total = SR * totalMs / 1000
        val buf = FloatArray(total)
        val dec = decay * tb.decayMul
        for (n in notes) {
            val start = SR * n.delayMs / 1000
            val f = n.freq * tb.pitch
            for (i in start until total) {
                val t = (i - start) / SR.toDouble()
                val env = (t / 0.006).coerceAtMost(1.0) * exp(-dec * t)
                if (env < 0.0005) break               // дальше тишина, считать нет смысла
                val w = 2 * PI * f * t
                val s = sin(w) + tb.h2 * sin(2 * w) * exp(-2 * dec * t) + tb.h3 * sin(3 * w)
                buf[i] += (s * env * n.vol).toFloat()
            }
        }
        val pcm = ShortArray(total) { (buf[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack(attrs, fmt, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
        track.write(pcm, 0, pcm.size)
        return track
    }
}
