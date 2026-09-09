package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeChallengeCountdownHoldPolicyTest {
    @Test
    fun `当前锁定会话且挑战界面可见时暂停倒计时`() {
        assertTrue(
            shouldHoldKnowledgeChallengeCountdown(
                requestedSessionId = 8L,
                currentSessionId = 8L,
                phase = MonitorPhase.LOCK,
                isLockActivityVisible = true,
                requestedVisible = true
            )
        )
    }

    @Test
    fun `离开挑战或Activity不可见后立即释放暂停`() {
        assertFalse(
            shouldHoldKnowledgeChallengeCountdown(8L, 8L, MonitorPhase.LOCK, true, false)
        )
        assertFalse(
            shouldHoldKnowledgeChallengeCountdown(8L, 8L, MonitorPhase.LOCK, false, true)
        )
    }

    @Test
    fun `旧会话和玩机阶段不能冻结当前倒计时`() {
        assertFalse(
            shouldHoldKnowledgeChallengeCountdown(7L, 8L, MonitorPhase.LOCK, true, true)
        )
        assertFalse(
            shouldHoldKnowledgeChallengeCountdown(8L, 8L, MonitorPhase.USAGE, true, true)
        )
        assertFalse(
            shouldHoldKnowledgeChallengeCountdown(
                NO_LOCK_SESSION,
                NO_LOCK_SESSION,
                MonitorPhase.LOCK,
                true,
                true
            )
        )
    }

    @Test
    fun `挑战心跳只在有限窗口内保持冻结`() {
        assertTrue(
            isKnowledgeChallengeHeartbeatFresh(
                lastHeartbeatElapsedMillis = 10_000L,
                nowElapsedMillis = 15_999L
            )
        )
        assertFalse(
            isKnowledgeChallengeHeartbeatFresh(
                lastHeartbeatElapsedMillis = 10_000L,
                nowElapsedMillis = 16_001L
            )
        )
        assertFalse(
            isKnowledgeChallengeHeartbeatFresh(
                lastHeartbeatElapsedMillis = 0L,
                nowElapsedMillis = 1L
            )
        )
    }
}
