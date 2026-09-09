package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorPauseStateTest {
    @Test
    fun `跳过锁定阶段后从完整玩机额度重新监督`() {
        val cycle = MonitorCycle(
            usageDurationMillis = 20 * 60_000L,
            lockDurationMillis = 5 * 60_000L
        )
        val locked = MonitorCycleSnapshot(
            phase = MonitorPhase.LOCK,
            remainingMillis = 90_000L,
            checkpointElapsedMillis = 100L,
            bootCount = 7,
            isInteractive = true
        )

        val skipped = cycle.skipCurrentPhase(locked, 200L, 7, true)

        assertEquals(MonitorPhase.USAGE, skipped.phase)
        assertEquals(20 * 60_000L, skipped.remainingMillis)
        assertEquals(200L, skipped.checkpointElapsedMillis)
    }

    @Test
    fun `同一锁定阶段累计暂停不超过三十分钟`() {
        val first = requireNotNull(
            MonitorPauseState.begin(
                previous = null,
                requestedMillis = 20 * 60_000L,
                nowEpochMillis = 1_000_000L,
                nowElapsedMillis = 100_000L,
                bootCount = 7
            )
        ).clearActive()

        val second = MonitorPauseState.begin(
            previous = first,
            requestedMillis = 10 * 60_000L,
            nowEpochMillis = 3_000_000L,
            nowElapsedMillis = 2_100_000L,
            bootCount = 7
        )

        assertNotNull(second)
        assertEquals(MAX_LOCK_PAUSE_MILLIS, second?.accumulatedPauseMillis)
        assertNull(
            MonitorPauseState.begin(
                previous = second?.clearActive(),
                requestedMillis = 60_000L,
                nowEpochMillis = 4_000_000L,
                nowElapsedMillis = 3_100_000L,
                bootCount = 7
            )
        )
    }

    @Test
    fun `暂停请求超过剩余额度时整体拒绝而不静默截断`() {
        val previous = MonitorPauseState(
            accumulatedPauseMillis = MAX_LOCK_PAUSE_MILLIS - 60_000L
        )

        assertNull(
            MonitorPauseState.begin(
                previous = previous,
                requestedMillis = 60_001L,
                nowEpochMillis = 1_000_000L,
                nowElapsedMillis = 100_000L,
                bootCount = 7
            )
        )

        val exactRemainder = requireNotNull(
            MonitorPauseState.begin(
                previous = previous,
                requestedMillis = 60_000L,
                nowEpochMillis = 1_000_000L,
                nowElapsedMillis = 100_000L,
                bootCount = 7
            )
        )
        assertEquals(MAX_LOCK_PAUSE_MILLIS, exactRemainder.accumulatedPauseMillis)
        assertEquals(0L, exactRemainder.remainingBudgetMillis)
    }

    @Test
    fun `暂停同时受单调时钟和墙钟约束且跨开机立即失效`() {
        val pause = requireNotNull(
            MonitorPauseState.begin(
                previous = null,
                requestedMillis = 5 * 60_000L,
                nowEpochMillis = 1_000_000L,
                nowElapsedMillis = 100_000L,
                bootCount = 7
            )
        )

        assertFalse(pause.isActiveAt(9_000_000L, 399_999L, 7))
        assertTrue(pause.isActiveAt(1_299_999L, 399_999L, 7))
        assertFalse(pause.isActiveAt(1_100_000L, 400_000L, 7))
        assertFalse(pause.isActiveAt(1_100_000L, 200_000L, 8))
        assertFalse(pause.isActiveAt(1_100_000L, 200_000L, MONITOR_UNKNOWN_BOOT_COUNT))
        assertFalse(pause.isActiveAt(1_299_999L, 99_999L, 7))
        assertFalse(pause.isActiveAt(999_999L, 200_000L, 7))
        assertFalse(pause.isActiveAt(1_300_000L, 200_000L, 7))
    }

    @Test
    fun `定时任务结束时间缩短实际暂停并计入真实额度`() {
        val pause = requireNotNull(
            MonitorPauseState.begin(
                previous = null,
                requestedMillis = 30 * 60_000L,
                nowEpochMillis = 1_000_000L,
                nowElapsedMillis = 100_000L,
                bootCount = 7,
                hardStopEpochMillis = 1_300_000L
            )
        )

        assertEquals(5 * 60_000L, pause.accumulatedPauseMillis)
        assertEquals(1_300_000L, pause.untilEpochMillis)
    }
}
