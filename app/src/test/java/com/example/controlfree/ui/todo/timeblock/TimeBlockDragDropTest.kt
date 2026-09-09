package com.example.controlfree.ui.todo.timeblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeBlockDragDropTest {
    @Test
    fun `日期列使用半开边界精确命中`() {
        assertNull(dayIndexAt(45.999f))
        assertEquals(0, dayIndexAt(46f))
        assertEquals(0, dayIndexAt(145.999f))
        assertEquals(1, dayIndexAt(146f))
        assertEquals(2, dayIndexAt(246f))
        assertEquals(2, dayIndexAt(345.999f))
        assertNull(dayIndexAt(346f))
    }

    @Test
    fun `三十分钟格使用半开边界向下吸附`() {
        assertEquals(0, minuteAt(0f))
        assertEquals(0, minuteAt(29.999f))
        assertEquals(30, minuteAt(30f))
        assertEquals(30, minuteAt(59.999f))
        assertEquals(60, minuteAt(60f))
        assertEquals(1_410, minuteAt(1_439.999f))
        assertNull(minuteAt(1_440f))
    }

    @Test
    fun `滚动后的负网格原点仍映射到完整时间轴`() {
        val payload = payload(
            width = 240f,
            height = 48f,
            anchorX = 12f,
            anchorY = 24f
        )
        val hit = resolveTimeBlockInboxGridDrop(
            dropXInRootPx = 314f,
            dropYInRootPx = 120f,
            payload = payload,
            geometry = geometry(topInRoot = -480f)
        )

        assertEquals(TimeBlockGridDropHit(dayIndex = 1, minute = 600), hit)
    }

    @Test
    fun `不同拖影尺寸按同一圆点位置得到相同格子`() {
        val small = payload(width = 100f, height = 48f, anchorX = 10f, anchorY = 24f)
        val large = payload(width = 280f, height = 72f, anchorX = 10f, anchorY = 36f)
        val grid = geometry()

        val smallHit = resolveTimeBlockInboxGridDrop(
            dropXInRootPx = 320f,
            dropYInRootPx = 360f,
            payload = small,
            geometry = grid
        )
        val largeHit = resolveTimeBlockInboxGridDrop(
            dropXInRootPx = 410f,
            dropYInRootPx = 360f,
            payload = large,
            geometry = grid
        )

        assertEquals(TimeBlockGridDropHit(dayIndex = 2, minute = 360), smallHit)
        assertEquals(smallHit, largeHit)
    }

    @Test
    fun `整行拖影以中心圆点作为系统热点精准落格`() {
        val fullRowPayload = payload(
            width = 996f,
            height = 48f,
            anchorX = 498f,
            anchorY = 24f
        )

        val hit = resolveTimeBlockInboxGridDrop(
            dropXInRootPx = 246f,
            dropYInRootPx = 30f,
            payload = fullRowPayload,
            geometry = geometry()
        )

        assertEquals(TimeBlockGridDropHit(dayIndex = 2, minute = 30), hit)
    }

    @Test
    fun `圆点落在网格右边界或底边界时不命中`() {
        val centeredPayload = payload(
            width = 100f,
            height = 48f,
            anchorX = 50f,
            anchorY = 24f
        )
        val grid = geometry()

        assertNull(
            resolveTimeBlockInboxGridDrop(
                dropXInRootPx = 346f,
                dropYInRootPx = 300f,
                payload = centeredPayload,
                geometry = grid
            )
        )
        assertNull(
            resolveTimeBlockInboxGridDrop(
                dropXInRootPx = 200f,
                dropYInRootPx = 1_440f,
                payload = centeredPayload,
                geometry = grid
            )
        )
    }

    @Test
    fun `全天条带按圆点使用半开日期边界`() {
        val centeredPayload = payload(
            width = 100f,
            height = 48f,
            anchorX = 50f,
            anchorY = 24f
        )
        val geometry = TimeBlockDayDropGeometry(
            leftInRootPx = 10f,
            widthPx = 346f,
            gutterWidthPx = 46f,
            dayCount = 3
        )

        assertNull(resolveTimeBlockDayDropIndex(55.999f, centeredPayload, geometry))
        assertEquals(0, resolveTimeBlockDayDropIndex(56f, centeredPayload, geometry))
        assertEquals(1, resolveTimeBlockDayDropIndex(156f, centeredPayload, geometry))
        assertEquals(2, resolveTimeBlockDayDropIndex(256f, centeredPayload, geometry))
        assertNull(resolveTimeBlockDayDropIndex(356f, centeredPayload, geometry))
    }

    @Test
    fun `全天任务预计时长向上对齐且午夜前保持释放格为开始格`() {
        assertEquals(
            TimeBlockDropWindow(startMinute = 10 * 60 + 30, endMinuteExclusive = 12 * 60 + 30),
            timeBlockDropWindow(dropMinute = 10 * 60 + 30, estimatedDurationMinutes = 95)
        )
        assertEquals(
            TimeBlockDropWindow(startMinute = 23 * 60 + 30, endMinuteExclusive = 24 * 60),
            timeBlockDropWindow(dropMinute = 23 * 60 + 30, estimatedDurationMinutes = 45)
        )
    }

    @Test
    fun `自定义吸附粒度同时作用于开始时间和预计时长`() {
        assertEquals(
            TimeBlockDropWindow(startMinute = 10 * 60 + 15, endMinuteExclusive = 11 * 60 + 45),
            timeBlockDropWindow(
                dropMinute = 10 * 60 + 29,
                estimatedDurationMinutes = 76,
                snapMinutes = 15
            )
        )
    }

    @Test
    fun `缩放使用最新开始时间并阻止零时长`() {
        assertEquals(
            4 * 60,
            timeBlockResizeEndMinute(
                currentStartMinute = 3 * 60 + 30,
                requestedEndMinuteExclusive = 3 * 60 + 30
            )
        )
        assertEquals(
            24 * 60,
            timeBlockResizeEndMinute(
                currentStartMinute = 23 * 60 + 45,
                requestedEndMinuteExclusive = 23 * 60 + 30
            )
        )
    }

    private fun dayIndexAt(x: Float): Int? = timeBlockDayIndexAt(
        anchorXInGridPx = x,
        gridWidthPx = 346f,
        gutterWidthPx = 46f,
        dayCount = 3
    )

    private fun minuteAt(y: Float): Int? = timeBlockMinuteAt(
        anchorYInGridPx = y,
        gridHeightPx = 1_440f,
        hourHeightPx = 60f,
        startMinute = 0,
        endMinuteExclusive = 1_440,
        snapMinutes = 30
    )

    private fun payload(
        width: Float,
        height: Float,
        anchorX: Float,
        anchorY: Float
    ) = TimeBlockInboxDragPayload(
        todoId = "todo-1",
        shadowWidthPx = width,
        shadowHeightPx = height,
        anchorXInShadowPx = anchorX,
        anchorYInShadowPx = anchorY
    )

    private fun geometry(topInRoot: Float = 0f) = TimeBlockGridDropGeometry(
        leftInRootPx = 0f,
        topInRootPx = topInRoot,
        widthPx = 346f,
        heightPx = 1_440f,
        gutterWidthPx = 46f,
        hourHeightPx = 60f,
        dayCount = 3,
        startMinute = 0,
        endMinuteExclusive = 1_440,
        snapMinutes = 30
    )
}
