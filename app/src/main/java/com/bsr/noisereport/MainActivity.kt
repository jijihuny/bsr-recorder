package com.bsr.noisereport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Car-side prototype of the noise proof recorder.
 * Flow: register symptom -> start tracking -> "방금 소리 났어" marks -> park summary -> write report -> report + QR.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var hub: SignalHub
    private val audio = AudioRing()
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var player: MediaPlayer? = null
    private var webView: WebView? = null

    private var tracking = false
    private var driveStart = 0L
    private var driveMarks = 0
    private var pendingSaves = 0
    private var screen = "home"
    private var draft = ReportDraft()
    private var editingId: String? = null
    private var liveUpdater: Runnable? = null
    /** Vehicle SDK on/off (home screen toggle). When off, every signal comes from the drive simulation. */
    private var useVehicleSdk = true

    private val dateFmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREAN).apply { timeZone = KST }

    companion object {
        /** The emulator runs on UTC; the service is Korea-only, so all dates are shown in KST. */
        val KST: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
        private const val REQ_MIC = 11
        const val PRE_SEC = 30
        const val POST_SEC = 10
        val AREAS = listOf("조수석 대시보드", "운전석 대시보드", "센터페시아", "운전석 문", "조수석 문", "뒷좌석", "트렁크·뒤쪽", "천장·선루프", "하체 앞쪽", "하체 뒤쪽")
        val SOUNDS = listOf("딸깍", "덜컹", "삐걱", "끽", "웅~", "쉬익", "드르륵")
        val MEMO_TAGS = listOf("방지턱 넘을 때", "출발할 때", "멈출 때", "핸들 돌릴 때", "추운 아침", "비 온 뒤", "고속 주행", "울퉁불퉁한 길")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        hub = SignalHub(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        showHome()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Gleo App Schema actions; also reachable with `adb shell am start -a MARK_NOISE`. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            "MARK_NOISE" -> if (tracking) mark(intent.getStringExtra("ACTION_PARAM")) else toast("기록이 켜져 있지 않아요. 먼저 기록을 시작하세요.")
            "START_TRACKING" -> if (store.symptom != null && !tracking) startDrive()
            "TRACKING_STATUS" -> toast("이번 주행 ${driveMarks}번, 지금까지 ${store.recordsForSymptom().size}번 기록했어요.")
            // Dev only: the Pleos display is FLAG_SECURE so screencap is black; render our own window instead.
            "com.bsr.noisereport.SNAPSHOT" -> main.postDelayed({ snapshot() }, 400)
        }
    }

    private fun snapshot() {
        val v = window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val out = File(filesDir, "snap.png")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    override fun onDestroy() {
        stopLive(); audio.stop(); hub.release(); player?.release(); tone?.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "home" -> @Suppress("DEPRECATION") super.onBackPressed()
            "drive" -> toast("기록 중에는 '주차하고 기록 끝내기'를 눌러 주세요.")
            "preview" -> showCompose()
            else -> showHome()
        }
    }

    // ---------------------------------------------------------------- common pieces

    private fun setScreen(name: String, content: View) {
        screen = name
        stopLive()
        webView = null
        setContentView(content)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun header(title: String, sub: String? = null, right: View? = null) = hbox(gap = 24) {
        addView(vbox(gap = 6) {
            addView(text(title, 40f, bold = true))
            if (sub != null) addView(text(sub, 22f, C.muted))
        }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) addView(right)
    }

    private fun stopLive() { liveUpdater?.let { main.removeCallbacks(it) }; liveUpdater = null }

    // ---------------------------------------------------------------- home

    private fun showHome() {
        val sym = store.symptom
        val recs = store.recordsForSymptom()
        val root = vbox(pad = 48, gap = 32) {
            addView(header("잡소리 리포트"))
            addView(hbox(gap = 32) {
                addView(cardBox(pad = 36, gap = 20) {
                    addView(text("등록한 증상", 22f, C.muted))
                    if (sym == null) {
                        addView(text("아직 없어요", 36f, bold = true))
                        addView(button("증상 등록하기") { showRegister() })
                    } else {
                        addView(text(sym.title, 40f, bold = true))
                        addView(text("등록 ${dateFmt.format(sym.createdAt)} · 지금까지 ${recs.size}번 기록", 22f, C.muted))
                        addView(hbox(gap = 20) {
                            addView(button("기록 시작하고 출발", size = 28f) { startDrive() })
                            addView(button("리포트 쓰기 (${recs.size}건)", "ghost", 26f) { showCompose() }.apply { setEnabledLook(recs.isNotEmpty()) })
                        })
                    }
                }.weight(1.4f))
                addView(cardBox(pad = 36, gap = 14) {
                    addView(text("차량 신호", 22f, C.muted))
                    addView(chip(if (useVehicleSdk) "Vehicle SDK 켜짐" else "Vehicle SDK 꺼짐", useVehicleSdk, 20f) {
                        useVehicleSdk = !useVehicleSdk; showHome()
                    })
                }.weight(1f))
            }, lp(h = 0, weight = 1f))
            addView(hbox(gap = 16) {
                addView(text("시연 도구", 20f, C.muted))
                addView(button("샘플 기록 5건 넣기", "ghost", 20f) {
                    if (store.symptom == null) {
                        store.symptom = Symptom(UUID.randomUUID().toString(), AREAS[0], SOUNDS[0], System.currentTimeMillis()); store.save()
                    }
                    SampleData.add(store); showHome()
                })
                addView(button("모든 기록 지우기", "ghost", 20f) { store.clearAll(); draft = ReportDraft(); showHome() })
            })
        }
        setScreen("home", root)
    }

    private fun showRegister() {
        var area: String? = store.symptom?.area
        var sound: String? = store.symptom?.sound
        lateinit var render: () -> Unit
        val body = vbox(gap = 28)
        render = {
            body.removeAllViews()
            body.addView(text("어디서 들리나요?", 28f, bold = true))
            body.addView(flow { AREAS.forEach { a -> addView(chip(a, a == area, 24f) { area = a; render() }) } })
            body.addView(text("어떤 소리인가요?", 28f, bold = true))
            body.addView(flow { SOUNDS.forEach { s -> addView(chip(s, s == sound, 24f) { sound = s; render() }) } })
            body.addView(hbox(gap = 20) {
                addView(button("등록", size = 28f) {
                    val a = area; val s = sound
                    if (a == null || s == null) { toast("위치와 소리를 골라 주세요."); return@button }
                    val old = store.symptom
                    store.symptom = if (old != null) old.copy(area = a, sound = s) else Symptom(UUID.randomUUID().toString(), a, s, System.currentTimeMillis())
                    store.save(); showHome()
                }.apply { setEnabledLook(area != null && sound != null) })
                addView(button("취소", "ghost", 26f) { showHome() })
            })
        }
        render()
        val root = vbox(pad = 48, gap = 32) {
            addView(header("증상 등록"))
            addView(scroll(body), lp(h = 0, weight = 1f))
        }
        setScreen("register", root)
    }

    // ---------------------------------------------------------------- driving

    private fun startDrive() {
        if (store.symptom == null) { showRegister(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        beginDrive()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("마이크 없이 진행해요. 소리 대신 시연용 합성음이 저장됩니다.")
            beginDrive()
        }
    }

    private fun beginDrive() {
        tracking = true; driveStart = System.currentTimeMillis(); driveMarks = 0
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audio.start()
        if (useVehicleSdk) {
            hub.connectVehicle()
            hub.readVin { vin -> if (!vin.isNullOrBlank()) { store.vin = vin; store.vehicleLabel = "Pleos Connect 차량" } }
        }
        hub.start()
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        showDriving()
    }

    private lateinit var tSpeed: TextView
    private lateinit var tGear: TextView
    private lateinit var tBrake: TextView
    private lateinit var tSteer: TextView
    private lateinit var tElapsed: TextView
    private lateinit var tCount: TextView
    private lateinit var tSaving: TextView
    private lateinit var meter: View
    private lateinit var meterBox: FrameLayout
    private var markBtn: View? = null
    private lateinit var tSdk: TextView

    private fun tile(label: String, value: TextView) = cardBox(pad = 28, gap = 6) {
        addView(text(label, 22f, C.muted))
        addView(value)
    }

    private fun showDriving() {
        val sym = store.symptom!!
        tSpeed = text("0", 64f, bold = true); tGear = text("P", 64f, bold = true)
        tBrake = text("-", 48f, bold = true); tSteer = text("0°", 64f, bold = true)
        tElapsed = text("", 24f, C.muted); tCount = text("", 30f, bold = true)
        tSaving = text("", 24f, C.warn)
        tSdk = text("", 20f, C.muted)
        meterBox = FrameLayout(this).apply { background = rounded(C.card2, dp(10).toFloat()) }
        meter = View(this).apply { background = rounded(C.ok, dp(10).toFloat()) }
        meterBox.addView(meter, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))

        val recDot = text("●  기록 중", 28f, C.rec, bold = true)
        val root = vbox(pad = 40, gap = 28) {
            addView(hbox(gap = 24) {
                addView(recDot)
                addView(text(sym.title, 28f, bold = true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(tElapsed)
            })
            addView(hbox(gap = 20) {
                addView(tile("속도 km/h", tSpeed).weight(1f))
                addView(tile("기어", tGear).weight(1f))
                addView(tile("브레이크", tBrake).weight(1f))
                addView(tile("조향각 (앞바퀴)", tSteer).weight(1f))
                addView(cardBox(pad = 28, gap = 14) {
                    addView(text("소리", 22f, C.muted))
                    addView(meterBox, lp(h = dp(36)))
                }.weight(1.4f))
            }, lp(h = dp(200)))
            addView(hbox(gap = 32) {
                addView(button("방금 소리 났어", "danger", 56f) { mark(null) }.also { markBtn = it }.apply {
                    setPadding(dp(40), dp(60), dp(40), dp(60))
                }.weight(2f))
                addView(vbox(gap = 16) {
                    addView(tCount)
                    addView(tSaving)
                    if (useVehicleSdk) {
                        addView(chip("Virtual Console 값 사용", hub.useVehicleSignals, 20f) {
                            hub.useVehicleSignals = !hub.useVehicleSignals; showDriving()
                        })
                    }
                }.weight(1.3f))
            }, lp(h = 0, weight = 1f))
            addView(hbox(gap = 20) {
                addView(View(this@MainActivity), lp(0, 1, 1f))
                addView(button("주차하고 기록 끝내기", "ghost", 26f) { endDrive() })
            })
        }
        setScreen("drive", root)
        updateCounts()
        val r = object : Runnable {
            override fun run() {
                val s = hub.live
                tSpeed.text = s.speed.toInt().toString()
                tGear.text = s.gear
                tBrake.text = if (s.brake) "밟음" else "안 밟음"
                tBrake.setTextColor(if (s.brake) C.warn else C.ink)
                tSteer.text = "${s.steer.toInt()}°"
                val sec = (System.currentTimeMillis() - driveStart) / 1000
                tElapsed.text = "주행 %d:%02d".format(sec / 60, sec % 60)
                val w = meterBox.width
                meter.layoutParams = (meter.layoutParams as FrameLayout.LayoutParams).apply { width = (w * audio.level).toInt().coerceAtLeast(if (audio.micOk) dp(4) else 0) }
                meter.requestLayout()
                recDot.alpha = if ((sec % 2L) == 0L) 1f else 0.45f
                tSdk.text = hub.sdkStatus + (hub.sdkBrake?.let { " · 브레이크 ${if (it) "밟음" else "안 밟음"}" } ?: "") + (hub.sdkSteer?.let { " · 조향 ${it.toInt()}°" } ?: "")
                main.postDelayed(this, 100)
            }
        }
        liveUpdater = r; main.post(r)
    }

    private fun updateCounts() {
        if (screen != "drive") return
        tCount.text = "이번 주행 ${driveMarks}번 · 지금까지 ${store.recordsForSymptom().size}번"
        tSaving.text = if (pendingSaves > 0) "저장 중…" else ""
    }

    /** C-05: keep 30 s before and 10 s after. The confirmation tone plays immediately. */
    private fun mark(location: String?) {
        val markMs = System.currentTimeMillis()
        val pos = audio.position()
        val sinceMin = ((markMs - driveStart) / 60000).toInt()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        driveMarks++; pendingSaves++
        updateCounts()
        markBtn?.animate()?.scaleX(0.96f)?.scaleY(0.96f)?.alpha(0.6f)?.setDuration(90)?.withEndAction {
            markBtn?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(160)?.start()
        }?.start()
        main.postDelayed({
            val sig = hub.snapshot(markMs, -PRE_SEC, POST_SEC)
            var pcm = if (audio.micOk) audio.copyAround(pos, PRE_SEC, POST_SEC) else ShortArray(0)
            val synth = pcm.isEmpty() || Wav.isSilent(pcm)
            if (synth) pcm = Wav.synthRattle(PRE_SEC + POST_SEC, PRE_SEC.toDouble(), seed = (markMs % 100000).toInt())
            val id = "r-" + UUID.randomUUID().toString().take(8)
            val file = File(store.audioDir, "$id.wav").also { Wav.write(it, pcm) }
            val sigBytes = sig.joinToString(";") { "${it.t},${it.speed},${it.steer},${it.brake},${it.gear}" }.toByteArray()
            hub.readContext { fan, temp, win, odo ->
                store.records += NoiseRecord(
                    id = id, symptomId = store.symptom!!.id, markedAt = markMs, audioFile = file.absolutePath, signals = sig,
                    minutesSinceStart = sinceMin, fanSpeed = fan, cabinTemp = temp, windowNote = win, odometerKm = odo,
                    hash = Wav.sha256(file.readBytes(), sigBytes), sample = false, audioSynth = synth,
                    area = location?.takeIf { it.isNotBlank() }, memo = "",
                )
                store.records.sortBy { it.markedAt }
                store.save()
                pendingSaves--
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                updateCounts()
                if (screen == "parked") showParked()
            }
        }, POST_SEC * 1000L + 200)
    }

    private fun endDrive() {
        tracking = false
        hub.stop(); audio.stop(); hub.disconnectVehicle()
        showParked()
    }

    private fun showParked() {
        val total = store.recordsForSymptom().size
        val root = vbox(pad = 64, gap = 36) {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("오늘 ${driveMarks}번 기록, 지금까지 ${total}번", 64f, bold = true))
            if (pendingSaves > 0) addView(text("저장 중…", 26f, C.warn))
            addView(hbox(gap = 20) {
                addView(button("리포트 쓰기", size = 30f) { showCompose() }.apply { setEnabledLook(total > 0) })
                addView(button("처음으로", "ghost", 28f) { showHome() })
            })
        }
        setScreen("parked", root)
    }

    // ---------------------------------------------------------------- report writing

    private fun showCompose() {
        val recs = store.recordsForSymptom().sortedByDescending { it.markedAt }
        if (recs.isEmpty()) { toast("아직 기록이 없어요."); showHome(); return }
        if (draft.selected.isEmpty()) draft.selected.addAll(recs.map { it.id })
        draft.selected.retainAll(recs.map { it.id }.toSet())
        if (editingId == null || recs.none { it.id == editingId }) editingId = recs.first().id

        val list = vbox(gap = 14)
        val editor = vbox(gap = 22)
        val bottom = hbox(gap = 20)
        lateinit var renderAll: () -> Unit

        fun renderList() {
            list.removeAllViews()
            recs.forEach { r ->
                val sel = r.id in draft.selected
                val editing = r.id == editingId
                list.addView(hbox(pad = 20, gap = 20) {
                    background = rounded(if (editing) C.card2 else C.card, dp(14).toFloat(), if (editing) C.accent else null, dp(2))
                    addView(text(if (sel) "☑" else "☐", 40f, if (sel) C.accent else C.muted).apply {
                        setOnClickListener { if (sel) draft.selected.remove(r.id) else draft.selected.add(r.id); renderAll() }
                    })
                    addView(vbox(gap = 4) {
                        addView(text(dateFmt.format(r.markedAt) + if (r.sample) "  · 샘플" else "", 24f, bold = true))
                        val where = r.area ?: "위치 입력 안 함"
                        val extra = listOfNotNull(r.memo.takeIf { it.isNotBlank() }, r.windowNote).joinToString(" · ")
                        addView(text("${r.speedAtMark.toInt()} km/h · ${changeSummary(r)} · $where" + if (extra.isNotEmpty()) " · $extra" else "", 20f, C.muted))
                    }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button("▶ 듣기", "ghost", 20f) { playClip(r, 0, (PRE_SEC + POST_SEC) * 1000) })
                    setOnClickListener { editingId = r.id; renderAll() }
                })
            }
        }

        fun renderEditor() {
            editor.removeAllViews()
            val r = recs.first { it.id == editingId }
            editor.addView(text("선택한 기록 · ${dateFmt.format(r.markedAt)}", 26f, bold = true))
            editor.addView(text("소리 위치", 22f, C.muted))
            editor.addView(flow {
                AREAS.forEach { a -> addView(chip(a, r.area == a, 20f) { r.area = if (r.area == a) null else a; store.save(); renderAll() }) }
            })
            editor.addView(text("메모", 22f, C.muted))
            val tags = r.memo.split(", ").filter { it.isNotBlank() }.toMutableSet()
            editor.addView(flow {
                MEMO_TAGS.forEach { m ->
                    addView(chip(m, m in tags, 20f) {
                        if (m in tags) tags.remove(m) else tags.add(m)
                        r.memo = MEMO_TAGS.filter { it in tags }.joinToString(", "); store.save(); renderAll()
                    })
                }
            })
            editor.addView(View(this).apply { setBackgroundColor(C.line) }, lp(h = dp(2)))
            editor.addView(text("받는 곳", 26f, bold = true))
            editor.addView(flow {
                addView(chip("정하지 않음", draft.shop == null, 20f) { draft.shop = null; renderAll() })
                SHOPS.forEach { s -> addView(chip("${s.name} · ${s.region}", draft.shop == s, 20f) { draft.shop = s; renderAll() }) }
            })
            editor.addView(text("리포트에 넣을 것", 26f, bold = true))
            editor.addView(flow {
                addView(chip("소리 원본", draft.includeAudio, 20f) { draft.includeAudio = !draft.includeAudio; renderAll() })
                addView(chip("차량 신호", draft.includeSignals, 20f) { draft.includeSignals = !draft.includeSignals; renderAll() })
                addView(chip("창문·공조 상태", draft.includeEnv, 20f) { draft.includeEnv = !draft.includeEnv; renderAll() })
                addView(chip("상황 메모", draft.includeMemo, 20f) { draft.includeMemo = !draft.includeMemo; renderAll() })
            })
        }

        fun renderBottom() {
            bottom.removeAllViews()
            bottom.addView(button("← 처음으로", "ghost", 24f) { stopPlayback(); showHome() })
            bottom.addView(View(this), lp(0, 1, 1f))
            bottom.addView(button("리포트 만들기 (${draft.selected.size}건)", size = 28f) {
                stopPlayback()
                val built = ReportBuilder.build(this, store, draft)
                showPreview(built)
            }.apply { setEnabledLook(draft.selected.isNotEmpty()) })
        }

        renderAll = { renderList(); renderEditor(); renderBottom() }
        renderAll()

        val root = vbox(pad = 40, gap = 24) {
            addView(header("리포트 쓰기"))
            addView(hbox(gap = 28) {
                addView(scroll(list).weight(1.15f))
                addView(scroll(cardBox(pad = 32, gap = 0) { addView(editor) }).weight(1f))
            }, lp(h = 0, weight = 1f))
            addView(bottom)
        }
        setScreen("compose", root)
    }

    /** Short text for the list: what changed around the mark (same rule as the report, -3 s..+1 s). */
    private fun changeSummary(r: NoiseRecord): String {
        val w = r.signals.filter { it.t >= -3.0 && it.t <= 1.0 }
        if (w.size < 2) return "신호 없음"
        val out = mutableListOf<String>()
        for (i in 1 until w.size) {
            if (w[i].brake != w[i - 1].brake) { out.removeAll { it.startsWith("브레이크") }; out += if (w[i].brake) "브레이크 밟음" else "브레이크 놓음" }
            if (w[i].gear != w[i - 1].gear) { out.removeAll { it.startsWith("기어") }; out += "기어 ${w[i - 1].gear}→${w[i].gear}" }
        }
        if (w.any { abs(it.steer - w[0].steer) >= 5 }) out += "조향 변화"
        return if (out.isEmpty()) "변화 없음" else out.joinToString(", ")
    }

    // ---------------------------------------------------------------- report preview

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPreview(built: BuiltReport) {
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 135
            setBackgroundColor(Color.WHITE)
            addJavascriptInterface(Bridge(), "BSRBridge")
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("ReportWeb", "${m.messageLevel()} ${m.message()} @${m.lineNumber()}"); return true
                }
            }
            loadDataWithBaseURL("file:///android_asset/", built.html, "text/html", "utf-8", null)
        }
        val qrSize = dp(360)
        val side = cardBox(pad = 32, gap = 18) {
            draft.shop?.let { addView(text(it.name, 26f, bold = true)) }
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(ReportBuilder.qr(built.link, 480))
                background = rounded(Color.WHITE, dp(12).toFloat())
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, LinearLayout.LayoutParams(qrSize, qrSize))
            addView(text(built.code, 26f, bold = true).apply { typeface = Typeface.MONOSPACE })
            addView(text("~ ${built.expires}", 20f, C.muted))
            addView(button("다시 고치기", "ghost", 24f) { stopPlayback(); showCompose() })
            addView(button("처음으로", "ghost", 24f) { stopPlayback(); showHome() })
        }
        val root = hbox(pad = 32, gap = 28) {
            addView(FrameLayout(this@MainActivity).apply {
                background = rounded(Color.WHITE, dp(16).toFloat()); clipToOutline = true
                addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }.weight(2.2f))
            addView(scroll(side).weight(1f))
        }
        setScreen("preview", root)
        webView = wv
    }

    inner class Bridge {
        @JavascriptInterface
        fun play(id: String, startMs: Int, durMs: Int) {
            main.post { store.records.firstOrNull { it.id == id }?.let { playClip(it, startMs, durMs) } }
        }

        @JavascriptInterface
        fun stop() { main.post { stopPlayback(notifyWeb = false) } }
    }

    // ---------------------------------------------------------------- audio playback

    private val stopRunnable = Runnable { stopPlayback() }

    private fun playClip(r: NoiseRecord, startMs: Int, durMs: Int) {
        stopPlayback(notifyWeb = false)
        val f = r.audioFile?.let { File(it) }?.takeIf { it.exists() } ?: return toast("소리 파일이 없어요.")
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnPreparedListener { it.seekTo(startMs); it.start() }
            setOnCompletionListener { stopPlayback() }
            prepareAsync()
        }
        main.postDelayed(stopRunnable, durMs.toLong() + 300)
    }

    private fun stopPlayback(notifyWeb: Boolean = true) {
        main.removeCallbacks(stopRunnable)
        player?.runCatching { stop(); release() }
        player = null
        if (notifyWeb) webView?.evaluateJavascript("window.onNativePlaybackEnded && window.onNativePlaybackEnded()", null)
    }
}
