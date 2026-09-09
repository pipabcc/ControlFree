package com.example.controlfree.ui.todo.anniversary

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

enum class AnniversaryType(val storedValue: String) {
    COUNTDOWN("COUNTDOWN"),
    COUNT_UP("COUNT_UP");

    companion object {
        fun fromStoredValue(value: String): AnniversaryType =
            entries.firstOrNull { it.storedValue == value } ?: COUNTDOWN
    }
}

enum class AnniversaryRepeatRule(val storedValue: String) {
    NONE("NONE"),
    MONTHLY("MONTHLY"),
    YEARLY("YEARLY");

    companion object {
        fun fromStoredValue(value: String): AnniversaryRepeatRule =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}

enum class AnniversaryCalendarType {
    SOLAR,
    LUNAR
}

data class AnniversaryDateTimeParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0,
    val isLeapMonth: Boolean = false
) {
    init {
        require(year in 1901..2099) { "年份必须在 1901 到 2099 之间" }
        require(month in 1..12) { "月份必须在 1 到 12 之间" }
        require(day in 1..31) { "日期必须在 1 到 31 之间" }
        require(hour in 0..23) { "小时必须在 0 到 23 之间" }
        require(minute in 0..59) { "分钟必须在 0 到 59 之间" }
        require(second in 0..59) { "秒必须在 0 到 59 之间" }
    }

    val localTime: LocalTime
        get() = LocalTime.of(hour, minute, second)
}

data class AnniversarySpec(
    val type: AnniversaryType,
    val calendarType: AnniversaryCalendarType,
    val dateTime: AnniversaryDateTimeParts,
    val repeatRule: AnniversaryRepeatRule,
    val zoneId: ZoneId
) {
    init {
        require(type != AnniversaryType.COUNT_UP || repeatRule == AnniversaryRepeatRule.NONE) {
            "正数类型的时刻必须保留原始起点，不能设置循环"
        }
    }
}

data class AnniversaryOccurrence(
    val instant: Instant,
    val isPast: Boolean,
    val isRecurring: Boolean
)

interface LunarCalendarConverter {
    fun toInstant(parts: AnniversaryDateTimeParts, zoneId: ZoneId): Instant
    fun fromInstant(instant: Instant, zoneId: ZoneId): AnniversaryDateTimeParts
}

