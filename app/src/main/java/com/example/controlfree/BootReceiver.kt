package com.example.controlfree

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.example.controlfree.boot.BootRecoveryContract
import com.example.controlfree.boot.BootRecoveryHintStore
import com.example.controlfree.data.MonitorRecoveryGuard
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.diagnostics.BootEvidenceStore
import com.example.controlfree.diagnostics.AndroidDiagnostics
import com.example.controlfree.diagnostics.DiagnosticEventType
import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import com.example.controlfree.supervision.runtime.ScheduledOwnerReadResult
import com.example.controlfree.supervision.runtime.SupervisionAlarmScheduler
import com.example.controlfree.supervision.runtime.SupervisionReconciliationResult
import com.example.controlfree.supervision.runtime.SupervisionScheduleCoordinator
import com.example.controlfree.supervision.app.AppSupervisionRuntimeStore
import com.example.controlfree.supervision.app.AppSupervisionSnapshotReadResult
import com.example.controlfree.supervision.persistence.SupervisionHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!MonitorRecoveryBroadcastPolicy.isRecoveryAction(action)) return
        val appContext = context.applicationContext
        if (
            MonitorRecoveryBroadcastPolicy.mustWaitForUserUnlock(action) ||
            !isUserUnlocked(appContext)
        ) {
            // 监督快照和计划数据库位于凭据加密存储。锁定启动阶段不能读取它们，
            // USER_UNLOCKED 或 BOOT_COMPLETED 到达后会进入同一个幂等恢复入口。
            return
        }

        val isRetryOnly = MonitorRecoveryBroadcastPolicy.isRetryOnlyAction(action)
        if (!isRetryOnly) {
            AndroidDiagnostics.record(appContext, DiagnosticEventType.BOOT_RECOVERY_RECEIVED)
        }
        var currentBootCount = BootEvidenceStore.UNKNOWN_BOOT_COUNT
        if (MonitorRecoveryBroadcastPolicy.recordsBootEvidence(action)) {
            try {
                val evidenceStore = BootEvidenceStore(appContext)
                evidenceStore.recordBootCompleted()
                currentBootCount =
                    evidenceStore.read()?.bootCount ?: BootEvidenceStore.UNKNOWN_BOOT_COUNT
            } catch (_: RuntimeException) {
                // 自检证据写入失败不能阻断监督恢复。
            }
        }
        val preferences = PreferenceManager(appContext)
        val scheduledOwner = (
            preferences.inspectScheduledMonitorOwner() as? ScheduledOwnerReadResult.Available
            )?.owner
        val monitorRecoveryRequired =
            runCatching { preferences.isMonitorActive() }.getOrDefault(true) ||
                scheduledOwner != null
        val appSupervisionRecoveryRequired = hasPersistedAppSupervision(appContext)
        BootRecoveryHintStore(appContext).replaceRuntimeHints(
            monitorRecoveryRequired = monitorRecoveryRequired,
            appSupervisionRecoveryRequired = appSupervisionRecoveryRequired
        )

        if (isRetryOnly) {
            restoreRuntimeOnDirectBootRetry(
                context = appContext,
                action = action,
                preferences = preferences,
                scheduledOwner = scheduledOwner
            )
            return
        }

        launchAsyncRecoveryWork(
            context = appContext,
            scheduledOwner = scheduledOwner,
            recordBootHistory = MonitorRecoveryBroadcastPolicy.recordsBootEvidence(action),
            bootCount = currentBootCount,
            recoveryExpected =
                monitorRecoveryRequired || appSupervisionRecoveryRequired
        )
        if (scheduledOwner != null) {
            // 定时监督必须先按当前墙钟和计划范围校准，不能直接套用普通监督恢复。
            return
        }
        val recoveryGuard = MonitorRecoveryGuard(appContext)
        MonitorRecoveryBroadcastCoordinator(
            isMonitorActive = preferences::isMonitorActive,
            requiresRecoveryLock = recoveryGuard::requiresLock,
            startMonitorService = {
                startMonitorService(
                    appContext,
                    Intent(appContext, MonitorService::class.java).setAction(action)
                )
            },
            armRecoveryLock = { recoveryGuard.markLockRequired() },
            isMonitorServiceRunning = { MonitorService.isRunning }
        ).handle(action)
    }

    private fun launchAsyncRecoveryWork(
        context: Context,
        scheduledOwner: ScheduledMonitorOwner?,
        recordBootHistory: Boolean,
        bootCount: Int,
        recoveryExpected: Boolean
    ) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                supervisorScope {
                    launch {
                        reconcileScheduledPlans(context, scheduledOwner)
                    }
                    launch {
                        restoreReminderAlarms(context)
                    }
                    if (recordBootHistory) {
                        launch {
                            recordDeviceBootHistory(
                                context = context,
                                bootCount = bootCount,
                                recoveryExpected = recoveryExpected
                            )
                        }
                    }
                }
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: RuntimeException) {
                    // 系统已回收 PendingResult 时无需影响后续恢复入口。
                }
            }
        }
    }

    private suspend fun reconcileScheduledPlans(
        context: Context,
        persistedOwner: ScheduledMonitorOwner?
    ) {
        try {
            val result = withTimeout(SCHEDULE_RECONCILIATION_TIMEOUT_MILLIS) {
                SupervisionScheduleCoordinator(context.applicationContext).reconcile()
            }
            if (requiresPersistedBootFallback(result)) {
                restoreScheduledMonitorAfterReconciliationFailure(context, persistedOwner)
            }
        } catch (_: TimeoutCancellationException) {
            restoreScheduledMonitorAfterReconciliationFailure(context, persistedOwner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            restoreScheduledMonitorAfterReconciliationFailure(context, persistedOwner)
        }
    }

    private fun restoreRuntimeOnDirectBootRetry(
        context: Context,
        action: String?,
        preferences: PreferenceManager,
        scheduledOwner: ScheduledMonitorOwner?
    ) {
        if (scheduledOwner != null) {
            restoreScheduledMonitorAfterReconciliationFailure(context, scheduledOwner)
            return
        }
        try {
            restorePersistedAppSupervision(context)
        } catch (_: RuntimeException) {
            AndroidDiagnostics.record(context, DiagnosticEventType.BOOT_RECOVERY_START_FAILED)
        }
        val recoveryGuard = MonitorRecoveryGuard(context)
        MonitorRecoveryBroadcastCoordinator(
            isMonitorActive = preferences::isMonitorActive,
            requiresRecoveryLock = recoveryGuard::requiresLock,
            startMonitorService = {
                startMonitorService(
                    context,
                    Intent(context, MonitorService::class.java).setAction(action)
                )
            },
            armRecoveryLock = { recoveryGuard.markLockRequired() },
            isMonitorServiceRunning = { MonitorService.isRunning }
        ).handle(action)
    }

    private fun restoreScheduledMonitorAfterReconciliationFailure(
        context: Context,
        persistedOwner: ScheduledMonitorOwner?
    ) {
        if (persistedOwner != null) {
            val preferences = PreferenceManager(context)
            val monitorActive = try {
                preferences.isMonitorActive()
            } catch (_: RuntimeException) {
                // 已成功读取定时来源但活动位暂时不可读时采用失败安全恢复。
                true
            }
            val shouldRestore = shouldRestorePersistedScheduledMonitor(
                monitorActive = monitorActive,
                serviceRunning = MonitorService.isRunning,
                nowEpochMillis = System.currentTimeMillis(),
                activeUntilEpochMillis = persistedOwner.activeUntilEpochMillis
            )
            if (shouldRestore) {
                try {
                    startMonitorService(
                        context,
                        Intent(context, MonitorService::class.java).apply {
                            action = MonitorService.ACTION_RESTORE_SCHEDULED_MONITOR
                        }
                    )
                } catch (_: RuntimeException) {
                    // 启动受限时保持默认锁定；用户解锁、系统重试或打开 App 后会再次恢复。
                    try {
                        MonitorRecoveryGuard(context).markLockRequired()
                    } catch (_: RuntimeException) {
                        // 主快照仍保持活动状态，后续入口仍可再次恢复。
                    }
                }
            }
        }
        try {
            restorePersistedAppSupervision(context)
        } catch (_: RuntimeException) {
            AndroidDiagnostics.record(context, DiagnosticEventType.BOOT_RECOVERY_START_FAILED)
        }
        try {
            SupervisionAlarmScheduler(context.applicationContext).scheduleRetry()
        } catch (_: RuntimeException) {
            // 打开 App 或后续系统广播仍会再次校准。
        }
    }

    private fun startMonitorService(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: RuntimeException) {
            AndroidDiagnostics.record(context, DiagnosticEventType.BOOT_RECOVERY_START_FAILED)
            throw error
        }
    }

    private fun isUserUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
    } catch (_: RuntimeException) {
        // 无法读取用户状态时交给后续恢复逻辑按失败安全路径处理。
        true
    }

    private fun restorePersistedAppSupervision(context: Context) {
        if (AppSupervisionService.isRunning) return
        val now = System.currentTimeMillis()
        val stored = AppSupervisionRuntimeStore(context).read()
        val hasActiveRules = stored is AppSupervisionSnapshotReadResult.Available &&
            (
                stored.snapshot.rules.any { rule -> now < rule.occurrenceEndEpochMillis } ||
                    stored.snapshot.triggerRules.any { rule -> now < rule.occurrenceEndEpochMillis }
                )
        if (!hasActiveRules) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AppSupervisionService::class.java)
            )
        } catch (_: RuntimeException) {
            AndroidDiagnostics.record(
                context,
                DiagnosticEventType.BOOT_RECOVERY_START_FAILED
            )
        }
    }

    private fun hasPersistedAppSupervision(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return try {
            val stored = AppSupervisionRuntimeStore(context).read()
            stored is AppSupervisionSnapshotReadResult.Available &&
                (
                    stored.snapshot.rules.any { rule -> now < rule.occurrenceEndEpochMillis } ||
                        stored.snapshot.triggerRules.any { rule ->
                            now < rule.occurrenceEndEpochMillis
                        }
                    )
        } catch (_: RuntimeException) {
            true
        }
    }

    private suspend fun recordDeviceBootHistory(
        context: Context,
        bootCount: Int,
        recoveryExpected: Boolean
    ) {
        try {
            val receivedAt = System.currentTimeMillis().coerceAtLeast(0L)
            val estimatedBootAt =
                (receivedAt - SystemClock.elapsedRealtime()).coerceIn(0L, receivedAt)
            SupervisionHistoryRepository.getInstance(context).recordDeviceBoot(
                bootCount = bootCount,
                occurredAtEpochMillis = estimatedBootAt,
                receivedAtEpochMillis = receivedAt,
                recoveryExpected = recoveryExpected
            )
        } catch (_: RuntimeException) {
            // 历史记录失败不能延迟或阻断监督恢复。
        }
    }

    private suspend fun restoreReminderAlarms(context: Context) {
        try {
            withTimeout(REMINDER_RECONCILIATION_TIMEOUT_MILLIS) {
                val repository = com.example.controlfree.todo.TodoRepository.getInstance(context)
                val scheduler = com.example.controlfree.todo.UnifiedReminderAlarmScheduler(context)

                // 1. 恢复待办提醒
                val activeTodos = repository.observeAllTodos().first().filter { !it.isCompleted }
                activeTodos.forEach { todo ->
                    scheduler.scheduleTodoReminders(todo)
                }

                // 2. 恢复时刻提醒
                val anniversaries = repository.observeAllAnniversaries().first()
                anniversaries.forEach { anniversary ->
                    scheduler.scheduleAnniversaryReminders(anniversary)
                }

                // 3. 恢复习惯提醒
                val habits = repository.observeActiveHabits().first()
                habits.forEach { habit ->
                    scheduler.scheduleHabitReminders(habit)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 提醒恢复失败不得阻断监督运行时恢复。
        }
    }

    private companion object {
        const val SCHEDULE_RECONCILIATION_TIMEOUT_MILLIS = 8_000L
        const val REMINDER_RECONCILIATION_TIMEOUT_MILLIS = 8_000L
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal object MonitorRecoveryBroadcastPolicy {
    fun isRecoveryAction(action: String?): Boolean = when (action) {
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_USER_UNLOCKED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY,
        BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY_RETRY -> true
        else -> false
    }

    fun mustWaitForUserUnlock(action: String?): Boolean =
        action == Intent.ACTION_LOCKED_BOOT_COMPLETED

    fun recordsBootEvidence(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY

    fun isRetryOnlyAction(action: String?): Boolean =
        action == BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY_RETRY
}

internal fun requiresPersistedBootFallback(result: SupervisionReconciliationResult): Boolean =
    result == SupervisionReconciliationResult.PlanStorageUnavailable ||
        result is SupervisionReconciliationResult.ConflictingPlans ||
        result == SupervisionReconciliationResult.RuntimeCommandFailed ||
        result == SupervisionReconciliationResult.RuntimePrerequisitesMissing

internal fun shouldRestorePersistedScheduledMonitor(
    monitorActive: Boolean,
    serviceRunning: Boolean,
    nowEpochMillis: Long,
    activeUntilEpochMillis: Long
): Boolean = monitorActive && !serviceRunning && nowEpochMillis < activeUntilEpochMillis

internal class MonitorRecoveryBroadcastCoordinator(
    private val isMonitorActive: () -> Boolean,
    private val requiresRecoveryLock: () -> Boolean,
    private val startMonitorService: () -> Unit,
    private val armRecoveryLock: () -> Unit,
    private val isMonitorServiceRunning: () -> Boolean = { false }
) {
    fun handle(action: String?) {
        if (
            !MonitorRecoveryBroadcastPolicy.isRecoveryAction(action) ||
            MonitorRecoveryBroadcastPolicy.mustWaitForUserUnlock(action)
        ) {
            return
        }
        val recoveryRequired = try {
            isMonitorActive() || requiresRecoveryLock()
        } catch (_: RuntimeException) {
            // 无法读取状态时按监督仍活动处理，禁止恢复广播静默失效。
            true
        }
        if (!recoveryRequired) return
        val serviceRunning = try {
            isMonitorServiceRunning()
        } catch (_: RuntimeException) {
            // 无法确认进程内状态时允许幂等启动命令，由 Service 读取权威快照恢复。
            false
        }
        if (serviceRunning) return

        try {
            startMonitorService()
        } catch (_: RuntimeException) {
            // 启动被厂商限制时保留独立锁定标记；用户再次打开 App 后默认锁定恢复。
            try {
                armRecoveryLock()
            } catch (_: RuntimeException) {
                // 独立标记自身也失败时没有可安全继续的后台动作，保留原活动偏好供下次恢复。
            }
        }
    }
}
