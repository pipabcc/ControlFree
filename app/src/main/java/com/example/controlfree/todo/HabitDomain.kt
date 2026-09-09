package com.example.controlfree.todo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object HabitScheduleCalculator {
    fun isScheduled(habit: HabitItemEntity, date: LocalDate): Boolean {
        val startDate = parseDate(habit.startDate) ?: LocalDate.ofEpochDay(0)
        if (date.isBefore(startDate)) return false
        return when (HabitFrequencyType.fromStoredValue(habit.frequencyType)) {
            HabitFrequencyType.DAILY -> true
            HabitFrequencyType.SPECIFIC_WEEKDAYS ->
                habit.weekdaysMask and weekdayBit(date.dayOfWeek) != 0
            HabitFrequencyType.WEEKLY_TARGET -> true
            HabitFrequencyType.INTERVAL ->
                (date.toEpochDay() - startDate.toEpochDay()) % habit.intervalDays.coerceAtLeast(1) == 0L
        }
    }

    fun scheduledDates(
        habit: HabitItemEntity,
        startInclusive: LocalDate,
        endInclusive: LocalDate
    ): List<LocalDate> {
        if (endInclusive.isBefore(startInclusive)) return emptyList()
        return generateSequence(startInclusive) { current ->
            current.plusDays(1).takeUnless { it.isAfter(endInclusive) }
        }.filter { isScheduled(habit, it) }.toList()
    }

    fun nextScheduledDate(
        habit: HabitItemEntity,
        afterExclusive: LocalDate
    ): LocalDate? {
        val configuredStart = parseDate(habit.startDate) ?: LocalDate.ofEpochDay(0)
        val firstCandidate = maxOf(afterExclusive.plusDays(1), configuredStart)
        return generateSequence(firstCandidate) { current -> current.plusDays(1) }
            .take(MAX_NEXT_SCHEDULE_SEARCH_DAYS)
            .firstOrNull { isScheduled(habit, it) }
    }

    private fun weekdayBit(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

    private const val MAX_NEXT_SCHEDULE_SEARCH_DAYS = 367
}

object HabitProgressCalculator {
    fun calculate(
        habit: HabitItemEntity,
        records: List<HabitRecordEntity>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        today: LocalDate
    ): HabitProgressStats {
        if (rangeEnd.isBefore(rangeStart)) return HabitProgressStats(0, 0, 0, 0)
        val completedByDate = records.mapNotNull { record ->
            parseDate(record.completedDate)?.let { date -> date to record.completionCount }
        }.toMap()
        return when (HabitFrequencyType.fromStoredValue(habit.frequencyType)) {
            HabitFrequencyType.WEEKLY_TARGET -> calculateWeeklyTarget(
                habit = habit,
                completedByDate = completedByDate,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                today = today
            )
            else -> calculateScheduledDays(
                habit = habit,
                completedByDate = completedByDate,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                today = today
            )
        }
    }

    private fun calculateScheduledDays(
        habit: HabitItemEntity,
        completedByDate: Map<LocalDate, Int>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        today: LocalDate
    ): HabitProgressStats {
        val target = habit.targetCountPerDay.coerceAtLeast(1)
        val scheduledDates = HabitScheduleCalculator.scheduledDates(habit, rangeStart, rangeEnd)
        val completedDates = scheduledDates.filter { (completedByDate[it] ?: 0) >= target }.toSet()
        val best = longestConsecutiveRun(scheduledDates, completedDates)
        val currentCandidates = scheduledDates.filter { !it.isAfter(today) }.toMutableList()
        if (currentCandidates.lastOrNull() == today && today !in completedDates) {
            currentCandidates.removeAt(currentCandidates.lastIndex)
        }
        var current = 0
        for (date in currentCandidates.asReversed()) {
            if (date !in completedDates) break
            current++
        }
        return HabitProgressStats(
            currentStreak = current,
            bestStreak = best,
            scheduledUnits = scheduledDates.size,
            completedUnits = completedDates.size
        )
    }

    private fun calculateWeeklyTarget(
        habit: HabitItemEntity,
        completedByDate: Map<LocalDate, Int>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        today: LocalDate
    ): HabitProgressStats {
        val dailyTarget = habit.targetCountPerDay.coerceAtLeast(1)
        val weeklyTarget = habit.weeklyTargetDays.coerceIn(1, 7)
        val firstWeek = rangeStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastWeek = rangeEnd.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = generateSequence(firstWeek) { current ->
            current.plusWeeks(1).takeUnless { it.isAfter(lastWeek) }
        }.toList()
        val completedWeeks = weeks.filter { weekStart ->
            (0L..6L).count { offset ->
                val date = weekStart.plusDays(offset)
                date in rangeStart..rangeEnd && (completedByDate[date] ?: 0) >= dailyTarget
            } >= weeklyTarget
        }.toSet()
        val best = longestConsecutiveRun(weeks, completedWeeks)
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val currentCandidates = weeks.filter { !it.isAfter(currentWeek) }.toMutableList()
        if (currentCandidates.lastOrNull() == currentWeek && currentWeek !in completedWeeks) {
            currentCandidates.removeAt(currentCandidates.lastIndex)
        }
        var current = 0
        for (week in currentCandidates.asReversed()) {
            if (week !in completedWeeks) break
            current++
        }
        return HabitProgressStats(
            currentStreak = current,
            bestStreak = best,
            scheduledUnits = weeks.size * weeklyTarget,
            completedUnits = weeks.sumOf { weekStart ->
                (0L..6L).count { offset ->
                    val date = weekStart.plusDays(offset)
                    date in rangeStart..rangeEnd && (completedByDate[date] ?: 0) >= dailyTarget
                }.coerceAtMost(weeklyTarget)
            }
        )
    }

    private fun longestConsecutiveRun(
        orderedUnits: List<LocalDate>,
        completedUnits: Set<LocalDate>
    ): Int {
        var best = 0
        var current = 0
        orderedUnits.forEach { unit ->
            if (unit in completedUnits) {
                current++
                best = maxOf(best, current)
            } else {
                current = 0
            }
        }
        return best
    }
}

internal fun parseDate(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (_: RuntimeException) {
    null
}
