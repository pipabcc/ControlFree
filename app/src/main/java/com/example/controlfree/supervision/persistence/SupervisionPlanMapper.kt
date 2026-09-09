package com.example.controlfree.supervision.persistence

import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId

sealed interface PlanMappingResult {
    data class Success(val plan: SupervisionPlan) : PlanMappingResult
    data class Failure(val planId: String, val reason: String) : PlanMappingResult
}

data class SupervisionPlanRecord(
    val plan: SupervisionPlanEntity,
    val globalPolicy: GlobalSupervisionPolicyEntity?,
    val appPolicy: AppSupervisionPolicyEntity?,
    val ranges: List<SupervisionTimeRangeEntity>,
    val disabledRanges: List<AppSupervisionDisabledTimeRangeEntity> = emptyList(),
    val triggerApps: List<SupervisionPlanTriggerAppEntity> = emptyList()
)

object SupervisionPlanMapper {
    private const val VALID_DAY_MASK = 0b111_1111

    fun toRecord(plan: SupervisionPlan): SupervisionPlanRecord = SupervisionPlanRecord(
        plan = SupervisionPlanEntity(
            planId = plan.id,
            name = plan.name,
            planType = plan.type.name,
            enabled = plan.enabled,
            zoneId = plan.schedule.zoneId.id,
            zoneMode = plan.schedule.zoneMode.name,
            activeDaysMask = encodeDays(plan.schedule.activeDays),
            createdAtEpochMillis = plan.createdAtEpochMillis,
            updatedAtEpochMillis = plan.updatedAtEpochMillis,
            oneTimeStartEpochMillis = plan.oneTimeFocusWindow?.startEpochMillis,
            oneTimeEndEpochMillis = plan.oneTimeFocusWindow?.endEpochMillis
        ),
        globalPolicy = when (val policy = plan.policy) {
            is GlobalCyclePolicy -> GlobalSupervisionPolicyEntity(
                plan.id,
                policy.usageDuration.toMinutes(),
                policy.lockDuration.toMinutes()
            )
            is FocusCyclePolicy -> GlobalSupervisionPolicyEntity(
                plan.id,
                policy.playDuration.toMinutes(),
                policy.lockDuration.toMinutes()
            )
            is AppRulePolicy -> null
        },
        appPolicy = (plan.policy as? AppRulePolicy)?.let { policy ->
            AppSupervisionPolicyEntity(
                plan.id,
                policy.packageName,
                policy.usageAllowance.toMinutes(),
                policy.restDuration.toMinutes(),
                policy.dailyUsageLimit.toMinutes()
            )
        },
        ranges = plan.schedule.ranges.map { range ->
            SupervisionTimeRangeEntity(plan.id, range.startMinute, range.endMinuteExclusive)
        },
        disabledRanges = (plan.policy as? AppRulePolicy)?.disabledRanges.orEmpty().map { range ->
            AppSupervisionDisabledTimeRangeEntity(
                plan.id,
                range.startMinute,
                range.endMinuteExclusive
            )
        },
        triggerApps = plan.triggerAppPackageNames.sorted().map { packageName ->
            SupervisionPlanTriggerAppEntity(plan.id, packageName)
        }
    )

