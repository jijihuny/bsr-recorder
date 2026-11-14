package com.bsr.noisereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One 10 Hz vehicle-state sample. [t] is seconds relative to the noise mark (negative = before). */
data class Sample(
    val t: Double,
    val speed: Float,
    val steer: Float,
    val brake: Boolean,
    val gear: String,
) {
    fun toJson() = JSONObject().apply {
        put("t", t); put("speed", speed.toDouble()); put("steer", steer.toDouble())
        put("brake", brake); put("gear", gear)
    }

    companion object {
        fun from(o: JSONObject) = Sample(
            o.getDouble("t"), o.getDouble("speed").toFloat(), o.getDouble("steer").toFloat(),
            o.getBoolean("brake"), o.getString("gear"),
        )
    }
}

/** The noise the driver registered once, e.g. "조수석 대시보드 · 딸깍". */
data class Symptom(val id: String, val area: String, val sound: String, val createdAt: Long) {
    val title get() = "$area · $sound"
    fun toJson() = JSONObject().apply {
        put("id", id); put("area", area); put("sound", sound); put("createdAt", createdAt)
    }

    companion object {
        fun from(o: JSONObject) = Symptom(o.getString("id"), o.getString("area"), o.getString("sound"), o.getLong("createdAt"))
    }
}

/** One saved mark: 30 s before + 10 s after of audio and vehicle state. */
data class NoiseRecord(
    val id: String,
    val symptomId: String,
    val markedAt: Long,
    val audioFile: String?,
    val signals: List<Sample>,
    val minutesSinceStart: Int,
    val fanSpeed: Int?,
    val cabinTemp: Float?,
    val windowNote: String?,
    val odometerKm: Float?,
    val hash: String,
    val sample: Boolean,
    val audioSynth: Boolean,
    var area: String?,
    var memo: String,
) {
    val speedAtMark: Float get() = signals.minByOrNull { kotlin.math.abs(it.t) }?.speed ?: 0f

    fun toJson() = JSONObject().apply {
        put("id", id); put("symptomId", symptomId); put("markedAt", markedAt)
        put("audioFile", audioFile ?: JSONObject.NULL)
        put("signals", JSONArray().apply { signals.forEach { put(it.toJson()) } })
        put("minutesSinceStart", minutesSinceStart)
        put("fanSpeed", fanSpeed ?: JSONObject.NULL)
        put("cabinTemp", cabinTemp?.toDouble() ?: JSONObject.NULL)
        put("windowNote", windowNote ?: JSONObject.NULL)
        put("odometerKm", odometerKm?.toDouble() ?: JSONObject.NULL)
        put("hash", hash); put("sample", sample); put("audioSynth", audioSynth)
        put("area", area ?: JSONObject.NULL); put("memo", memo)
    }

    companion object {
        fun from(o: JSONObject): NoiseRecord {
            val arr = o.getJSONArray("signals")
            return NoiseRecord(
                id = o.getString("id"),
                symptomId = o.getString("symptomId"),
                markedAt = o.getLong("markedAt"),
                audioFile = o.optStringOrNull("audioFile"),
                signals = (0 until arr.length()).map { Sample.from(arr.getJSONObject(it)) },
                minutesSinceStart = o.optInt("minutesSinceStart"),
                fanSpeed = if (o.isNull("fanSpeed")) null else o.getInt("fanSpeed"),
                cabinTemp = if (o.isNull("cabinTemp")) null else o.getDouble("cabinTemp").toFloat(),
                windowNote = o.optStringOrNull("windowNote"),
                odometerKm = if (o.isNull("odometerKm")) null else o.getDouble("odometerKm").toFloat(),
                hash = o.optString("hash"),
                sample = o.optBoolean("sample"),
                audioSynth = o.optBoolean("audioSynth"),
                area = o.optStringOrNull("area"),
                memo = o.optString("memo"),
            )
        }
    }
}

fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

/** Plain JSON-file persistence. Small data, single process: good enough for the prototype. */
class Store(private val context: Context) {
    private val file = File(context.filesDir, "store.json")
    var symptom: Symptom? = null
    val records = mutableListOf<NoiseRecord>()
    var vehicleLabel: String = "Pleos Connect 에뮬레이터"
    var vin: String? = null

    val audioDir: File get() = File(context.filesDir, "audio").apply { mkdirs() }

    init { load() }

    fun load() {
        records.clear(); symptom = null
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            symptom = o.optJSONObject("symptom")?.let { Symptom.from(it) }
            val arr = o.optJSONArray("records") ?: JSONArray()
            for (i in 0 until arr.length()) records += NoiseRecord.from(arr.getJSONObject(i))
            records.sortBy { it.markedAt }
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("symptom", symptom?.toJson() ?: JSONObject.NULL)
        o.put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        file.writeText(o.toString())
    }

    fun recordsForSymptom(): List<NoiseRecord> = symptom?.let { s -> records.filter { it.symptomId == s.id } } ?: emptyList()

    fun clearAll() {
        records.clear(); symptom = null
        audioDir.listFiles()?.forEach { it.delete() }
        save()
    }
}
