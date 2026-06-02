package com.example.blindassist

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.Surface
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.lifecycle.lifecycleScope
import com.example.blindassist.depth.ContourDetector
import com.example.blindassist.depth.DistanceEstimator
import com.example.blindassist.depth.DepthMaskProcessor
import com.example.blindassist.depth.MiDaSInference
import com.example.blindassist.ui.CameraOverlayView
import com.example.blindassist.tracking.MultiObjectTracker

import com.example.blindassist.gemini.GeminiDescriber
import com.example.blindassist.gemini.SceneDescribeController
import android.view.GestureDetector
import android.view.MotionEvent
import android.annotation.SuppressLint
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tiltEstimator: com.example.blindassist.sensor.TiltEstimator
    private lateinit var midasInference: MiDaSInference
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var pipelineManager: PipelineManager

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: CameraOverlayView
    private lateinit var depthImageView: ImageView
    private lateinit var devModePanel: TextView
    private lateinit var btnSettings: Button
    
    private lateinit var heightInputLayout: LinearLayout
    private lateinit var etUserHeight: EditText
    private lateinit var tvHeightError: TextView
    private lateinit var btnStart: Button
    private lateinit var btnCancelHeight: Button

    private val tracker = MultiObjectTracker()
    private lateinit var distanceEstimator: DistanceEstimator
    private lateinit var alertManager: com.example.blindassist.alert.AlertManager
    private var focalLengthMm: Float? = null
    private var sensorHeightMm: Float? = null
    private var intrinsicsUpdated = false

    var isDevMode = false

    private var loggingJob: Job? = null
    
    private lateinit var sceneDescribeController: SceneDescribeController
    private lateinit var gestureDetector: GestureDetector
    
    // Manage processing state to prevent overlapping inferences
    @Volatile private var isProcessing = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        depthImageView = findViewById(R.id.depthImageView)
        devModePanel = findViewById(R.id.devModePanel)
        btnSettings = findViewById(R.id.btnSettings)

        btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Chỉnh sửa chiều cao")
            val devModeTitle = if (isDevMode) "Tắt DevMode" else "Bật DevMode"
            popup.menu.add(devModeTitle)
            
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Chỉnh sửa chiều cao" -> {
                        val prefs = getSharedPreferences("blind_assist_prefs", Context.MODE_PRIVATE)
                        val savedHeight = prefs.getInt(Config.PREF_KEY_HEIGHT, 160)
                        etUserHeight.setText(savedHeight.toString())
                        tvHeightError.visibility = View.GONE
                        btnCancelHeight.visibility = View.VISIBLE
                        btnStart.text = "Lưu"
                        heightInputLayout.visibility = View.VISIBLE
                        true
                    }
                    "Bật DevMode", "Tắt DevMode" -> {
                        isDevMode = !isDevMode
                        val visibility = if (isDevMode) View.VISIBLE else View.GONE
                        overlayView.visibility = visibility
                        depthImageView.visibility = visibility
                        devModePanel.visibility = visibility
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        OpenCVLoader.initLocal()

        val prefs = getSharedPreferences("blind_assist_prefs", Context.MODE_PRIVATE)
        val savedHeight = prefs.getInt(Config.PREF_KEY_HEIGHT, -1)
        
        heightInputLayout = findViewById(R.id.heightInputLayout)
        etUserHeight = findViewById(R.id.etUserHeight)
        tvHeightError = findViewById(R.id.tvHeightError)
        btnStart = findViewById(R.id.btnStart)
        btnCancelHeight = findViewById(R.id.btnCancelHeight)

        btnCancelHeight.setOnClickListener {
            heightInputLayout.visibility = View.GONE
        }

        btnStart.setOnClickListener {
            val input = etUserHeight.text.toString()
            if (input.isEmpty()) {
                tvHeightError.text = "Vui lòng nhập chiều cao"
                tvHeightError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val heightCm = input.toIntOrNull()
            if (heightCm == null || heightCm < Config.MIN_USER_HEIGHT_CM || heightCm > Config.MAX_USER_HEIGHT_CM) {
                tvHeightError.text = "Chiều cao phải từ ${Config.MIN_USER_HEIGHT_CM}cm đến ${Config.MAX_USER_HEIGHT_CM}cm"
                tvHeightError.visibility = View.VISIBLE
            } else {
                prefs.edit().putInt(Config.PREF_KEY_HEIGHT, heightCm).apply()
                heightInputLayout.visibility = View.GONE
                
                if (::distanceEstimator.isInitialized) {
                    distanceEstimator.H = heightCm * Config.CAMERA_HEIGHT_RATIO / 100.0
                } else {
                    initializeApp(heightCm)
                }
            }
        }

        if (savedHeight != -1) {
            heightInputLayout.visibility = View.GONE
            initializeApp(savedHeight)
        } else {
            btnCancelHeight.visibility = View.GONE
            btnStart.text = "Bắt đầu"
            heightInputLayout.visibility = View.VISIBLE
        }
    }

    private fun initializeApp(heightCm: Int) {
        val H = heightCm * Config.CAMERA_HEIGHT_RATIO / 100.0
        distanceEstimator = DistanceEstimator(H = H)
        tracker.distanceEstimator = distanceEstimator

        tiltEstimator = com.example.blindassist.sensor.TiltEstimator(this)
        tiltEstimator.start()
        
        alertManager = com.example.blindassist.alert.AlertManager(this)
        midasInference = MiDaSInference(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        pipelineManager = PipelineManager(
            tiltEstimator = tiltEstimator,
            midasInference = midasInference,
            tracker = tracker,
            distanceEstimator = distanceEstimator,
            alertManager = alertManager,
            overlayView = overlayView,
            devModePanel = devModePanel,
            depthImageView = depthImageView,
            devModeProvider = { isDevMode }
        )

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotBlank()) {
            val geminiDescriber = GeminiDescriber(apiKey)
            sceneDescribeController = SceneDescribeController(
                geminiDescriber, alertManager, pipelineManager, lifecycleScope
            )
        } else {
            Log.w("MainActivity", "GEMINI_API_KEY is blank, describe feature disabled")
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (::sceneDescribeController.isInitialized) {
                    sceneDescribeController.onDoubleTap()
                }
                return true
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                var foundCameraId: String? = null
                for (cameraId in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        foundCameraId = cameraId
                        break
                    }
                }
                
                if (foundCameraId != null) {
                    val characteristics = cameraManager.getCameraCharacteristics(foundCameraId)
                    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
                        focalLengthMm = focalLengths[0]
                        sensorHeightMm = sensorSize.height
                        Log.d("MainActivity", "Found camera intrinsics: focalLengthMm=$focalLengthMm, sensorHeightMm=$sensorHeightMm")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to extract camera intrinsics", e)
            }

            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        processImageProxy(image)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(image: ImageProxy) {
        if (isProcessing) {
            image.close()
            return
        }
        isProcessing = true

        var rawBitmap: Bitmap? = null
        var bitmap: Bitmap? = null

        try {
            rawBitmap = image.toBitmap()
            val rotationDegrees = image.imageInfo.rotationDegrees

            // CameraX toBitmap() does NOT apply rotation, so we must rotate it manually
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            
            bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            
            val frameWidth = bitmap.width
            val frameHeight = bitmap.height

            if (!intrinsicsUpdated) {
                val cy = frameHeight / 2.0
                val fy: Double = if (focalLengthMm != null && sensorHeightMm != null) {
                    ((focalLengthMm!! / sensorHeightMm!!) * frameHeight).toDouble()
                } else {
                    Log.w("MainActivity", "Camera intrinsics unavailable, using heuristic")
                    frameHeight * 1.07
                }
                distanceEstimator.updateIntrinsics(fy, cy)
                intrinsicsUpdated = true
            }

            val grayMat = Mat()
            val rgbaMat = Mat()
            Utils.bitmapToMat(bitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            rgbaMat.release()

            pipelineManager.onFrame(bitmap, grayMat, frameWidth, frameHeight, lifecycleScope)
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to process frame", e)
        } finally {
            if (rawBitmap !== bitmap) {
                rawBitmap?.recycle()
            }
            image.close()
            isProcessing = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tiltEstimator.isInitialized) {
            tiltEstimator.start()
        }
        loggingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::tiltEstimator.isInitialized) {
            tiltEstimator.stop()
        }
        if (::alertManager.isInitialized) {
            alertManager.stop()
        }
        loggingJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::alertManager.isInitialized) {
            alertManager.shutdown()
        }
        if (::midasInference.isInitialized) {
            midasInference.close()
        }
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        if (::sceneDescribeController.isInitialized) {
            sceneDescribeController.close()
        }
        if (::pipelineManager.isInitialized) {
            pipelineManager.clearLatestFrame()
        }
    }
}