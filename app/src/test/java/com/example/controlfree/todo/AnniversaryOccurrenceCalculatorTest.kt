package com.example.controlfree.todo

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryOccurrenceCalculatorTest {
    @Test
    fun 农历年度三十日在目标小月回退到二十九日() {
        val sourceSolarDate = solarDateOf(2021, 2, 30, isLeapMonth = false)
        val anniversary = anniversary(
            id = "lunar-month-end",
            targetSolarDate = sourceSolarDate,
            isLunar = true,
            repeatRule = AnniversaryRepeatRule.YEARLY,
            sourceYear = 2021,
            sourceMonth = 2,
            sourceDay = 30
        )

        val occurrences = AnniversaryOccurrenceCalculator.occurrencesBetween(
            anniversary,
            Instant.parse("2022-01-01T00:00:00Z"),
            Instant.parse("2023-01-01T00:00:00Z")
        )

        assertEquals(1, occurrences.size)
        val lunar = ChineseLunarCalendar.solarToLunar(occurrences.single().date)
        assertEquals(ChineseLunarDate(2022, 2, 29, false), lunar)
        assertTrue(ChineseLunarCalendar.isMonthEnd(occurrences.single().date))
    }

    @Test
    fun 闰月年度纪念优先闰月且无闰月年份回退普通月() {
        val sourceSolarDate = solarDateOf(2023, 2, 15, isLeapMonth = true)
        val anniversary = anniversary(
            id = "lunar-leap-month",
            targetSolarDate = sourceSolarDate,
            isLunar = true,
            repeatRule = AnniversaryRepeatRule.YEARLY,
            sourceYear = 2023,
            sourceMonth = 2,
            sourceDay = 15,
            isLunarLeapMonth = true
        )

        val sourceYearOccurrence = AnniversaryOccurrenceCalculator.occurrencesBetween(
            anniversary,
            sourceSolarDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
            Instant.parse("2024-01-01T00:00:00Z")
        ).single()
        val followingYearOccurrence = AnniversaryOccurrenceCalculator.occurrencesBetween(
            anniversary,
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2025-01-01T00:00:00Z")
        ).single()

        assertEquals(
            ChineseLunarDate(2023, 2, 15, true),
            ChineseLunarCalendar.solarToLunar(sourceYearOccurrence.date)
        )
        assertEquals(
            ChineseLunarDate(2024, 2, 15, false),
            ChineseLunarCalendar.solarToLunar(followingYearOccurrence.date)
        )
    }

    @Test
    fun 已到期一次性倒数日在任意刷新时间都生成相同补偿键() {
        val target = LocalDate.of(2026, 7, 20)
        val anniversary = anniversary(
            id = "missed-one-shot",
            targetSolarDate = target,
            repeatRule = AnniversaryRepeatRule.NONE,
            createdAt = Instant.parse("2026-07-01T00:00:00Z")
        )

        val firstRefresh = AnniversaryCatchUpCalculator.dueOccurrences(
            anniversary,
            Instant.parse("2026-07-21T00:00:00Z")
        )
        val laterRefresh = AnniversaryCatchUpCalculator.dueOccurrences(
            anniversary,
            Instant.parse("2026-08-01T00:00:00Z")
        )

        assertEquals(1, firstRefresh.size)
        assertEquals(firstRefresh, laterRefresh)
    }

    @Test
    fun 重复倒数日只追补最近一次已经到期的周期() {
        val anniversary = anniversary(
            id = "monthly-catch-up",
            targetSolarDate = LocalDate.of(2026, 1, 1),
            repeatRule = AnniversaryRepeatRule.MONTHLY,
            createdAt = Instant.parse("2026-01-15T00:00:00Z")
        )

        val due = AnniversaryCatchUpCalculator.dueOccurrences(
            anniversary,
            Instant.parse("2026-04-30T23:00:00Z")
        )

        assertEquals(
            listOf(LocalDate.of(2026, 4, 1)),
            due.map(AnniversaryOccurrence::date)
        )
    }

    @Test
    fun 正数纪念日不会进入到期补偿() {
        val anniversary = anniversary(
            id = "count-up",
            targetSolarDate = LocalDate.of(2020, 1, 1),
            repeatRule = AnniversaryRepeatRule.NONE,
            type = AnniversaryType.COUNT_UP
        )

        assertTrue(
            AnniversaryCatchUpCalculator.dueOccurrences(
                anniversary,
                Instant.parse("2026-07-22T00:00:00Z")
            ).isEmpty()
        )
    }

    @Test
    fun 公历月底不是农历月底时不会误判() {
        val date = generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }
            .first { it.dayOfMonth == it.lengthOfMonth() && !ChineseLunarCalendar.isMonthEnd(it) }

        assertFalse(ChineseLunarCalendar.isMonthEnd(date))
    }

    private fun anniversary(
        id: String,
        targetSolarDate: LocalDate,
        isLunar: Boolean = false,
        repeatRule: AnniversaryRepeatRule,
        sourceYear: Int = targetSolarDate.year,
        sourceMonth: Int = targetSolarDate.monthValue,
        sourceDay: Int = targetSolarDate.dayOfMonth,
        isLunarLeapMonth: Boolean = false,
        createdAt: Instant = targetSolarDate.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        type: AnniversaryType = AnniversaryType.COUNTDOWN
    ): AnniversaryItemEntity {
        val target = LocalDateTime.of(targetSolarDate, LocalTime.of(9, 0))
            .toInstant(ZoneOffset.UTC)
        return AnniversaryItemEntity(
            id = id,
            title = id,
            targetDateEpochMillis = target.toEpochMilli(),
            isLunar = isLunar,
            type = type.storedValue,
            repeatRule = repeatRule.storedValue,
            isPinnedTop = false,
            showOnWidget = false,
            createdAtEpochMillis = createdAt.toEpochMilli(),
            sourceYear = sourceYear,
            sourceMonth = sourceMonth,
            sourceDay = sourceDay,
            sourceHour = 9,
            sourceMinute = 0,
            sourceSecond = 0,
            isLunarLeapMonth = isLunarLeapMonth,
            zoneId = ZoneOffset.UTC.id
        )
    }

    private fun solarDateOf(
        lunarYear: Int,
        lunarMonth: Int,
        lunarDay: Int,
        isLeapMonth: Boolean
    ): LocalDate {
        val start = LocalDate.of(lunarYear, 1, 1)
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { it.isBefore(start.plusYears(2)) }
            .first { date ->
                ChineseLunarCalendar.solarToLunar(date) == ChineseLunarDate(
                    lunarYear,
                    lunarMonth,
                    lunarDay,
                    isLeapMonth
                )
            }
    }
}
