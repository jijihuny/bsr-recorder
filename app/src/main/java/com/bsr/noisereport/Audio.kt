package com.bsr.noisereport

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Keeps the most recent [keepSec] seconds of 16 kHz mono PCM in memory. Nothing is written to disk until
 * [captureAround] is called for a mark. (Speech removal by VAD is in the spec but not in this prototype.)
 */
class AudioRing(private val keepSec: Int = 40) {
    companion object { const val SR = 16000; private const val TAG = "AudioRing" }

    private val buf = ShortArray(SR * keepSec)
    private var written = 0L            // total samples ever written
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile var level = 0f; private set   // 0..1 RMS for the meter
    @Volatile var micOk = false; private set
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR / 5 * 2))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "AudioRecord init failed"); micOk = false; r?.release(); return false }
        record = r; running = true; micOk = true
        synchronized(buf) { written = 0 }
        r.startRecording()
        thread = Thread {
            val chunk = ShortArray(SR / 10)
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                var sum = 0.0
                synchronized(buf) {
                    for (i in 0 until n) {
                        buf[((written + i) % buf.size).toInt()] = chunk[i]
                        sum += chunk[i].toDouble() * chunk[i]
                    }
                    written += n
                }
                level = (sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat()
            }
        }.apply { name = "audio-ring"; start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
        record?.runCatching { stop(); release() }; record = null
        level = 0f
    }

    /** Samples written so far, used to anchor the mark position. */
    fun position(): Long = synchronized(buf) { written }

    /** Copy [before] s before and [after] s after the sample index [markPos]. Call once [after] s have passed. */
    fun copyAround(markPos: Long, before: Int, after: Int): ShortArray {
        synchronized(buf) {
            val start = maxOf(0L, markPos - before * SR, written - buf.size)
            val end = minOf(written, markPos + after * SR)
            val out = ShortArray((before + after) * SR)
            val offset = (start - (markPos - before * SR)).toInt()
            var i = start
            while (i < end) { out[offset + (i - start).toInt()] = buf[(i % buf.size).toInt()]; i++ }
            return out
        }
    }
}

object Wav {
    fun write(file: File, pcm: ShortArray, sr: Int = AudioRing.SR) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sr); putInt(sr * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size * 2)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
    }

    fun sha256(vararg parts: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun isSilent(pcm: ShortArray): Boolean = pcm.all { abs(it.toInt()) < 40 }

    /**
     * Synthetic rattle used for sample records (and mixed into silent emulator captures so the demo is audible).
     * The first burst lands exactly at [markSec].
     */
    fun synthRattle(seconds: Int, markSec: Double, seed: Int, bursts: Int = 3, background: Float = 0.12f): ShortArray {
        val rnd = Random(seed)
        val sr = AudioRing.SR
        val out = FloatArray(sr * seconds)
        var lp = 0f
        for (i in out.indices) { lp += 0.12f * ((rnd.nextFloat() * 2 - 1) - lp); out[i] = lp * background * 3 }
        val f0 = 1100 + rnd.nextFloat() * 900
        for (b in 0 until bursts) {
            val start = ((markSec + if (b == 0) 0.0 else rnd.nextDouble() * 1.4) * sr).toInt()
            val hits = 5 + rnd.nextInt(8); val gap = sr / (24 + rnd.nextInt(18)); val amp = 0.3f + rnd.nextFloat() * 0.4f
            for (h in 0 until hits) {
                val at = start + h * gap; val f = f0 * (0.9f + rnd.nextFloat() * 0.2f); val len = (sr * 0.018).toInt()
                for (i in 0 until len) if (at + i in out.indices) out[at + i] += (amp * exp(-i / (len * 0.25)) * sin(2 * PI * f * i / sr)).toFloat()
            }
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        return ShortArray(out.size) { (out[it] / peak * 0.8f * Short.MAX_VALUE).toInt().toShort() }
    }
}
