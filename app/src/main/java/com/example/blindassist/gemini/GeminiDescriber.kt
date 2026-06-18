package com.example.blindassist.gemini

import android.graphics.Bitmap
import com.example.blindassist.Config
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content

class GeminiDescriber(apiKey: String) {
    private val model = GenerativeModel(
        modelName = Config.GEMINI_MODEL_NAME,
        apiKey = apiKey
    )

    suspend fun describe(bitmap: Bitmap, metadata: SceneMetadata): String? {
        val prompt = buildPrompt(metadata)
        val geminiBitmap = downscaleForGemini(bitmap, Config.DESCRIBE_IMAGE_MAX_SIDE)
        return try {
            val response = model.generateContent(content {
                image(geminiBitmap)
                text(prompt)
            })
            response.text?.takeIf { it.isNotBlank() }
        } finally {
            if (geminiBitmap !== bitmap) {
                geminiBitmap.recycle()
            }
        }
    }

    private fun downscaleForGemini(src: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height).toFloat()
        if (scale >= 1f) return src
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun buildPrompt(metadata: SceneMetadata): String {
        val obstacleDetails = if (metadata.obstacles.isEmpty()) {
            "Không phát hiện vật cản đáng chú ý."
        } else {
            metadata.obstacles.joinToString(", ") { obs ->
                val distStr = if (obs.hasReliableDistance && obs.distanceMeters != null) {
                    "${String.format("%.1f", obs.distanceMeters)}m"
                } else {
                    "khoảng cách không xác định"
                }
                "vật cản bên ${obs.zone} ($distStr)"
            }
        }

        val tiltStr = metadata.tiltAngleDeg?.let { String.format("%.1f", it) } ?: "không rõ"

        return """
            Bạn là trợ lý mô tả cảnh cho người khiếm thị.

            Hãy mô tả ngắn gọn bằng tiếng Việt trong 2-3 câu, dễ nghe qua TTS.
            Ưu tiên thông tin quan trọng cho di chuyển an toàn: vật cản gần, hướng trái/phải/phía trước,
            lối đi có thể đi, có thể đi về bên nào để tránh vật cản, người hoặc phương tiện nếu thấy rõ.

            Thông tin từ hệ thống nhận diện:
            - Số vật cản phát hiện: ${metadata.obstacleCount}
            - Chi tiết: $obstacleDetails
            - Góc nghiêng điện thoại: $tiltStr độ

            Quy tắc:
            - Nếu không chắc vật thể là gì, nói "có vật cản" thay vì đoán.
            - Chỉ nhắc khoảng cách khi metadata có distance tin cậy.
            - Không trả lời quá 3 câu.
            - Không dùng markdown.
        """.trimIndent()
    }
}