    fun fromRecord(record: SupervisionPlanWithRanges): PlanMappingResult = try {
        val row = record.plan
        require(record.ranges.isNotEmpty()) { "监督时间段为空" }
        require(record.ranges.all { it.planId == row.planId }) { "时间段关联了错误的计划" }
        val oneTimeWindow = when {
            row.oneTimeStartEpochMillis == null && row.oneTimeEndEpochMillis == null -> null
            row.oneTimeStartEpochMillis != null && row.oneTimeEndEpochMillis != null ->
                OneTimeFocusWindow(row.oneTimeStartEpochMillis, row.oneTimeEndEpochMillis)
            else -> error("一次性专注时间窗字段不完整")
        }
        val type = SupervisionPlanType.valueOf(row.planType)
        val policy = when (type) {
            SupervisionPlanType.GLOBAL -> {
                require(record.appPolicy == null) { "全局计划不能包含 App 策略" }
                val stored = requireNotNull(record.globalPolicy) { "全局计划缺少策略" }
                require(stored.planId == row.planId) { "全局策略关联了错误的计划" }
                GlobalCyclePolicy(
                    durationFromMinutes(stored.usageDurationMinutes, "使用时长"),
                    durationFromMinutes(stored.lockDurationMinutes, "锁定时长")
                )
            }
            SupervisionPlanType.APP -> {
                require(record.globalPolicy == null) { "App 计划不能包含全局策略" }
                val stored = requireNotNull(record.appPolicy) { "App 计划缺少策略" }
                require(stored.planId == row.planId) { "App 策略关联了错误的计划" }
                AppRulePolicy(
                    stored.packageName,
                    durationFromMinutes(stored.usageAllowanceMinutes, "App 可用时长"),
                    durationFromMinutes(stored.restDurationMinutes, "App 休息时长"),
                    durationFromMinutes(stored.dailyUsageLimitMinutes, "App 每日累计上限"),
                    record.disabledRanges
                        .also { ranges ->
                            require(ranges.all { it.planId == row.planId }) {
                                "禁用时段关联了错误的计划"
                            }
                        }
                        .sortedWith(compareBy({ it.startMinute }, { it.endMinuteExclusive }))
                        .map { DailyTimeRange(it.startMinute, it.endMinuteExclusive) }
                )
            }
            SupervisionPlanType.FOCUS -> {
                require(record.appPolicy == null) { "专注计划不能包含 App 策略" }
                val stored = requireNotNull(record.globalPolicy) { "专注计划缺少策略" }
                require(stored.planId == row.planId) { "专注策略关联了错误的计划" }
                FocusCyclePolicy(
                    lockDuration = durationFromMinutes(
                        stored.lockDurationMinutes,
                        "专注锁定时长"
                    ),
                    playDuration = durationFromMinutes(
                        stored.usageDurationMinutes,
                        "专注玩机时长"
                    )
                )
            }
        }
        PlanMappingResult.Success(
            SupervisionPlan(
                id = row.planId,
                name = row.name,
                type = type,
                enabled = row.enabled,
                schedule = WeeklySchedule(
                    zoneId = ZoneId.of(row.zoneId),
                    activeDays = decodeDays(row.activeDaysMask),
                    ranges = record.ranges
                        .sortedWith(compareBy({ it.startMinute }, { it.endMinuteExclusive }))
                        .map { DailyTimeRange(it.startMinute, it.endMinuteExclusive) },
                    zoneMode = ScheduleZoneMode.valueOf(row.zoneMode)
                ),
                policy = policy,
                createdAtEpochMillis = row.createdAtEpochMillis,
                updatedAtEpochMillis = row.updatedAtEpochMillis,
                scheduledEnableAtEpochMillis = record.activationReservation?.let { reservation ->
                    require(reservation.planId == row.planId) {
                        "预约开启记录关联了错误的计划"
                    }
                    require(reservation.enableAtEpochMillis >= 0L) { "预约开启时间无效" }
                    reservation.enableAtEpochMillis
                },
                oneTimeFocusWindow = oneTimeWindow,
                triggerAppPackageNames = record.triggerApps
                    .also { apps ->
                        require(apps.all { it.planId == row.planId }) {
                            "触发 App 关联了错误的计划"
                        }
                    }
                    .mapTo(linkedSetOf(), SupervisionPlanTriggerAppEntity::packageName)
            )
        )
    } catch (error: RuntimeException) {
        PlanMappingResult.Failure(record.plan.planId, error.message ?: "监督计划数据无效")
    }

    private fun encodeDays(days: Set<DayOfWeek>): Int {
        require(days.isNotEmpty()) { "至少选择一个星期" }
        return days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }
    }

    private fun decodeDays(mask: Int): Set<DayOfWeek> {
        require(mask != 0 && mask and VALID_DAY_MASK == mask) { "星期位掩码无效" }
        return DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
            mask and (1 shl (day.value - 1)) != 0
        }
    }

    private fun durationFromMinutes(minutes: Long, fieldName: String): Duration {
        require(minutes in 1L..1_440L) { "$fieldName 必须是 1-1440 分钟" }
        return Duration.ofMinutes(minutes)
    }
}
