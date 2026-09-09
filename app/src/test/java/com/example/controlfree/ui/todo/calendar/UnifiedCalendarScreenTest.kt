package com.example.controlfree.ui.todo.calendar

import com.example.controlfree.productivity.calendar.CalendarEventType
import com.example.controlfree.productivity.calendar.ProductivityCalendarEvent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedCalendarScreenTest {
    @Test
    fun `三日视图按今天昨天前天倒序展示`() {
        val today = LocalDate.of(2026, 7, 30)

        val dates = visibleDatesFor(
            viewMode = CalendarViewMode.DAY,
            today = today
        )

        assertEquals(
            listOf(today, today.minusDays(1), today.minusDays(2)),
            dates
        )
        assertEquals(today.minusDays(2), statisticsRangeFor(dates).startDate)
        assertEquals(today, statisticsRangeFor(dates).endDateInclusive)
    }

    @Test
    fun `周视图展示最近七天且最后一天为今天`() {
        val today = LocalDate.of(2026, 8, 2)
        val dates = visibleDatesFor(CalendarViewMode.WEEK, today)

        assertEquals(RECENT_WEEK_DAY_COUNT, dates.size)
        assertEquals(today.minusDays(6), dates.first())
        assertEquals(today, dates.last())
    }

    @Test
    fun `月视图展示最近三十天并保持跨月网格对齐`() {
        val today = LocalDate.of(2026, 8, 5)
        val dates = visibleDatesFor(CalendarViewMode.MONTH, today)
        val cells = alignedDateGridCells(dates)

        assertEquals(RECENT_MONTH_DAY_COUNT, dates.size)
        assertEquals(today.minusDays(29), dates.first())
        assertEquals(today, dates.last())
        assertEquals(dates, cells.filterNotNull())
        assertEquals(0, cells.size % 7)
    }

    @Test
    fun `监督和专注记录超过两条时默认只展示前两条`() {
        val events = (1..3).map { index ->
            ProductivityCalendarEvent(
                id = "focus-$index",
                title = "专注 $index",
                type = CalendarEventType.FOCUS,
                startEpochMillis = index.toLong()
            )
        }

        assertEquals(events.take(2), collapsedCalendarEvents(events, expanded = false))
        assertEquals(events, collapsedCalendarEvents(events, expanded = true))
    }

    @Test
    fun `折叠状态键按日期和记录类型隔离`() {
        val date = LocalDate.of(2026, 7, 26)

        assertEquals("2026-07-26:FOCUS", calendarEventGroupKey(date, CalendarEventType.FOCUS))
        assertEquals(
            "2026-07-26:SUPERVISION",
            calendarEventGroupKey(date, CalendarEventType.SUPERVISION)
        )
    }
}
