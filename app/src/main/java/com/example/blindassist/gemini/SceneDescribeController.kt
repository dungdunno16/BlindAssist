package com.example.blindassist.gemini

import com.example.blindassist.Config
import com.example.blindassist.PipelineManager
import com.example.blindassist.alert.AlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SceneDescribeController(
    private val geminiDescriber: GeminiDescriber,
    private val alertManager: AlertManager,
    private val pipelineManager: PipelineManager,
    private val scope: CoroutineScope
) {
    private var lastDescribeTimeMs: Long = 0L
    private var describeJob: Job? = null
    @Volatile private var isDescribing: Boolean = false

    fun onDoubleTap() {
        // 1. Check if already processing
        if (isDescribing) {
            alertManager.speakImmediate("Đang xử lý, vui lòng chờ")
            return
        }
        
        // 2. Cooldown check
        val now = System.currentTimeMillis()
        if (now - lastDescribeTimeMs < Config.DESCRIBE_COOLDOWN_MS) return
        lastDescribeTimeMs = now

        // 3. Snapshot frame + metadata
        val snapshot = pipelineManager.getLatestFrameSnapshot()
        if (snapshot == null) {
            alertManager.speakImmediate("Chưa có ảnh camera, vui lòng thử lại")
            return
        }
        val metadata = pipelineManager.getSceneMetadataSnapshot()

        // 4. Suppress all alerts (voice + vibration) + give feedback
        isDescribing = true
        alertManager.suppressIndefinitely()
        alertManager.speakImmediate("Đang mô tả cảnh...")

        // 5. Call API with timeout
        describeJob = scope.launch(Dispatchers.IO) {
            try {
                val result = withTimeoutOrNull(Config.DESCRIBE_TIMEOUT_MS) {
                    geminiDescriber.describe(snapshot, metadata)
                }
                withContext(Dispatchers.Main) {
                    val text = when {
                        result != null -> result
                        else -> "Mạng chậm, không thể mô tả cảnh lúc này"
                    }
                    alertManager.speakImmediate(text) {
                        alertManager.resumeAlerts()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SceneDescribe", "Gemini API failed", e)
                withContext(Dispatchers.Main) {
                    alertManager.speakImmediate("Không thể mô tả cảnh, vui lòng kiểm tra mạng") {
                        alertManager.resumeAlerts()
                    }
                }
            } finally {
                snapshot.recycle()
                isDescribing = false
            }
        }
    }

    fun close() {
        describeJob?.cancel()
    }
}
