package com.bsr.noisereport

import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Demo records for showing the report without driving for a week. Signals follow the same shapes as
 * mockups/mechanic-report.html; audio is a synthetic rattle. Every record is flagged sample = true.
 */
object SampleData {
    private data class Ev(
        val daysAgo: Int, val hour: Int, val min: Int, val speed: Float, val release: Double?, val steerAt: Double? = null,
        val steerDelta: Float = 0f, val stopped: Boolean = false, val gearFrom: String? = null, val gearAt: Double = 0.0,
        val since: Int, val fan: Int, val win: String?, val withArea: Boolean, val bursts: Int,
    )

    private val events = listOf(
        Ev(12, 8, 14, 31f, -1.2, since = 9, fan = 2, win = null, withArea = true, bursts = 3),
        Ev(10, 8, 21, 28f, -0.8, since = 7, fan = 3, win = null, withArea = true, bursts = 4),
        Ev(7, 18, 47, 38f, null, steerAt = -0.5, steerDelta = 7f, since = 22, fan = 2, win = null, withArea = false, bursts = 2),
        Ev(5, 8, 9, 6f, -1.0, stopped = true, gearFrom = "N", gearAt = -2.4, since = 1, fan = 2, win = null, withArea = true, bursts = 3),
        Ev(4, 8, 17, 33f, -1.6, since = 8, fan = 1, win = "운전석 창 30% 열림", withArea = true, bursts = 3),
    )

    fun add(store: Store) {
        val symptom = store.symptom ?: return
        events.forEachIndexed { i, e ->
            val cal = Calendar.getInstance(MainActivity.KST).apply {
                add(Calendar.DAY_OF_YEAR, -e.daysAgo); set(Calendar.HOUR_OF_DAY, e.hour); set(Calendar.MINUTE, e.min); set(Calendar.SECOND, 0)
            }
            val id = "sample-" + UUID.randomUUID().toString().take(8)
            val pcm = Wav.synthRattle(40, 30.0, seed = (i + 1) * 7919, bursts = e.bursts, background = if (e.win != null) 0.5f else 0.12f)
            val file = File(store.audioDir, "$id.wav").also { Wav.write(it, pcm) }
            val sig = signals(e, i + 1)
            store.records += NoiseRecord(
                id = id, symptomId = symptom.id, markedAt = cal.timeInMillis, audioFile = file.absolutePath, signals = sig,
                minutesSinceStart = e.since, fanSpeed = e.fan, cabinTemp = null, windowNote = e.win, odometerKm = 8240f + i * 47f,
                hash = Wav.sha256(file.readBytes()), sample = true, audioSynth = true,
                area = if (e.withArea) symptom.area else null, memo = "",
            )
        }
        store.records.sortBy { it.markedAt }
        store.save()
    }

    /** Port of buildSignals() in the mockup, on a -30..+10 s grid at 0.1 s. */
    private fun signals(e: Ev, n: Int): List<Sample> {
        val rnd = Random(n * 101)
        val a = if (e.stopped) 6.0 else 2.4
        val low = if (e.release == null) e.speed.toDouble() else max(0.0, e.speed + a * e.release)
        val brakeFrom = when { e.release == null -> null; e.stopped -> -99.0; else -> e.release - 4 }
        val cap = e.speed + 12.0
        var wander = 0.0
        val out = ArrayList<Sample>()
        for (k in -300..100) {
            val t = k / 10.0
            var v = when {
                e.release == null -> e.speed.toDouble()
                t >= e.release -> min(cap, low + a * (t - e.release))
                e.stopped -> 0.0
                brakeFrom != null && t >= brakeFrom -> low + (e.release - t) * 3.5
                else -> low + 14
            }
            if (v > 0) v += (rnd.nextDouble() - 0.5) * 0.7
            wander += (rnd.nextDouble() - 0.5) * 0.25 - wander * 0.08
            var s = wander
            if (e.steerAt != null) {
                val up = ((t - e.steerAt) / 0.4).coerceIn(0.0, 1.0)
                val down = ((t - 5) / 1.5).coerceIn(0.0, 1.0)
                s += e.steerDelta * up * (1 - down)
            }
            val brake = e.release != null && brakeFrom != null && t >= brakeFrom && t < e.release
            val gear = if (e.gearFrom != null && t < e.gearAt) e.gearFrom else "D"
            out += Sample((t * 10).roundToInt() / 10.0, max(0.0, v).toFloat(), s.toFloat(), brake, gear)
        }
        return out
    }
}
