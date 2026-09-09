package com.example.controlfree.growth

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthTimeWindowTest {
    @Test
    fun `自然日窗口遵循设备时区而不是固定二十四小时`() {
        val zone = ZoneId.of("America/New_York")
        val instant = Instant.parse("2026-03-08T16:00:00Z").toEpochMilli()
        val window = GrowthDayWindow.containing(instant, zone)

        assertTrue(window.contains(instant))
        assertEquals(23L * 60L * 60L * 1_000L, window.endExclusiveEpochMillis - window.startEpochMillis)
    }
}
