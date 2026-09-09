package com.example.controlfree.ui.main

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanCardActionsTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `默认预约时间为当前时间后一小时并清除秒`() {
        val now = LocalDateTime.of(2026, 7, 18, 9, 23, 45)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        assertEquals(
            LocalDateTime.of(2026, 7, 18, 10, 23),
            defaultScheduledEnableDateTime(now, zoneId)
        )
    }

    @Test
    fun `预约时间必须至少晚于当前一分钟`() {
        val now = 1_800_000_000_000L

        assertTrue(scheduledEnableValidationMessage(now + 59_999L, now) != null)
        assertNull(scheduledEnableValidationMessage(now + 60_000L, now))
    }

    @Test
    fun `本地日期时间可以稳定转换并格式化`() {
        val date = LocalDate.of(2026, 7, 20)
        val time = LocalTime.of(8, 30)
        val epochMillis = resolveScheduledEnableEpochMillis(date, time, zoneId)

        assertEquals(
            Instant.parse("2026-07-20T00:30:00Z").toEpochMilli(),
            epochMillis
        )
        assertTrue(
            formatScheduledEnableAt(epochMillis, zoneId, Locale.SIMPLIFIED_CHINESE)
                .contains("08:30")
        )
    }

    @Test
    fun `任务忙碌期间拒绝重复卡片操作`() {
        var executionCount = 0

        runPlanInteractionIfIdle(busy = true) { executionCount += 1 }

        assertEquals(0, executionCount)
    }

    @Test
    fun `任务空闲时只执行当前交互`() {
        var executionCount = 0

        runPlanInteractionIfIdle(busy = false) { executionCount += 1 }

        assertEquals(1, executionCount)
    }
}
