package com.example.controlfree.supervision.persistence

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "supervision_plans",
    indices = [
        Index(value = ["enabled", "plan_type"]),
        Index(value = ["zone_mode"]),
        Index(value = ["one_time_start_epoch_millis"])
    ]
)
data class SupervisionPlanEntity(
    @PrimaryKey @ColumnInfo(name = "plan_id") val planId: String,
    val name: String,
    @ColumnInfo(name = "plan_type") val planType: String,
    val enabled: Boolean,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "zone_mode") val zoneMode: String,
    @ColumnInfo(name = "active_days_mask") val activeDaysMask: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    /** 非空时表示一次性专注锁的绝对开始时间。 */
    @ColumnInfo(name = "one_time_start_epoch_millis") val oneTimeStartEpochMillis: Long? = null,
    /** 非空时表示一次性专注锁的绝对结束时间（半开区间）。 */
    @ColumnInfo(name = "one_time_end_epoch_millis") val oneTimeEndEpochMillis: Long? = null
)

@Entity(
    tableName = "supervision_time_ranges",
    primaryKeys = ["plan_id", "start_minute", "end_minute_exclusive"],
    foreignKeys = [ForeignKey(
        entity = SupervisionPlanEntity::class,
        parentColumns = ["plan_id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["plan_id"])]
)
data class SupervisionTimeRangeEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "end_minute_exclusive") val endMinuteExclusive: Int
)

@Entity(
    tableName = "global_supervision_policies",
    foreignKeys = [ForeignKey(
        entity = SupervisionPlanEntity::class,
        parentColumns = ["plan_id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )]
)
data class GlobalSupervisionPolicyEntity(
    @PrimaryKey @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "usage_duration_minutes") val usageDurationMinutes: Long,
    @ColumnInfo(name = "lock_duration_minutes") val lockDurationMinutes: Long
)

@Entity(
    tableName = "app_supervision_policies",
    foreignKeys = [ForeignKey(
        entity = SupervisionPlanEntity::class,
        parentColumns = ["plan_id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["package_name"])]
)
data class AppSupervisionPolicyEntity(
    @PrimaryKey @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "usage_allowance_minutes") val usageAllowanceMinutes: Long,
    @ColumnInfo(name = "rest_duration_minutes") val restDurationMinutes: Long,
    @ColumnInfo(name = "daily_usage_limit_minutes", defaultValue = "1440")
    val dailyUsageLimitMinutes: Long = 1_440L
)

@Entity(
    tableName = "app_supervision_disabled_time_ranges",
    primaryKeys = ["plan_id", "start_minute", "end_minute_exclusive"],
    foreignKeys = [ForeignKey(
        entity = SupervisionPlanEntity::class,
        parentColumns = ["plan_id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["plan_id"])]
)
data class AppSupervisionDisabledTimeRangeEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "end_minute_exclusive") val endMinuteExclusive: Int
)

/** 一个监督任务可由多个 App 中的任意一个触发。 */
@Entity(
    tableName = "supervision_plan_trigger_apps",
    primaryKeys = ["plan_id", "package_name"],
    foreignKeys = [ForeignKey(
        entity = SupervisionPlanEntity::class,
        parentColumns = ["plan_id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["plan_id"]),
        Index(value = ["package_name"])
    ]
)
data class SupervisionPlanTriggerAppEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "package_name") val packageName: String
)

/**
 * 每个计划最多保留一个待执行预约。
 *
 * 预约独立于计划主体，编辑计划时无需重写它；删除计划则通过外键自动清理。
 */
@Entity(
    tableName = "plan_activation_reservations",
    foreignKeys = [ForeignKey(
        entity = SupervisionPlanEntity::class,
        parentColumns = ["plan_id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )]
)
data class PlanActivationReservationEntity(
    @PrimaryKey @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "enable_at_epoch_millis") val enableAtEpochMillis: Long
)

data class SupervisionPlanWithRanges(
    @Embedded val plan: SupervisionPlanEntity,
    @Relation(parentColumn = "plan_id", entityColumn = "plan_id")
    val globalPolicy: GlobalSupervisionPolicyEntity?,
    @Relation(parentColumn = "plan_id", entityColumn = "plan_id")
    val appPolicy: AppSupervisionPolicyEntity?,
    @Relation(parentColumn = "plan_id", entityColumn = "plan_id")
    val ranges: List<SupervisionTimeRangeEntity>,
    @Relation(parentColumn = "plan_id", entityColumn = "plan_id")
    val activationReservation: PlanActivationReservationEntity? = null,
    @Relation(parentColumn = "plan_id", entityColumn = "plan_id")
    val disabledRanges: List<AppSupervisionDisabledTimeRangeEntity> = emptyList(),
    @Relation(parentColumn = "plan_id", entityColumn = "plan_id")
    val triggerApps: List<SupervisionPlanTriggerAppEntity> = emptyList()
)
