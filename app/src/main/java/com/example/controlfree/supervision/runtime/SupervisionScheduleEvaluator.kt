package com.example.controlfree.supervision.runtime

import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.app.AppTriggerRule
import android.util.Log
import java.time.Instant
import java.time.ZoneId

data class ActiveDeviceSupervision(
    val plan: SupervisionPlan,
    val activeUntil: Instant
)

data class ActiveAppSupervision(
    val plan: SupervisionPlan,
    val activeUntil: Instant,
    val isExecutionWindowActive: Boolean = true,
    val cycleActiveUntil: Instant = activeUntil,
    val disabledUntil: Instant? = null,
    val dailyUsageDateEpochDay: Long = 0L,
    val dailyUsageResetAt: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
)

data class ActiveAppTrigger(
    val plan: SupervisionPlan,
    val activeUntil: Instant
)

sealed interface SupervisionScheduleEvaluation {
    data class Ready(
        val active: ActiveDeviceSupervision?,
        val activeApps: List<ActiveAppSupervision>,
        val activeAppTriggers: List<ActiveAppTrigger>,
        val nextReconciliationAt: Instant?
    ) : SupervisionScheduleEvaluation

    data class ConflictingActivePlans(val planIds: Set<String>) : SupervisionScheduleEvaluation
}

object SupervisionScheduleEvaluator {
    fun evaluate(
        plans: Collection<SupervisionPlan>,
        now: Instant,
        deviceZoneId: ZoneId,
        suppression: ScheduledOccurrenceSuppression?
    ): SupervisionScheduleEvaluation {
        val enabledPlans = plans.filter { plan ->
            plan.enabled && when (plan.type) {
                SupervisionPlanType.GLOBAL -> plan.policy is GlobalCyclePolicy
                SupervisionPlanType.APP -> plan.policy is AppRulePolicy
                SupervisionPlanType.FOCUS -> plan.policy is FocusCyclePolicy
            }
        }
        val enabledDevicePlans = enabledPlans.filter { it.type != SupervisionPlanType.APP }
        val enabledAppPlans = enabledPlans.filter { it.type == SupervisionPlanType.APP }
        val appTriggerPlans = plans.filter { plan ->
            !plan.enabled &&
                plan.type == SupervisionPlanType.GLOBAL &&
                plan.triggerAppPackageNames.isNotEmpty()
        }
        val nowEpochMillis = now.toEpochMilli()
        val activePlans = enabledDevicePlans.filter { plan ->
            isActiveAt(plan, now, deviceZoneId) &&
                suppression?.suppresses(plan, nowEpochMillis) != true
        }
        if (activePlans.size > 1) {
            return SupervisionScheduleEvaluation.ConflictingActivePlans(
                activePlans.mapTo(linkedSetOf(), SupervisionPlan::id)
            )
        }
        val activeAppPlans = enabledAppPlans.mapNotNull { plan ->
            activeAppSupervision(plan, now, deviceZoneId)
        }
        val conflictingActiveAppIds = activeAppPlans
            .groupBy { active -> (active.plan.policy as AppRulePolicy).packageName }
            .values
            .filter { sameAppPlans ->
                sameAppPlans.count(ActiveAppSupervision::isExecutionWindowActive) > 1
            }
            .flatten()
            .mapTo(linkedSetOf()) { it.plan.id }
        if (conflictingActiveAppIds.isNotEmpty()) {
            return SupervisionScheduleEvaluation.ConflictingActivePlans(conflictingActiveAppIds)
        }

        val active = activePlans.singleOrNull()?.let { plan ->
            ActiveDeviceSupervision(
                plan = plan,
                activeUntil = nextStateChangeAfter(plan, now, deviceZoneId)
                    ?: CONTINUOUS_OCCURRENCE_END
            )
        }
        val activeApps = activeAppPlans.sortedBy { it.plan.id }
        val activeAppTriggers = appTriggerPlans
            .filter { it.schedule.isActiveAt(now, deviceZoneId) }
            .map { plan ->
                ActiveAppTrigger(
                    plan = plan,
                    activeUntil = plan.schedule.nextStateChangeAfter(now, deviceZoneId)
                        ?: CONTINUOUS_OCCURRENCE_END
                )
            }
            .sortedBy { it.plan.id }
        val nextPlanBoundary = (enabledPlans + appTriggerPlans).asSequence()
            .flatMap { plan ->
                sequence {
                    nextStateChangeAfter(plan, now, deviceZoneId)?.let { yield(it) }
                    if (plan.type == SupervisionPlanType.APP) {
                        appDisabledSchedule(plan)?.nextStateChangeAfter(now, deviceZoneId)
                            ?.let { yield(it) }
                    }
                }
            }
            .minOrNull()
        val nextDailyReset = activeAppPlans.minOfOrNull(ActiveAppSupervision::dailyUsageResetAt)
        val nextScheduleBoundary = listOfNotNull(nextPlanBoundary, nextDailyReset).minOrNull()
        val suppressionBoundary = suppression
            ?.suppressUntilEpochMillis
            ?.takeIf {
                it > nowEpochMillis && it != CONTINUOUS_OCCURRENCE_END_EPOCH_MILLIS
            }
            ?.let(Instant::ofEpochMilli)
        return SupervisionScheduleEvaluation.Ready(
            active = active,
            activeApps = activeApps,
            activeAppTriggers = activeAppTriggers,
            nextReconciliationAt = listOfNotNull(nextScheduleBoundary, suppressionBoundary).minOrNull()
        )
    }

