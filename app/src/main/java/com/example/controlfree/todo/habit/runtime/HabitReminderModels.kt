package com.example.controlfree.todo.habit.runtime

import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitFrequencyType
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.HabitScheduleCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class HabitReminderSettings(
    val enabled: Boolean = true,
    val reminderMinute: Int = DEFAULT_REMINDER_MINUTE
) {
    fun normalized(): HabitReminderSettings = copy(
        reminderMinute = reminderMinute.coerceIn(0, MINUTES_PER_DAY - 1)
    )

    companion object {
        const val DEFAULT_REMINDER_MINUTE = 20 * 60
        const val MINUTES_PER_DAY = 24 * 60
    }
}

data class PendingHabitReminder(
    val habit: HabitItemEntity,
    val completionCount: Int
)

object HabitReminderPlanner {
    fun nextTrigger(
        now: ZonedDateTime,
        reminderMinute: Int
    ): Instant {
        val normalizedMinute = reminderMinute.coerceIn(
            0,
            HabitReminderSettings.MINUTES_PER_DAY - 1
        )
        val localTime = LocalTime.of(normalizedMinute / 60, normalizedMinute % 60)
        val todayTrigger = now.toLocalDate().atTime(localTime).atZone(now.zone)
        return if (todayTrigger.toInstant().isAfter(now.toInstant())) {
            todayTrigger.toInstant()
        } else {
            now.toLocalDate().plusDays(1).atTime(localTime).atZone(now.zone).toInstant()
        }
    }

    fun pendingHabits(
        habits: Collection<HabitItemEntity>,
        records: Collection<HabitRecordEntity>,
        date: LocalDate
    ): List<PendingHabitReminder> {
        val completionByHabit = records.asSequence()
            .filter { record -> record.completedDate == date.toString() }
            .groupBy(HabitRecordEntity::habitId)
            .mapValues { (_, sameHabitRecords) ->
                sameHabitRecords.maxOfOrNull(HabitRecordEntity::completionCount) ?: 0
            }

        return habits.asSequence()
            .filterNot(HabitItemEntity::isArchived)
            .filter { habit -> HabitScheduleCalculator.isScheduled(habit, date) }
            .filterNot { habit -> weeklyTargetReached(habit, records, date) }
            .map { habit ->
                PendingHabitReminder(
                    habit = habit,
                    completionCount = completionByHabit[habit.id] ?: 0
                )
            }
            .filter { pending ->
                pending.completionCount < pending.habit.targetCountPerDay.coerceAtLeast(1)
            }
            .sortedWith(
                compareBy<PendingHabitReminder> { it.habit.createdAtEpochMillis }
                    .thenBy { it.habit.name }
            )
            .toList()
    }

    private fun weeklyTargetReached(
        habit: HabitItemEntity,
        records: Collection<HabitRecordEntity>,
        date: LocalDate
    ): Boolean {
        if (HabitFrequencyType.fromStoredValue(habit.frequencyType) != HabitFrequencyType.WEEKLY_TARGET) {
            return false
        }
        val weekStart = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val habitStart = runCatching { LocalDate.parse(habit.startDate) }.getOrDefault(weekStart)
        val effectiveStart = maxOf(weekStart, habitStart)
        if (effectiveStart.isAfter(weekEnd)) return false
        val requiredDays = minOf(
            habit.weeklyTargetDays.coerceIn(1, 7),
            ChronoUnit.DAYS.between(effectiveStart, weekEnd).toInt() + 1
        )
        val completedDays = records.asSequence()
            .filter { record -> record.habitId == habit.id }
            .mapNotNull { record ->
                val recordDate = runCatching { LocalDate.parse(record.completedDate) }.getOrNull()
                    ?: return@mapNotNull null
                recordDate to record
            }
            .filter { (recordDate, record) ->
                recordDate in weekStart..date &&
                    record.completionCount >= habit.targetCountPerDay.coerceAtLeast(1)
            }
            .map { (recordDate, _) -> recordDate }
            .distinct()
            .count()
        return completedDays >= requiredDays
    }
}

fun HabitReminderSettings.formattedTime(): String =
    String.format(Locale.ROOT, "%02d:%02d", reminderMinute / 60, reminderMinute % 60)
