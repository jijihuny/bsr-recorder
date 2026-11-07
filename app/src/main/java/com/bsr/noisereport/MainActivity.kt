package com.bsr.noisereport

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** First Vehicle SDK call from the Pleos getting-started guide: read the driver door position. */
class MainActivity : AppCompatActivity() {

    private lateinit var vehicle: Vehicle
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 24f
            setPadding(48, 48, 48, 48)
            text = "Reading driver door position..."
        }
        setContentView(output)

        vehicle = Vehicle(this)
        vehicle.initialize()
        vehicle.getDoor().getCurrentDoorPosition(
            area = DoorArea(DoorValue.ROW_1_LEFT),
            onComplete = { pos -> show("Driver door position: $pos") },
            onFailed = { e -> show("Vehicle SDK error: ${e.message}") },
        )
    }

    private fun show(message: String) {
        Log.i(TAG, message)
        runOnUiThread { output.text = message }
    }

    override fun onDestroy() {
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "VehicleCheck"
    }
}
