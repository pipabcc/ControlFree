package com.example.controlfree

import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import com.example.controlfree.supervision.runtime.ScheduledOccurrenceSuppression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorStopPolicyTest {
    @Test
    fun `锁定动作请求在三十秒边界内有效且超时一毫秒即失效`() {
        val requestedAt = 100_000L

        assertTrue(isFreshLockActionRequest(requestedAt, requestedAt))
        assertTrue(isFreshLockActionRequest(requestedAt, requestedAt + 30_000L))
        assertFalse(isFreshLockActionRequest(requestedAt, requestedAt + 30_001L))
    }

    @Test
    fun `锁定动作请求拒绝未来时间负时间和非法有效期`() {
        assertFalse(isFreshLockActionRequest(-1L, 100_000L))
        assertFalse(isFreshLockActionRequest(100_001L, 100_000L))
        assertFalse(
            isFreshLockActionRequest(
                requestedAtElapsedMillis = 100_000L,
                nowElapsedMillis = 100_000L,
                maximumAgeMillis = -1L
            )
        )
        assertTrue(
            isFreshLockActionRequest(
                requestedAtElapsedMillis = 100_000L,
                nowElapsedMillis = 100_000L,
                maximumAgeMillis = 0L
            )
        )
        assertFalse(
            isFreshLockActionRequest(
                requestedAtElapsedMillis = 100_000L,
                nowElapsedMillis = 100_001L,
                maximumAgeMillis = 0L
            )
        )
    }

    @Test
    fun `运行中的会话模式优先于尚未完成的持久化值`() {
        assertTrue(
            resolveStopSessionMode(
                hasActiveRuntimeMonitor = true,
                runtimeSessionMode = MonitorSessionMode.FOCUS,
                persistedSessionMode = MonitorSessionMode.SUPERVISION
            ) == MonitorSessionMode.FOCUS
        )
        assertTrue(
            resolveStopSessionMode(
                hasActiveRuntimeMonitor = false,
                runtimeSessionMode = MonitorSessionMode.SUPERVISION,
                persistedSessionMode = MonitorSessionMode.FOCUS
            ) == MonitorSessionMode.FOCUS
        )
    }

    @Test
    fun `应急解锁后普通定时监督重新调度而专注避免立即重锁`() {
        assertFalse(
            shouldSuppressScheduledOccurrence(
                MonitorService.ACTION_FORCE_UNLOCK,
                MonitorSessionMode.SUPERVISION
            )
        )
        assertTrue(
            shouldSuppressScheduledOccurrence(
                MonitorService.ACTION_FORCE_UNLOCK,
                MonitorSessionMode.FOCUS
            )
        )
    }

    @Test
    fun `普通停止抑制当前周期而计划边界停止不抑制`() {
        assertTrue(
            shouldSuppressScheduledOccurrence(
                MonitorService.ACTION_STOP_MONITOR,
                MonitorSessionMode.SUPERVISION
            )
        )
        assertFalse(
            shouldSuppressScheduledOccurrence(
                MonitorService.ACTION_STOP_SCHEDULED_MONITOR,
                MonitorSessionMode.SUPERVISION
            )
        )
    }

    @Test
    fun `普通终止和应急解锁都会关闭对应的定时专注计划`() {
        val owner = owner()

        assertTrue(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_STOP_MONITOR,
                MonitorSessionMode.FOCUS,
                owner
            )
        )
        assertTrue(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_FORCE_UNLOCK,
                MonitorSessionMode.FOCUS,
                owner
            )
        )
        assertFalse(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_STOP_MONITOR,
                MonitorSessionMode.SUPERVISION,
                owner
            )
        )
        assertFalse(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_FORCE_UNLOCK,
                MonitorSessionMode.SUPERVISION,
                owner
            )
        )
        assertFalse(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_STOP_MONITOR,
                MonitorSessionMode.FOCUS,
                null
            )
        )
        assertFalse(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_FORCE_UNLOCK,
                MonitorSessionMode.FOCUS,
                null
            )
        )
        assertFalse(
            shouldDisableScheduledFocusPlan(
                MonitorService.ACTION_STOP_SCHEDULED_MONITOR,
                MonitorSessionMode.FOCUS,
                owner
            )
        )
    }

    @Test
    fun `已抑制的旧定时启动命令会被拒绝`() {
        val owner = owner()
        val suppression = ScheduledOccurrenceSuppression(
            planId = owner.planId,
            planUpdatedAtEpochMillis = owner.planUpdatedAtEpochMillis,
            suppressUntilEpochMillis = owner.activeUntilEpochMillis
        )

        assertTrue(isScheduledStartSuppressed(owner, suppression, nowEpochMillis = 1_500L))
        assertFalse(isScheduledStartSuppressed(owner, suppression, nowEpochMillis = 2_000L))
        assertFalse(
            isScheduledStartSuppressed(
                owner.copy(planUpdatedAtEpochMillis = 2L),
                suppression,
                nowEpochMillis = 1_500L
            )
        )
    }

    @Test
    fun `锁定界面优先由Activity接管且仅在超时后回退悬浮层`() {
        assertTrue(
            shouldAttemptPrimaryLockActivity(
                isInteractive = true,
                isActivityVisible = false,
                isOverlayVisible = false,
                isLaunchPending = false,
                mustUseOverlayFallback = false
            )
        )
        assertFalse(
            shouldAttemptPrimaryLockActivity(
                isInteractive = true,
                isActivityVisible = false,
                isOverlayVisible = false,
                isLaunchPending = false,
                mustUseOverlayFallback = true
            )
        )
        assertFalse(
            shouldAttemptPrimaryLockActivity(
                isInteractive = false,
                isActivityVisible = false,
                isOverlayVisible = false,
                isLaunchPending = false,
                mustUseOverlayFallback = false
            )
        )
    }

    @Test
    fun `锁定结束必须先持久化再发布玩机状态`() {
        val lock = MonitorCycleSnapshot(MonitorPhase.LOCK, 1_000L, 10L, 1, true)
        val usage = lock.copy(phase = MonitorPhase.USAGE, remainingMillis = 600_000L)
        val nextLock = usage.copy(phase = MonitorPhase.LOCK, remainingMillis = 300_000L)

        assertTrue(shouldPersistBeforePublishingPhaseTransition(lock, usage))
        assertFalse(shouldPersistBeforePublishingPhaseTransition(usage, nextLock))
        assertFalse(shouldPersistBeforePublishingPhaseTransition(lock, lock))
    }

    @Test
    fun `恢复保护保留已落盘锁定剩余时间而不是重置完整时长`() {
        val savedLock = MonitorCycleSnapshot(MonitorPhase.LOCK, 60_000L, 10L, 1, true)
        val savedUsage = savedLock.copy(phase = MonitorPhase.USAGE)

        assertEquals(60_000L, retainedLockSnapshotForRecovery(savedLock)?.remainingMillis)
        assertEquals(1L, retainedLockSnapshotForRecovery(savedLock.copy(remainingMillis = 0L))?.remainingMillis)
        assertEquals(null, retainedLockSnapshotForRecovery(savedUsage))
    }

    @Test
    fun `同一锁定会话只附加一次全屏恢复入口`() {
        assertTrue(
            shouldAttachFallbackFullScreenIntent(
                sessionId = 11L,
                dispatchedSessionId = NO_LOCK_SESSION,
                canUseFullScreenIntent = true
            )
        )
        assertFalse(
            shouldAttachFallbackFullScreenIntent(
                sessionId = 11L,
                dispatchedSessionId = 11L,
                canUseFullScreenIntent = true
            )
        )
        assertFalse(
            shouldAttachFallbackFullScreenIntent(
                sessionId = 11L,
                dispatchedSessionId = NO_LOCK_SESSION,
                canUseFullScreenIntent = false
            )
        )
    }

    @Test
    fun `备用锁页Activity同一会话最多尝试两次并按间隔限流`() {
        assertTrue(
            shouldAttemptFallbackActivity(
                sessionId = 11L,
                attemptSessionId = NO_LOCK_SESSION,
                attemptCount = 2,
                lastAttemptElapsedMillis = 99_000L,
                nowElapsedMillis = 99_000L
            )
        )
        assertFalse(
            shouldAttemptFallbackActivity(
                sessionId = 11L,
                attemptSessionId = 11L,
                attemptCount = 2,
                lastAttemptElapsedMillis = 90_000L,
                nowElapsedMillis = 100_000L
            )
        )
        assertFalse(
            shouldAttemptFallbackActivity(
                sessionId = 11L,
                attemptSessionId = 11L,
                attemptCount = 1,
                lastAttemptElapsedMillis = 100_000L,
                nowElapsedMillis = 101_000L
            )
        )
        assertTrue(
            shouldAttemptFallbackActivity(
                sessionId = 11L,
                attemptSessionId = 11L,
                attemptCount = 1,
                lastAttemptElapsedMillis = 100_000L,
                nowElapsedMillis = 102_000L
            )
        )
    }

    @Test
    fun `服务销毁且恢复阶段未知时按锁定交接而不是误解锁`() {
        assertTrue(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = true,
                recoveryGuardRequiresLock = false,
                recoveryPhase = null
            )
        )
        assertTrue(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = true,
                recoveryGuardRequiresLock = true,
                recoveryPhase = MonitorPhase.USAGE
            )
        )
        assertFalse(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = true,
                recoveryGuardRequiresLock = false,
                recoveryPhase = MonitorPhase.USAGE
            )
        )
        assertFalse(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = true,
                recoveryGuardRequiresLock = false,
                recoveryPhase = MonitorPhase.LOCK,
                pauseActive = true
            )
        )
        assertTrue(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = true,
                recoveryGuardRequiresLock = true,
                recoveryPhase = MonitorPhase.LOCK,
                pauseActive = true
            )
        )
        assertFalse(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = false,
                recoveryGuardRequiresLock = true,
                recoveryPhase = MonitorPhase.LOCK
            )
        )
        assertFalse(
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = true,
                recoveryGuardRequiresLock = true,
                recoveryPhase = MonitorPhase.LOCK,
                hasTimedLockState = false
            )
        )
    }

    private fun owner() = ScheduledMonitorOwner(
        planId = "focus-plan",
        planUpdatedAtEpochMillis = 1L,
        activeUntilEpochMillis = 2_000L
    )

    @Test
    fun `不需要交接锁层时必须彻底结束运行时会话`() {
        // 只标记"运行时不可用"会留下没有期限的僵尸真值，用户只能重启手机才能清除。
        assertEquals(
            RuntimeSessionDisposal.END,
            runtimeSessionDisposalOnDestroy(shouldHandOffLockSurface = false)
        )
    }

    @Test
    fun `需要交接锁层时保留运行时会话`() {
        assertEquals(
            RuntimeSessionDisposal.RETAIN,
            runtimeSessionDisposalOnDestroy(shouldHandOffLockSurface = true)
        )
    }

    @Test
    fun `恢复保护覆盖玩机阶段时不能把玩机额度当锁定时长`() {
        assertEquals(
            300,
            retainedHandoffRemainingSeconds(
                recoveryGuardRequiresLock = true,
                recoveryPhase = MonitorPhase.USAGE,
                observedRemainingSeconds = 1_800,
                configuredLockSeconds = 300
            )
        )
        assertEquals(
            42,
            retainedHandoffRemainingSeconds(
                recoveryGuardRequiresLock = true,
                recoveryPhase = MonitorPhase.LOCK,
                observedRemainingSeconds = 42,
                configuredLockSeconds = 300
            )
        )
    }

    @Test
    fun `落盘连续失败达到次数上限后强制结束锁定`() {
        assertTrue(
            shouldForceLockCompletionWithoutPersistence(
                failureCount = MAX_LOCK_COMPLETION_PERSIST_FAILURES,
                firstFailureElapsedMillis = 1_000L,
                nowElapsedMillis = 2_000L
            )
        )
    }

    @Test
    fun `落盘失败超过等待上限后强制结束锁定`() {
        assertTrue(
            shouldForceLockCompletionWithoutPersistence(
                failureCount = 1,
                firstFailureElapsedMillis = 1_000L,
                nowElapsedMillis = 1_000L + MAX_LOCK_COMPLETION_PERSIST_WAIT_MILLIS
            )
        )
    }

    @Test
    fun `落盘失败未达上限时继续等待磁盘确认`() {
        assertFalse(
            shouldForceLockCompletionWithoutPersistence(
                failureCount = 1,
                firstFailureElapsedMillis = 1_000L,
                nowElapsedMillis = 2_000L
            )
        )
    }

    @Test
    fun `没有失败记录时不会强制结束锁定`() {
        assertFalse(
            shouldForceLockCompletionWithoutPersistence(
                failureCount = 0,
                firstFailureElapsedMillis = 0L,
                nowElapsedMillis = 10_000_000L
            )
        )
    }

    @Test
    fun `锁动作过渡期间普通快照必须延迟`() {
        assertTrue(shouldDeferSnapshotPersistence(isLockActionTransitionInFlight = true))
        assertFalse(shouldDeferSnapshotPersistence(isLockActionTransitionInFlight = false))
    }

    @Test
    fun `合并后的其他快照不能冒充原请求成功`() {
        val requested = MonitorCycleSnapshot(
            phase = MonitorPhase.LOCK,
            remainingMillis = 60_000L,
            checkpointElapsedMillis = 1_000L,
            bootCount = 7,
            isInteractive = false
        )
        val mergedResult = MonitorSnapshotPersistResult(
            snapshot = requested.copy(phase = MonitorPhase.USAGE),
            pauseState = null,
            guardArmed = true,
            progressSaved = true,
            guardCleared = true
        )

        assertFalse(
            isSnapshotPersistResultForRequest(
                result = mergedResult,
                requestedSnapshot = requested,
                requestedPauseState = null
            )
        )
        assertTrue(
            isSnapshotPersistResultForRequest(
                result = mergedResult.copy(snapshot = requested),
                requestedSnapshot = requested,
                requestedPauseState = null
            )
        )
    }

    @Test
    fun `停止清理事务只能有一个 owner 和一个 worker`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val firstOwner = Any()
        val secondOwner = Any()
        val worker = Any()

        val started = coordinator.begin(firstOwner, worker, startedElapsedMillis = 1_000L)

        assertTrue(coordinator.hasPending())
        assertEquals(worker, coordinator.pendingWorker())
        assertEquals(1_000L, started?.startedElapsedMillis)
        assertNull(coordinator.begin(secondOwner, Any(), startedElapsedMillis = 2_000L))
    }

    @Test
    fun `新 Service 接管停止清理时保留 generation 截止时间和 worker`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val originalOwner = Any()
        val replacementOwner = Any()
        val worker = Any()
        val started = requireNotNull(
            coordinator.begin(originalOwner, worker, startedElapsedMillis = 1_000L)
        )

        val adopted = requireNotNull(coordinator.adopt(replacementOwner))

        assertEquals(started.generation, adopted.generation)
        assertEquals(started.startedElapsedMillis, adopted.startedElapsedMillis)
        assertEquals(worker, adopted.worker)
        assertFalse(adopted.resultReady)
        assertTrue(coordinator.isOwnedBy(adopted.generation, replacementOwner))
        assertFalse(coordinator.isOwnedBy(adopted.generation, originalOwner))
    }

    @Test
    fun `主线程回调丢失后接管实例仍可消费已登记结果且只能消费一次`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val worker = Any()
        val started = requireNotNull(
            coordinator.begin(Any(), worker, startedElapsedMillis = 1_000L)
        )
        val result = MonitorStateClearResult(progressCleared = true, guardCleared = true)
        assertTrue(coordinator.recordResult(started.generation, result))

        val adopted = requireNotNull(coordinator.adopt(Any()))
        val claim = requireNotNull(coordinator.claimResult(adopted.generation))

        assertTrue(adopted.resultReady)
        assertEquals(worker, claim.worker)
        assertEquals(result, claim.result)
        assertNull(coordinator.claimResult(adopted.generation))
        assertFalse(coordinator.hasPending())
    }

    @Test
    fun `旧 generation 的迟到结果不能污染当前停止事务`() {
        val coordinator = ProcessStopCoordinator<Any>()
        val first = requireNotNull(coordinator.begin(Any(), Any(), 1_000L))
        assertTrue(coordinator.cancel(first.generation) != null)
        val secondWorker = Any()
        val second = requireNotNull(coordinator.begin(Any(), secondWorker, 2_000L))

        val staleRecorded = coordinator.recordResult(
            first.generation,
            MonitorStateClearResult(progressCleared = true, guardCleared = true)
        )

        assertFalse(staleRecorded)
        assertTrue(coordinator.isPending(second.generation))
        assertEquals(secondWorker, coordinator.pendingWorker())
    }

    @Test
    fun `持久化真值处于玩机阶段时启动失败不得重建锁定`() {
        // 凭空重建满时长锁定正是"暂停或跳过后 1 分钟变 10 分钟"的来源。
        assertFalse(
            shouldRebuildLockAfterRuntimeFailure(
                currentPhase = MonitorPhase.USAGE,
                persistedPhase = MonitorPhase.USAGE,
                recoveryGuardRequiresLock = true
            )
        )
    }

    @Test
    fun `持久化真值仍在锁定阶段时启动失败允许重建锁定`() {
        assertTrue(
            shouldRebuildLockAfterRuntimeFailure(
                currentPhase = MonitorPhase.USAGE,
                persistedPhase = MonitorPhase.LOCK,
                recoveryGuardRequiresLock = true
            )
        )
    }

    @Test
    fun `恢复保护未要求锁定时启动失败不重建锁定`() {
        assertFalse(
            shouldRebuildLockAfterRuntimeFailure(
                currentPhase = MonitorPhase.USAGE,
                persistedPhase = MonitorPhase.LOCK,
                recoveryGuardRequiresLock = false
            )
        )
    }

    @Test
    fun `当前已在锁定阶段时无需重建`() {
        assertFalse(
            shouldRebuildLockAfterRuntimeFailure(
                currentPhase = MonitorPhase.LOCK,
                persistedPhase = MonitorPhase.LOCK,
                recoveryGuardRequiresLock = true
            )
        )
    }
}