class AnniversaryOccurrenceResolver(
    private val lunarCalendar: LunarCalendarConverter
) {
    fun anchorInstant(spec: AnniversarySpec): Instant = when (spec.calendarType) {
        AnniversaryCalendarType.SOLAR -> strictSolarInstant(spec.dateTime, spec.zoneId)
        AnniversaryCalendarType.LUNAR -> lunarCalendar.toInstant(spec.dateTime, spec.zoneId)
    }

    fun resolve(spec: AnniversarySpec, now: Instant): AnniversaryOccurrence {
        if (spec.type == AnniversaryType.COUNT_UP || spec.repeatRule == AnniversaryRepeatRule.NONE) {
            val anchor = anchorInstant(spec)
            return AnniversaryOccurrence(
                instant = anchor,
                isPast = !anchor.isAfter(now),
                isRecurring = false
            )
        }
        val next = when (spec.calendarType) {
            AnniversaryCalendarType.SOLAR -> resolveNextSolar(spec, now)
            AnniversaryCalendarType.LUNAR -> resolveNextLunar(spec, now)
        }
        return AnniversaryOccurrence(next, isPast = false, isRecurring = true)
    }

    private fun resolveNextSolar(spec: AnniversarySpec, now: Instant): Instant {
        val nowLocal = now.atZone(spec.zoneId)
        // 重复纪念日不能早于用户设置的原始目标；否则一个尚未开始的
        // 2030 年度倒数会被错误解析成当前年份的 occurrence。
        val anchor = anchorInstant(spec)
        return when (spec.repeatRule) {
            AnniversaryRepeatRule.NONE -> anchorInstant(spec)
            AnniversaryRepeatRule.MONTHLY -> {
                var month = maxOf(
                    YearMonth.from(nowLocal),
                    YearMonth.from(anchor.atZone(spec.zoneId))
                )
                repeat(2) {
                    val candidate = solarCandidate(spec.dateTime, month, spec.zoneId)
                    if (!candidate.isBefore(now) && !candidate.isBefore(anchor)) return candidate
                    month = month.plusMonths(1)
                }
                error("无法解析下一个公历月度时刻")
            }

            AnniversaryRepeatRule.YEARLY -> {
                var year = maxOf(nowLocal.year, anchor.atZone(spec.zoneId).year)
                repeat(2) {
                    val month = YearMonth.of(year, spec.dateTime.month)
                    val candidate = solarCandidate(spec.dateTime, month, spec.zoneId)
                    if (!candidate.isBefore(now) && !candidate.isBefore(anchor)) return candidate
                    year++
                }
                error("无法解析下一个公历年度时刻")
            }
        }
    }

    private fun resolveNextLunar(spec: AnniversarySpec, now: Instant): Instant =
        when (spec.repeatRule) {
            AnniversaryRepeatRule.NONE -> anchorInstant(spec)
            AnniversaryRepeatRule.YEARLY -> resolveNextLunarYearly(spec, now)
            AnniversaryRepeatRule.MONTHLY -> resolveNextLunarMonthly(spec, now)
        }

    private fun resolveNextLunarYearly(spec: AnniversarySpec, now: Instant): Instant {
        val currentLunarYear = lunarCalendar.fromInstant(now, spec.zoneId).year
        val anchorLunarYear = spec.dateTime.year
        for (year in maxOf(currentLunarYear, anchorLunarYear)..2099) {
            val requested = spec.dateTime.copy(year = year)
            lunarCandidateWithFallback(requested, spec.zoneId)?.let { candidate ->
                val isOnOrAfterAnchor = year > anchorLunarYear ||
                    lunarCandidateWithFallback(spec.dateTime, spec.zoneId)
                        ?.let { anchor -> !candidate.isBefore(anchor) } == true
                if (!candidate.isBefore(now) && isOnOrAfterAnchor) return candidate
            }
        }
        error("农历年度时刻超出支持范围")
    }

    private fun resolveNextLunarMonthly(spec: AnniversarySpec, now: Instant): Instant {
        val anchor = anchorInstant(spec)
        val startDate = maxOf(
            now.atZone(spec.zoneId).toLocalDate(),
            anchor.atZone(spec.zoneId).toLocalDate()
        )
        for (offset in 0L..420L) {
            val solarDate = startDate.plusDays(offset)
            val candidateLocal = LocalDateTime.of(solarDate, spec.dateTime.localTime)
            val candidate = candidateLocal.atZone(spec.zoneId).toInstant()
            if (candidate.isBefore(now) || candidate.isBefore(anchor)) continue
            val lunar = lunarCalendar.fromInstant(candidate, spec.zoneId)
            val isRequestedDay = lunar.day == spec.dateTime.day
            val isClampedMonthEnd = spec.dateTime.day == 30 &&
                lunar.day == 29 &&
                isLastLunarDay(candidate, spec.zoneId, lunar)
            if (isRequestedDay || isClampedMonthEnd) return candidate
        }
        error("无法在支持范围内解析下一个农历月度时刻")
    }

    private fun lunarCandidateWithFallback(
        requested: AnniversaryDateTimeParts,
        zoneId: ZoneId
    ): Instant? {
        val leapVariants = if (requested.isLeapMonth) listOf(true, false) else listOf(false)
        for (isLeap in leapVariants) {
            val candidateDays = if (requested.day == 30) listOf(30, 29) else listOf(requested.day)
            for (day in candidateDays) {
                val candidateParts = requested.copy(day = day, isLeapMonth = isLeap)
                val candidate = runCatching { lunarCalendar.toInstant(candidateParts, zoneId) }.getOrNull()
                if (candidate != null) return candidate
            }
        }
        return null
    }

    private fun isLastLunarDay(
        candidate: Instant,
        zoneId: ZoneId,
        lunar: AnniversaryDateTimeParts
    ): Boolean {
        val tomorrow = candidate.atZone(zoneId).plusDays(1).toInstant()
        val tomorrowLunar = lunarCalendar.fromInstant(tomorrow, zoneId)
        return tomorrowLunar.day == 1 && (
            tomorrowLunar.month != lunar.month ||
                tomorrowLunar.isLeapMonth != lunar.isLeapMonth ||
                tomorrowLunar.year != lunar.year
            )
    }

    private fun strictSolarInstant(parts: AnniversaryDateTimeParts, zoneId: ZoneId): Instant =
        LocalDateTime.of(
            parts.year,
            parts.month,
            parts.day,
            parts.hour,
            parts.minute,
            parts.second
        ).atZone(zoneId).toInstant()

    private fun solarCandidate(
        parts: AnniversaryDateTimeParts,
        month: YearMonth,
        zoneId: ZoneId
    ): Instant {
        val day = minOf(parts.day, month.lengthOfMonth())
        return ZonedDateTime.of(month.atDay(day), parts.localTime, zoneId).toInstant()
    }
}

data class AnniversaryCounter(
    val days: Long,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val reached: Boolean
) {
    fun format(): String = when {
        days > 0 -> "%d天 %02d:%02d:%02d".format(days, hours, minutes, seconds)
        else -> "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}

object AnniversaryCounterCalculator {
    fun calculate(
        type: AnniversaryType,
        occurrence: Instant,
        clock: Clock
    ): AnniversaryCounter {
        val now = clock.instant()
        val raw = when (type) {
            AnniversaryType.COUNTDOWN -> Duration.between(now, occurrence)
            AnniversaryType.COUNT_UP -> Duration.between(occurrence, now)
        }
        val reached = when (type) {
            AnniversaryType.COUNTDOWN -> !occurrence.isAfter(now)
            AnniversaryType.COUNT_UP -> !occurrence.isAfter(now)
        }
        val totalSeconds = raw.seconds.coerceAtLeast(0L)
        val days = totalSeconds / SECONDS_PER_DAY
        val hours = ((totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR).toInt()
        val minutes = ((totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
        val seconds = (totalSeconds % SECONDS_PER_MINUTE).toInt()
        return AnniversaryCounter(days, hours, minutes, seconds, reached)
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
    private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
}
