package com.example.controlfree.ui.todo.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.ceil

enum class HabitFrequencyType(val storedValue: String) {
    DAILY("DAILY"),
    SPECIFIC_WEEKDAYS("SPECIFIC_WEEKDAYS"),
    WEEKLY_TARGET("WEEKLY_TARGET"),
    INTERVAL("INTERVAL");

    companion object {
        fun fromStoredValue(value: String): HabitFrequencyType =
            entries.firstOrNull { it.storedValue == value } ?: DAILY
    }
}

enum class HabitStreakUnit(val label: String) {
    DAY("天"),
    WEEK("周")
}

data class HabitSchedule(
    val frequency: HabitFrequencyType,
    val targetCountPerDay: Int = 1,
    val weekdaysMask: Int = ALL_WEEKDAYS_MASK,
    val weeklyTargetDays: Int = 3,
    val intervalDays: Int = 1,
    val startDate: LocalDate
) {
    init {
        require(targetCountPerDay in 1..99) { "每日目标次数必须在 1 到 99 之间" }
        require(weekdaysMask in 0..ALL_WEEKDAYS_MASK) { "星期掩码无效" }
        require(weeklyTargetDays in 1..7) { "每周目标天数必须在 1 到 7 之间" }
        require(intervalDays in 1..365) { "间隔天数必须在 1 到 365 之间" }
        if (frequency == HabitFrequencyType.SPECIFIC_WEEKDAYS) {
            require(weekdaysMask != 0) { "指定星期模式至少选择一天" }
        }
    }
}

data class HabitDayProgress(
    val date: LocalDate,
    val completionCount: Int,
    val note: String? = null,
    val isBackfill: Boolean = false
)

data class HabitStreak(
    val current: Int,
    val best: Int,
    val unit: HabitStreakUnit
)

data class HabitMonthStatistics(
    val completedUnits: Int,
    val requiredUnits: Int,
    val completedDays: Int,
    val scheduledDays: Int
) {
    val completionRate: Float
        get() = if (requiredUnits == 0) 0f else {
            (completedUnits.toFloat() / requiredUnits.toFloat()).coerceIn(0f, 1f)
        }
}

data class HabitBadge(
    val threshold: Int,
    val title: String,
    val description: String
)

val STANDARD_HABIT_BADGES: List<HabitBadge> = listOf(
    HabitBadge(7, "初见坚持", "连续达标 7 个计划周期"),
    HabitBadge(21, "习惯成形", "连续达标 21 个计划周期"),
    HabitBadge(100, "百日恒心", "连续达标 100 个计划周期")
)

object HabitProgressCalculator {
    fun isScheduled(schedule: HabitSchedule, date: LocalDate): Boolean {
        if (date.isBefore(schedule.startDate)) return false
        return when (schedule.frequency) {
            HabitFrequencyType.DAILY,
            HabitFrequencyType.WEEKLY_TARGET -> true

            HabitFrequencyType.SPECIFIC_WEEKDAYS ->
                schedule.weekdaysMask and date.dayOfWeek.bitMask != 0

            HabitFrequencyType.INTERVAL ->
                ChronoUnit.DAYS.between(schedule.startDate, date) % schedule.intervalDays == 0L
        }
    }

    fun heatLevel(completionCount: Int, targetCount: Int): Int {
        if (completionCount <= 0 || targetCount <= 0) return 0
        return ceil(completionCount.coerceAtMost(targetCount) * 4.0 / targetCount)
            .toInt()
            .coerceIn(1, 4)
    }

    fun calculateStreak(
        schedule: HabitSchedule,
        progress: Collection<HabitDayProgress>,
        today: LocalDate
    ): HabitStreak = when (schedule.frequency) {
        HabitFrequencyType.WEEKLY_TARGET -> calculateWeeklyStreak(schedule, progress, today)
        else -> calculateDayStreak(schedule, progress, today)
    }

