package com.example.controlfree.ui.todo.timeblock

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeBlockModelsTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 7, 29)

    @Test
    fun `四视图使用各自稳定的日期范围`() {
        assertEquals(
            listOf(today, today.plusDays(1), today.plusDays(2)),
            visibleDates(TimeBlockViewMode.THREE_DAYS, today)
        )
        assertEquals(LocalDate.of(2026, 7, 27), visibleDates(TimeBlockViewMode.WEEK, today).first())
        assertEquals(42, visibleDates(TimeBlockViewMode.MONTH, today).size)
        assertEquals(listOf(today), visibleDates(TimeBlockViewMode.TIMELINE, today))
    }

    @Test
    fun `月网格固定六行且以周一开头`() {
        val dates = monthGridDates(YearMonth.of(2026, 8))

        assertEquals(42, dates.size)
        assertEquals(LocalDate.of(2026, 7, 27), dates.first())
        assertEquals(java.time.DayOfWeek.MONDAY, dates.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 6), dates.last())
    }

    @Test
    fun `并发条目分配独立泳道且非并发条目复用泳道`() {
        val first = timed("first", 9 * 60, 11 * 60)
        val overlap = timed("overlap", 10 * 60, 12 * 60)
        val later = timed("later", 12 * 60, 13 * 60)

        val placements = timedPlacements(listOf(first, overlap, later), listOf(today), zoneId)

        assertEquals(2, placements.first { it.entry.id == "first" }.laneCount)
        assertEquals(2, placements.first { it.entry.id == "overlap" }.laneCount)
        assertEquals(1, placements.first { it.entry.id == "later" }.laneCount)
        assertEquals(0, placements.first { it.entry.id == "later" }.lane)
    }

    @Test
    fun `空档会合并重叠占用并按三十分钟过滤`() {
        val first = timed("first", 9 * 60, 10 * 60)
        val overlap = timed("overlap", 9 * 60 + 30, 11 * 60)

        val gaps = freeGaps(
            date = today,
            entries = listOf(first, overlap),
            zoneId = zoneId,
            windowStartMinute = 8 * 60,
            windowEndMinute = 12 * 60
        )

        assertEquals(
            listOf(
                TimeBlockGap(today, 8 * 60, 9 * 60),
                TimeBlockGap(today, 11 * 60, 12 * 60)
            ),
            gaps
        )
    }

    @Test
    fun `过期只应用于尚未完成且截止时间已过的待办`() {
        val now = epochMillis(today, 12 * 60, zoneId)
        val active = deadline("active", 10 * 60, date = today.minusDays(1))
        val earlierToday = deadline("earlier-today", 10 * 60)
        val future = deadline("future", 14 * 60)
        val completed = deadline("completed", 10 * 60, completed = true, date = today.minusDays(1))

        assertTrue(isExpired(active, now, zoneId))
        assertFalse(isExpired(earlierToday, now, zoneId))
        assertFalse(isExpired(future, now, zoneId))
        assertFalse(isExpired(completed, now, zoneId))
        assertFalse(isExpired(active.copy(source = TimeBlockSource.EVENT), now, zoneId))
    }

    @Test
    fun `时间线时长文案与原型保持一致`() {
        assertEquals("45′", formatDurationMinutes(45))
        assertEquals("1小时", formatDurationMinutes(60))
        assertEquals("1小时30′", formatDurationMinutes(90))
    }

    @Test
    fun `日历标签覆盖原型中的节日和节气`() {
        assertEquals(
            TimeBlockCalendarLabel("建党节", isSolarTerm = false),
            timeBlockCalendarLabel(LocalDate.of(2026, 7, 1))
        )
        assertEquals(
            TimeBlockCalendarLabel("小暑", isSolarTerm = true),
            timeBlockCalendarLabel(LocalDate.of(2026, 7, 7))
        )
        assertEquals(
            TimeBlockCalendarLabel("大暑", isSolarTerm = true),
            timeBlockCalendarLabel(LocalDate.of(2026, 7, 23))
        )
        assertEquals(
            TimeBlockCalendarLabel("立秋", isSolarTerm = true),
            timeBlockCalendarLabel(LocalDate.of(2026, 8, 7))
        )
        assertNull(timeBlockCalendarLabel(LocalDate.of(2026, 7, 29)))
    }

    private fun timed(id: String, startMinute: Int, endMinute: Int) = TimeBlockEntry(
        source = TimeBlockSource.EVENT,
        id = id,
        title = id,
        project = "工作",
        priority = 0,
        isCompleted = false,
        scheduledStartEpochMillis = epochMillis(today, startMinute, zoneId),
        scheduledEndEpochMillis = epochMillis(today, endMinute, zoneId),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )

    private fun deadline(
        id: String,
        minute: Int,
        completed: Boolean = false,
        date: LocalDate = today
    ) = TimeBlockEntry(
        source = TimeBlockSource.TODO,
        id = id,
        title = id,
        project = "工作",
        priority = 0,
        isCompleted = completed,
        dueAtEpochMillis = epochMillis(date, minute, zoneId),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )
}
