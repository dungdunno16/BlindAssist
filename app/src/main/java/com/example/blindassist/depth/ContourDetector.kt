package com.example.blindassist.depth

import com.example.blindassist.Config
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

object ContourDetector {

    fun detect(mask: Mat, frameWidth: Int, frameHeight: Int): List<Rect> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.findContours(
            mask,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val result = mutableListOf<Rect>()
        val minArea = Config.MIN_CONTOUR_AREA.toDouble()
        val frameArea = (frameWidth * frameHeight).toDouble()
        val maxArea = frameArea * Config.MAX_CONTOUR_RATIO

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minArea..maxArea) {
                result.add(Imgproc.boundingRect(contour))
            }
            contour.release() // Release MatOfPoint memory
        }

        hierarchy.release()

        return result
    }
}
