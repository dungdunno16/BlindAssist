package com.example.blindassist.tracking

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Core
import org.opencv.video.KalmanFilter

class BoxKalmanFilter(box: Rect) {
    val kf = KalmanFilter(8, 4)

    init {
        // Transition matrix: cx'=cx+dx, cy'=cy+dy, w'=w+dw, h'=h+dh
        kf.set_transitionMatrix(Mat.eye(8, 8, CvType.CV_32F).also { m ->
            m.put(0, 4, floatArrayOf(1f))
            m.put(1, 5, floatArrayOf(1f))
            m.put(2, 6, floatArrayOf(1f))
            m.put(3, 7, floatArrayOf(1f))
        })

        // Measurement matrix: chỉ observe [cx, cy, w, h]
        kf.set_measurementMatrix(Mat.zeros(4, 8, CvType.CV_32F).also { m ->
            m.put(0, 0, floatArrayOf(1f))
            m.put(1, 1, floatArrayOf(1f))
            m.put(2, 2, floatArrayOf(1f))
            m.put(3, 3, floatArrayOf(1f))
        })

        // Noise
        kf.set_processNoiseCov(Mat.eye(8, 8, CvType.CV_32F).also {
            Core.multiply(it, Scalar(1e-2), it)
        })
        kf.set_measurementNoiseCov(Mat.eye(4, 4, CvType.CV_32F).also {
            Core.multiply(it, Scalar(1e-1), it)
        })
        kf.set_errorCovPost(Mat.eye(8, 8, CvType.CV_32F))

        // Initial state
        val cx = (box.x + box.width / 2.0f)
        val cy = (box.y + box.height / 2.0f)
        val state = kf.get_statePost()
        state.put(0, 0, floatArrayOf(
            cx, cy, box.width.toFloat(), box.height.toFloat(),
            0f, 0f, 0f, 0f
        ))
        kf.set_statePost(state)
    }

    fun predict(): Rect {
        val pred = kf.predict()
        return stateToRect(pred)
    }

    fun update(box: Rect) {
        val cx = box.x + box.width / 2.0f
        val cy = box.y + box.height / 2.0f
        val measurement = Mat(4, 1, CvType.CV_32F)
        measurement.put(0, 0, floatArrayOf(
            cx, cy, box.width.toFloat(), box.height.toFloat()
        ))
        kf.correct(measurement)
        measurement.release()
    }

    fun getState(): Rect = stateToRect(kf.get_statePost())

    private fun stateToRect(mat: Mat): Rect {
        val cx = mat.get(0, 0)[0].toFloat()
        val cy = mat.get(1, 0)[0].toFloat()
        val w  = mat.get(2, 0)[0].toFloat().coerceAtLeast(1f)
        val h  = mat.get(3, 0)[0].toFloat().coerceAtLeast(1f)
        return Rect((cx - w / 2).toInt(), (cy - h / 2).toInt(), w.toInt(), h.toInt())
    }

    fun release() {
        kf.get_transitionMatrix().release()
        kf.get_measurementMatrix().release()
        kf.get_processNoiseCov().release()
        kf.get_measurementNoiseCov().release()
        kf.get_errorCovPost().release()
    }
}