    fun calculateMonthStatistics(
        schedule: HabitSchedule,
        progress: Collection<HabitDayProgress>,
        month: YearMonth,
        today: LocalDate
    ): HabitMonthStatistics {
        val monthEnd = minOf(month.atEndOfMonth(), today)
        val monthStart = maxOf(month.atDay(1), schedule.startDate)
        if (monthEnd.isBefore(monthStart)) {
            return HabitMonthStatistics(0, 0, 0, 0)
        }
        val progressByDate = progress.associateBy(HabitDayProgress::date)
        if (schedule.frequency == HabitFrequencyType.WEEKLY_TARGET) {
            return calculateWeeklyMonthStatistics(
                schedule = schedule,
                progressByDate = progressByDate,
                start = monthStart,
                endInclusive = monthEnd
            )
        }

        var requiredUnits = 0
        var completedUnits = 0
        var scheduledDays = 0
        var completedDays = 0
        for (date in datesBetween(monthStart, monthEnd)) {
            if (!isScheduled(schedule, date)) continue
            scheduledDays++
            requiredUnits += schedule.targetCountPerDay
            val count = progressByDate[date]?.completionCount.orZero()
                .coerceIn(0, schedule.targetCountPerDay)
            completedUnits += count
            if (count >= schedule.targetCountPerDay) completedDays++
        }
        return HabitMonthStatistics(completedUnits, requiredUnits, completedDays, scheduledDays)
    }

    fun unlockedBadges(bestStreak: Int): Set<Int> = STANDARD_HABIT_BADGES
        .asSequence()
        .filter { bestStreak >= it.threshold }
        .map(HabitBadge::threshold)
        .toSet()

    private fun calculateDayStreak(
        schedule: HabitSchedule,
        progress: Collection<HabitDayProgress>,
        today: LocalDate
    ): HabitStreak {
        if (today.isBefore(schedule.startDate)) return HabitStreak(0, 0, HabitStreakUnit.DAY)
        val completedDates = progress
            .asSequence()
            .filter { it.completionCount >= schedule.targetCountPerDay }
            .map(HabitDayProgress::date)
            .toSet()
        val scheduledDates = datesBetween(schedule.startDate, today)
            .filter { isScheduled(schedule, it) }
        var running = 0
        var best = 0
        for (date in scheduledDates) {
            if (date in completedDates) {
                running++
                best = maxOf(best, running)
            } else {
                running = 0
            }
        }

        var cursor = today
        if (isScheduled(schedule, cursor) && cursor !in completedDates) {
            cursor = previousScheduledDate(schedule, cursor.minusDays(1)) ?: return HabitStreak(0, best, HabitStreakUnit.DAY)
        } else if (!isScheduled(schedule, cursor)) {
            cursor = previousScheduledDate(schedule, cursor) ?: return HabitStreak(0, best, HabitStreakUnit.DAY)
        }
        var current = 0
        while (!cursor.isBefore(schedule.startDate) && cursor in completedDates) {
            current++
            cursor = previousScheduledDate(schedule, cursor.minusDays(1)) ?: break
        }
        return HabitStreak(current, best, HabitStreakUnit.DAY)
    }

