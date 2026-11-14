package com.bsr.noisereport

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Latest vehicle state as shown on the driving screen. */
data class LiveState(val speed: Float, val steer: Float, val brake: Boolean, val gear: String)

/**
 * Commute-like drive generator. The Pleos emulator has no speed or gear source (Virtual Console has no
 * speed card), so speed and gear always come from here; brake and steering can come from the Vehicle SDK.
 */
class DriveSim(seed: Int = 7) {
    private val rnd = Random(seed)
    private var phase = 0
    private var phaseT = 0.0
    private var speed = 0f
    private var steer = 0f
    private var wander = 0f
    private var brake = false
    private var gear = "P"

    // phase: 0 park->D, 1 accelerate, 2 cruise, 3 brake, 4 slow turn, 5 accelerate again, 6 cruise, 7 brake to stop, 8 wait
    private val durations = doubleArrayOf(2.0, 8.0, 10.0, 4.0, 4.0, 7.0, 8.0, 6.0, 3.0)

    fun step(dt: Double): LiveState {
        phaseT += dt
        if (phaseT >= durations[phase]) { phaseT = 0.0; phase = (phase + 1) % durations.size; if (phase == 0) phase = 1 }
        wander += ((rnd.nextFloat() - 0.5f) * 0.6f - wander * 0.08f)
        var target = steerTargetFor(phase)
        when (phase) {
            0 -> { gear = "P"; brake = true; speed = 0f; if (phaseT > 1.5) gear = "D" }
            1, 5 -> { gear = "D"; brake = false; speed = min(42f, speed + (3.2f * dt).toFloat()) }
            2, 6 -> { brake = false; speed += ((36f - speed) * 0.05f + (rnd.nextFloat() - 0.5f) * 0.8f) }
            3 -> { brake = true; speed = max(9f, speed - (6.5f * dt).toFloat()) }
            4 -> { brake = false; speed = max(6f, min(14f, speed + ((rnd.nextFloat() - 0.4f) * 0.5f))) }
            7 -> { brake = true; speed = max(0f, speed - (7f * dt).toFloat()) }
            8 -> { brake = true; speed = 0f }
        }
        steer += (target + wander - steer) * 0.25f
        return LiveState(max(0f, speed), steer, brake, gear)
    }

    private fun steerTargetFor(p: Int) = if (p == 4) 28f else 0f
}

/**
 * Collects vehicle state at 10 Hz into a 40 s ring buffer. Sources: [DriveSim] for speed/gear and,
 * when [useVehicleSignals] is on and the Vehicle SDK delivers values, brake pedal and steering angle.
 */
class SignalHub(private val context: Context) {
    companion object { const val HZ = 10; const val KEEP_SEC = 40; private const val TAG = "SignalHub" }

    private val main = Handler(Looper.getMainLooper())
    private val sim = DriveSim()
    private var vehicle: Vehicle? = null

    @Volatile var sdkBrake: Boolean? = null; private set
    @Volatile var sdkSteer: Float? = null; private set
    @Volatile var sdkStatus: String = "Vehicle SDK: 연결 안 함 (기록을 시작할 때 연결)"; private set
    var useVehicleSignals = false

