package com.example.controlfree.todo

import java.time.LocalDate

enum class TodoUrgencyMode(val storedValue: String) {
    AUTO("AUTO"),
    URGENT("URGENT"),
    NOT_URGENT("NOT_URGENT");

    companion object {
        fun fromStoredValue(value: String?): TodoUrgencyMode =
            entries.firstOrNull { it.storedValue == value } ?: AUTO
    }
}

enum class TodoRecurrenceType(val storedValue: String) {
    NONE("NONE"),
    DAILY("DAILY"),
    WEEKDAYS("WEEKDAYS"),
    WEEKLY_DAYS("WEEKLY_DAYS"),
    MONTHLY_DAY("MONTHLY_DAY"),
    COMPLETION_INTERVAL("COMPLETION_INTERVAL");

    companion object {
        fun fromStoredValue(value: String?): TodoRecurrenceType =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}

enum class HabitFrequencyType(val storedValue: String) {
    DAILY("DAILY"),
    SPECIFIC_WEEKDAYS("SPECIFIC_WEEKDAYS"),
    WEEKLY_TARGET("WEEKLY_TARGET"),
    INTERVAL("INTERVAL");

    companion object {
        fun fromStoredValue(value: String?): HabitFrequencyType = when (value) {
            // v5 的草稿实现曾写入该值，升级后仍按特定星期解释。
            "WEEKLY_DAYS" -> SPECIFIC_WEEKDAYS
            else -> entries.firstOrNull { it.storedValue == value } ?: DAILY
        }
    }
}

enum class AnniversaryType(val storedValue: String) {
    COUNTDOWN("COUNTDOWN"),
    COUNT_UP("COUNT_UP");

    companion object {
        fun fromStoredValue(value: String?): AnniversaryType =
            entries.firstOrNull { it.storedValue == value } ?: COUNTDOWN
    }
}

enum class AnniversaryRepeatRule(val storedValue: String) {
    NONE("NONE"),
    MONTHLY("MONTHLY"),
    YEARLY("YEARLY");

    companion object {
        fun fromStoredValue(value: String?): AnniversaryRepeatRule =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}

enum class QuickNoteStatus(val storedValue: String) {
    RAW("RAW"),
    CONVERTED_TO_TODO("CONVERTED_TO_TODO"),
    CONVERTED_TO_HABIT("CONVERTED_TO_HABIT"),
    CONVERTED_TO_FOCUS("CONVERTED_TO_FOCUS"),
    ARCHIVED("ARCHIVED")
}

enum class QuickNoteCaptureSource(val storedValue: String) {
    TEXT("TEXT")
}

enum class QuickNoteConversionTarget(val storedValue: String) {
    TODO("TODO"),
    HABIT("HABIT"),
    FOCUS("FOCUS"),
    LEDGER("LEDGER")
}

enum class AchievementType(val storedValue: String) {
    HABIT_STREAK("HABIT_STREAK"),
    ANNIVERSARY_REACHED("ANNIVERSARY_REACHED")
}

enum class CommitmentSourceType(val storedValue: String) {
    TODO("TODO"),
    HABIT("HABIT")
}

enum class ProductivityRewardType(val storedValue: String) {
    TODO_COMPLETED("TODO_COMPLETED"),
    HABIT_TARGET_REACHED("HABIT_TARGET_REACHED"),
    QUICK_NOTE_CAPTURED("QUICK_NOTE_CAPTURED")
}

enum class CelebrationType(val storedValue: String) {
    HABIT_STREAK_BADGE("HABIT_STREAK_BADGE"),
    ANNIVERSARY_REACHED("ANNIVERSARY_REACHED")
}

enum class TodoTimeGroup {
    OVERDUE,
    TODAY,
    TOMORROW,
    THIS_WEEK,
    FUTURE,
    NO_DATE
}

enum class TodoMatrixQuadrant {
    IMPORTANT_URGENT,
    IMPORTANT_NOT_URGENT,
    NOT_IMPORTANT_URGENT,
    NOT_IMPORTANT_NOT_URGENT
}

data class TodoSubtaskNode(
    val subtask: TodoSubtaskEntity,
    val children: List<TodoSubtaskNode>
)

data class HabitProgressStats(
    val currentStreak: Int,
    val bestStreak: Int,
    val scheduledUnits: Int,
    val completedUnits: Int
) {
    val completionRate: Double
        get() = if (scheduledUnits == 0) 0.0 else completedUnits.toDouble() / scheduledUnits
}

data class HabitCheckInResult(
    val record: HabitRecordEntity,
    val targetReachedNow: Boolean,
    val targetReached: Boolean,
    val unlockedBadgeTiers: Set<Int>
)

data class TodoCompletionResult(
    val todo: TodoItemEntity,
    val nextRecurringTodo: TodoItemEntity?
)

data class AnniversaryOccurrence(
    val key: String,
    val date: LocalDate,
    val occursAtEpochMillis: Long
)

data class CommitmentRuntimeSnapshot(
    val policies: List<CommitmentPolicyWithApps>,
    val openOccurrences: List<CommitmentOccurrenceEntity>
)

data class ActiveCommitmentBlock(
    val policyId: String,
    val occurrenceId: String,
    val sourceType: CommitmentSourceType,
    val sourceId: String,
    val packageName: String,
    val deadlineEpochMillis: Long,
    val expiresAtEpochMillis: Long
)

object ProductivityRewardPolicy {
    const val TODO_COMPLETION_POINTS = 1
    const val HABIT_TARGET_POINTS = 2
    const val QUICK_NOTE_POINTS = 1
    const val MAX_REWARDED_QUICK_NOTES_PER_DAY = 5
    val HABIT_STREAK_BADGE_TIERS: Set<Int> = setOf(7, 21, 100)
}
