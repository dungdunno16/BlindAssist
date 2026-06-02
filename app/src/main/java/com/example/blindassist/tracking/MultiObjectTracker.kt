package com.example.blindassist.tracking

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import com.example.blindassist.Config

class MultiObjectTracker {
    var distanceEstimator: com.example.blindassist.depth.DistanceEstimator? = null

    private var activeTrackers = mutableListOf<TrackerEntry>()
    private var nextTrackId = 0
    private var prevGray: Mat? = null

    fun updateWithDetections(detections: List<Rect>, grayFrame: Mat): List<TrackerEntry> {
        val newTrackers = mutableListOf<TrackerEntry>()
        val usedTrackerIds = mutableSetOf<Int>()

        for (det in detections) {

            // Bước 1: Tìm tracker cũ có IoU cao nhất với det
            var bestIou = 0f
            var bestTracker: TrackerEntry? = null
            for (tracker in activeTrackers) {
                val iou = calculateIoU(det, tracker.box)
                if (iou > bestIou && tracker.id !in usedTrackerIds) {
                    bestIou = iou
                    bestTracker = tracker
                }
            }

            val id: Int
            val kf: BoxKalmanFilter
            val smoothed: Rect

            if (bestIou >= Config.IOU_MATCH_THRESHOLD && bestTracker != null) {
                // Kế thừa tracker cũ
                id = bestTracker.id
                kf = bestTracker.kf
                kf.predict()
                kf.update(det)
                smoothed = kf.getState()
                usedTrackerIds.add(id)
            } else {
                // Tạo tracker mới
                id = nextTrackId++
                kf = BoxKalmanFilter(det)
                smoothed = det
            }

            // Bước 2: Lấy feature points trong bbox để dùng cho Optical Flow
            val points = extractFeaturePoints(grayFrame, det)

            newTrackers.add(TrackerEntry(
                id = id,
                box = det,
                smoothedBox = smoothed,
                kf = kf,
                points = points
            ))
        }

        // Release tracker cũ không được kế thừa
        for (tracker in activeTrackers) {
            if (tracker.id !in usedTrackerIds) {
                distanceEstimator?.removeTrack(tracker.id)
                tracker.release()
            }
        }

        activeTrackers = newTrackers
        prevGray?.release()
        prevGray = grayFrame.clone()
        return activeTrackers.toList()
    }

    fun updateWithOpticalFlow(grayFrame: Mat): List<TrackerEntry> {
        val prev = prevGray
        if (prev == null || activeTrackers.isEmpty()) {
            prevGray?.release()
            prevGray = grayFrame.clone()
            return activeTrackers.toList()
        }

        val surviving = mutableListOf<TrackerEntry>()

        for (tracker in activeTrackers) {
            tracker.kf.predict()

            val p1 = MatOfPoint2f()
            val status = MatOfByte()
            val err = MatOfFloat()

            Video.calcOpticalFlowPyrLK(
                prev, grayFrame,
                tracker.points, p1,
                status, err,
                Size(Config.OF_WIN_SIZE.toDouble(), Config.OF_WIN_SIZE.toDouble()),
                2  // maxLevel pyramid
            )

            val st = status.toArray()
            val newPts = p1.toArray()
            val oldPts = tracker.points.toArray()

            val goodIndices = st.indices.filter { st[it].toInt() == 1 }

            if (goodIndices.isNotEmpty()) {
                // Tính median dx, dy — robust hơn mean khi có outlier
                val dxList = goodIndices.map { newPts[it].x - oldPts[it].x }
                val dyList = goodIndices.map { newPts[it].y - oldPts[it].y }
                val dx = dxList.median()
                val dy = dyList.median()

                // Di chuyển bbox theo optical flow
                val newBox = Rect(
                    (tracker.box.x + dx).toInt().coerceAtLeast(0),
                    (tracker.box.y + dy).toInt().coerceAtLeast(0),
                    tracker.box.width,
                    tracker.box.height
                )

                tracker.kf.update(newBox)
                tracker.smoothedBox = tracker.kf.getState()
                tracker.box = newBox
                tracker.points = MatOfPoint2f(*goodIndices.map { newPts[it] }.toTypedArray())
                tracker.missedFrames = 0
                surviving.add(tracker)
            } else {
                // Optical flow fail — tăng missedFrames, dùng Kalman predict
                tracker.missedFrames++
                if (tracker.missedFrames <= Config.MAX_MISSED_FRAMES) {
                    tracker.smoothedBox = tracker.kf.getState()
                    surviving.add(tracker)
                } else {
                    distanceEstimator?.removeTrack(tracker.id)
                    tracker.release()
                }
            }

            p1.release()
            status.release()
            err.release()
        }

        activeTrackers = surviving
        prevGray?.release()
        prevGray = grayFrame.clone()
        return activeTrackers.toList()
    }

    private fun extractFeaturePoints(gray: Mat, bbox: Rect): MatOfPoint2f {
        // Clamp bbox về trong frame
        val x = bbox.x.coerceIn(0, gray.cols() - 1)
        val y = bbox.y.coerceIn(0, gray.rows() - 1)
        val w = bbox.width.coerceAtMost(gray.cols() - x)
        val h = bbox.height.coerceAtMost(gray.rows() - y)

        val result = MatOfPoint2f()
        val centerPt = Point(
            bbox.x + bbox.width / 2.0,
            bbox.y + bbox.height / 2.0
        )

        if (w <= 0 || h <= 0) {
            result.fromArray(centerPt)
            return result
        }

        val mask = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8U)
        val roiRect = Rect(x, y, w, h)
        mask.submat(roiRect).setTo(Scalar(255.0))

        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
            gray, corners,
            Config.MAX_CORNERS,
            Config.QUALITY_LEVEL,
            Config.MIN_DISTANCE,
            mask
        )
        mask.release()

        if (corners.empty()) {
            result.fromArray(centerPt)
        } else {
            result.fromArray(*corners.toArray().map { Point(it.x, it.y) }.toTypedArray())
        }
        corners.release()
        return result
    }

    private fun calculateIoU(a: Rect, b: Rect): Float {
        val interX      = maxOf(a.x, b.x)
        val interY      = maxOf(a.y, b.y)
        val interRight  = minOf(a.x + a.width,  b.x + b.width)
        val interBottom = minOf(a.y + a.height, b.y + b.height)

        val interW = maxOf(0, interRight  - interX)
        val interH = maxOf(0, interBottom - interY)
        val interArea = interW * interH

        val unionArea = a.width * a.height + b.width * b.height - interArea
        return if (unionArea > 0) interArea.toFloat() / unionArea else 0f
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        return if (size % 2 == 0)
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
        else
            sorted[size / 2]
    }

    fun clearAll() {
        activeTrackers.forEach { 
            distanceEstimator?.removeTrack(it.id)
            it.release() 
        }
        activeTrackers.clear()
        prevGray?.release()
        prevGray = null
    }

    fun activeTracks(): List<TrackerEntry> = activeTrackers.toList()
}
