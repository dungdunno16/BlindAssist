package com.example.blindassist.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.blindassist.Config
import kotlin.math.abs

class TiltEstimator(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    private var _tiltRad: Double? = null

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    private val deadbandRad = Math.toRadians(Config.TILT_DEADBAND_DEG)

    fun start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(
                this,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun getTiltRad(): Double? {
        return _tiltRad
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // angles[1] is pitch
            val pitch = orientationAngles[1].toDouble()
            val newTiltRad = pitch + Math.PI / 2

            val currentTilt = _tiltRad
            if (currentTilt == null || abs(newTiltRad - currentTilt) > deadbandRad) {
                _tiltRad = newTiltRad
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }
}
