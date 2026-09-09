package com.example.controlfree.todo.anniversary.runtime

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AnniversaryNotificationTextTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `剩余时长按天小时分钟粗粒度展示`() {
        assertEquals("7天3小时", formatCoarseDuration(7L * DAY + 3L * HOUR + 25L * MINUTE))
        assertEquals("3小时25分钟", formatCoarseDuration(3L * HOUR + 25L * MINUTE))
        assertEquals("25分钟", formatCoarseDuration(25L * MINUTE))
        assertEquals("0分钟", formatCoarseDuration(0L))
        assertEquals("0分钟", formatCoarseDuration(-5_000L))
    }

    @Test
    fun `同年目标时间省略年份`() {
        val now = Instant.parse("2026-07-24T04:00:00Z")
        val target = Instant.parse("2026-08-01T01:00:00Z")
        assertEquals("8月1日 09:00", formatAnniversaryInstant(target, now, zone))
    }

    @Test
    fun `跨年目标时间带年份`() {
        val now = Instant.parse("2026-07-24T04:00:00Z")
        val target = Instant.parse("2027-01-01T01:30:00Z")
        assertEquals("2027年1月1日 09:30", formatAnniversaryInstant(target, now, zone))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }
}