    private fun calculateWeeklyStreak(
        schedule: HabitSchedule,
        progress: Collection<HabitDayProgress>,
        today: LocalDate
    ): HabitStreak {
        if (today.isBefore(schedule.startDate)) return HabitStreak(0, 0, HabitStreakUnit.WEEK)
        val weekFields = WeekFields.ISO
        val completedByWeek = progress
            .asSequence()
            .filter { !it.date.isBefore(schedule.startDate) }
            .filter { it.completionCount >= schedule.targetCountPerDay }
            .groupBy { it.date.with(weekFields.dayOfWeek(), 1) }
            .mapValues { (_, records) -> records.map(HabitDayProgress::date).distinct().size }
        val firstWeek = schedule.startDate.with(weekFields.dayOfWeek(), 1)
        val currentWeek = today.with(weekFields.dayOfWeek(), 1)
        val weeks = generateSequence(firstWeek) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(currentWeek) }
            .toList()
        var running = 0
        var best = 0
        for (week in weeks) {
            val target = weeklyTargetForSlice(schedule, week, week.plusDays(6))
            if (target > 0 && completedByWeek[week].orZero() >= target) {
                running++
                best = maxOf(best, running)
            } else {
                running = 0
            }
        }
        var cursor = currentWeek
        val currentTarget = weeklyTargetForSlice(schedule, cursor, cursor.plusDays(6))
        if (completedByWeek[cursor].orZero() < currentTarget) cursor = cursor.minusWeeks(1)
        var current = 0
        while (!cursor.isBefore(firstWeek)) {
            val fullTarget = weeklyTargetForSlice(schedule, cursor, cursor.plusDays(6))
            if (fullTarget == 0 || completedByWeek[cursor].orZero() < fullTarget) break
            current++
            cursor = cursor.minusWeeks(1)
        }
        return HabitStreak(current, best, HabitStreakUnit.WEEK)
    }

    private fun calculateWeeklyMonthStatistics(
        schedule: HabitSchedule,
        progressByDate: Map<LocalDate, HabitDayProgress>,
        start: LocalDate,
        endInclusive: LocalDate
    ): HabitMonthStatistics {
        val weekFields = WeekFields.ISO
        val datesByWeek = datesBetween(start, endInclusive).groupBy {
            it.with(weekFields.dayOfWeek(), 1)
        }
        var completedDays = 0
        var scheduledDays = 0
        for ((_, dates) in datesByWeek) {
            val targetDays = minOf(schedule.weeklyTargetDays, dates.size)
            val achievedDays = dates.count { date ->
                progressByDate[date]?.completionCount.orZero() >= schedule.targetCountPerDay
            }.coerceAtMost(targetDays)
            scheduledDays += targetDays
            completedDays += achievedDays
        }
        return HabitMonthStatistics(
            completedUnits = completedDays * schedule.targetCountPerDay,
            requiredUnits = scheduledDays * schedule.targetCountPerDay,
            completedDays = completedDays,
            scheduledDays = scheduledDays
        )
    }

    private fun weeklyTargetForSlice(
        schedule: HabitSchedule,
        weekStart: LocalDate,
        endInclusive: LocalDate
    ): Int {
        val effectiveStart = maxOf(weekStart, schedule.startDate)
        if (endInclusive.isBefore(effectiveStart)) return 0
        val availableDays = ChronoUnit.DAYS.between(effectiveStart, endInclusive).toInt() + 1
        return minOf(schedule.weeklyTargetDays, availableDays)
    }

    private fun previousScheduledDate(schedule: HabitSchedule, from: LocalDate): LocalDate? {
        var cursor = from
        while (!cursor.isBefore(schedule.startDate)) {
            if (isScheduled(schedule, cursor)) return cursor
            cursor = cursor.minusDays(1)
        }
        return null
    }

    private fun datesBetween(start: LocalDate, endInclusive: LocalDate): List<LocalDate> {
        if (endInclusive.isBefore(start)) return emptyList()
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endInclusive) }
            .toList()
    }
}

data class HabitTemplate(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val frequency: HabitFrequencyType,
    val targetCountPerDay: Int = 1,
    val weeklyTargetDays: Int = 3,
    val weekdaysMask: Int = ALL_WEEKDAYS_MASK
)

object HabitTemplateCatalog {
    val templates: List<HabitTemplate> = listOf(
        HabitTemplate("water", "喝水", "water", "#2196F3", HabitFrequencyType.DAILY, 8),
        HabitTemplate("reading", "阅读", "book", "#7E57C2", HabitFrequencyType.DAILY),
        HabitTemplate("exercise", "运动", "run", "#EF6C00", HabitFrequencyType.WEEKLY_TARGET, weeklyTargetDays = 3),
        HabitTemplate("words", "背单词", "school", "#00897B", HabitFrequencyType.SPECIFIC_WEEKDAYS, weekdaysMask = WORKDAYS_MASK),
        HabitTemplate("wake", "早起", "alarm", "#F9A825", HabitFrequencyType.DAILY),
        HabitTemplate("meditation", "冥想", "meditation", "#5E35B1", HabitFrequencyType.DAILY),
        HabitTemplate("sleep", "早睡", "bedtime", "#3949AB", HabitFrequencyType.DAILY)
    )
}

const val ALL_WEEKDAYS_MASK: Int = 0b1111111
const val WORKDAYS_MASK: Int = 0b0011111

fun DayOfWeek.toMask(): Int = bitMask

fun weekdayMaskOf(vararg days: DayOfWeek): Int = days.fold(0) { mask, day -> mask or day.bitMask }

private val DayOfWeek.bitMask: Int
    get() = 1 shl (value - 1)

private fun Int?.orZero(): Int = this ?: 0
