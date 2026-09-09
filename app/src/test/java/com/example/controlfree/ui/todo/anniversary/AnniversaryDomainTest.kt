package com.example.controlfree.ui.todo.anniversary

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryDomainTest {
    private val fakeLunar = object : LunarCalendarConverter {
        override fun toInstant(parts: AnniversaryDateTimeParts, zoneId: ZoneId): Instant =
            LocalDateTime.of(
                parts.year,
                parts.month,
                minOf(parts.day, 30),
                parts.hour,
                parts.minute,
                parts.second
            ).atZone(zoneId).toInstant()

        override fun fromInstant(instant: Instant, zoneId: ZoneId): AnniversaryDateTimeParts {
            val value = instant.atZone(zoneId)
            return AnniversaryDateTimeParts(
                year = value.year,
                month = value.monthValue,
                day = value.dayOfMonth,
                hour = value.hour,
                minute = value.minute,
                second = value.second
            )
        }
    }
    private val resolver = AnniversaryOccurrenceResolver(fakeLunar)

    @Test
    fun 年度公历重复会把二月二十九日收敛到非闰年月末() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.of(2027, 1, 1, 0, 0).atZone(zone).toInstant()
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.SOLAR,
            dateTime = AnniversaryDateTimeParts(2024, 2, 29, 9, 30, 5),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = zone
        )

        val occurrence = resolver.resolve(spec, now)

        assertEquals(
            LocalDateTime.of(2027, 2, 28, 9, 30, 5),
            occurrence.instant.atZone(zone).toLocalDateTime()
        )
        assertTrue(occurrence.isRecurring)
        assertFalse(occurrence.isPast)
    }

    @Test
    fun 月度公历重复会把三十一日收敛到月末() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2026-04-01T00:00:00Z")
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.SOLAR,
            dateTime = AnniversaryDateTimeParts(2026, 1, 31, 8, 0, 0),
            repeatRule = AnniversaryRepeatRule.MONTHLY,
            zoneId = zone
        )

        assertEquals(
            Instant.parse("2026-04-30T08:00:00Z"),
            resolver.resolve(spec, now).instant
        )
    }

    @Test
    fun 未来公历年度重复不会提前到当前年份() {
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.SOLAR,
            dateTime = AnniversaryDateTimeParts(2030, 8, 15, 9, 30, 5),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = ZoneOffset.UTC
        )

        val occurrence = resolver.resolve(spec, Instant.parse("2026-07-01T00:00:00Z"))

        assertEquals(Instant.parse("2030-08-15T09:30:05Z"), occurrence.instant)
        assertTrue(occurrence.isRecurring)
        assertFalse(occurrence.isPast)
    }

    @Test
    fun 未来公历月度重复从原始锚点开始() {
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.SOLAR,
            dateTime = AnniversaryDateTimeParts(2030, 8, 31, 9),
            repeatRule = AnniversaryRepeatRule.MONTHLY,
            zoneId = ZoneOffset.UTC
        )

        assertEquals(
            Instant.parse("2030-08-31T09:00:00Z"),
            resolver.resolve(spec, Instant.parse("2026-07-01T00:00:00Z")).instant
        )
    }

    @Test
    fun 年度农历普通日期保持原日() {
        val zone = ZoneOffset.UTC
        val now = Instant.parse("2027-01-01T00:00:00Z")
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.LUNAR,
            dateTime = AnniversaryDateTimeParts(2026, 8, 15, 9),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = zone
        )

        assertEquals(
            Instant.parse("2027-08-15T09:00:00Z"),
            resolver.resolve(spec, now).instant
        )
    }

    @Test
    fun 未来农历年度重复不会提前到当前农历年() {
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.LUNAR,
            dateTime = AnniversaryDateTimeParts(2030, 8, 15, 9),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = ZoneOffset.UTC
        )

        assertEquals(
            Instant.parse("2030-08-15T09:00:00Z"),
            resolver.resolve(spec, Instant.parse("2026-01-01T00:00:00Z")).instant
        )
    }

    @Test
    fun 年度农历三十日在小月统一回退到二十九日() {
        val zone = ZoneOffset.UTC
        val smallMonthLunar = object : LunarCalendarConverter {
            override fun toInstant(parts: AnniversaryDateTimeParts, zoneId: ZoneId): Instant {
                require(parts.year != 2027 || parts.month != 8 || parts.day <= 29)
                return LocalDateTime.of(
                    parts.year,
                    parts.month,
                    parts.day,
                    parts.hour,
                    parts.minute,
                    parts.second
                ).atZone(zoneId).toInstant()
            }

            override fun fromInstant(instant: Instant, zoneId: ZoneId): AnniversaryDateTimeParts {
                val value = instant.atZone(zoneId)
                return AnniversaryDateTimeParts(
                    year = value.year,
                    month = value.monthValue,
                    day = value.dayOfMonth,
                    hour = value.hour,
                    minute = value.minute,
                    second = value.second
                )
            }
        }
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.LUNAR,
            dateTime = AnniversaryDateTimeParts(2026, 8, 30, 9),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = zone
        )

        assertEquals(
            Instant.parse("2027-08-29T09:00:00Z"),
            AnniversaryOccurrenceResolver(smallMonthLunar).resolve(
                spec,
                Instant.parse("2027-01-01T00:00:00Z")
            ).instant
        )
    }

    @Test
    fun 闰月年度纪念在目标年无闰月时回退同名普通月() {
        val attemptedLeapValues = mutableListOf<Boolean>()
        val calendar = object : LunarCalendarConverter {
            override fun toInstant(parts: AnniversaryDateTimeParts, zoneId: ZoneId): Instant {
                attemptedLeapValues += parts.isLeapMonth
                require(!parts.isLeapMonth) { "目标年没有该闰月" }
                return LocalDateTime.of(
                    parts.year,
                    parts.month,
                    parts.day,
                    parts.hour,
                    parts.minute,
                    parts.second
                ).atZone(zoneId).toInstant()
            }

            override fun fromInstant(instant: Instant, zoneId: ZoneId): AnniversaryDateTimeParts {
                val value = instant.atZone(zoneId)
                return AnniversaryDateTimeParts(
                    year = value.year,
                    month = value.monthValue,
                    day = value.dayOfMonth,
                    hour = value.hour,
                    minute = value.minute,
                    second = value.second
                )
            }
        }
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.LUNAR,
            dateTime = AnniversaryDateTimeParts(2026, 8, 15, 9, isLeapMonth = true),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = ZoneOffset.UTC
        )

        val occurrence = AnniversaryOccurrenceResolver(calendar).resolve(
            spec,
            Instant.parse("2027-01-01T00:00:00Z")
        )

        assertEquals(Instant.parse("2027-08-15T09:00:00Z"), occurrence.instant)
        assertEquals(listOf(true, false), attemptedLeapValues)
    }

    @Test
    fun 精确到秒的倒计时格式正确() {
        val now = Instant.parse("2026-07-22T00:00:00Z")
        val target = now.plusSeconds(2 * 86_400L + 3_661L)
        val counter = AnniversaryCounterCalculator.calculate(
            AnniversaryType.COUNTDOWN,
            target,
            Clock.fixed(now, ZoneOffset.UTC)
        )

        assertEquals("2天 01:01:01", counter.format())
        assertFalse(counter.reached)
    }

    @Test
    fun 已到期倒计时归零且标记到达() {
        val now = Instant.parse("2026-07-22T00:00:01Z")
        val counter = AnniversaryCounterCalculator.calculate(
            AnniversaryType.COUNTDOWN,
            now.minusSeconds(1),
            Clock.fixed(now, ZoneOffset.UTC)
        )

        assertEquals("00:00:00", counter.format())
        assertTrue(counter.reached)
    }

    @Test
    fun 正数日始终保留原始锚点且不生成循环() {
        val spec = AnniversarySpec(
            type = AnniversaryType.COUNT_UP,
            calendarType = AnniversaryCalendarType.SOLAR,
            dateTime = AnniversaryDateTimeParts(2030, 1, 1, 8),
            repeatRule = AnniversaryRepeatRule.NONE,
            zoneId = ZoneOffset.UTC
        )

        val occurrence = resolver.resolve(spec, Instant.parse("2026-01-01T00:00:00Z"))

        assertEquals(Instant.parse("2030-01-01T08:00:00Z"), occurrence.instant)
        assertFalse(occurrence.isPast)
        assertFalse(occurrence.isRecurring)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 正数日拒绝循环规则() {
        AnniversarySpec(
            type = AnniversaryType.COUNT_UP,
            calendarType = AnniversaryCalendarType.SOLAR,
            dateTime = AnniversaryDateTimeParts(2026, 1, 1),
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = ZoneOffset.UTC
        )
    }

    @Test
    fun 农历编辑日期拒绝不存在的三十一日() {
        val draft = AnniversaryEditorDraft(
            title = "农历纪念日",
            calendarType = AnniversaryCalendarType.LUNAR,
            year = 2026,
            month = 8,
            day = 31,
            zoneId = "Asia/Shanghai"
        )

        assertFalse(draft.isValid)
    }
}
