package com.example.blindassist.depth

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.blindassist.Config
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

class MiDaSInference(context: Context) {
    private var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    
    private val TAG = "MiDaSInference"
    private val inputSize = Config.MIDAS_INPUT_SIZE
    private val numPixels = inputSize * inputSize

    // Input buffer for CHW float32
    // 1 * 3 * 256 * 256 * 4 bytes
    private val inputBuffer = ByteBuffer.allocateDirect(3 * numPixels * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    
    // Output array for [1, 256, 256]
    private val outputArray = Array(1) { Array(inputSize) { FloatArray(inputSize) } }

    private fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filePath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    init {
        val mappedByteBuffer = loadMappedFile(context, "MiDaS_small.tflite")
        val compatList = CompatibilityList()
        val options = Interpreter.Options()

        if (compatList.isDelegateSupportedOnThisDevice) {
            try {
                gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                options.addDelegate(gpuDelegate)
                Log.i(TAG, "GpuDelegate is being used.")
            } catch (e: Exception) {
                Log.e(TAG, "GpuDelegate initialization failed, falling back to CPU", e)
                gpuDelegate?.close()
                gpuDelegate = null
                options.numThreads = 4
            }
        } else {
            Log.i(TAG, "GpuDelegate is not supported, using CPU.")
            options.numThreads = 4
        }

        interpreter = Interpreter(mappedByteBuffer, options)
    }

    fun infer(frameBitmap: Bitmap): Mat {
        // 1. Resize Bitmap to 256x256
        val resizedBitmap = Bitmap.createScaledBitmap(frameBitmap, inputSize, inputSize, true)

        // 2. Convert to float32 CHW and normalize [0.0, 1.0]
        val intValues = IntArray(numPixels)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        inputBuffer.rewind()
        // Write all R
        for (i in 0 until numPixels) {
            val pixel = intValues[i]
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
        }
        // Write all G
        for (i in 0 until numPixels) {
            val pixel = intValues[i]
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
        }
        // Write all B
        for (i in 0 until numPixels) {
            val pixel = intValues[i]
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        
        if (resizedBitmap != frameBitmap) {
            resizedBitmap.recycle()
        }

        // 3. interpreter.run(inputArray, outputArray)
        inputBuffer.rewind()
        interpreter.run(inputBuffer, outputArray)

        // 4. Wrap output to Mat 256x256 CV_32F
        val wrapMat = Mat(inputSize, inputSize, CvType.CV_32F)
        val outFloats = FloatArray(numPixels)
        var idx = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                outFloats[idx++] = outputArray[0][y][x]
            }
        }
        wrapMat.put(0, 0, outFloats)

        // 5. Resize Mat to frameWidth x frameHeight
        val outMat = Mat()
        Imgproc.resize(wrapMat, outMat, Size(frameBitmap.width.toDouble(), frameBitmap.height.toDouble()))

        // 6 & 7. Release temp Mats and return final Mat
        wrapMat.release()
        return outMat
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
