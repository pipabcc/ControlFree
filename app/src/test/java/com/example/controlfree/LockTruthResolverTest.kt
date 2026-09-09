package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTruthResolverTest {
    @Test
    fun `运行时生成的 session 始终为正数且非零`() {
        RuntimeLockTruthRegistry.resetForTest()

        repeat(256) {
            val sessionId = RuntimeLockTruthRegistry.beginSession(
                phase = MonitorPhase.LOCK,
                shouldShowLockUi = true,
                remainingSeconds = 60,
                lockCountsDownWhileInteractive = false
            )

            assertTrue(sessionId > NO_LOCK_SESSION)
            assertTrue(RuntimeLockTruthRegistry.isCurrentSession(sessionId))
        }
        RuntimeLockTruthRegistry.endSession(RuntimeLockTruthRegistry.currentSessionId())
    }

    @Test
    fun `新 session 会使旧命令失效`() {
        RuntimeLockTruthRegistry.resetForTest()
        val oldSession = RuntimeLockTruthRegistry.beginSession(
            phase = MonitorPhase.LOCK,
            shouldShowLockUi = true,
            remainingSeconds = 60,
            lockCountsDownWhileInteractive = false
        )
        val newSession = RuntimeLockTruthRegistry.beginSession(
            phase = MonitorPhase.LOCK,
            shouldShowLockUi = true,
            remainingSeconds = 60,
            lockCountsDownWhileInteractive = false
        )

        assertFalse(RuntimeLockTruthRegistry.isCurrentSession(oldSession))
        assertTrue(RuntimeLockTruthRegistry.isCurrentSession(newSession))
        RuntimeLockTruthRegistry.endSession(newSession)
    }

    @Test
    fun `专注会话的失联真值在亮屏时继续衰减`() {
        RuntimeLockTruthRegistry.resetForTest()
        val sessionId = RuntimeLockTruthRegistry.beginSession(
            phase = MonitorPhase.LOCK,
            shouldShowLockUi = true,
            remainingSeconds = 60,
            lockCountsDownWhileInteractive = true
        )
        RuntimeLockTruthRegistry.markRuntimeUnavailable(
            sessionId = sessionId,
            nowElapsedMillis = NOW,
            isInteractive = true,
            lockDurationMillis = 60_000L
        )

        val settled = RuntimeLockTruthRegistry.settleRetained(
            nowElapsedMillis = NOW + 3_000L,
            isInteractive = true
        )

        assertEquals(57, settled?.remainingSeconds)
        RuntimeLockTruthRegistry.endSession(sessionId)
    }

    @Test
    fun `实时 LOCK 覆盖仍停留在 USAGE 的磁盘快照`() {
        val decision = resolve(
            runtime = runtime(shouldShowLockUi = true),
            persistedPhase = MonitorPhase.USAGE
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(42, decision.remainingSeconds)
        assertEquals(LockTruthSource.LIVE_RUNTIME, decision.source)
    }

    @Test
    fun `实时白名单放行覆盖写入过程中的恢复标记`() {
        val decision = resolve(
            runtime = runtime(shouldShowLockUi = false),
            recoveryLockRequired = true,
            persistedPhase = MonitorPhase.LOCK
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.LIVE_RUNTIME, decision.source)
    }

    @Test
    fun `旧 session 的 Activity 在当前仍需锁定时换绑新 session`() {
        val decision = resolve(
            runtime = runtime(shouldShowLockUi = true),
            expectedSessionId = SESSION_ID - 1L,
            recoveryLockRequired = true,
            persistedPhase = MonitorPhase.LOCK
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(SESSION_ID, decision.sessionId)
        assertEquals(LockTruthSource.STALE_SESSION, decision.source)
    }

    @Test
    fun `旧 session 不能覆盖当前实时放行状态`() {
        val decision = resolve(
            runtime = runtime(shouldShowLockUi = false),
            expectedSessionId = SESSION_ID - 1L,
            recoveryLockRequired = true,
            persistedPhase = MonitorPhase.LOCK
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.STALE_SESSION, decision.source)
    }

    @Test
    fun `Service 不可用时 recovery guard 仍保持锁定`() {
        val decision = resolve(
            runtime = runtime(shouldShowLockUi = false).copy(isRuntimeAvailable = false),
            recoveryLockRequired = true,
            persistedMonitorActive = false,
            persistedPhase = null
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(LockTruthSource.RECOVERY_GUARD, decision.source)
    }

    @Test
    fun `Service 销毁后保留的锁资源在兜底期内不因磁盘滞后退出`() {
        val decision = resolve(
            runtime = retainedRuntime(remainingMillis = 42_000L),
            recoveryLockRequired = false,
            persistedPhase = MonitorPhase.USAGE
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(LockTruthSource.RETAINED_RUNTIME, decision.source)
        assertEquals(42, decision.remainingSeconds)
        assertTrue(decision.isUnattendedFallback)
    }

    @Test
    fun `保留的锁资源衰减耗尽后不再压制磁盘真值`() {
        val decision = resolve(
            runtime = retainedRuntime(remainingMillis = 0L),
            persistedMonitorActive = true,
            persistedPhase = MonitorPhase.USAGE
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, decision.source)
    }

    @Test
    fun `保留的锁资源触及绝对上限后交还给持久化真值`() {
        val decision = resolve(
            runtime = retainedRuntime(
                remainingMillis = 300_000L,
                unavailableSinceElapsedMillis = NOW - 1_200_000L,
                ceilingMillis = 1_200_000L
            ),
            persistedPhase = MonitorPhase.LOCK,
            persistedRemainingSeconds = 30
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(LockTruthSource.PERSISTED_SNAPSHOT, decision.source)
        // 必须沿用磁盘剩余时间，不能回落到完整锁机时长。
        assertEquals(30, decision.remainingSeconds)
    }

    @Test
    fun `保留的锁资源不能复活已停止的监督`() {
        val decision = resolve(
            runtime = retainedRuntime(remainingMillis = 0L),
            persistedMonitorActive = false,
            persistedPhase = null
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, decision.source)
    }

    @Test
    fun `旧 session 不能让已耗尽的保留真值重新锁定`() {
        val decision = resolve(
            runtime = retainedRuntime(remainingMillis = 0L),
            expectedSessionId = SESSION_ID - 1L,
            persistedMonitorActive = false,
            persistedPhase = null
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(SESSION_ID, decision.sessionId)
        assertEquals(LockTruthSource.NONE, decision.source)
    }

    @Test
    fun `旧 session 不能绕过保留真值的绝对上限`() {
        val decision = resolve(
            runtime = retainedRuntime(
                remainingMillis = 300_000L,
                unavailableSinceElapsedMillis = NOW - 1_200_000L,
                ceilingMillis = 1_200_000L
            ),
            expectedSessionId = SESSION_ID - 1L,
            persistedMonitorActive = false,
            persistedPhase = MonitorPhase.USAGE
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, decision.source)
    }

    @Test
    fun `恢复保护兜底优先采用持久化剩余时间而不是完整锁机时长`() {
        val decision = resolve(
            runtime = null,
            recoveryLockRequired = true,
            persistedRemainingSeconds = 30
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(LockTruthSource.RECOVERY_GUARD, decision.source)
        assertEquals(30, decision.remainingSeconds)
    }

    @Test
    fun `无快照的恢复保护兜底随单调时钟耗尽`() {
        val fresh = resolve(
            runtime = null,
            recoveryLockRequired = true,
            persistedRemainingSeconds = 0,
            serviceUnavailableSinceElapsedMillis = NOW
        )
        assertTrue(fresh.shouldStayLocked)
        assertEquals(60, fresh.remainingSeconds)
        assertTrue(fresh.isUnattendedFallback)

        val exhausted = resolve(
            runtime = null,
            recoveryLockRequired = true,
            persistedMonitorActive = false,
            persistedPhase = null,
            persistedRemainingSeconds = 0,
            // fallbackLockSeconds 是 60 秒，失联已超过该时长。
            serviceUnavailableSinceElapsedMillis = NOW - 60_000L
        )
        assertFalse(exhausted.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, exhausted.source)
    }

    @Test
    fun `恢复保护不能把玩机额度当作锁定剩余时间`() {
        val fresh = resolve(
            runtime = null,
            recoveryLockRequired = true,
            persistedPhase = MonitorPhase.USAGE,
            persistedRemainingSeconds = 1_800,
            serviceUnavailableSinceElapsedMillis = NOW
        )
        assertTrue(fresh.shouldStayLocked)
        assertEquals(60, fresh.remainingSeconds)
        assertTrue(fresh.isUnattendedFallback)

        val exhausted = resolve(
            runtime = null,
            recoveryLockRequired = true,
            persistedPhase = MonitorPhase.USAGE,
            persistedRemainingSeconds = 1_800,
            serviceUnavailableSinceElapsedMillis = NOW - 60_000L
        )
        assertFalse(exhausted.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, exhausted.source)
    }

    @Test
    fun `无快照的持久化锁定兜底同样随单调时钟耗尽`() {
        val decision = resolve(
            runtime = null,
            persistedMonitorActive = true,
            persistedPhase = MonitorPhase.LOCK,
            persistedRemainingSeconds = 0,
            serviceUnavailableSinceElapsedMillis = NOW - 60_000L
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, decision.source)
    }

    @Test
    fun `无实时状态时持久化 LOCK 保持锁定`() {
        val decision = resolve(
            runtime = null,
            persistedPhase = MonitorPhase.LOCK
        )

        assertTrue(decision.shouldStayLocked)
        assertEquals(LockTruthSource.PERSISTED_SNAPSHOT, decision.source)
    }

    @Test
    fun `完整有效的持久化暂停状态暂不恢复锁层`() {
        val decision = resolve(
            runtime = null,
            persistedPhase = MonitorPhase.LOCK,
            persistedPauseActive = true
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.PAUSED_SNAPSHOT, decision.source)
    }

    @Test
    fun `完整持久化暂停优先清除旧恢复保护造成的僵尸锁层`() {
        val decision = resolve(
            runtime = null,
            recoveryLockRequired = true,
            persistedPhase = MonitorPhase.LOCK,
            persistedPauseActive = true
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.PAUSED_SNAPSHOT, decision.source)
    }

    @Test
    fun `停止监督后的旧通知不能复活锁层`() {
        val decision = resolve(
            runtime = null,
            expectedSessionId = SESSION_ID,
            persistedMonitorActive = false,
            persistedPhase = null
        )

        assertFalse(decision.shouldStayLocked)
        assertEquals(LockTruthSource.NONE, decision.source)
    }

    @Test
    fun `失联锚点不会被后续健康心跳推后`() {
        val resolved = resolveRuntimeUnavailableAnchorRecord(
            runtimeAvailable = false,
            persisted = RuntimeUnavailableAnchorRecord(
                elapsedMillis = NOW - 30_000L,
                bootCount = 7
            ),
            observedElapsedMillis = NOW - 1_000L,
            nowElapsedMillis = NOW,
            currentBootCount = 7
        )

        assertEquals(NOW - 30_000L, resolved?.elapsedMillis)
    }

    @Test
    fun `进程重建后沿用同一启动批次的持久化失联锚点`() {
        val persisted = RuntimeUnavailableAnchorRecord(
            elapsedMillis = NOW - 45_000L,
            bootCount = 7
        )

        val resolved = resolveRuntimeUnavailableAnchorRecord(
            runtimeAvailable = false,
            persisted = persisted,
            observedElapsedMillis = 0L,
            nowElapsedMillis = NOW,
            currentBootCount = 7
        )

        assertEquals(persisted, resolved)
    }

    @Test
    fun `实时运行时恢复后清除失联锚点`() {
        val resolved = resolveRuntimeUnavailableAnchorRecord(
            runtimeAvailable = true,
            persisted = RuntimeUnavailableAnchorRecord(NOW - 45_000L, 7),
            observedElapsedMillis = NOW,
            nowElapsedMillis = NOW,
            currentBootCount = 7
        )

        assertNull(resolved)
    }

    @Test
    fun `设备重启后旧单调锚点失效`() {
        val resolved = resolveRuntimeUnavailableAnchorRecord(
            runtimeAvailable = false,
            persisted = RuntimeUnavailableAnchorRecord(NOW - 45_000L, 6),
            observedElapsedMillis = NOW - 2_000L,
            nowElapsedMillis = NOW,
            currentBootCount = 7
        )

        assertEquals(NOW - 2_000L, resolved?.elapsedMillis)
        assertEquals(7, resolved?.bootCount)
    }

    private fun runtime(shouldShowLockUi: Boolean) = RuntimeLockTruth(
        sessionId = SESSION_ID,
        isRuntimeAvailable = true,
        isMonitorActive = true,
        phase = MonitorPhase.LOCK,
        shouldShowLockUi = shouldShowLockUi,
        remainingSeconds = 42
    )

    private fun retainedRuntime(
        remainingMillis: Long = 42_000L,
        unavailableSinceElapsedMillis: Long = NOW,
        ceilingMillis: Long = 1_200_000L
    ) = runtime(shouldShowLockUi = true).copy(
        isRuntimeAvailable = false,
        retained = RetainedLockDecay(
            unavailableSinceElapsedMillis = unavailableSinceElapsedMillis,
            settledAtElapsedMillis = NOW,
            settledInteractive = false,
            remainingMillis = remainingMillis,
            ceilingMillis = ceilingMillis
        )
    )

    private fun resolve(
        runtime: RuntimeLockTruth?,
        expectedSessionId: Long = SESSION_ID,
        recoveryLockRequired: Boolean = false,
        persistedMonitorActive: Boolean = true,
        persistedPhase: MonitorPhase? = MonitorPhase.LOCK,
        persistedPauseActive: Boolean = false,
        persistedRemainingSeconds: Int = 30,
        serviceUnavailableSinceElapsedMillis: Long = NOW
    ) = LockTruthResolver.resolve(
        LockTruthInputs(
            runtime = runtime,
            expectedSessionId = expectedSessionId,
            recoveryLockRequired = recoveryLockRequired,
            persistedMonitorActive = persistedMonitorActive,
            persistedPhase = persistedPhase,
            persistedPauseActive = persistedPauseActive,
            persistedRemainingSeconds = persistedRemainingSeconds,
            fallbackLockSeconds = 60,
            nowElapsedMillis = NOW,
            serviceUnavailableSinceElapsedMillis = serviceUnavailableSinceElapsedMillis
        )
    )

    private companion object {
        const val SESSION_ID = 100L
        const val NOW = 10_000_000L
    }
}
