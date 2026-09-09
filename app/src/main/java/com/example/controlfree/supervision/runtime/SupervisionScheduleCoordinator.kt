package com.example.controlfree.supervision.runtime

import android.content.Context
import android.util.Log
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.controlfree.AppSupervisionService
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.MonitorService
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.runtime.readMonitorRuntimeCapabilities
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.app.AppSupervisionBinaryCodec
import com.example.controlfree.supervision.app.AppSupervisionRule
import com.example.controlfree.supervision.app.AppTriggerRule
import com.example.controlfree.supervision.app.AppSupervisionRuntimeSnapshot
import com.example.controlfree.supervision.app.AppSupervisionRuntimeStore
import com.example.controlfree.supervision.app.AppSupervisionSnapshotReadResult
import com.example.controlfree.supervision.history.SupervisionHistoryRecorder
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.PlanWriteResult
import com.example.controlfree.supervision.persistence.ScheduledEnableBatchResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SupervisionReconciliationResult {
    data class Completed(
        val runtimeAction: ScheduledRuntimeAction,
        val appRuntimeAction: AppSupervisionRuntimeAction,
        val nextReconciliationAt: Instant?,
        val alarmPrecision: SupervisionAlarmPrecision?
    ) : SupervisionReconciliationResult

    data object PlanStorageUnavailable : SupervisionReconciliationResult
    data class ConflictingPlans(val planIds: Set<String>) : SupervisionReconciliationResult
    data object RuntimePrerequisitesMissing : SupervisionReconciliationResult
    data object RuntimeCommandFailed : SupervisionReconciliationResult
}

enum class AppSupervisionRuntimeAction {
    KEEP,
    APPLY,
    STOP,
    CLEAR_STALE
}

