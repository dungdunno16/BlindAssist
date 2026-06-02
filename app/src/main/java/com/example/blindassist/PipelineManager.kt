package com.example.blindassist

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.blindassist.alert.AlertManager
import com.example.blindassist.depth.BBoxMerger
import com.example.blindassist.depth.ContourDetector
import com.example.blindassist.depth.DepthMaskProcessor
import com.example.blindassist.depth.DistanceEstimator
import com.example.blindassist.depth.MiDaSInference
import com.example.blindassist.sensor.TiltEstimator
import com.example.blindassist.tracking.MultiObjectTracker
import com.example.blindassist.tracking.TrackerEntry
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
    
    @Volatile private var hasNewDetections = false
    private var latestDetections: List<Rect> = emptyList()
    @Volatile private var latestDepthBitmap: Bitmap? = null
    
    private val frameLock = Any()
    private var _latestFrameBitmap: Bitmap? = null

    private var latestTracks: List<TrackerEntry> = emptyList()
    private var lastFrameWidth: Int = 0
    
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
                    if (prevFgCount > 0) {
                        val ratio = currFgCount.toDouble() / prevFgCount
                        if (ratio < Config.SCENE_CHANGE_MIN_RATIO || ratio > Config.SCENE_CHANGE_MAX_RATIO) {
                            // Run on Main thread since tracker/distanceEstimator are not thread-safe
                            launch(Dispatchers.Main) {
                                tracker.clearAll()
                                distanceEstimator.clearAll()
                            }
                        }
                    }
                    prevFgCount = currFgCount

                    latestDetections = mergedBoxes
                    
                    latestDepthBitmap = depthBitmap
                    
                    hasNewDetections = true
                    
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
                // Tracking
                val tracks = if (hasNewDetections) {
                    hasNewDetections = false
                    tracker.updateWithDetections(latestDetections, grayMat)
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

                latestTracks = tracks.toList()
                lastFrameWidth = frameWidth

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

                // Dev Mode UI
                val isDevMode = devModeProvider()
                if (isDevMode) {
                    overlayView.updateTracks(tracks, frameWidth, frameHeight)
                    
                    latestDepthBitmap?.let {
                        depthImageView.setImageBitmap(it)
                    }

                    val stats = StringBuilder()
                    stats.append("FPS: ${String.format("%.1f", currentFps)}\n")
                    stats.append("Objects: ${tracks.size}\n")
                    
                    val tiltStr = tiltRad?.let { String.format("%.1f°", Math.toDegrees(it)) } ?: "N/A"
                    stats.append("Tilt: $tiltStr\n\n")
                    
                    for (track in tracks) {
                        val zStr = track.distanceM?.let { String.format("%.2fm", it) } ?: "N/A"
                        stats.append("ID: ${track.id} -> $zStr\n")
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
    }

    fun getSceneMetadataSnapshot(): com.example.blindassist.gemini.SceneMetadata {
        val obstacles = latestTracks.map { track ->
            val centerX = track.smoothedBox.x + track.smoothedBox.width / 2.0
            val zone = when {
                lastFrameWidth == 0 -> "giữa"
                centerX < lastFrameWidth * 0.35 -> "trái"
                centerX > lastFrameWidth * 0.65 -> "phải"
                else -> "giữa"
            }
            com.example.blindassist.gemini.ObstacleInfo(
                id = track.id,
                zone = zone,
                distanceMeters = track.distanceM,
                hasReliableDistance = track.distanceM != null
            )
        }
        return com.example.blindassist.gemini.SceneMetadata(
            obstacleCount = obstacles.size,
            obstacles = obstacles,
            tiltAngleDeg = tiltEstimator.getTiltRad()?.let { Math.toDegrees(it) },
            fps = currentFps
        )
    }
}
