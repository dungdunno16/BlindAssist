package com.example.blindassist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.blindassist.tracking.TrackerEntry

class CameraOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val tracks = mutableListOf<TrackerEntry>()
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private var imageWidth = 1
    private var imageHeight = 1

    fun updateTracks(newTracks: List<TrackerEntry>, imgW: Int, imgH: Int) {
        imageWidth = imgW
        imageHeight = imgH
        tracks.clear()
        tracks.addAll(newTracks)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth <= 0 || imageHeight <= 0 || tracks.isEmpty()) return

        // Matches CameraX PreviewView with FIT_CENTER
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val scale = Math.min(scaleX, scaleY)

        val scaledImageW = imageWidth * scale
        val scaledImageH = imageHeight * scale

        val offsetX = (width - scaledImageW) / 2f
        val offsetY = (height - scaledImageH) / 2f

        for (track in tracks) {
            val box = track.smoothedBox
            val left = box.x * scale + offsetX
            val top = box.y * scale + offsetY
            val right = (box.x + box.width) * scale + offsetX
            val bottom = (box.y + box.height) * scale + offsetY

            val z = track.distanceM
            if (z != null) {
                if (z < 0.8) {
                    paint.color = Color.RED
                } else if (z < 1.5) {
                    paint.color = Color.rgb(255, 165, 0) // ORANGE
                } else if (z < 3.0) {
                    paint.color = Color.YELLOW
                } else {
                    paint.color = Color.GRAY
                }
            } else {
                paint.color = Color.GRAY
            }

            canvas.drawRect(left, top, right, bottom, paint)
            
            val centerX = box.x + box.width / 2.0
            val zone = when {
                centerX < imageWidth * 0.35 -> "bên trái"
                centerX > imageWidth * 0.65 -> "bên phải"
                else -> "phía trước"
            }

            val distanceStr = z?.let { String.format("%.2fm", it) } ?: "N/A"
            canvas.drawText("ID: ${track.id}  $distanceStr  $zone", left, top - 10f, textPaint)
        }
    }
}
