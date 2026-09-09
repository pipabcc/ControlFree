package com.example.controlfree.diagnostics

import com.example.controlfree.MonitorPhase
import com.example.controlfree.data.MonitorProgressReadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfCheckRunnerPolicyTest {
    @Test
    fun `有效快照落盘后临时恢复保护已清除仍报告健康`() {
        val result = resolveSnapshotRecoveryState(
            monitoringActive = true,
            guardArmed = false,
            progressStatus = MonitorProgressReadStatus.AVAILABLE
        )

        assertEquals(SnapshotRecoveryState.HEALTHY, result)
    }

    @Test
    fun `有效快照且恢复保护已武装时报告健康`() {
        val result = resolveSnapshotRecoveryState(
            monitoringActive = true,
            guardArmed = true,
            progressStatus = MonitorProgressReadStatus.AVAILABLE
        )

        assertEquals(SnapshotRecoveryState.HEALTHY, result)
    }

    @Test
    fun `新锁定会话没有探测时不能复用历史健康状态`() {
        val result = resolveMediaGuardHealth(
            monitoringActive = true,
            health = MonitorRuntimeHealth(
                serviceAvailable = true,
                phase = MonitorPhase.LOCK,
                mediaReplayGuardEnabled = true,
                lastMediaProbeElapsedMillis = 0L,
                mediaProbeAvailable = false
            ),
            nowElapsedMillis = 10_000L,
            staleAfterMillis = 20_000L
        )

        assertEquals(MediaGuardState.NOT_CHECKED, result.state)
    }

    @Test
    fun `媒体探针不可用或过期时不能报告健康`() {
        val unavailable = resolveMediaGuardHealth(
            monitoringActive = true,
            health = healthyLockHealth().copy(mediaProbeAvailable = false),
            nowElapsedMillis = 10_000L,
            staleAfterMillis = 20_000L
        )
        val stale = resolveMediaGuardHealth(
            monitoringActive = true,
            health = healthyLockHealth().copy(lastMediaProbeElapsedMillis = 1_000L),
            nowElapsedMillis = 30_000L,
            staleAfterMillis = 20_000L
        )

        assertEquals(MediaGuardState.PROBE_UNAVAILABLE, unavailable.state)
        assertEquals(MediaGuardState.PROBE_UNAVAILABLE, stale.state)
    }

    private fun healthyLockHealth() = MonitorRuntimeHealth(
        serviceAvailable = true,
        phase = MonitorPhase.LOCK,
        mediaReplayGuardEnabled = true,
        lastMediaProbeElapsedMillis = 9_000L,
        mediaProbeAvailable = true
    )
}
