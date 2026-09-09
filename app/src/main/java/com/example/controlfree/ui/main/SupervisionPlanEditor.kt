package com.example.controlfree.ui.main

import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.MAX_PLAN_TRIGGER_APPS
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId

internal const val MAX_GLOBAL_PLAN_NAME_LENGTH = 40
internal const val MAX_GLOBAL_PLAN_RANGES = 6
internal const val MAX_APP_PLAN_NAME_LENGTH = 40
internal const val MAX_APP_PLAN_RANGES = 6
internal const val MAX_APP_DISABLED_RANGES = 6
internal const val MAX_FOCUS_PLAN_NAME_LENGTH = 40
internal const val MAX_FOCUS_PLAN_RANGES = 6

internal data class TimeRangeDraft(
    val id: Long,
    val startMinute: Int,
    val endMinuteExclusive: Int
) {
    init {
        require(startMinute in 0 until 24 * 60)
        require(endMinuteExclusive in 1..24 * 60)
    }
}

internal data class GlobalPlanEditorDraft(
    val planId: String?,
    val expectedUpdatedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long?,
    val name: String,
    val enabled: Boolean,
    val activeDays: Set<DayOfWeek>,
    val ranges: List<TimeRangeDraft>,
    val usageMinutes: Int,
    val lockMinutes: Int,
    val zoneId: ZoneId,
    val zoneMode: ScheduleZoneMode,
    val scheduledEnableAtEpochMillis: Long? = null,
    val triggerAppPackageNames: Set<String> = emptySet()
)

internal sealed interface GlobalPlanBuildResult {
    data class Success(
        val plan: SupervisionPlan,
        val expectedUpdatedAtEpochMillis: Long?
    ) : GlobalPlanBuildResult

    data class Invalid(val reason: String) : GlobalPlanBuildResult
}

internal object GlobalPlanEditorMapper {
    fun fromPlan(plan: SupervisionPlan): GlobalPlanEditorDraft {
        require(plan.type == SupervisionPlanType.GLOBAL) { "只能编辑全局监督计划" }
        val policy = plan.policy as GlobalCyclePolicy
        return GlobalPlanEditorDraft(
            planId = plan.id,
            expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
            createdAtEpochMillis = plan.createdAtEpochMillis,
            name = plan.name,
            enabled = plan.enabled,
            activeDays = plan.schedule.activeDays,
            ranges = plan.schedule.ranges.mapIndexed { index, range ->
                TimeRangeDraft(
                    id = index.toLong() + 1L,
                    startMinute = range.startMinute,
                    endMinuteExclusive = range.endMinuteExclusive
                )
            },
            usageMinutes = policy.usageDuration.toMinutes().toInt(),
            lockMinutes = policy.lockDuration.toMinutes().toInt(),
            zoneId = plan.schedule.zoneId,
            zoneMode = plan.schedule.zoneMode,
            scheduledEnableAtEpochMillis = plan.scheduledEnableAtEpochMillis,
            triggerAppPackageNames = plan.triggerAppPackageNames
        )
    }

