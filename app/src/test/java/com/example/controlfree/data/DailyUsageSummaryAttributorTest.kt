package com.example.controlfree.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyUsageSummaryAttributorTest {
    @Test
    fun `跨天日桶只按中点归属到一天`() {
        val result = DailyUsageSummaryAttributor.attribute(
            buckets = listOf(
                // 桶从第一天 20:00 跨到第二天 04:00，中点在第二天 00:00 之后。
                bucket(
                    firstMillis = day1Start + 20 * HOUR,
                    lastMillis = day2Start + 4 * HOUR,
                    foregroundMillis = 5 * HOUR
                )
            ),
            days = twoDays()
        )

        assertNull(result.getValue(DATE_1)[APP_A])
        assertEquals(5 * HOUR, result.getValue(DATE_2).getValue(APP_A).foregroundMillis)
    }

    @Test
    fun `单包单日汇总不超过当日实际长度`() {
        val result = DailyUsageSummaryAttributor.attribute(
            buckets = listOf(
                bucket(day1Start, day1Start + DAY, foregroundMillis = 20 * HOUR),
                bucket(day1Start + HOUR, day1Start + DAY, foregroundMillis = 9 * HOUR)
            ),
            days = twoDays()
        )

        assertEquals(DAY, result.getValue(DATE_1).getValue(APP_A).foregroundMillis)
    }

    @Test
    fun `中点落在窗口之前的回溯桶被丢弃`() {
        val result = DailyUsageSummaryAttributor.attribute(
            buckets = listOf(
                bucket(day1Start - DAY, day1Start - HOUR, foregroundMillis = 2 * HOUR)
            ),
            days = twoDays()
        )

        assertTrue(result.getValue(DATE_1).isEmpty())
        assertTrue(result.getValue(DATE_2).isEmpty())
    }

    @Test
    fun `同一天内的多个桶正常求和并保留最新使用时间`() {
        val result = DailyUsageSummaryAttributor.attribute(
            buckets = listOf(
                bucket(
                    day1Start,
                    day1Start + 2 * HOUR,
                    foregroundMillis = HOUR,
                    lastUsedMillis = day1Start + 2 * HOUR
                ),
                bucket(
                    day1Start + 5 * HOUR,
                    day1Start + 7 * HOUR,
                    foregroundMillis = HOUR,
                    lastUsedMillis = day1Start + 7 * HOUR
                )
            ),
            days = twoDays()
        )

        val summary = result.getValue(DATE_1).getValue(APP_A)
        assertEquals(2 * HOUR, summary.foregroundMillis)
        assertEquals(day1Start + 7 * HOUR, summary.lastTimeUsedMillis)
    }

    @Test
    fun `末尾时间戳异常超过窗口时中点被钳制回窗口内`() {
        val days = twoDays()
        val result = DailyUsageSummaryAttributor.attribute(
            buckets = listOf(
                bucket(
                    firstMillis = day2Start + HOUR,
                    lastMillis = day2Start + 3 * DAY,
                    foregroundMillis = HOUR
                )
            ),
            days = days
        )

        assertEquals(HOUR, result.getValue(DATE_2).getValue(APP_A).foregroundMillis)
    }

    private fun bucket(
        firstMillis: Long,
        lastMillis: Long,
        foregroundMillis: Long,
        lastUsedMillis: Long = lastMillis
    ) = UsageSummaryBucket(
        packageName = APP_A,
        firstTimeStampMillis = firstMillis,
        lastTimeStampMillis = lastMillis,
        foregroundMillis = foregroundMillis,
        lastTimeUsedMillis = lastUsedMillis
    )

    private fun twoDays() = listOf(
        UsageDayRange(DATE_1, day1Start, day2Start),
        UsageDayRange(DATE_2, day2Start, day2Start + DAY)
    )

    private companion object {
        const val APP_A = "example.a"
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
        val DATE_1: LocalDate = LocalDate.of(2026, 7, 20)
        val DATE_2: LocalDate = LocalDate.of(2026, 7, 21)
        const val day1Start = 1_800_000_000_000L
        const val day2Start = day1Start + DAY
    }
}
