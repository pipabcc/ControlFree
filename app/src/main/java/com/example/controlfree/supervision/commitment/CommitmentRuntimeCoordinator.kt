package com.example.controlfree.supervision.commitment

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.controlfree.AppSupervisionService
import com.example.controlfree.supervision.runtime.SupervisionAlarmPrecision
import com.example.controlfree.todo.TodoRepository
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CommitmentRuntimeAction {
    NONE,
    RECONCILE_RUNNING,
    START_OR_RECONCILE
}

data class CommitmentReconciliationResult(
    val runtimeAction: CommitmentRuntimeAction,
    val hasActiveWindow: Boolean,
    val nextBoundaryEpochMillis: Long?,
    val alarmPrecision: SupervisionAlarmPrecision?
)

class CommitmentRuntimeCoordinator(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val appContext = context.applicationContext
    private val repository = TodoRepository.getInstance(appContext)
    private val alarmScheduler = CommitmentAlarmScheduler(appContext)

    suspend fun reconcile(): CommitmentReconciliationResult = mutex.withLock {
        val now = clock.millis().coerceAtLeast(0L)
        repository.reconcileCommitmentOccurrences(now)
        val snapshot = repository.getCommitmentRuntimeSnapshot()
        val hasActiveWindow = CommitmentRuntimePolicy.hasActiveWindow(snapshot, now)
        val nextBoundary = CommitmentRuntimePolicy.nextBoundaryEpochMillis(snapshot, now)
        alarmScheduler.cancelRetry()
        val precision = nextBoundary?.let(alarmScheduler::schedule) ?: run {
            alarmScheduler.cancelBoundary()
            null
        }
        val action = decideCommitmentRuntimeAction(
            hasActiveWindow = hasActiveWindow,
            serviceRunning = AppSupervisionService.isRunning
        )
        if (action != CommitmentRuntimeAction.NONE && !dispatchReconciliation()) {
            alarmScheduler.scheduleRetry()
            throw IllegalStateException("无法启动防拖延运行时")
        }
        CommitmentReconciliationResult(
            runtimeAction = action,
            hasActiveWindow = hasActiveWindow,
            nextBoundaryEpochMillis = nextBoundary,
            alarmPrecision = precision
        )
    }

    private fun dispatchReconciliation(): Boolean = try {
        val intent = Intent(appContext, AppSupervisionService::class.java).apply {
            action = AppSupervisionService.ACTION_RECONCILE_COMMITMENTS
        }
        if (AppSupervisionService.isRunning) {
            appContext.startService(intent)
        } else {
            ContextCompat.startForegroundService(appContext, intent)
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        val mutex = Mutex()
    }
}

internal fun decideCommitmentRuntimeAction(
    hasActiveWindow: Boolean,
    serviceRunning: Boolean
): CommitmentRuntimeAction = when {
    hasActiveWindow -> CommitmentRuntimeAction.START_OR_RECONCILE
    serviceRunning -> CommitmentRuntimeAction.RECONCILE_RUNNING
    else -> CommitmentRuntimeAction.NONE
}
