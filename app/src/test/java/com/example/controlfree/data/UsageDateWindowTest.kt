package com.example.controlfree.data

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageDateWindowTest {
    @Test
    fun `春季夏令时自然日按23小时计算`() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2025, 3, 10, 12, 0, 0, 0, zone)
        val window = UsageDateWindowFactory.create(now.toInstant().toEpochMilli(), zone)
        val dstDay = window.days.single { it.date == LocalDate.of(2025, 3, 9) }

        assertEquals(Duration.ofHours(23L).toMillis(), dstDay.durationMillis)
    }

    @Test
    fun `秋季夏令时自然日按25小时计算`() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2025, 11, 3, 12, 0, 0, 0, zone)
        val window = UsageDateWindowFactory.create(now.toInstant().toEpochMilli(), zone)
        val dstDay = window.days.single { it.date == LocalDate.of(2025, 11, 2) }

        assertEquals(Duration.ofHours(25L).toMillis(), dstDay.durationMillis)
    }

    @Test
    fun `窗口固定包含今天和前六个本地自然日`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 7, 16, 15, 30, 0, 0, zone)
        val window = UsageDateWindowFactory.create(now.toInstant().toEpochMilli(), zone)

        assertEquals(7, window.days.size)
        assertEquals(LocalDate.of(2026, 7, 10), window.days.first().date)
        assertEquals(LocalDate.of(2026, 7, 16), window.days.last().date)
        assertEquals(now.toInstant().toEpochMilli(), window.days.last().endMillis)
        window.days.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.endMillis, next.startMillis)
        }
    }

    @Test
    fun `查询从第八个本地自然日零点开始预热`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2025, 4, 6, 9, 0, 0, 0, zone)
        val window = UsageDateWindowFactory.create(now.toInstant().toEpochMilli(), zone)
        val expectedQueryStart = LocalDate.of(2025, 3, 30)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(expectedQueryStart, window.queryStartMillis)
        assertEquals(LocalDate.of(2025, 3, 31), window.days.first().date)
        assertEquals(
            Duration.ofHours(23L).toMillis(),
            window.days.first().startMillis - window.queryStartMillis
        )
    }

    @Test
    fun `午夜时今天区间可以为零但仍保留七日边界`() {
        val zone = ZoneId.of("UTC")
        val now = LocalDate.of(2026, 1, 8).atStartOfDay(zone)
        val window = UsageDateWindowFactory.create(now.toInstant().toEpochMilli(), zone)

        assertEquals(0L, window.days.last().durationMillis)
        assertEquals(LocalDate.of(2026, 1, 2), window.days.first().date)
        assertEquals(LocalDate.of(2026, 1, 8), window.days.last().date)
    }
}
