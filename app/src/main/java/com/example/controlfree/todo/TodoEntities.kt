package com.example.controlfree.todo

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "todo_items",
    indices = [
        Index(value = ["due_date_epoch_millis"]),
        Index(value = ["scheduled_start_epoch_millis"]),
        Index(value = ["is_completed"]),
        Index(value = ["priority"]),
        Index(value = ["category"]),
        Index(value = ["associated_focus_plan_id"]),
        Index(value = ["recurrence_series_id", "recurrence_sequence"], unique = true)
    ]
)
data class TodoItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "due_date_epoch_millis") val dueDateEpochMillis: Long?,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean,
    @ColumnInfo(name = "completed_at_epoch_millis") val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "repeat_rule") val repeatRule: String?,
    @ColumnInfo(name = "associated_focus_plan_id") val associatedFocusPlanId: String?,
    @ColumnInfo(name = "supervision_lock_enabled") val supervisionLockEnabled: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "scheduled_start_epoch_millis") val scheduledStartEpochMillis: Long? = null,
    @ColumnInfo(name = "scheduled_end_epoch_millis") val scheduledEndEpochMillis: Long? = null,
    @ColumnInfo(name = "estimated_focus_minutes") val estimatedFocusMinutes: Int? = null,
    @ColumnInfo(name = "is_important", defaultValue = "0") val isImportant: Boolean = false,
    @ColumnInfo(name = "urgency_mode", defaultValue = "'AUTO'") val urgencyMode: String = TodoUrgencyMode.AUTO.storedValue,
    @ColumnInfo(name = "recurrence_type", defaultValue = "'NONE'") val recurrenceType: String = TodoRecurrenceType.NONE.storedValue,
    @ColumnInfo(name = "recurrence_interval", defaultValue = "1") val recurrenceInterval: Int = 1,
    @ColumnInfo(name = "recurrence_days_mask", defaultValue = "0") val recurrenceDaysMask: Int = 0,
    @ColumnInfo(name = "recurrence_day_of_month") val recurrenceDayOfMonth: Int? = null,
    @ColumnInfo(name = "recurrence_series_id") val recurrenceSeriesId: String? = null,
    @ColumnInfo(name = "recurrence_sequence", defaultValue = "0") val recurrenceSequence: Int = 0,
    @ColumnInfo(name = "updated_at_epoch_millis", defaultValue = "0") val updatedAtEpochMillis: Long = createdAtEpochMillis,
    @ColumnInfo(name = "reminders_json") val remindersJson: String? = null
)

@Entity(
    tableName = "todo_subtasks",
    indices = [
        Index(value = ["todo_id"]),
        Index(value = ["parent_subtask_id"]),
        Index(value = ["todo_id", "sort_order"])
    ]
)
data class TodoSubtaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "todo_id") val todoId: String,
    @ColumnInfo(name = "parent_subtask_id") val parentSubtaskId: String?,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean,
    @ColumnInfo(name = "completed_at_epoch_millis") val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long = createdAtEpochMillis
)

@Entity(
    tableName = "habit_items",
    indices = [
        Index(value = ["is_archived"]),
        Index(value = ["frequency_type"])
    ]
)
data class HabitItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "icon_res") val iconRes: String,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    @ColumnInfo(name = "frequency_type") val frequencyType: String,
    @ColumnInfo(name = "target_count_per_day") val targetCountPerDay: Int,
    @ColumnInfo(name = "current_streak") val currentStreak: Int,
    @ColumnInfo(name = "best_streak") val bestStreak: Int,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "weekdays_mask", defaultValue = "127") val weekdaysMask: Int = ALL_WEEKDAYS_MASK,
    @ColumnInfo(name = "weekly_target_days", defaultValue = "3") val weeklyTargetDays: Int = 3,
    @ColumnInfo(name = "interval_days", defaultValue = "1") val intervalDays: Int = 1,
    @ColumnInfo(name = "start_date", defaultValue = "'1970-01-01'") val startDate: String = EPOCH_LOCAL_DATE,
    @ColumnInfo(name = "updated_at_epoch_millis", defaultValue = "0") val updatedAtEpochMillis: Long = createdAtEpochMillis,
    @ColumnInfo(name = "reminders_json") val remindersJson: String? = null
)

@Entity(
    tableName = "habit_records",
    indices = [
        Index(value = ["habit_id", "completed_date"], unique = true),
        Index(value = ["completed_date"])
    ]
)
data class HabitRecordEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    @ColumnInfo(name = "completed_date") val completedDate: String,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "reward_points") val rewardPoints: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "completion_count", defaultValue = "1") val completionCount: Int = 1,
    @ColumnInfo(name = "is_backfill", defaultValue = "0") val isBackfill: Boolean = false,
    @ColumnInfo(name = "updated_at_epoch_millis", defaultValue = "0") val updatedAtEpochMillis: Long = createdAtEpochMillis
)