    fun build(
        draft: GlobalPlanEditorDraft,
        newPlanId: String,
        nowEpochMillis: Long,
        deviceZoneId: ZoneId
    ): GlobalPlanBuildResult = try {
        val normalizedName = draft.name.trim()
        require(normalizedName.isNotEmpty()) { "请输入计划名称" }
        require(normalizedName.length <= MAX_GLOBAL_PLAN_NAME_LENGTH) {
            "计划名称最多 $MAX_GLOBAL_PLAN_NAME_LENGTH 个字符"
        }
        require(draft.activeDays.isNotEmpty()) { "请至少选择一个执行日" }
        require(draft.ranges.isNotEmpty()) { "请至少设置一个执行时间段" }
        require(draft.ranges.size <= MAX_GLOBAL_PLAN_RANGES) {
            "每个计划最多设置 $MAX_GLOBAL_PLAN_RANGES 个时间段"
        }
        require(draft.usageMinutes in 1..1_440) { "可用时长必须是 1-1440 分钟" }
        require(draft.lockMinutes in 1..1_440) { "锁定时长必须是 1-1440 分钟" }
        validatePlanActivationDraft(
            enabled = draft.enabled,
            scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
            nowEpochMillis = nowEpochMillis
        )
        require(draft.triggerAppPackageNames.size <= MAX_PLAN_TRIGGER_APPS) {
            "每个任务最多添加 $MAX_PLAN_TRIGGER_APPS 个触发 App"
        }
        require(
            draft.triggerAppPackageNames.isEmpty() ||
                draft.scheduledEnableAtEpochMillis == null
        ) { "App 触发和预约开启不能同时选择" }
        val existingVersion = draft.expectedUpdatedAtEpochMillis
        require(existingVersion != Long.MAX_VALUE) { "计划版本无效，请重新打开编辑器" }
        val createdAt = draft.createdAtEpochMillis ?: nowEpochMillis.coerceAtLeast(0L)
        val updatedAt = if (existingVersion == null) {
            nowEpochMillis.coerceAtLeast(createdAt)
        } else {
            maxOf(nowEpochMillis, existingVersion + 1L, createdAt)
        }
        val planId = draft.planId ?: newPlanId.trim()
        require(planId.isNotEmpty()) { "无法生成计划编号" }
        val storedZone = when (draft.zoneMode) {
            ScheduleZoneMode.FOLLOW_DEVICE -> deviceZoneId
            ScheduleZoneMode.FIXED -> draft.zoneId
        }
        GlobalPlanBuildResult.Success(
            plan = SupervisionPlan(
                id = planId,
                name = normalizedName,
                type = SupervisionPlanType.GLOBAL,
                enabled = draft.enabled,
                schedule = WeeklySchedule(
                    zoneId = storedZone,
                    activeDays = draft.activeDays,
                    ranges = draft.ranges.map { range ->
                        DailyTimeRange(range.startMinute, range.endMinuteExclusive)
                    },
                    zoneMode = draft.zoneMode
                ),
                policy = GlobalCyclePolicy(
                    usageDuration = Duration.ofMinutes(draft.usageMinutes.toLong()),
                    lockDuration = Duration.ofMinutes(draft.lockMinutes.toLong())
                ),
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = updatedAt,
                scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
                triggerAppPackageNames = draft.triggerAppPackageNames
            ),
            expectedUpdatedAtEpochMillis = existingVersion
        )
    } catch (error: IllegalArgumentException) {
        GlobalPlanBuildResult.Invalid(error.message ?: "监督计划参数无效")
    }
}

internal data class FocusPlanEditorDraft(
    val planId: String?,
    val expectedUpdatedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long?,
    val name: String,
    val enabled: Boolean,
    val activeDays: Set<DayOfWeek>,
    val ranges: List<TimeRangeDraft>,
    val lockMinutes: Int,
    val playMinutes: Int,
    val zoneId: ZoneId,
    val zoneMode: ScheduleZoneMode,
    val oneTimeFocusWindow: OneTimeFocusWindow? = null,
    val scheduledEnableAtEpochMillis: Long? = null
)

internal sealed interface FocusPlanBuildResult {
    data class Success(
        val plan: SupervisionPlan,
        val expectedUpdatedAtEpochMillis: Long?
    ) : FocusPlanBuildResult

    data class Invalid(val reason: String) : FocusPlanBuildResult
}

internal object FocusPlanEditorMapper {
    fun fromPlan(plan: SupervisionPlan): FocusPlanEditorDraft {
        require(plan.type == SupervisionPlanType.FOCUS) { "只能编辑专注计划" }
        val policy = plan.policy as FocusCyclePolicy
        return FocusPlanEditorDraft(
            planId = plan.id,
            expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
            createdAtEpochMillis = plan.createdAtEpochMillis,
            name = plan.name,
            enabled = plan.enabled,
            activeDays = plan.schedule.activeDays,
            ranges = plan.schedule.ranges.mapIndexed { index, range ->
                TimeRangeDraft(
                    id = index.toLong() + 1L,
                    startMinute = range.startMinute,
                    endMinuteExclusive = range.endMinuteExclusive
                )
            },
            lockMinutes = policy.lockDuration.toMinutes().toInt(),
            playMinutes = policy.playDuration.toMinutes().toInt(),
            zoneId = plan.schedule.zoneId,
            zoneMode = plan.schedule.zoneMode,
            oneTimeFocusWindow = plan.oneTimeFocusWindow,
            scheduledEnableAtEpochMillis = plan.scheduledEnableAtEpochMillis
        )
    }

