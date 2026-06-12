package com.example.blindassist.depth

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object DepthMaskProcessor {
    fun process(depthMat: Mat, frameWidth: Int, frameHeight: Int): Mat {

        // Bước 1 — Normalize
        val normMat = Mat()
        Core.normalize(depthMat, normMat, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

        // Bước 2 — Crop ROI: phần dưới 4/5 frame
        val roiY = normMat.rows() / 5
        val roiH = normMat.rows() - roiY
        val roiRect = Rect(0, roiY, normMat.cols(), roiH)
        val roiMat = normMat.submat(roiRect)

        // Bước 3 — Gaussian blur trên ROI
        val blurred = Mat()
        Imgproc.GaussianBlur(roiMat, blurred, Size(5.0, 5.0), 0.0)
        roiMat.release()
        normMat.release()

        // Bước 4 — Canny trên ROI
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 30.0, 100.0)
        blurred.release()

        // Bước 5 — Dilate mạnh để nối cạnh
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val roiMask = Mat()
        Imgproc.dilate(edges, roiMask, kernel, org.opencv.core.Point(-1.0, -1.0), 4)
        edges.release()
        kernel.release()

        // Bước 6 — Đưa ROI mask vào mask gốc (tương ứng với vị trí đã crop)
        val fullMask = Mat.zeros(depthMat.rows(), depthMat.cols(), CvType.CV_8U)
        val maskRoiDest = fullMask.submat(roiRect)
        roiMask.copyTo(maskRoiDest)
        
        roiMask.release()
        maskRoiDest.release()

        return fullMask
    }
}
