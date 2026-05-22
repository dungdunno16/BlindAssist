package com.example.blindassist.depth

import com.example.blindassist.Config
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object DepthMaskProcessor {

    fun process(depthMat: Mat, frameWidth: Int, frameHeight: Int): Mat {
        // Step 1: Normalize to [0, 255] CV_8U
        val normMat = Mat()
        Core.normalize(depthMat, normMat, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

        // Step 2: Crop ROI - lower 2/3 of the frame
        val roiY = frameHeight / 3
        val roiH = frameHeight - roiY
        val roi = Rect(0, roiY, frameWidth, roiH)
        val normMatRoi = Mat(normMat, roi)

        // Step 3: Dynamic threshold based on percentile
        val flat = normMatRoi.reshape(1, 1)
        val sortedFlat = Mat()
        Core.sort(flat, sortedFlat, Core.SORT_EVERY_ROW or Core.SORT_ASCENDING)

        val foregroundRatio = Config.FOREGROUND_RATIO
        var cutoffIdx = (sortedFlat.cols() * (1f - foregroundRatio)).toInt()
        if (cutoffIdx < 0) cutoffIdx = 0
        if (cutoffIdx >= sortedFlat.cols()) cutoffIdx = sortedFlat.cols() - 1

        val thresholdArr = ByteArray(1)
        sortedFlat.get(0, cutoffIdx, thresholdArr)
        val thresholdVal = (thresholdArr[0].toInt() and 0xFF).toDouble()

        val maskRoi = Mat()
        Imgproc.threshold(normMatRoi, maskRoi, thresholdVal, 255.0, Imgproc.THRESH_BINARY)

        // Step 4: Morphology
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val openMask = Mat()
        Imgproc.morphologyEx(maskRoi, openMask, Imgproc.MORPH_OPEN, kernel)
        
        val closeMask = Mat()
        Imgproc.morphologyEx(openMask, closeMask, Imgproc.MORPH_CLOSE, kernel)

        // Step 5: Return mask with full frame size
        val fullMask = Mat.zeros(frameHeight, frameWidth, CvType.CV_8U)
        val fullMaskRoi = Mat(fullMask, roi)
        closeMask.copyTo(fullMaskRoi)

        // Step 6: Release temporary Mats
        normMat.release()
        normMatRoi.release()
        flat.release()
        sortedFlat.release()
        maskRoi.release()
        kernel.release()
        openMask.release()
        closeMask.release()
        fullMaskRoi.release()

        return fullMask
    }
}
