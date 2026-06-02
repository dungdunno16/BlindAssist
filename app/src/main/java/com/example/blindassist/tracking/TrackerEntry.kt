package com.example.blindassist.tracking

import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect

data class TrackerEntry(
    val id: Int,
    var box: Rect,                 // bbox raw từ detection/optical flow
    var smoothedBox: Rect,         // bbox sau Kalman smooth
    val kf: BoxKalmanFilter,
    var points: MatOfPoint2f,      // feature points cho Optical Flow
    var distanceM: Double? = null,
    var missedFrames: Int = 0
) {
    fun release() {
        kf.release()
        points.release()
    }
}
