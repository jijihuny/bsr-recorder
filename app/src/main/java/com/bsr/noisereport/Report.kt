package com.bsr.noisereport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Shops that handle cabin noise as a regular service (from claude/bsr_shops_2026-11-03.csv in the project). */
data class Shop(val name: String, val region: String, val type: String)

val SHOPS = listOf(
    Shop("프람티드카랩", "경기 고양시", "잡소리 전문"),
    Shop("노이즈게러지", "경기 파주시", "잡소리 전문"),
    Shop("체인지업카", "대구 수성구", "잡소리 전문"),
    Shop("카오디오플러스", "서울 성동구", "카오디오·방음"),
    Shop("카사운드매니아", "서울 양천구", "카오디오·방음"),
    Shop("팀리더스", "부산 해운대구", "방음 전문"),
    Shop("노이즈킹 본점", "경기 용인시", "방음 전문"),
)

class ReportDraft {
    var shop: Shop? = null
    var includeAudio = true
    var includeSignals = true
    var includeEnv = true
    var includeMemo = true
    val selected = linkedSetOf<String>()
}

data class BuiltReport(val html: String, val code: String, val link: String, val expires: String, val hash: String)

object ReportBuilder {
    private val dayFmt = SimpleDateFormat("MM/dd (E)", Locale.KOREAN).apply { timeZone = MainActivity.KST }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREAN).apply { timeZone = MainActivity.KST }
    private val isoDay = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).apply { timeZone = MainActivity.KST }

    fun build(context: Context, store: Store, draft: ReportDraft): BuiltReport {
        val recs = store.recordsForSymptom().filter { it.id in draft.selected }.sortedBy { it.markedAt }
        val now = System.currentTimeMillis()
        val expires = now + 30L * 24 * 3600 * 1000
        val hash = Wav.sha256(*recs.map { it.hash.toByteArray() }.toTypedArray())
        val code = hash.take(6).uppercase()
        val link = "https://bsr-report.app/r/$code"

        val events = JSONArray()
        recs.forEachIndexed { i, r ->
            events.put(JSONObject().apply {
                put("n", i + 1)
                put("id", r.id)
                put("date", dayFmt.format(r.markedAt))
                put("time", timeFmt.format(r.markedAt))
                put("speed", r.speedAtMark.toInt())
                put("area", r.area ?: JSONObject.NULL)
                put("memo", if (draft.includeMemo) r.memo else "")
                put("since", r.minutesSinceStart)
                put("fan", if (draft.includeEnv) (r.fanSpeed ?: JSONObject.NULL) else JSONObject.NULL)
                put("temp", if (draft.includeEnv) (r.cabinTemp?.toDouble() ?: JSONObject.NULL) else JSONObject.NULL)
                put("win", if (draft.includeEnv) (r.windowNote ?: JSONObject.NULL) else JSONObject.NULL)
                put("odo", r.odometerKm?.toDouble() ?: JSONObject.NULL)
                put("audio", draft.includeAudio && r.audioFile != null)
                put("synth", r.audioSynth)
                put("sample", r.sample)
                put("hash", r.hash.take(12))
                // Chart window -15..+10 s at 0.2 s keeps the page light; the full 40 s stays on the car.
                put("sig", JSONArray().apply {
                    r.signals.filter { it.t >= -15.0 && it.t <= 10.0 && ((it.t * 10).toInt() % 2 == 0) }.forEach { put(it.toJson()) }
                })
            })
        }
        val sym = store.symptom
        val data = JSONObject().apply {
            put("symptom", sym?.title ?: "")
            put("symptomArea", sym?.area ?: "")
            put("vehicle", store.vehicleLabel)
            put("vin", store.vin?.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{11,17}")) }?.takeLast(6) ?: JSONObject.NULL)
            put("from", recs.firstOrNull()?.let { isoDay.format(it.markedAt) } ?: "")
            put("to", recs.lastOrNull()?.let { isoDay.format(it.markedAt) } ?: "")
            put("created", isoDay.format(now))
            put("expires", isoDay.format(expires))
            put("shop", draft.shop?.let { "${it.name} (${it.region})" } ?: JSONObject.NULL)
            put("includeSignals", draft.includeSignals)
            put("includeAudio", draft.includeAudio)
            put("includeEnv", draft.includeEnv)
            put("anySample", recs.any { it.sample })
            put("anySynth", recs.any { it.audioSynth })
            put("hash", hash.take(16))
            put("code", code)
            put("events", events)
        }
        val tpl = context.assets.open("report.html").bufferedReader().readText()
        val html = tpl.replace("/*__BSR_DATA__*/null", data.toString())
        return BuiltReport(html, code, link, isoDay.format(expires), hash)
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