    private fun activeAppSupervision(
        plan: SupervisionPlan,
        now: Instant,
        deviceZoneId: ZoneId
    ): ActiveAppSupervision? {
        val policy = plan.policy as AppRulePolicy
        val executionActive = plan.schedule.isActiveAt(now, deviceZoneId)
        val disabledSchedule = appDisabledSchedule(plan)
        val disabledActive = disabledSchedule?.isActiveAt(now, deviceZoneId) == true
        if (!executionActive && !disabledActive) return null

        val cycleEnd = if (executionActive) {
            plan.schedule.nextStateChangeAfter(now, deviceZoneId) ?: CONTINUOUS_OCCURRENCE_END
        } else {
            null
        }
        val disabledEnd = if (disabledActive) {
            requireNotNull(disabledSchedule).nextStateChangeAfter(now, deviceZoneId)
                ?: CONTINUOUS_OCCURRENCE_END
        } else {
            null
        }
        val nextExecutionBoundary = plan.schedule.nextStateChangeAfter(now, deviceZoneId)
        val nextDisabledBoundary = disabledSchedule?.nextStateChangeAfter(now, deviceZoneId)
        val effectiveZone = plan.schedule.resolvedZoneId(deviceZoneId)
        val localDate = now.atZone(effectiveZone).toLocalDate()
        val dailyReset = localDate.plusDays(1L).atStartOfDay(effectiveZone).toInstant()
        val activeUntil = listOfNotNull(
            nextExecutionBoundary,
            nextDisabledBoundary
        ).minOrNull() ?: CONTINUOUS_OCCURRENCE_END
        return ActiveAppSupervision(
            plan = plan,
            activeUntil = activeUntil,
            isExecutionWindowActive = executionActive,
            cycleActiveUntil = cycleEnd ?: Instant.EPOCH,
            disabledUntil = disabledEnd,
            dailyUsageDateEpochDay = localDate.toEpochDay(),
            dailyUsageResetAt = dailyReset
        )
    }

    private fun appDisabledSchedule(plan: SupervisionPlan): WeeklySchedule? {
        val policy = plan.policy as? AppRulePolicy ?: return null
        if (policy.disabledRanges.isEmpty()) return null
        return WeeklySchedule(
            zoneId = plan.schedule.zoneId,
            activeDays = plan.schedule.activeDays,
            ranges = policy.disabledRanges,
            zoneMode = plan.schedule.zoneMode
        )
    }

