package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockOverlayControllerPolicyTest {
    @Test
    fun `revoked permission has a distinct failure result`() {
        assertEquals(
            OverlayShowResult.PERMISSION_REVOKED,
            resolveOverlayShowPrecondition(
                hasOverlayPermission = false,
                hasWindowManager = true
            )
        )
    }

    @Test
    fun `missing window manager is reported as window error`() {
        assertEquals(
            OverlayShowResult.WINDOW_ERROR,
            resolveOverlayShowPrecondition(
                hasOverlayPermission = true,
                hasWindowManager = false
            )
        )
    }

    @Test
    fun `valid preconditions allow the window operation to continue`() {
        assertNull(
            resolveOverlayShowPrecondition(
                hasOverlayPermission = true,
                hasWindowManager = true
            )
        )
    }

    @Test
    fun `allowed app rows remain compact while accommodating larger fonts`() {
        assertEquals(84, resolveAllowedAppRowHeightDp(1f))
        assertEquals(96, resolveAllowedAppRowHeightDp(1.3f))
        assertEquals(108, resolveAllowedAppRowHeightDp(1.8f))
    }

    @Test
    fun `overlay removal retries are finite and only run while attached`() {
        assertTrue(shouldRetryOverlayRemoval(isAttachedToWindow = true, completedAttempts = 0))
        assertTrue(
            shouldRetryOverlayRemoval(
                isAttachedToWindow = true,
                completedAttempts = OVERLAY_REMOVAL_MAX_ATTEMPTS - 1
            )
        )
        assertFalse(
            shouldRetryOverlayRemoval(
                isAttachedToWindow = true,
                completedAttempts = OVERLAY_REMOVAL_MAX_ATTEMPTS
            )
        )
        assertFalse(shouldRetryOverlayRemoval(isAttachedToWindow = false, completedAttempts = 0))
        assertFalse(shouldRetryOverlayRemoval(isAttachedToWindow = true, completedAttempts = -1))
    }

    @Test
    fun `a later explicit hide request starts a new finite removal batch`() {
        assertFalse(
            shouldResetOverlayRemovalAttempts(
                isSameView = true,
                isRetryScheduled = false,
                isAttachedToWindow = true,
                completedAttempts = OVERLAY_REMOVAL_MAX_ATTEMPTS - 1
            )
        )
        assertFalse(
            shouldResetOverlayRemovalAttempts(
                isSameView = true,
                isRetryScheduled = true,
                isAttachedToWindow = true,
                completedAttempts = OVERLAY_REMOVAL_MAX_ATTEMPTS
            )
        )
        assertTrue(
            shouldResetOverlayRemovalAttempts(
                isSameView = true,
                isRetryScheduled = false,
                isAttachedToWindow = true,
                completedAttempts = OVERLAY_REMOVAL_MAX_ATTEMPTS
            )
        )
        assertTrue(
            shouldResetOverlayRemovalAttempts(
                isSameView = false,
                isRetryScheduled = false,
                isAttachedToWindow = true,
                completedAttempts = 0
            )
        )
    }

    @Test
    fun `truth directive dismisses the overlay once the lock has ended`() {
        assertEquals(
            OverlayTruthDirective.Dismiss,
            resolveOverlayTruthDirective(
                decision = LockTruthDecision(
                    shouldStayLocked = false,
                    remainingSeconds = 0,
                    sessionId = 42L,
                    source = LockTruthSource.LIVE_RUNTIME
                ),
                attachedSessionId = 7L
            )
        )
    }

    @Test
    fun `truth directive dismisses a stale overlay left behind by a paused monitor`() {
        // 暂停/跳过成功后残留的旧会话窗口：真值说不该锁着，就必须自拆，
        // 不能停在冻结倒计时上把用户困在僵尸锁屏里。
        assertEquals(
            OverlayTruthDirective.Dismiss,
            resolveOverlayTruthDirective(
                decision = LockTruthDecision(
                    shouldStayLocked = false,
                    remainingSeconds = 123,
                    sessionId = NO_LOCK_SESSION,
                    source = LockTruthSource.PAUSED_SNAPSHOT
                ),
                attachedSessionId = 7L
            )
        )
    }

    @Test
    fun `truth directive adopts the arbitrated session while still locked`() {
        assertEquals(
            OverlayTruthDirective.Continue(sessionId = 42L, remainingSeconds = 90),
            resolveOverlayTruthDirective(
                decision = LockTruthDecision(
                    shouldStayLocked = true,
                    remainingSeconds = 90,
                    sessionId = 42L,
                    source = LockTruthSource.LIVE_RUNTIME
                ),
                attachedSessionId = 7L
            )
        )
    }

    @Test
    fun `truth directive keeps the attached session when arbitration has none`() {
        assertEquals(
            OverlayTruthDirective.Continue(sessionId = 7L, remainingSeconds = 30),
            resolveOverlayTruthDirective(
                decision = LockTruthDecision(
                    shouldStayLocked = true,
                    remainingSeconds = 30,
                    sessionId = NO_LOCK_SESSION,
                    source = LockTruthSource.PERSISTED_SNAPSHOT
                ),
                attachedSessionId = 7L
            )
        )
    }
}
