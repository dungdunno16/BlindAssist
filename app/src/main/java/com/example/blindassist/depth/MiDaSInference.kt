package com.example.blindassist.depth

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MiDaSInference(context: Context) {
    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    private val inputLayout: InputLayout
    private val inputWidth: Int
    private val inputHeight: Int
    private val outputWidth: Int
    private val outputHeight: Int
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val inputPixels: IntArray
    private val outputFloats: FloatArray

    private fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filePath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private var isFirstInference = true

    init {
        val mappedByteBuffer = loadMappedFile(context, MODEL_ASSET)
        val compatList = CompatibilityList()
        val options = Interpreter.Options()

        Log.i(TAG, "══════════════════════════════════════")
        Log.i(TAG, "GPU supported: ${compatList.isDelegateSupportedOnThisDevice}")

        if (compatList.isDelegateSupportedOnThisDevice) {
            try {
                gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                options.addDelegate(gpuDelegate)
                Log.i(TAG, "✅ GpuDelegate ACTIVE")
            } catch (e: Exception) {
                Log.e(TAG, "❌ GpuDelegate FAILED, falling back to CPU", e)
                gpuDelegate?.close()
                gpuDelegate = null
                options.numThreads = 4
                Log.i(TAG, "⚠️ Using CPU with 4 threads")
            }
        } else {
            Log.i(TAG, "⚠️ GPU not supported, using CPU with 4 threads")
            options.numThreads = 4
        }

        interpreter = Interpreter(mappedByteBuffer, options)

        require(interpreter.inputTensorCount == 1) {
            "$MODEL_ASSET must have exactly one input tensor, found ${interpreter.inputTensorCount}"
        }
        require(interpreter.outputTensorCount == 1) {
            "$MODEL_ASSET must have exactly one output tensor, found ${interpreter.outputTensorCount}"
        }

        val inputTensor = interpreter.getInputTensor(0)
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "$MODEL_ASSET input must be FLOAT32, found ${inputTensor.dataType()}"
        }
        val inputSpec = parseInputShape(inputTensor.shape())
        inputLayout = inputSpec.layout
        inputHeight = inputSpec.height
        inputWidth = inputSpec.width

        val outputTensor = interpreter.getOutputTensor(0)
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "$MODEL_ASSET output must be FLOAT32, found ${outputTensor.dataType()}"
        }
        val outputSpec = parseOutputShape(outputTensor.shape())
        outputHeight = outputSpec.height
        outputWidth = outputSpec.width

        inputBuffer = allocateFloatBuffer(inputTensor.numElements())
        outputBuffer = allocateFloatBuffer(outputTensor.numElements())
        inputPixels = IntArray(inputWidth * inputHeight)
        outputFloats = FloatArray(outputWidth * outputHeight)

        Log.i(
            TAG,
            "Model: $MODEL_ASSET, input=${inputTensor.shape().contentToString()} ($inputLayout), " +
                "output=${outputTensor.shape().contentToString()}"
        )
        Log.i(TAG, "Backend: ${if (gpuDelegate != null) "GPU" else "CPU"}")
        Log.i(TAG, "══════════════════════════════════════")
    }

    fun infer(frameBitmap: Bitmap): Mat {
        // 1. Resize Bitmap to the model input size
        val resizedBitmap = Bitmap.createScaledBitmap(frameBitmap, inputWidth, inputHeight, true)

        // 2. Convert to float32 in the model's channel layout and normalize [0.0, 1.0]
        resizedBitmap.getPixels(inputPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        writeInputBuffer()
        
        if (resizedBitmap != frameBitmap) {
            resizedBitmap.recycle()
        }

        // 3. Run into a flat output buffer so both [1,H,W] and [1,H,W,1] are supported
        inputBuffer.rewind()
        outputBuffer.clear()
        val t0 = System.nanoTime()
        interpreter.run(inputBuffer, outputBuffer)
        val inferMs = (System.nanoTime() - t0) / 1_000_000.0
        if (isFirstInference) {
            Log.i(TAG, "══ First inference: %.1f ms (includes warmup) ══".format(inferMs))
            isFirstInference = false
        } else {
            Log.d(TAG, "Inference: %.1f ms".format(inferMs))
        }

        // 4. Wrap the single-channel output as a CV_32F Mat
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(outputFloats)
        val wrapMat = Mat(outputHeight, outputWidth, CvType.CV_32F)
        wrapMat.put(0, 0, outputFloats)

        // 5. Resize Mat to frameWidth x frameHeight
        val outMat = Mat()
        Imgproc.resize(wrapMat, outMat, Size(frameBitmap.width.toDouble(), frameBitmap.height.toDouble()))

        // 6 & 7. Release temp Mats and return final Mat
        wrapMat.release()
        return outMat
    }

    private fun writeInputBuffer() {
        inputBuffer.clear()
        when (inputLayout) {
            InputLayout.NCHW -> {
                for (shift in intArrayOf(16, 8, 0)) {
                    for (pixel in inputPixels) {
                        inputBuffer.putFloat(((pixel shr shift) and 0xFF) / 255.0f)
                    }
                }
            }

            InputLayout.NHWC -> {
                for (pixel in inputPixels) {
                    inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                    inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                    inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
                }
            }
        }
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }

    private fun parseInputShape(shape: IntArray): InputSpec {
        require(shape.size == 4 && shape[0] == 1) {
            "$MODEL_ASSET input shape must be [1,3,H,W] or [1,H,W,3], found ${shape.contentToString()}"
        }

        val spec = when {
            shape[1] == RGB_CHANNELS -> InputSpec(InputLayout.NCHW, shape[2], shape[3])
            shape[3] == RGB_CHANNELS -> InputSpec(InputLayout.NHWC, shape[1], shape[2])
            else -> throw IllegalArgumentException(
                "$MODEL_ASSET input shape must have 3 RGB channels, found ${shape.contentToString()}"
            )
        }
        require(spec.height > 0 && spec.width > 0) {
            "$MODEL_ASSET input dimensions must be positive, found ${shape.contentToString()}"
        }
        return spec
    }

    private fun parseOutputShape(shape: IntArray): OutputSpec {
        val spec = when {
            shape.size == 3 && shape[0] == 1 -> OutputSpec(shape[1], shape[2])
            shape.size == 4 && shape[0] == 1 && shape[3] == 1 -> OutputSpec(shape[1], shape[2])
            else -> throw IllegalArgumentException(
                "$MODEL_ASSET output shape must be [1,H,W] or [1,H,W,1], found ${shape.contentToString()}"
            )
        }
        require(spec.height > 0 && spec.width > 0) {
            "$MODEL_ASSET output dimensions must be positive, found ${shape.contentToString()}"
        }
        return spec
    }

    private fun allocateFloatBuffer(elementCount: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(elementCount * FLOAT_BYTES).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private enum class InputLayout {
        NCHW,
        NHWC
    }

    private data class InputSpec(
        val layout: InputLayout,
        val height: Int,
        val width: Int
    )

    private data class OutputSpec(
        val height: Int,
        val width: Int
    )

    private companion object {
        private const val TAG = "MiDaSInference"
        private const val MODEL_ASSET = "1.tflite"
        private const val RGB_CHANNELS = 3
        private const val FLOAT_BYTES = 4
    }
}