    private fun isActiveAt(
        plan: SupervisionPlan,
        now: Instant,
        deviceZoneId: ZoneId
    ): Boolean = plan.oneTimeFocusWindow?.contains(now.toEpochMilli())
        ?: plan.schedule.isActiveAt(now, deviceZoneId)

    private fun nextStateChangeAfter(
        plan: SupervisionPlan,
        now: Instant,
        deviceZoneId: ZoneId
    ): Instant? = plan.oneTimeFocusWindow?.let { window ->
        when {
            now.toEpochMilli() < window.startEpochMillis ->
                Instant.ofEpochMilli(window.startEpochMillis)
            now.toEpochMilli() < window.endEpochMillis ->
                Instant.ofEpochMilli(window.endEpochMillis)
            else -> null
        }
    } ?: plan.schedule.nextStateChangeAfter(now, deviceZoneId)

    private const val CONTINUOUS_OCCURRENCE_END_EPOCH_MILLIS = Long.MAX_VALUE
    internal val CONTINUOUS_OCCURRENCE_END =
        Instant.ofEpochMilli(CONTINUOUS_OCCURRENCE_END_EPOCH_MILLIS)
}

internal fun ActiveAppTrigger.toRuntimeRule(): AppTriggerRule = AppTriggerRule(
    planId = plan.id,
    planUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
    planName = plan.name,
    packageNames = plan.triggerAppPackageNames,
    occurrenceEndEpochMillis = activeUntil.toEpochMilli()
)

enum class ScheduledRuntimeAction {
    KEEP,
    START_OR_REPLACE,
    RESTORE_SCHEDULED,
    STOP_SCHEDULED,
    CLEAR_STALE_OWNER,
    HOLD_CORRUPT_STATE
}

object ScheduledRuntimeDecisionPolicy {
    fun decide(
        monitorActive: Boolean,
        serviceRunning: Boolean,
        ownerResult: ScheduledOwnerReadResult,
        active: ActiveDeviceSupervision?,
        scheduledRuntimeOwned: Boolean = false
    ): ScheduledRuntimeAction {
        if (ownerResult == ScheduledOwnerReadResult.Corrupted) {
            return if (monitorActive) {
                ScheduledRuntimeAction.HOLD_CORRUPT_STATE
            } else {
                ScheduledRuntimeAction.CLEAR_STALE_OWNER
            }
        }
        val owner = (ownerResult as? ScheduledOwnerReadResult.Available)?.owner
        if (scheduledRuntimeOwned && owner == null) {
            // 定时启动授权或首个快照落盘期间，持久化 owner 尚不可见。
            // 此时计划已经停用就必须主动停止，不能被“Service 正在运行”误判为手动启动。
            return if (active == null) {
                ScheduledRuntimeAction.STOP_SCHEDULED
            } else {
                ScheduledRuntimeAction.KEEP
            }
        }
        return when {
            monitorActive && owner == null -> ScheduledRuntimeAction.KEEP
            !monitorActive && serviceRunning -> ScheduledRuntimeAction.KEEP
            monitorActive && active == null -> ScheduledRuntimeAction.STOP_SCHEDULED
            monitorActive && owner.matches(active) && serviceRunning -> ScheduledRuntimeAction.KEEP
            monitorActive && owner.matches(active) -> ScheduledRuntimeAction.RESTORE_SCHEDULED
            monitorActive -> ScheduledRuntimeAction.START_OR_REPLACE
            active != null -> ScheduledRuntimeAction.START_OR_REPLACE
            owner != null -> ScheduledRuntimeAction.CLEAR_STALE_OWNER
            else -> ScheduledRuntimeAction.KEEP
        }
    }

    private fun ScheduledMonitorOwner?.matches(active: ActiveDeviceSupervision?): Boolean =
        this != null &&
            active != null &&
            planId == active.plan.id &&
            planUpdatedAtEpochMillis == active.plan.updatedAtEpochMillis &&
            activeUntilEpochMillis == active.activeUntil.toEpochMilli()
}
