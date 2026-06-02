package com.example.blindassist.depth

import com.example.blindassist.Config
import org.opencv.core.Rect
import kotlin.math.atan
import kotlin.math.tan

class DistanceEstimator(
    private var fy: Double = 1.0, // Default to avoid crash, updated in updateIntrinsics
    private var cy: Double = 1.0,
    var H: Double
) {
    private val smoothZ = mutableMapOf<Int, Double>()

    fun updateIntrinsics(newFy: Double, newCy: Double) {
        this.fy = newFy
        this.cy = newCy
    }

    fun estimate(trackId: Int, bbox: Rect, frameHeight: Int, tiltRad: Double?): Double? {
        if (tiltRad == null) return null

        val vBottom = bbox.y + bbox.height
        // If the bounding box touches the bottom of the frame, the object is likely cropped
        // meaning its true bottom is further down, so our distance estimate would be wrong.
        if (vBottom >= frameHeight - 5) return null

        val alpha = atan((vBottom - cy) / fy)
        val totalAngle = tiltRad + alpha

        if (totalAngle <= Math.toRadians(Config.MIN_TOTAL_ANGLE_DEG)) return null

        val rawZ = H / tan(totalAngle)

        if (rawZ < Config.MIN_RELIABLE_Z || rawZ > Config.MAX_RELIABLE_Z) return null

        val beta = Config.Z_EMA_BETA
        val previousSmoothZ = smoothZ[trackId]
        
        val currentSmoothZ = if (previousSmoothZ != null) {
            beta * rawZ + (1 - beta) * previousSmoothZ
        } else {
            rawZ
        }

        smoothZ[trackId] = currentSmoothZ
        return currentSmoothZ
    }

    fun removeTrack(trackId: Int) {
        smoothZ.remove(trackId)
    }

    fun clearAll() {
        smoothZ.clear()
    }
}
