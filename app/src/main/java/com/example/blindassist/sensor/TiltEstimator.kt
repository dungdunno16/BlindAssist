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
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

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
        } else if (gravitySensor != null) {
            sensorManager.registerListener(
                this,
                gravitySensor,
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
        if (event == null) return
        
        var newTiltRad: Double? = null

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // angles[1] is pitch
            val pitch = orientationAngles[1].toDouble()
            newTiltRad = pitch + Math.PI / 2
        } else if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            
            // atan2(y, z) where y is along long-axis and z is perpendicular to screen
            // If device is vertical, y is ~9.8, z is ~0 -> atan2(9.8, 0) = PI/2
            newTiltRad = kotlin.math.atan2(y, z)
        }

        if (newTiltRad != null) {
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