    fun build(
        draft: FocusPlanEditorDraft,
        newPlanId: String,
        nowEpochMillis: Long,
        deviceZoneId: ZoneId
    ): FocusPlanBuildResult = try {
        val normalizedName = draft.name.trim()
        require(normalizedName.isNotEmpty()) { "请输入专注任务名称" }
        require(normalizedName.length <= MAX_FOCUS_PLAN_NAME_LENGTH) {
            "专注任务名称最多 $MAX_FOCUS_PLAN_NAME_LENGTH 个字符"
        }
        require(draft.activeDays.isNotEmpty()) { "请至少选择一个执行日" }
        require(draft.ranges.isNotEmpty()) { "请至少设置一个执行时间段" }
        require(draft.ranges.size <= MAX_FOCUS_PLAN_RANGES) {
            "每个专注任务最多设置 $MAX_FOCUS_PLAN_RANGES 个时间段"
        }
        require(draft.lockMinutes in MIN_FOCUS_LOCK_MINUTES..MAX_FOCUS_LOCK_MINUTES) {
            "锁定时长必须是 $MIN_FOCUS_LOCK_MINUTES-$MAX_FOCUS_LOCK_MINUTES 分钟"
        }
        require(draft.playMinutes in 1..60) { "玩机时长必须是 1-60 分钟" }
        if (draft.oneTimeFocusWindow == null) {
            validatePlanActivationDraft(
                enabled = draft.enabled,
                scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
                nowEpochMillis = nowEpochMillis
            )
        }
        val existingVersion = draft.expectedUpdatedAtEpochMillis
        require(existingVersion != Long.MAX_VALUE) { "任务版本无效，请重新打开编辑器" }
        val createdAt = draft.createdAtEpochMillis ?: nowEpochMillis.coerceAtLeast(0L)
        val updatedAt = if (existingVersion == null) {
            nowEpochMillis.coerceAtLeast(createdAt)
        } else {
            maxOf(nowEpochMillis, existingVersion + 1L, createdAt)
        }
        val planId = draft.planId ?: newPlanId.trim()
        require(planId.isNotEmpty()) { "无法生成专注任务编号" }
        val storedZone = when (draft.zoneMode) {
            ScheduleZoneMode.FOLLOW_DEVICE -> deviceZoneId
            ScheduleZoneMode.FIXED -> draft.zoneId
        }
        FocusPlanBuildResult.Success(
            plan = SupervisionPlan(
                id = planId,
                name = normalizedName,
                type = SupervisionPlanType.FOCUS,
                enabled = draft.enabled,
                schedule = WeeklySchedule(
                    zoneId = storedZone,
                    activeDays = draft.activeDays,
                    ranges = draft.ranges.map { range ->
                        DailyTimeRange(range.startMinute, range.endMinuteExclusive)
                    },
                    zoneMode = draft.zoneMode
                ),
                policy = FocusCyclePolicy(
                    lockDuration = Duration.ofMinutes(draft.lockMinutes.toLong()),
                    playDuration = Duration.ofMinutes(draft.playMinutes.toLong())
                ),
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = updatedAt,
                scheduledEnableAtEpochMillis = draft.oneTimeFocusWindow
                    ?.takeIf { !draft.enabled && it.startEpochMillis > nowEpochMillis }
                    ?.startEpochMillis
                    ?: draft.scheduledEnableAtEpochMillis,
                oneTimeFocusWindow = draft.oneTimeFocusWindow
            ),
            expectedUpdatedAtEpochMillis = existingVersion
        )
    } catch (error: IllegalArgumentException) {
        FocusPlanBuildResult.Invalid(error.message ?: "专注任务参数无效")
    }
}

internal data class AppPlanEditorDraft(
    val planId: String?,
    val expectedUpdatedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long?,
    val name: String,
    val enabled: Boolean,
    val activeDays: Set<DayOfWeek>,
    val ranges: List<TimeRangeDraft>,
    val disabledRanges: List<TimeRangeDraft>,
    val packageName: String,
    val packageLabel: String,
    val usageAllowanceMinutes: Int,
    val restMinutes: Int,
    val dailyUsageLimitMinutes: Int,
    val zoneId: ZoneId,
    val zoneMode: ScheduleZoneMode,
    val scheduledEnableAtEpochMillis: Long? = null
)

