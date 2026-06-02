package com.example.blindassist.depth

import android.util.Log
import org.opencv.core.Rect
import com.example.blindassist.Config
import kotlin.math.max
import kotlin.math.min

object BBoxMerger {

    private const val TAG = "BBoxMerger"

    fun merge(boxes: List<Rect>, frameWidth: Int, frameHeight: Int): List<Rect> {
        if (boxes.isEmpty()) return emptyList()
        if (boxes.size == 1) {
            Log.d(TAG, "frame=${frameWidth}x${frameHeight} | raw=1 → merged=1 (passthrough)")
            return boxes
        }

        val uf = UnionFind(boxes.size)

        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                if (shouldMerge(boxes[i], boxes[j])) {
                    uf.union(i, j)
                }
            }
        }

        val result = mutableListOf<Rect>()
        val groups = uf.groups()

        for ((root, group) in groups) {
            if (group.size == 1) {
                result.add(boxes[group[0]])
            } else {
                result.add(mergeGroup(group.map { boxes[it] }, frameWidth, frameHeight))
            }
        }

        // --- Debug log ---
        val groupSizes = groups.values.map { it.size }.sortedDescending()
        Log.d(TAG, "frame=${frameWidth}x${frameHeight} | raw=${boxes.size} → merged=${result.size} | groups=$groupSizes")
        for ((i, box) in result.withIndex()) {
            Log.d(TAG, "  box[$i]: x=${box.x} y=${box.y} w=${box.width} h=${box.height} area=${box.width * box.height}")
        }

        return result
    }

    private fun shouldMerge(a: Rect, b: Rect): Boolean {
        // Calculate Intersection over Union (IoU)
        val interX = max(a.x, b.x)
        val interY = max(a.y, b.y)
        val interRight = min(a.x + a.width, b.x + b.width)
        val interBottom = min(a.y + a.height, b.y + b.height)

        val interWidth = max(0, interRight - interX)
        val interHeight = max(0, interBottom - interY)
        val interArea = interWidth * interHeight

        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val unionArea = areaA + areaB - interArea

        val iou = if (unionArea > 0) interArea.toFloat() / unionArea else 0f

        if (iou >= Config.MERGE_IOU_THRESHOLD) return true

        // Calculate Gap
        val gapX = max(0, max(a.x, b.x) - min(a.x + a.width, b.x + b.width))
        val gapY = max(0, max(a.y, b.y) - min(a.y + a.height, b.y + b.height))

        return gapX <= Config.MERGE_GAP_PX && gapY <= Config.MERGE_GAP_PX
    }

    private fun mergeGroup(boxes: List<Rect>, frameWidth: Int, frameHeight: Int): Rect {
        val minX = boxes.minOf { it.x }.coerceAtLeast(0)
        val minY = boxes.minOf { it.y }.coerceAtLeast(0)
        val maxX = boxes.maxOf { it.x + it.width }.coerceAtMost(frameWidth)
        val maxY = boxes.maxOf { it.y + it.height }.coerceAtMost(frameHeight)
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }
}
