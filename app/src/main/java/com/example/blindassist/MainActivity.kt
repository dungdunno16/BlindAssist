package com.example.blindassist

import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import com.example.blindassist.depth.MiDaSInference

class MainActivity : AppCompatActivity() {

    private lateinit var tiltEstimator: com.example.blindassist.sensor.TiltEstimator
    private var loggingJob: kotlinx.coroutines.Job? = null
    
    private lateinit var midasInference: MiDaSInference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        OpenCVLoader.initLocal()

        tiltEstimator = com.example.blindassist.sensor.TiltEstimator(this)
        midasInference = MiDaSInference(this)
        
        // Test MiDaS Inference
        val bitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        if (bitmap != null) {
            val startTime = System.currentTimeMillis()
            val resultMat = midasInference.infer(bitmap)
            val endTime = System.currentTimeMillis()
            android.util.Log.d("MiDaSInferenceTest", "Inference time: ${endTime - startTime}ms")
            
            // --- Step 3 Test ---
            val maskMat = com.example.blindassist.depth.DepthMaskProcessor.process(resultMat, bitmap.width, bitmap.height)
            val rawBoxes = com.example.blindassist.depth.ContourDetector.detect(maskMat, bitmap.width, bitmap.height)
            
            android.util.Log.d("Step3Test", "Detected ${rawBoxes.size} bounding boxes")
            for ((index, box) in rawBoxes.withIndex()) {
                android.util.Log.d("Step3Test", "Box $index: x=${box.x}, y=${box.y}, w=${box.width}, h=${box.height}")
            }
            
            val overlayView = findViewById<com.example.blindassist.ui.CameraOverlayView>(R.id.overlayView)
            overlayView.updateBoxes(rawBoxes)

            maskMat.release()
            resultMat.release()
        }
    }

    override fun onResume() {
        super.onResume()
        tiltEstimator.start()
        loggingJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            while (isActive) {
                android.util.Log.d("TiltEstimatorTest", "Current tiltRad: ${tiltEstimator.getTiltRad()}")
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        tiltEstimator.stop()
        loggingJob?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        midasInference.close()
    }
}