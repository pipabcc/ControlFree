package com.example.controlfree.ui.todo.viewmodel

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedCalendarDataRangeTest {
    @Test
    fun `数据源覆盖最近三十天和未来推荐窗口`() {
        val today = LocalDate.of(2026, 8, 2)
        val range = calendarDataRange(today)

        assertEquals(today.minusDays(31), range.startDate)
        assertEquals(today.plusDays(7), range.endDateInclusive)
        assertTrue(today.minusDays(29) in range)
        assertTrue(today in range)
        assertFalse(today.minusDays(32) in range)
    }
}