class SupervisionScheduleCoordinator(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val appContext = context.applicationContext
    private val repository = SupervisionPlanRepository.getInstance(appContext)
    private val preferences = PreferenceManager(appContext)
    private val alarmScheduler = SupervisionAlarmScheduler(appContext)
    private val appRuntimeStore = AppSupervisionRuntimeStore(appContext)
    private val historyRecorder = SupervisionHistoryRecorder.getInstance(appContext)
    private val scheduledEnableNotifier = ScheduledEnableNotifier(appContext)

    suspend fun reconcile(): SupervisionReconciliationResult =
        reconciliationMutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): SupervisionReconciliationResult {
        val now = clock.instant()
        when (repository.disableExpiredOneTimeFocusPlans(now.toEpochMilli())) {
            is PlanWriteResult.Success -> Unit
            PlanWriteResult.StorageFailure,
            is PlanWriteResult.CorruptData,
            is PlanWriteResult.InvalidInput,
            is PlanWriteResult.NotFound,
            is PlanWriteResult.Conflicts,
            is PlanWriteResult.DuplicateIds,
            is PlanWriteResult.StaleData,
            is PlanWriteResult.NonIncreasingVersion -> {
                alarmScheduler.scheduleRetry()
                return SupervisionReconciliationResult.PlanStorageUnavailable
            }
        }
        val scheduledEnableResult = repository.activateDueScheduledEnables(now.toEpochMilli())
        val scheduledEnableBatch = when (scheduledEnableResult) {
            is ScheduledEnableBatchResult.Completed -> scheduledEnableResult
            is ScheduledEnableBatchResult.InvalidInput,
            ScheduledEnableBatchResult.StorageFailure -> {
                alarmScheduler.scheduleRetry()
                return SupervisionReconciliationResult.PlanStorageUnavailable
            }
        }
        scheduledEnableNotifier.notifyActivationFailures(scheduledEnableBatch.failures)

        try {
            repository.resetExpiredAppTriggerPlans(now.toEpochMilli(), clock.zone)
        } catch (_: RuntimeException) {
            alarmScheduler.scheduleRetry()
            return SupervisionReconciliationResult.PlanStorageUnavailable
        }

        val plans = when (val loaded = repository.loadPlans()) {
            is PlanLoadResult.Success -> {
                Log.d("AppTriggerDebug", "Loaded plans size: ${loaded.plans.size}")
                loaded.plans.forEach { plan ->
                    Log.d("AppTriggerDebug", "Plan loaded: id=${plan.id}, name=${plan.name}, enabled=${plan.enabled}, type=${plan.type}, triggerApps=${plan.triggerAppPackageNames}, scheduleActive=${plan.schedule.isActiveAt(now, clock.zone)}")
                }
                loaded.plans
            }
            is PlanLoadResult.CorruptData,
            PlanLoadResult.StorageFailure -> {
                alarmScheduler.scheduleRetry()
                return SupervisionReconciliationResult.PlanStorageUnavailable
            }
        }
        val suppression = preferences.getScheduledOccurrenceSuppression(now.toEpochMilli())
        val evaluation = SupervisionScheduleEvaluator.evaluate(
            plans = plans,
            now = now,
            deviceZoneId = clock.zone,
            suppression = suppression
        )
        if (evaluation is SupervisionScheduleEvaluation.ConflictingActivePlans) {
            alarmScheduler.scheduleRetry()
            return SupervisionReconciliationResult.ConflictingPlans(evaluation.planIds)
        }
        evaluation as SupervisionScheduleEvaluation.Ready
        alarmScheduler.cancelRetry()
        val nextScheduledEnableAt = earliestScheduledEnableAt(plans, now)
        val nextReconciliationAt = earliestInstant(
            evaluation.nextReconciliationAt,
            nextScheduledEnableAt
        )
        val nextAlarmPrecision = nextReconciliationAt?.let(alarmScheduler::schedule)
            ?: run {
                alarmScheduler.cancel()
                null
            }
        val ownerResult = preferences.inspectScheduledMonitorOwner()
        val monitorActive = preferences.isMonitorActive()
        val runtimeAction = ScheduledRuntimeDecisionPolicy.decide(
            monitorActive = monitorActive,
            serviceRunning = MonitorService.isRunning,
            ownerResult = ownerResult,
            active = evaluation.active,
            scheduledRuntimeOwned = MonitorService.hasScheduledRuntimeOwnership()
        )
        Log.d("AppTriggerDebug", "reconcileLocked activeApps=${evaluation.activeApps.size}, activeAppTriggers=${evaluation.activeAppTriggers.size}")
        evaluation.activeAppTriggers.forEach {
            Log.d("AppTriggerDebug", "activeAppTrigger planId=${it.plan.id}, name=${it.plan.name}, enabled=${it.plan.enabled}, triggerAppPackageNames=${it.plan.triggerAppPackageNames}")
        }
        val appRules = evaluation.activeApps.map(ActiveAppSupervision::toRuntimeRule)
        val appTriggerRules = evaluation.activeAppTriggers.map(ActiveAppTrigger::toRuntimeRule)
        val storedAppRuntime = appRuntimeStore.read()
        val appRuntimeAction = decideAppSupervisionRuntimeAction(
            activeRules = appRules,
            triggerRules = appTriggerRules,
            serviceRunning = AppSupervisionService.isRunning,
            storedRuntime = storedAppRuntime
        )
        Log.d("AppTriggerDebug", "appRuntimeAction=$appRuntimeAction, isRunning=${AppSupervisionService.isRunning}")
        val capabilities by lazy {
            try {
                readMonitorRuntimeCapabilities(appContext)
            } catch (_: RuntimeException) {
                null
            }
        }
        var prerequisitesMissing = false
        val globalCommandSucceeded = if (
            requiresRuntimePrerequisites(runtimeAction, monitorActive) &&
            capabilities?.canStartMonitor != true
        ) {
            prerequisitesMissing = true
            true
        } else {
            when (runtimeAction) {
                ScheduledRuntimeAction.KEEP,
                ScheduledRuntimeAction.HOLD_CORRUPT_STATE -> true
                ScheduledRuntimeAction.CLEAR_STALE_OWNER -> preferences.clearScheduledMonitorOwner()
                ScheduledRuntimeAction.STOP_SCHEDULED -> dispatchStopScheduledMonitor()
                ScheduledRuntimeAction.RESTORE_SCHEDULED -> dispatchRestoreScheduledMonitor()
                ScheduledRuntimeAction.START_OR_REPLACE -> dispatchStartScheduledMonitor(
                    requireNotNull(evaluation.active)
                )
            }
        }
        val appCommandSucceeded = when (appRuntimeAction) {
            AppSupervisionRuntimeAction.KEEP -> true
            AppSupervisionRuntimeAction.CLEAR_STALE -> appRuntimeStore.clear()
            AppSupervisionRuntimeAction.STOP -> dispatchStopAppSupervision()
            AppSupervisionRuntimeAction.APPLY -> {
                val runtimePrepared = prepareAppRuntimeSnapshot(
                    rules = appRules,
                    triggerRules = appTriggerRules,
                    storedRuntime = storedAppRuntime,
                    nowEpochMillis = now.toEpochMilli()
                )
                val canStart = capabilities?.canStartAppSupervision == true
                Log.d("AppTriggerDebug", "APPLY branch: runtimePrepared=$runtimePrepared, canStartAppSupervision=$canStart")
                when {
                    !runtimePrepared -> false
                    !AppSupervisionService.isRunning && !canStart -> {
                        Log.w("AppTriggerDebug", "Cannot start AppSupervisionService: prerequisites missing!")
                        prerequisitesMissing = true
                        true
                    }
                    else -> {
                        val ok = dispatchApplyAppSupervision(appRules, appTriggerRules)
                        Log.d("AppTriggerDebug", "dispatchApplyAppSupervision returns $ok")
                        ok
                    }
                }
            }
        }
        if (!globalCommandSucceeded || !appCommandSucceeded) {
            alarmScheduler.scheduleRetry()
            return SupervisionReconciliationResult.RuntimeCommandFailed
        }
        if (
            evaluation.active == null &&
            !monitorActive &&
            !MonitorService.isRunning
        ) {
            historyRecorder.closeGlobal(
                if (ownerResult is ScheduledOwnerReadResult.Available) {
                    SupervisionSessionEndReason.COMPLETED
                } else {
                    SupervisionSessionEndReason.CANCELLED
                }
            )
        }
        if (appRules.isEmpty() && !AppSupervisionService.isRunning) {
            historyRecorder.reconcileApps(emptyList())
        }
        if (prerequisitesMissing) {
            val activatedPlanNames = plans
                .filter { it.id in scheduledEnableBatch.activatedPlanIds }
                .map(SupervisionPlan::name)
            if (activatedPlanNames.isNotEmpty()) {
                scheduledEnableNotifier.notifyRuntimePrerequisitesMissing(activatedPlanNames)
            }
            alarmScheduler.scheduleRetry()
            return SupervisionReconciliationResult.RuntimePrerequisitesMissing
        }
        return SupervisionReconciliationResult.Completed(
            runtimeAction = runtimeAction,
            appRuntimeAction = appRuntimeAction,
            nextReconciliationAt = nextReconciliationAt,
            alarmPrecision = nextAlarmPrecision
        )
    }

    private fun dispatchStartScheduledMonitor(active: ActiveDeviceSupervision): Boolean {
        val configuration = active.plan.toScheduledCycleConfiguration()
        return dispatchMonitorCommand(
            Intent(appContext, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START_SCHEDULED_MONITOR
                putExtra(MonitorService.EXTRA_SCHEDULED_PLAN_ID, active.plan.id)
                putExtra(MonitorService.EXTRA_SCHEDULED_PLAN_NAME, active.plan.name)
                putExtra(
                    MonitorService.EXTRA_SCHEDULED_PLAN_UPDATED_AT,
                    active.plan.updatedAtEpochMillis
                )
                putExtra(
                    MonitorService.EXTRA_SCHEDULED_ACTIVE_UNTIL,
                    active.activeUntil.toEpochMilli()
                )
                putExtra(
                    MonitorService.EXTRA_SCHEDULED_USAGE_MINUTES,
                    configuration.usageMinutes
                )
                putExtra(
                    MonitorService.EXTRA_SCHEDULED_LOCK_MINUTES,
                    configuration.lockMinutes
                )
                putExtra(
                    MonitorService.EXTRA_SESSION_MODE,
                    configuration.sessionMode.storedValue
                )
            }
        )
    }

    private fun dispatchStopScheduledMonitor(): Boolean = dispatchMonitorCommand(
        Intent(appContext, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP_SCHEDULED_MONITOR
        }
    )

    private fun dispatchRestoreScheduledMonitor(): Boolean = dispatchMonitorCommand(
        Intent(appContext, MonitorService::class.java).apply {
            action = MonitorService.ACTION_RESTORE_SCHEDULED_MONITOR
        }
    )

    private fun dispatchMonitorCommand(intent: Intent): Boolean = try {
        if (MonitorService.isRunning) {
            appContext.startService(intent)
        } else {
            ContextCompat.startForegroundService(
                appContext,
                intent
            )
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun dispatchApplyAppSupervision(
        rules: List<AppSupervisionRule>,
        triggerRules: List<AppTriggerRule>
    ): Boolean = try {
        dispatchAppSupervisionCommand(
            Intent(appContext, AppSupervisionService::class.java).apply {
                action = AppSupervisionService.ACTION_APPLY_ACTIVE_RULES
                putExtra(
                    AppSupervisionService.EXTRA_ACTIVE_RULES,
                    AppSupervisionBinaryCodec.encodeRules(rules)
                )
                putExtra(
                    AppSupervisionService.EXTRA_TRIGGER_RULES,
                    AppSupervisionBinaryCodec.encodeTriggerRules(triggerRules)
                )
            }
        )
    } catch (_: RuntimeException) {
        false
    }

    private fun dispatchStopAppSupervision(): Boolean = dispatchAppSupervisionCommand(
        Intent(appContext, AppSupervisionService::class.java).apply {
            action = AppSupervisionService.ACTION_STOP_APP_SUPERVISION
        }
    )

    private fun dispatchAppSupervisionCommand(intent: Intent): Boolean = try {
        if (AppSupervisionService.isRunning) {
            appContext.startService(intent)
        } else {
            ContextCompat.startForegroundService(appContext, intent)
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun prepareAppRuntimeSnapshot(
        rules: List<AppSupervisionRule>,
        triggerRules: List<AppTriggerRule>,
        storedRuntime: AppSupervisionSnapshotReadResult,
        nowEpochMillis: Long
    ): Boolean {
        val snapshotDaily = (storedRuntime as? AppSupervisionSnapshotReadResult.Available)
            ?.snapshot
            ?.dailyUsageStates
            .orEmpty()
        val persistedDaily = when (val stored = appRuntimeStore.readDailyUsageStates()) {
            is com.example.controlfree.supervision.app.AppSupervisionDailyUsageReadResult.Available ->
                stored.states.filter { state -> rules.any(state::matches) }
            com.example.controlfree.supervision.app.AppSupervisionDailyUsageReadResult.None,
            com.example.controlfree.supervision.app.AppSupervisionDailyUsageReadResult.Corrupted ->
                snapshotDaily.filter { state -> rules.any(state::matches) }
        }
        return when (storedRuntime) {
            // 保留损坏快照，让 Service 在启动时识别损坏并建立保守休息状态。
            AppSupervisionSnapshotReadResult.Corrupted -> true
            AppSupervisionSnapshotReadResult.None -> appRuntimeStore.save(
                AppSupervisionRuntimeSnapshot(
                    rules = rules,
                    states = emptyList(),
                    savedAtEpochMillis = nowEpochMillis,
                    dailyUsageStates = persistedDaily,
                    triggerRules = triggerRules
                )
            )
            is AppSupervisionSnapshotReadResult.Available -> {
                val matchingStates = storedRuntime.snapshot.states.filter { state ->
                    rules.any(state::matches)
                }
                appRuntimeStore.save(
                    AppSupervisionRuntimeSnapshot(
                        rules = rules,
                        states = matchingStates,
                        savedAtEpochMillis = nowEpochMillis,
                        dailyUsageStates = persistedDaily,
                        triggerRules = triggerRules
                    )
                )
            }
        }
    }

    private companion object {
        val reconciliationMutex = Mutex()
    }
}

internal fun earliestScheduledEnableAt(
    plans: Collection<SupervisionPlan>,
    now: Instant
): Instant? = plans.asSequence()
    .mapNotNull(SupervisionPlan::scheduledEnableAtEpochMillis)
    .filter { it > now.toEpochMilli() }
    .minOrNull()
    ?.let(Instant::ofEpochMilli)

internal fun earliestInstant(first: Instant?, second: Instant?): Instant? = when {
    first == null -> second
    second == null -> first
    first <= second -> first
    else -> second
}

internal fun requiresRuntimePrerequisites(
    runtimeAction: ScheduledRuntimeAction,
    monitorActive: Boolean
): Boolean = runtimeAction == ScheduledRuntimeAction.START_OR_REPLACE && !monitorActive

internal data class ScheduledCycleConfiguration(
    val sessionMode: MonitorSessionMode,
    val usageMinutes: Int,
    val lockMinutes: Int
)

internal fun SupervisionPlan.toScheduledCycleConfiguration(): ScheduledCycleConfiguration =
    when (val cyclePolicy = policy) {
        is GlobalCyclePolicy -> ScheduledCycleConfiguration(
            sessionMode = MonitorSessionMode.SUPERVISION,
            usageMinutes = cyclePolicy.usageDuration.toMinutes().toInt(),
            lockMinutes = cyclePolicy.lockDuration.toMinutes().toInt()
        )
        is FocusCyclePolicy -> ScheduledCycleConfiguration(
            sessionMode = MonitorSessionMode.FOCUS,
            usageMinutes = cyclePolicy.playDuration.toMinutes().toInt(),
            lockMinutes = cyclePolicy.lockDuration.toMinutes().toInt()
        )
        is AppRulePolicy -> error("App 独立监督不能占用设备锁定通道")
    }

internal fun ActiveAppSupervision.toRuntimeRule(): AppSupervisionRule {
    val policy = plan.policy as AppRulePolicy
    return AppSupervisionRule(
        planId = plan.id,
        planUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
        planName = plan.name,
        packageName = policy.packageName,
        occurrenceEndEpochMillis = activeUntil.toEpochMilli(),
        usageAllowanceMillis = policy.usageAllowance.toMillis(),
        restDurationMillis = policy.restDuration.toMillis(),
        dailyUsageLimitMillis = policy.dailyUsageLimit.toMillis(),
        dailyUsageDateEpochDay = dailyUsageDateEpochDay,
        dailyUsageResetAtEpochMillis = dailyUsageResetAt.toEpochMilli(),
        isExecutionWindowActive = isExecutionWindowActive,
        cycleOccurrenceEndEpochMillis = if (isExecutionWindowActive) {
            cycleActiveUntil.toEpochMilli()
        } else {
            0L
        },
        disabledUntilEpochMillis = disabledUntil?.toEpochMilli() ?: 0L
    )
}

internal fun decideAppSupervisionRuntimeAction(
    activeRules: List<AppSupervisionRule>,
    serviceRunning: Boolean,
    storedRuntime: AppSupervisionSnapshotReadResult,
    triggerRules: List<AppTriggerRule> = emptyList()
): AppSupervisionRuntimeAction = when {
    (activeRules.isNotEmpty() || triggerRules.isNotEmpty()) &&
        serviceRunning &&
        storedRuntime is AppSupervisionSnapshotReadResult.Available &&
        storedRuntime.snapshot.rules == activeRules &&
        storedRuntime.snapshot.triggerRules == triggerRules -> AppSupervisionRuntimeAction.KEEP
    activeRules.isNotEmpty() || triggerRules.isNotEmpty() -> AppSupervisionRuntimeAction.APPLY
    serviceRunning -> AppSupervisionRuntimeAction.STOP
    storedRuntime != AppSupervisionSnapshotReadResult.None ->
        AppSupervisionRuntimeAction.CLEAR_STALE
    else -> AppSupervisionRuntimeAction.KEEP
}
