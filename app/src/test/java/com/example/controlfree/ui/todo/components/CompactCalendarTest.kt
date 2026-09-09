package com.example.controlfree.ui.todo.components

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCalendarTest {

    @Test
    fun `七月网格按周日开头生成完整七列和五行`() {
        val cells = compactCalendarMonthCells(YearMonth.of(2026, 7))

        assertEquals(35, cells.size)
        assertEquals(0, cells.size % 7)
        assertNull(cells[0])
        assertNull(cells[1])
        assertNull(cells[2])
        assertEquals(LocalDate.of(2026, 7, 1), cells[3])
        assertEquals(LocalDate.of(2026, 7, 31), cells[33])
        assertNull(cells[34])
        assertEquals(31, cells.filterNotNull().size)
    }

    @Test
    fun `需要六周的月份不会裁掉末尾日期`() {
        val cells = compactCalendarMonthCells(YearMonth.of(2026, 8))

        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 8, 1), cells[6])
        assertEquals(LocalDate.of(2026, 8, 31), cells[36])
        assertEquals((1..31).toList(), cells.filterNotNull().map { it.dayOfMonth })
    }

    @Test
    fun `年月输入只接受范围内年份和一到十二月`() {
        assertEquals(
            YearMonth.of(2026, 7),
            parseCalendarYearMonth("2026", "7")
        )
        assertNull(parseCalendarYearMonth("1899", "12"))
        assertNull(parseCalendarYearMonth("2101", "1"))
        assertNull(parseCalendarYearMonth("2026", "0"))
        assertNull(parseCalendarYearMonth("2026", "13"))
        assertNull(parseCalendarYearMonth("年份", "7"))
        assertTrue(DEFAULT_CALENDAR_YEAR_RANGE.contains(2026))
    }
}
