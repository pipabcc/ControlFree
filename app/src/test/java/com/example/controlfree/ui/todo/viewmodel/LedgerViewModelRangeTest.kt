package com.example.controlfree.ui.todo.viewmodel

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerViewModelRangeTest {
    @Test
    fun `summary ranges use the selected month and half open boundaries`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 7, 23, 12, 0, 0, 0, zone)

        val ranges = ledgerSummaryRanges(2026, 7, now, zone)

        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.day.startEpochMillis
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 24, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.day.endExclusiveEpochMillis
        )
        assertTrue(ranges.day.startEpochMillis < ranges.day.endExclusiveEpochMillis)
        assertEquals(
            ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.month.endExclusiveEpochMillis
        )
    }

    @Test
    fun `day range follows zone transition without assuming 24 hours`() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, zone)

        val range = ledgerSummaryRanges(2026, 3, now, zone).day

        assertEquals(23 * 60 * 60 * 1_000L, range.endExclusiveEpochMillis - range.startEpochMillis)
    }

    @Test
    fun `historical month keeps day and week anchored to the actual current date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 7, 23, 12, 0, 0, 0, zone)

        val ranges = ledgerSummaryRanges(2025, 12, now, zone)

        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.day.startEpochMillis
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 20, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.week.startEpochMillis
        )
        assertEquals(
            ZonedDateTime.of(2025, 12, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.month.startEpochMillis
        )
        assertEquals(
            ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ranges.year.startEpochMillis
        )
    }

}