@Entity(
    tableName = "anniversary_items",
    indices = [
        Index(value = ["target_date_epoch_millis"]),
        Index(value = ["is_pinned_top"]),
        Index(value = ["show_on_widget"]),
        Index(value = ["show_on_lock_screen"])
    ]
)
data class AnniversaryItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "target_date_epoch_millis") val targetDateEpochMillis: Long,
    @ColumnInfo(name = "is_lunar") val isLunar: Boolean,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "repeat_rule") val repeatRule: String,
    @ColumnInfo(name = "is_pinned_top") val isPinnedTop: Boolean,
    @ColumnInfo(name = "show_on_widget") val showOnWidget: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "source_year", defaultValue = "0") val sourceYear: Int = 0,
    @ColumnInfo(name = "source_month", defaultValue = "0") val sourceMonth: Int = 0,
    @ColumnInfo(name = "source_day", defaultValue = "0") val sourceDay: Int = 0,
    @ColumnInfo(name = "source_hour", defaultValue = "0") val sourceHour: Int = 0,
    @ColumnInfo(name = "source_minute", defaultValue = "0") val sourceMinute: Int = 0,
    @ColumnInfo(name = "source_second", defaultValue = "0") val sourceSecond: Int = 0,
    @ColumnInfo(name = "is_lunar_leap_month", defaultValue = "0") val isLunarLeapMonth: Boolean = false,
    @ColumnInfo(name = "zone_id", defaultValue = "'Asia/Shanghai'") val zoneId: String = DEFAULT_ZONE_ID,
    @ColumnInfo(name = "show_on_lock_screen", defaultValue = "0") val showOnLockScreen: Boolean = false,
    @ColumnInfo(name = "updated_at_epoch_millis", defaultValue = "0") val updatedAtEpochMillis: Long = createdAtEpochMillis,
    @ColumnInfo(name = "reminders_json") val remindersJson: String? = null
)

@Entity(
    tableName = "anniversary_occurrences",
    indices = [
        Index(value = ["anniversary_id", "occurrence_key"], unique = true),
        Index(value = ["occurs_at_epoch_millis"])
    ]
)
data class AnniversaryOccurrenceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "anniversary_id") val anniversaryId: String,
    @ColumnInfo(name = "occurrence_key") val occurrenceKey: String,
    @ColumnInfo(name = "occurs_at_epoch_millis") val occursAtEpochMillis: Long,
    @ColumnInfo(name = "recorded_at_epoch_millis") val recordedAtEpochMillis: Long
)

@Entity(
    tableName = "quick_notes",
    indices = [
        Index(value = ["status"]),
        Index(value = ["created_at_epoch_millis"])
    ]
)
data class QuickNoteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "media_uri") val mediaUri: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "media_mime_type") val mediaMimeType: String? = null,
    @ColumnInfo(name = "media_display_name") val mediaDisplayName: String? = null,
    @ColumnInfo(name = "media_size_bytes") val mediaSizeBytes: Long? = null,
    @ColumnInfo(name = "capture_source", defaultValue = "'TEXT'") val captureSource: String = QuickNoteCaptureSource.TEXT.storedValue,
    @ColumnInfo(name = "updated_at_epoch_millis", defaultValue = "0") val updatedAtEpochMillis: Long = createdAtEpochMillis,
    @ColumnInfo(name = "ai_advice") val aiAdvice: String? = null,
    @ColumnInfo(name = "ai_assistant_fingerprint") val aiAssistantFingerprint: String? = null
)

@Entity(
    tableName = "quick_note_conversions",
    primaryKeys = ["note_id", "target_type"],
    indices = [Index(value = ["target_id"])]
)
data class QuickNoteConversionEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long
)

@Entity(
    tableName = "achievement_unlocks",
    indices = [
        Index(value = ["achievement_type", "subject_id"]),
        Index(value = ["unlocked_at_epoch_millis"])
    ]
)
data class AchievementUnlockEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "achievement_type") val achievementType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String?,
    @ColumnInfo(name = "tier") val tier: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "unlocked_at_epoch_millis") val unlockedAtEpochMillis: Long
)

@Entity(
    tableName = "commitment_policies",
    indices = [Index(value = ["source_type", "source_id"], unique = true)]
)
data class CommitmentPolicyEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "local_deadline_minute") val localDeadlineMinute: Int?,
    @ColumnInfo(name = "grace_minutes") val graceMinutes: Int,
    @ColumnInfo(name = "max_lock_minutes") val maxLockMinutes: Int,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "commitment_blocked_apps",
    primaryKeys = ["policy_id", "package_name"],
    indices = [Index(value = ["package_name"])]
)
data class CommitmentBlockedAppEntity(
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "package_name") val packageName: String
)

@Entity(
    tableName = "commitment_occurrences",
    indices = [
        Index(value = ["policy_id", "occurrence_key"], unique = true),
        Index(value = ["deadline_epoch_millis"]),
        Index(value = ["satisfied_at_epoch_millis"])
    ]
)
data class CommitmentOccurrenceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "occurrence_key") val occurrenceKey: String,
    @ColumnInfo(name = "deadline_epoch_millis") val deadlineEpochMillis: Long,
    @ColumnInfo(name = "expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    @ColumnInfo(name = "activated_at_epoch_millis") val activatedAtEpochMillis: Long?,
    @ColumnInfo(name = "satisfied_at_epoch_millis") val satisfiedAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "productivity_reward_outbox",
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["processed_at_epoch_millis"]),
        Index(value = ["reward_type", "local_date"])
    ]
)
data class ProductivityRewardEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "reward_type") val rewardType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "balance_points") val balancePoints: Int,
    @ColumnInfo(name = "experience_points") val experiencePoints: Int,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "processed_at_epoch_millis") val processedAtEpochMillis: Long?
)

@Entity(
    tableName = "celebration_events",
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["consumed_at_epoch_millis"])
    ]
)
data class CelebrationEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "celebration_type") val celebrationType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "consumed_at_epoch_millis") val consumedAtEpochMillis: Long?
)

data class CommitmentPolicyWithApps(
    @Embedded val policy: CommitmentPolicyEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "policy_id"
    )
    val blockedApps: List<CommitmentBlockedAppEntity>
)

internal const val ALL_WEEKDAYS_MASK = 0b1111111
internal const val EPOCH_LOCAL_DATE = "1970-01-01"
internal const val DEFAULT_ZONE_ID = "Asia/Shanghai"
