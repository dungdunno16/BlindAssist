package com.example.blindassist

import android.graphics.Bitmap
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import com.example.blindassist.alert.AlertManager
import com.example.blindassist.depth.BBoxMerger
import com.example.blindassist.depth.ContourDetector
import com.example.blindassist.depth.DepthMaskProcessor
import com.example.blindassist.depth.DistanceEstimator
import com.example.blindassist.depth.MiDaSInference
import com.example.blindassist.gemini.ObstacleInfo
import com.example.blindassist.gemini.SceneMetadata
import com.example.blindassist.sensor.TiltEstimator
import com.example.blindassist.tracking.MultiObjectTracker
import com.example.blindassist.ui.CameraOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.android.Utils

class PipelineManager(
    private val tiltEstimator: TiltEstimator,
    private val midasInference: MiDaSInference,
    private val tracker: MultiObjectTracker,
    private val distanceEstimator: DistanceEstimator,
    private val alertManager: AlertManager,
    private val overlayView: CameraOverlayView,
    private val devModePanel: TextView,
    private val depthImageView: ImageView,
    private val devModeProvider: () -> Boolean
) {
    private var inferenceJob: Job? = null
    
    private data class DetectionResult(
        val boxes: List<Rect>,
        val sceneChanged: Boolean
    )

    private val detectionLock = Any()
    private var pendingDetectionResult: DetectionResult? = null

    private val depthLock = Any()
    private var pendingDepthBitmap: Bitmap? = null
    private var displayedDepthBitmap: Bitmap? = null
    
    private val frameLock = Any()
    private var _latestFrameBitmap: Bitmap? = null

    @Volatile
    private var latestSceneMetadata = SceneMetadata(
        obstacleCount = 0,
        obstacles = emptyList(),
        tiltAngleDeg = null,
        fps = 0f
    )
    
    private var prevFgCount = 0

    // FPS calculation
    private var lastFpsTime = 0L
    private var framesSinceLastFps = 0
    @Volatile var currentFps: Float = 0f
        private set

    fun onFrame(bitmap: Bitmap, grayMat: Mat, frameWidth: Int, frameHeight: Int, scope: CoroutineScope) {
        // --- Step A: Background Inference ---
        if (inferenceJob?.isActive != true) {
            inferenceJob = scope.launch(Dispatchers.Default) {
                try {
                    val resultMat = midasInference.infer(bitmap)
                    
                    // Create preview bitmap for dev mode
                    val normMat = Mat()
                    Core.normalize(resultMat, normMat, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
                    val depthBitmap = Bitmap.createBitmap(resultMat.width(), resultMat.height(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(normMat, depthBitmap)
                    normMat.release()
                    
                    val maskMat = DepthMaskProcessor.process(resultMat, frameWidth, frameHeight)
                    val rawBoxes = ContourDetector.detect(maskMat, frameWidth, frameHeight)
                    val mergedBoxes = BBoxMerger.merge(rawBoxes, frameWidth, frameHeight)
                    
                    // Scene change detection
                    val currFgCount = mergedBoxes.size
                    var sceneChanged = false
                    if (prevFgCount > 0) {
                        val ratio = currFgCount.toDouble() / prevFgCount
                        if (ratio < Config.SCENE_CHANGE_MIN_RATIO || ratio > Config.SCENE_CHANGE_MAX_RATIO) {
                            sceneChanged = true
                        }
                    }
                    prevFgCount = currFgCount

                    synchronized(detectionLock) {
                        pendingDetectionResult = DetectionResult(mergedBoxes, sceneChanged)
                    }

                    setPendingDepthBitmap(depthBitmap)
                    
                    resultMat.release()
                    maskMat.release()
                } catch (e: Exception) {
                    Log.e("PipelineManager", "Inference failed", e)
                } finally {
                    updateLatestFrame(bitmap)
                    bitmap.recycle()
                }
            }
        } else {
            updateLatestFrame(bitmap)
            bitmap.recycle()
        }

        // --- Step B: Main Thread Tracking & UI Update ---
        scope.launch(Dispatchers.Main) {
            try {
                val detectionResult = synchronized(detectionLock) {
                    pendingDetectionResult.also {
                        pendingDetectionResult = null
                    }
                }

                // Tracking
                val tracks = if (detectionResult != null) {
                    if (detectionResult.sceneChanged) {
                        tracker.clearAll()
                        distanceEstimator.clearAll()
                    }
                    tracker.updateWithDetections(detectionResult.boxes, grayMat)
                } else {
                    tracker.updateWithOpticalFlow(grayMat)
                }
                
                // Release grayMat after tracking is done
                grayMat.release()
                
                // Distance Estimation
                val tiltRad = tiltEstimator.getTiltRad()
                for (track in tracks) {
                    track.distanceM = distanceEstimator.estimate(track.id, track.smoothedBox, frameHeight, tiltRad)
                }

                // Alerts
                alertManager.update(tracks, frameWidth)

                // FPS Calculation
                framesSinceLastFps++
                val currentTime = System.currentTimeMillis()
                if (lastFpsTime == 0L) {
                    lastFpsTime = currentTime
                } else if (currentTime - lastFpsTime >= 1000) {
                    currentFps = (framesSinceLastFps * 1000.0 / (currentTime - lastFpsTime)).toFloat()
                    framesSinceLastFps = 0
                    lastFpsTime = currentTime
                }

                latestSceneMetadata = buildSceneMetadata(tracks, frameWidth, tiltRad)

                // Dev Mode UI
                val isDevMode = devModeProvider()
                updateDepthPreview(isDevMode)
                if (isDevMode) {
                    overlayView.updateTracks(tracks, frameWidth, frameHeight)

                    val stats = StringBuilder()
                    stats.append("FPS: ${String.format("%.1f", currentFps)}\n")
                    stats.append("Objects: ${tracks.size}\n")
                    
                    val tiltStr = tiltRad?.let { String.format("%.1f°", Math.toDegrees(it)) } ?: "N/A"
                    stats.append("Tilt: $tiltStr\n\n")
                    
                    for (track in tracks) {
                        val zStr = track.distanceM?.let { String.format("%.2fm", it) } ?: "N/A"
                        stats.append("Object-> $zStr\n")
                    }
                    devModePanel.text = stats.toString()
                }
                
            } catch (e: Exception) {
                Log.e("PipelineManager", "Main thread update failed", e)
            }
        }
    }

    private fun updateLatestFrame(bitmap: Bitmap) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        synchronized(frameLock) {
            _latestFrameBitmap?.recycle()
            _latestFrameBitmap = copy
        }
    }

    fun getLatestFrameSnapshot(): Bitmap? {
        synchronized(frameLock) {
            return _latestFrameBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun clearLatestFrame() {
        synchronized(frameLock) {
            _latestFrameBitmap?.recycle()
            _latestFrameBitmap = null
        }
        clearDepthPreview()
    }

    private fun setPendingDepthBitmap(bitmap: Bitmap) {
        val oldPending = synchronized(depthLock) {
            val old = pendingDepthBitmap
            pendingDepthBitmap = bitmap
            old
        }
        oldPending?.recycle()
    }

    private fun takePendingDepthBitmap(): Bitmap? {
        return synchronized(depthLock) {
            pendingDepthBitmap.also {
                pendingDepthBitmap = null
            }
        }
    }

    private fun updateDepthPreview(isDevMode: Boolean) {
        val pending = takePendingDepthBitmap()

        if (isDevMode) {
            pending?.let { newBitmap ->
                val oldDisplayed = displayedDepthBitmap
                depthImageView.setImageBitmap(newBitmap)
                oldDisplayed?.recycle()
                displayedDepthBitmap = newBitmap
            }
        } else {
            pending?.recycle()
            if (displayedDepthBitmap != null) {
                depthImageView.setImageBitmap(null)
                displayedDepthBitmap?.recycle()
                displayedDepthBitmap = null
            }
        }
    }

    private fun clearDepthPreview() {
        val pending = takePendingDepthBitmap()
        pending?.recycle()

        depthImageView.setImageBitmap(null)
        displayedDepthBitmap?.recycle()
        displayedDepthBitmap = null
    }

    private fun buildSceneMetadata(
        tracks: List<com.example.blindassist.tracking.TrackerEntry>,
        frameWidth: Int,
        tiltRad: Double?
    ): SceneMetadata {
        val obstacles = tracks.map { track ->
            val zone = com.example.blindassist.alert.AlertManager.classifyZone(track.smoothedBox, frameWidth)
            ObstacleInfo(
                id = track.id,
                zone = zone.text,
                distanceMeters = track.distanceM,
                hasReliableDistance = track.distanceM != null
            )
        }
        return SceneMetadata(
            obstacleCount = obstacles.size,
            obstacles = obstacles,
            tiltAngleDeg = tiltRad?.let { Math.toDegrees(it) },
            fps = currentFps
        )
    }

    fun getSceneMetadataSnapshot(): SceneMetadata = latestSceneMetadata
}
