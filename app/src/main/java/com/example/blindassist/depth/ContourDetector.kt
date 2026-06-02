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
        // Fix: Use mask dimensions instead of camera frame dimensions
        val maxArea = (mask.cols() * mask.rows()) * Config.MAX_CONTOUR_RATIO.toDouble()
        
        val minWidth = mask.cols() * 0.15 // at least 5% width
        val minHeight = mask.rows() * 0.15 // at least 5% height

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minArea..maxArea) {
                val bbox = Imgproc.boundingRect(contour)
                // Filter out small bounding boxes (noise/artifacts)
                if (bbox.width >= minWidth && bbox.height >= minHeight) {
                    result.add(bbox)
                }
            }
            contour.release() // Release MatOfPoint memory
        }

        hierarchy.release()

        return result
    }
}
