package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockActivityHandoffTest {
    private val expected = ExpectedLockActivityHandoff(
        token = "handoff-token",
        lockSessionId = 42L,
        expiresAtElapsedMillis = 2_000L
    )

    @Test
    fun `当前会话的有效一次性交接令牌允许 Activity 接管`() {
        assertTrue(
            isExpectedLockActivityHandoff(
                expected = expected,
                incomingToken = "handoff-token",
                incomingSessionId = 42L,
                isVisible = true,
                nowElapsedMillis = 1_500L
            )
        )
    }

    @Test
    fun `错误会话错误令牌和过期令牌都不能绕过双层保护`() {
        assertFalse(
            isExpectedLockActivityHandoff(
                expected,
                incomingToken = "wrong-token",
                incomingSessionId = 42L,
                isVisible = true,
                nowElapsedMillis = 1_500L
            )
        )
        assertFalse(
            isExpectedLockActivityHandoff(
                expected,
                incomingToken = "handoff-token",
                incomingSessionId = 43L,
                isVisible = true,
                nowElapsedMillis = 1_500L
            )
        )
        assertFalse(
            isExpectedLockActivityHandoff(
                expected,
                incomingToken = "handoff-token",
                incomingSessionId = 42L,
                isVisible = true,
                nowElapsedMillis = 2_001L
            )
        )
        assertFalse(
            isExpectedLockActivityHandoff(
                expected,
                incomingToken = "handoff-token",
                incomingSessionId = 42L,
                isVisible = false,
                nowElapsedMillis = 1_500L
            )
        )
    }

    @Test
    fun `聊天交接保留用户选择的暂停分钟数`() {
        val request = OverlayLockActivityRequest.Intervention(
            action = LockPendingAction.pause(17),
            openChat = true
        )

        assertEquals(17, request.action.pauseMinutes)
        assertTrue(request.openChat)
    }

    @Test
    fun `相同锁定会话重入保留当前子界面`() {
        assertFalse(shouldResetLockActivityUi(currentSessionId = 42L, incomingSessionId = 42L))
        assertFalse(
            shouldResetLockActivityUi(
                currentSessionId = 42L,
                incomingSessionId = NO_LOCK_SESSION
            )
        )
        assertTrue(shouldResetLockActivityUi(currentSessionId = 42L, incomingSessionId = 43L))
    }
}
