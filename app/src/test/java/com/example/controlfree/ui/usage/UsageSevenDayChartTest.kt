package com.example.controlfree.ui.usage

import com.example.controlfree.data.AppUsageDayDetails
import com.example.controlfree.data.AppUsageSessionDetailCompleteness
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSevenDayChartTest {
    @Test
    fun `图表固定按日期升序取最新七天并合计时长`() {
        val start = LocalDate.of(2026, 7, 25)
        val source = (0 until 9).map { offset ->
            day(
                date = start.plusDays(offset.toLong()),
                foregroundMillis = (offset + 1L) * MINUTE
            )
        }.reversed()

        val model = buildUsageSevenDayChartModel(source)

        assertEquals(start.plusDays(2L), model.days.first().date)
        assertEquals(start.plusDays(8L), model.days.last().date)
        assertEquals((3L + 4L + 5L + 6L + 7L + 8L + 9L) * MINUTE, model.totalMillis)
    }

    @Test
    fun `排名颜色语义与全机七日图一致且同值保持日期顺序`() {
        val emphases = resolveUsageBarEmphases(
            listOf(60L, 30L, 60L, 10L, 0L, 20L, 40L)
        )

        assertEquals(UsageBarEmphasis.HIGHEST, emphases[0])
        assertEquals(UsageBarEmphasis.SECOND, emphases[2])
        assertEquals(UsageBarEmphasis.THIRD, emphases[6])
        assertEquals(UsageBarEmphasis.STANDARD, emphases[4])
    }

    @Test
    fun `纵轴使用整刻度并为零数据保留一小时坐标`() {
        assertEquals(60L * MINUTE, resolveUsageChartAxisTopMillis(0L))
        assertEquals(30L * MINUTE, resolveUsageChartAxisTopMillis(21L * MINUTE))
        assertEquals(120L * MINUTE, resolveUsageChartAxisTopMillis(61L * MINUTE))
        assertEquals(1_800L * MINUTE, resolveUsageChartAxisTopMillis(1_441L * MINUTE))
    }

    @Test
    fun `合计与坐标格式保持紧凑中文表达`() {
        assertEquals("0分钟", formatUsageSevenDayTotal(0L))
        assertEquals("42分钟", formatUsageSevenDayTotal(42L * MINUTE))
        assertEquals("3小时12分钟", formatUsageSevenDayTotal(192L * MINUTE))
        assertEquals("1.5h", formatUsageChartAxisLabel(90L * MINUTE, useHourUnit = true))
        assertEquals("30m", formatUsageChartAxisLabel(30L * MINUTE, useHourUnit = false))
    }

    private fun day(
        date: LocalDate,
        foregroundMillis: Long
    ) = AppUsageDayDetails(
        date = date,
        foregroundMillis = foregroundMillis,
        sessions = emptyList(),
        sessionDetailCompleteness = AppUsageSessionDetailCompleteness.COMPLETE
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