internal sealed interface AppPlanBuildResult {
    data class Success(
        val plan: SupervisionPlan,
        val expectedUpdatedAtEpochMillis: Long?
    ) : AppPlanBuildResult

    data class Invalid(val reason: String) : AppPlanBuildResult
}

internal object AppPlanEditorMapper {
    fun fromPlan(plan: SupervisionPlan, packageLabel: String): AppPlanEditorDraft {
        require(plan.type == SupervisionPlanType.APP) { "只能编辑 App 独立监督计划" }
        val policy = plan.policy as AppRulePolicy
        return AppPlanEditorDraft(
            planId = plan.id,
            expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
            createdAtEpochMillis = plan.createdAtEpochMillis,
            name = plan.name,
            enabled = plan.enabled,
            activeDays = plan.schedule.activeDays,
            ranges = plan.schedule.ranges.mapIndexed { index, range ->
                TimeRangeDraft(
                    id = index.toLong() + 1L,
                    startMinute = range.startMinute,
                    endMinuteExclusive = range.endMinuteExclusive
                )
            },
            disabledRanges = policy.disabledRanges.mapIndexed { index, range ->
                TimeRangeDraft(
                    id = APP_DISABLED_RANGE_ID_OFFSET + index.toLong() + 1L,
                    startMinute = range.startMinute,
                    endMinuteExclusive = range.endMinuteExclusive
                )
            },
            packageName = policy.packageName,
            packageLabel = packageLabel.ifBlank { policy.packageName },
            usageAllowanceMinutes = policy.usageAllowance.toMinutes().toInt(),
            restMinutes = policy.restDuration.toMinutes().toInt(),
            dailyUsageLimitMinutes = policy.dailyUsageLimit.toMinutes().toInt(),
            zoneId = plan.schedule.zoneId,
            zoneMode = plan.schedule.zoneMode,
            scheduledEnableAtEpochMillis = plan.scheduledEnableAtEpochMillis
        )
    }

    fun build(
        draft: AppPlanEditorDraft,
        newPlanId: String,
        nowEpochMillis: Long,
        deviceZoneId: ZoneId
    ): AppPlanBuildResult = try {
        val normalizedName = draft.name.trim()
        require(normalizedName.isNotEmpty()) { "请输入计划名称" }
        require(normalizedName.length <= MAX_APP_PLAN_NAME_LENGTH) {
            "计划名称最多 $MAX_APP_PLAN_NAME_LENGTH 个字符"
        }
        require(draft.packageName.isNotBlank()) { "请选择要监督的 App" }
        require(draft.activeDays.isNotEmpty()) { "请至少选择一个执行日" }
        require(draft.ranges.isNotEmpty()) { "请至少设置一个执行时间段" }
        require(draft.ranges.size <= MAX_APP_PLAN_RANGES) {
            "每个计划最多设置 $MAX_APP_PLAN_RANGES 个时间段"
        }
        require(draft.disabledRanges.size <= MAX_APP_DISABLED_RANGES) {
            "每个计划最多设置 $MAX_APP_DISABLED_RANGES 个禁用时段"
        }
        require(draft.usageAllowanceMinutes in 1..1_440) {
            "App 可用额度必须是 1-1440 分钟"
        }
        require(draft.restMinutes in 1..1_440) { "App 休息时长必须是 1-1440 分钟" }
        require(draft.dailyUsageLimitMinutes in 1..1_440) {
            "每日累计使用上限必须是 1-1440 分钟"
        }
        validatePlanActivationDraft(
            enabled = draft.enabled,
            scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
            nowEpochMillis = nowEpochMillis
        )
        val existingVersion = draft.expectedUpdatedAtEpochMillis
        require(existingVersion != Long.MAX_VALUE) { "计划版本无效，请重新打开编辑器" }
        val createdAt = draft.createdAtEpochMillis ?: nowEpochMillis.coerceAtLeast(0L)
        val updatedAt = if (existingVersion == null) {
            nowEpochMillis.coerceAtLeast(createdAt)
        } else {
            maxOf(nowEpochMillis, existingVersion + 1L, createdAt)
        }
        val planId = draft.planId ?: newPlanId.trim()
        require(planId.isNotEmpty()) { "无法生成计划编号" }
        val storedZone = when (draft.zoneMode) {
            ScheduleZoneMode.FOLLOW_DEVICE -> deviceZoneId
            ScheduleZoneMode.FIXED -> draft.zoneId
        }
        AppPlanBuildResult.Success(
            plan = SupervisionPlan(
                id = planId,
                name = normalizedName,
                type = SupervisionPlanType.APP,
                enabled = draft.enabled,
                schedule = WeeklySchedule(
                    zoneId = storedZone,
                    activeDays = draft.activeDays,
                    ranges = draft.ranges.map { range ->
                        DailyTimeRange(range.startMinute, range.endMinuteExclusive)
                    },
                    zoneMode = draft.zoneMode
                ),
                policy = AppRulePolicy(
                    packageName = draft.packageName,
                    usageAllowance = Duration.ofMinutes(draft.usageAllowanceMinutes.toLong()),
                    restDuration = Duration.ofMinutes(draft.restMinutes.toLong()),
                    dailyUsageLimit = Duration.ofMinutes(draft.dailyUsageLimitMinutes.toLong()),
                    disabledRanges = draft.disabledRanges.map { range ->
                        DailyTimeRange(range.startMinute, range.endMinuteExclusive)
                    }
                ),
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = updatedAt,
                scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis
            ),
            expectedUpdatedAtEpochMillis = existingVersion
        )
    } catch (error: IllegalArgumentException) {
        AppPlanBuildResult.Invalid(error.message ?: "App 监督计划参数无效")
    }
}