    private val ring = ArrayDeque<Pair<Long, LiveState>>()
    var live: LiveState = LiveState(0f, 0f, false, "P"); private set
    var listener: ((LiveState) -> Unit)? = null
    private var running = false

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { sdkBrake = status; markSdkOk() }
        override fun onFailed(e: Exception) { Log.w(TAG, "brake", e) }
    }
    private val steerListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { sdkSteer = angle; markSdkOk() }
        override fun onFailed(e: Exception) { Log.w(TAG, "steer", e) }
    }

    private fun markSdkOk() { sdkStatus = "Vehicle SDK 연결됨 (브레이크·조향각 수신)" }

    /** Connect lazily: only while tracking, so the home and report screens never touch the vehicle service. */
    fun connectVehicle() {
        if (vehicle != null) return
        vehicle = runCatching { Vehicle(context).also { it.initialize() } }.onFailure { sdkStatus = "Vehicle SDK 연결 실패"; Log.w(TAG, "Vehicle init failed", it) }.getOrNull()
        if (vehicle != null) sdkStatus = "Vehicle SDK 연결 중"
        vehicle?.let { v ->
            runCatching {
                v.getBrake().getCurrentBrakePedalStatus({ sdkBrake = it; markSdkOk() }, { Log.w(TAG, "brake get", it) })
                v.getSteeringWheel().getCurrentSteeringWheelAngle({ sdkSteer = it; markSdkOk() }, { Log.w(TAG, "steer get", it) })
                v.getBrake().registerBrakePedalStatus(brakeListener)
                v.getSteeringWheel().registerSteeringWheelAngle(steerListener)
            }.onFailure { sdkStatus = "Vehicle SDK 오류: ${it.message}" }
        }
    }

    val sdkAvailable get() = sdkBrake != null || sdkSteer != null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val s = sim.step(1.0 / HZ)
            val real = useVehicleSignals && sdkAvailable
            val state = if (real) s.copy(brake = sdkBrake ?: s.brake, steer = sdkSteer ?: s.steer) else s
            live = state
            val now = System.currentTimeMillis()
            synchronized(ring) {
                ring.addLast(now to state)
                while (ring.isNotEmpty() && now - ring.first().first > KEEP_SEC * 1000L) ring.removeFirst()
            }
            listener?.invoke(state)
            main.postDelayed(this, 1000L / HZ)
        }
    }

    fun start() { if (running) return; running = true; synchronized(ring) { ring.clear() }; main.post(tick) }
    fun stop() { running = false; main.removeCallbacks(tick) }

    /** Samples from [fromSec] to [toSec] relative to [markMs], resampled on a 0.1 s grid. */
    fun snapshot(markMs: Long, fromSec: Int, toSec: Int): List<Sample> {
        val copy = synchronized(ring) { ring.toList() }
        if (copy.isEmpty()) return emptyList()
        val out = ArrayList<Sample>()
        var idx = 0
        var k = fromSec * 10
        while (k <= toSec * 10) {
            val target = markMs + k * 100L
            while (idx < copy.size - 1 && copy[idx + 1].first <= target) idx++
            val (_, st) = copy[idx]
            if (copy[0].first <= target + 150) out += Sample(k / 10.0, st.speed, st.steer, st.brake, st.gear)
            k++
        }
        return out
    }

    /** One-shot reads taken when a mark is saved. Each callback may never fire on vehicles without the feature. */
    fun readContext(onDone: (fan: Int?, temp: Float?, window: String?, odo: Float?) -> Unit) {
        val v = vehicle ?: return onDone(null, null, null, null)
        var fan: Int? = null; var temp: Float? = null; var odo: Float? = null
        val windows = mutableMapOf<String, Int>()
        runCatching {
            v.getHVAC().getHvacFanSpeed(SeatArea(SeatValue.ROW_1_LEFT), { fan = it }, {})
            v.getOdometer().getCurrentDistanceDriven({ odo = it }, {})
            listOf(
                WindowValue.ROW_1_LEFT to "운전석 창", WindowValue.ROW_1_RIGHT to "조수석 창",
                WindowValue.ROW_2_LEFT to "뒷좌석 왼쪽 창", WindowValue.ROW_2_RIGHT to "뒷좌석 오른쪽 창",
                WindowValue.ROOF_TOP_1 to "선루프",
            ).forEach { (w, name) -> v.getWindow().getCurrentWindowPosition(WindowArea(w), { p -> if (p != null && p > 0) windows[name] = p }, {}) }
        }
        // Callbacks are async IPC; give them a moment, then report whatever arrived.
        main.postDelayed({
            val note = if (windows.isEmpty()) null else windows.entries.joinToString(", ") { "${it.key} 열림" }
            onDone(fan, temp, note, odo)
        }, 600)
    }

    fun readVin(onDone: (String?) -> Unit) {
        val v = vehicle ?: return onDone(null)
        runCatching { v.getCarInfo().getVINInfo({ main.post { onDone(it) } }, { main.post { onDone(null) } }) }.onFailure { onDone(null) }
    }

    fun disconnectVehicle() {
        vehicle?.let { v ->
            runCatching {
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getSteeringWheel().unregisterSteeringWheelAngle(steerListener)
                v.release()
            }
        }
        vehicle = null
        sdkBrake = null; sdkSteer = null
        sdkStatus = "Vehicle SDK: 연결 안 함 (기록을 시작할 때 연결)"
    }

    fun release() { stop(); disconnectVehicle() }
}
