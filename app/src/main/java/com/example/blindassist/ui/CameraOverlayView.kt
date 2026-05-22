package com.example.blindassist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import org.opencv.core.Rect

class CameraOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxes = mutableListOf<Rect>()
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    fun updateBoxes(newBoxes: List<Rect>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in boxes) {
            canvas.drawRect(
                box.x.toFloat(),
                box.y.toFloat(),
                (box.x + box.width).toFloat(),
                (box.y + box.height).toFloat(),
                paint
            )
        }
    }
}
