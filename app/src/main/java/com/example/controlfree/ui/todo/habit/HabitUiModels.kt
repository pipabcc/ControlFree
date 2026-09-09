package com.example.controlfree.ui.todo.habit

import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.toHabitReminderConfig
import com.example.controlfree.ui.todo.commitment.CommitmentEditorSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HabitEditorDraft(
    val id: String? = null,
    val name: String = "",
    val iconKey: String = "check",
    val colorHex: String = HABIT_COLORS.first(),
    val frequency: HabitFrequencyType = HabitFrequencyType.DAILY,
    val targetCountPerDay: Int = 1,
    val weekdaysMask: Int = ALL_WEEKDAYS_MASK,
    val weeklyTargetDays: Int = 3,
    val intervalDays: Int = 2,
    val startDate: LocalDate = LocalDate.now(),
    val commitmentSettings: CommitmentEditorSettings = CommitmentEditorSettings(),
    val reminderConfig: com.example.controlfree.todo.HabitReminderConfig = com.example.controlfree.todo.HabitReminderConfig()
) {
    val isValid: Boolean
        get() = name.trim().isNotEmpty() &&
            targetCountPerDay in 1..99 &&
            weeklyTargetDays in 1..7 &&
            intervalDays in 1..365 &&
            (frequency != HabitFrequencyType.SPECIFIC_WEEKDAYS || weekdaysMask != 0) &&
            (!commitmentSettings.enabled || (
                commitmentSettings.localDeadlineMinute != null &&
                    commitmentSettings.blockedPackages.isNotEmpty()
                ))

    companion object {
        fun fromTemplate(template: HabitTemplate, today: LocalDate): HabitEditorDraft =
            HabitEditorDraft(
                name = template.name,
                iconKey = template.iconKey,
                colorHex = template.colorHex,
                frequency = template.frequency,
                targetCountPerDay = template.targetCountPerDay,
                weekdaysMask = template.weekdaysMask,
                weeklyTargetDays = template.weeklyTargetDays,
                startDate = today
            )

        fun fromEntity(
            entity: HabitItemEntity,
            zoneId: ZoneId,
            commitmentSettings: CommitmentEditorSettings = CommitmentEditorSettings()
        ): HabitEditorDraft =
            HabitEditorDraft(
                id = entity.id,
                name = entity.name,
                iconKey = entity.iconRes,
                colorHex = entity.colorHex,
                frequency = HabitFrequencyType.fromStoredValue(entity.frequencyType),
                targetCountPerDay = entity.targetCountPerDay,
                weekdaysMask = entity.weekdaysMask,
                weeklyTargetDays = entity.weeklyTargetDays,
                intervalDays = entity.intervalDays,
                startDate = parseHabitDate(entity.startDate)
                    ?: Instant.ofEpochMilli(entity.createdAtEpochMillis).atZone(zoneId).toLocalDate(),
                commitmentSettings = commitmentSettings,
                reminderConfig = entity.remindersJson.toHabitReminderConfig()
            )
    }
}

data class HabitRecordEditorDraft(
    val date: LocalDate,
    val note: String = "",
    val isBackfill: Boolean
)

fun HabitItemEntity.toSchedule(zoneId: ZoneId): HabitSchedule = HabitSchedule(
    frequency = HabitFrequencyType.fromStoredValue(frequencyType),
    targetCountPerDay = targetCountPerDay.coerceAtLeast(1),
    weekdaysMask = weekdaysMask.coerceIn(0, ALL_WEEKDAYS_MASK),
    weeklyTargetDays = weeklyTargetDays.coerceIn(1, 7),
    intervalDays = intervalDays.coerceAtLeast(1),
    startDate = parseHabitDate(startDate)
        ?: Instant.ofEpochMilli(createdAtEpochMillis).atZone(zoneId).toLocalDate()
)

fun HabitRecordEntity.toDayProgress(): HabitDayProgress? {
    val date = parseHabitDate(completedDate) ?: return null
    return HabitDayProgress(date, completionCount, note, isBackfill)
}

fun parseHabitDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

val HABIT_COLORS: List<String> = listOf(
    "#087939",
    "#006D77",
    "#465CC7",
    "#7E57C2",
    "#EF6C00",
    "#B3261E",
    "#455A64",
    "#AD1457"
)
