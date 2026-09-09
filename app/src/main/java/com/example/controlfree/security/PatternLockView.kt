package com.example.controlfree.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.controlfree.theme.BrandColorInts
import kotlin.math.hypot
import kotlin.math.min

class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onPatternComplete: (List<Int>) -> Unit = {}

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColorInts.TextTertiary
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColorInts.Primary
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColorInts.Primary
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (BrandColorInts.Primary and 0x00FFFFFF) or (0xCC shl 24)
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val centers = MutableList(9) { PointF() }
    private val selected = mutableListOf<Int>()
    private var activePointer = PointF()
    private var isDrawing = false
    private var isParentInterceptionDisallowed = false
    private var hitRadius = 0f

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val cell = size / 3f
        hitRadius = cell * 0.32f
        for (index in centers.indices) {
            val row = index / 3
            val column = index % 3
            centers[index].set(
                left + cell * (column + 0.5f),
                top + cell * (row + 0.5f)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (selected.size > 1) {
            for (index in 0 until selected.lastIndex) {
                val start = centers[selected[index]]
                val end = centers[selected[index + 1]]
                canvas.drawLine(start.x, start.y, end.x, end.y, linePaint)
            }
        }
        if (isDrawing && selected.isNotEmpty()) {
            val last = centers[selected.last()]
            canvas.drawLine(last.x, last.y, activePointer.x, activePointer.y, linePaint)
        }

        centers.forEachIndexed { index, center ->
            if (index in selected) {
                canvas.drawCircle(center.x, center.y, dp(12f), selectedPaint)
                canvas.drawCircle(center.x, center.y, dp(22f), selectedRingPaint)
            } else {
                canvas.drawCircle(center.x, center.y, dp(13f), normalPaint)
            }
        }
    }

    /**
     * Compose 主界面可随亮/深色主题传入语义色；原生锁屏不调用时继续使用固定深色色板。
     */
    fun setColors(normalColor: Int, selectedColor: Int) {
        val lineColor = (selectedColor and 0x00FFFFFF) or (0xCC shl 24)
        if (
            normalPaint.color == normalColor &&
            selectedPaint.color == selectedColor &&
            selectedRingPaint.color == selectedColor &&
            linePaint.color == lineColor
        ) {
            return
        }
        normalPaint.color = normalColor
        selectedPaint.color = selectedColor
        selectedRingPaint.color = selectedColor
        linePaint.color = lineColor
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(clearPatternRunnable)
                selected.clear()
                isDrawing = true
                setParentInterceptionDisallowed(true)
                activePointer.set(event.x, event.y)
                addHitNode(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return false
                setParentInterceptionDisallowed(true)
                addHistoricalPoints(event)
                addPointerPoint(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                if (!isDrawing) return false
                addHistoricalPoints(event)
                addPointerPoint(event.x, event.y)
                isDrawing = false
                setParentInterceptionDisallowed(false)
                performClick()
                postDelayed(clearPatternRunnable, CLEAR_DELAY_MILLIS)
                if (selected.isNotEmpty()) onPatternComplete(selected.toList())
            }
            MotionEvent.ACTION_CANCEL -> {
                resetTouchState()
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun clearPattern() {
        removeCallbacks(clearPatternRunnable)
        resetTouchState()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearPatternRunnable)
        resetTouchState()
        super.onDetachedFromWindow()
    }

    private fun addHistoricalPoints(event: MotionEvent) {
        for (index in 0 until event.historySize) {
            addPointerPoint(event.getHistoricalX(index), event.getHistoricalY(index))
        }
    }

    private fun addPointerPoint(x: Float, y: Float) {
        addNodesAlongSegment(activePointer.x, activePointer.y, x, y)
        activePointer.set(x, y)
    }

    private fun addNodesAlongSegment(startX: Float, startY: Float, endX: Float, endY: Float) {
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0f) {
            addHitNode(endX, endY)
            return
        }

        val radiusSquared = hitRadius * hitRadius
        centers.indices
            .asSequence()
            .filterNot(selected::contains)
            .mapNotNull { index ->
                val center = centers[index]
                val position = (
                    (center.x - startX) * deltaX + (center.y - startY) * deltaY
                ) / lengthSquared
                if (position !in 0f..1f) return@mapNotNull null

                val closestX = startX + position * deltaX
                val closestY = startY + position * deltaY
                val distanceX = center.x - closestX
                val distanceY = center.y - closestY
                if (distanceX * distanceX + distanceY * distanceY <= radiusSquared) {
                    index to position
                } else {
                    null
                }
            }
            .sortedBy { (_, position) -> position }
            .forEach { (index, _) -> addHitNode(index) }
    }

    private fun addHitNode(x: Float, y: Float) {
        val hit = centers.indexOfFirst { center -> hypot(center.x - x, center.y - y) <= hitRadius }
        if (hit >= 0) addHitNode(hit)
    }

    private fun addHitNode(hit: Int) {
        if (hit in selected) return

        selected.lastOrNull()?.let { previous ->
            skippedMiddleNode(previous, hit)
                ?.takeIf { it !in selected }
                ?.let(selected::add)
        }
        selected.add(hit)
    }

    private fun resetTouchState() {
        isDrawing = false
        setParentInterceptionDisallowed(false)
        selected.clear()
    }

    private fun setParentInterceptionDisallowed(disallowed: Boolean) {
        if (isParentInterceptionDisallowed == disallowed) return
        parent?.requestDisallowInterceptTouchEvent(disallowed)
        isParentInterceptionDisallowed = disallowed
    }

    private fun skippedMiddleNode(from: Int, to: Int): Int? {
        val pair = minOf(from, to) to maxOf(from, to)
        return when (pair) {
            0 to 2 -> 1
            0 to 6 -> 3
            0 to 8 -> 4
            1 to 7 -> 4
            2 to 6 -> 4
            2 to 8 -> 5
            3 to 5 -> 4
            6 to 8 -> 7
            else -> null
        }
    }

    private val clearPatternRunnable = Runnable {
        selected.clear()
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val CLEAR_DELAY_MILLIS = 450L
    }
}
