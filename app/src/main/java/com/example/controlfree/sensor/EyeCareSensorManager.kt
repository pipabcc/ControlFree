package com.example.controlfree.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

internal enum class EyeDistanceStatus {
    TOO_CLOSE,
    SAFE
}

internal class EyeCareSensorManager(
    context: Context,
    private val onStatusChanged: (EyeDistanceStatus) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var currentStatus = EyeDistanceStatus.SAFE

    fun start() {
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val distance = event.values[0]
        val maxRange = event.sensor.maximumRange
        
        // 接近传感器临界判断：距离过小通常代表遮挡，这里判定为视距过近
        val isClose = distance < 4.0f || (maxRange > 5.0f && distance < (maxRange * 0.3f))
        val nextStatus = if (isClose) EyeDistanceStatus.TOO_CLOSE else EyeDistanceStatus.SAFE

        if (nextStatus != currentStatus) {
            currentStatus = nextStatus
            onStatusChanged(currentStatus)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