private const val APP_DISABLED_RANGE_ID_OFFSET = 1_000_000L

internal fun validatePlanActivationDraft(
    enabled: Boolean,
    scheduledEnableAtEpochMillis: Long?,
    nowEpochMillis: Long
) {
    require(!enabled || scheduledEnableAtEpochMillis == null) {
        "立即启用和预约开启不能同时选择"
    }
    scheduledEnableAtEpochMillis?.let { enableAt ->
        require(scheduledEnableValidationMessage(enableAt, nowEpochMillis) == null) {
            scheduledEnableValidationMessage(enableAt, nowEpochMillis)
                ?: "预约开启时间无效"
        }
    }
}

internal object TimeRangeSuggestion {
    private const val SLOT_MINUTES = 60

    fun next(ranges: List<TimeRangeDraft>, id: Long): TimeRangeDraft? {
        if (ranges.size >= MAX_GLOBAL_PLAN_RANGES) return null
        val preferredStarts = buildList {
            ranges.asReversed().forEach { range ->
                add(if (range.endMinuteExclusive == 24 * 60) 0 else range.endMinuteExclusive)
            }
            addAll(8 * 60 until 24 * 60 step SLOT_MINUTES)
            addAll(0 until 8 * 60 step SLOT_MINUTES)
        }.distinct()
        return preferredStarts.asSequence()
            .map { start -> oneHourRange(id, start) }
            .firstOrNull { candidate ->
                ranges.none { existing -> overlapsWithinDay(existing, candidate) }
            }
    }

    private fun oneHourRange(id: Long, start: Int): TimeRangeDraft {
        val rawEnd = start + SLOT_MINUTES
        val end = when {
            rawEnd < 24 * 60 -> rawEnd
            rawEnd == 24 * 60 -> 24 * 60
            else -> rawEnd - 24 * 60
        }
        return TimeRangeDraft(id, start, end)
    }

    private fun overlapsWithinDay(first: TimeRangeDraft, second: TimeRangeDraft): Boolean =
        segments(first).any { left ->
            segments(second).any { right ->
                left.first < right.second && right.first < left.second
            }
        }

    private fun segments(range: TimeRangeDraft): List<Pair<Int, Int>> {
        val end = range.endMinuteExclusive
        return when {
            end > range.startMinute -> listOf(range.startMinute to end)
            else -> listOf(range.startMinute to 24 * 60, 0 to end)
        }
    }
}
