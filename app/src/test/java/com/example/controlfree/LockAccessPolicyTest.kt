package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockAccessPolicyTest {
    @Test
    fun `来电期间只有确认拨号器前台才放行`() {
        val decision = decide(
            isCallActive = true,
            isCallUiConfirmed = true,
            hasUsageAccess = false,
            foregroundPackage = "com.android.dialer",
            allowedPackages = emptySet()
        )

        assertFalse(decision.shouldShowLockUi)
        assertFalse(decision.shouldHoldAudioFocus)
        assertEquals(LockAccessReason.ACTIVE_CALL, decision.reason)
    }

    @Test
    fun `通话活跃但未确认拨号器时保持锁定`() {
        val decision = decide(
            isCallActive = true,
            isCallUiConfirmed = false,
            hasUsageAccess = true,
            foregroundPackage = "example.blocked",
            allowedPackages = emptySet()
        )

        assertTrue(decision.shouldShowLockUi)
        assertTrue(decision.shouldHoldAudioFocus)
        assertEquals(LockAccessReason.HARD_LOCK, decision.reason)
    }

    @Test
    fun `有使用权限且前台应用在白名单时放行`() {
        val decision = decide(
            hasUsageAccess = true,
            foregroundPackage = "example.allowed",
            allowedPackages = setOf("example.allowed")
        )

        assertFalse(decision.shouldShowLockUi)
        assertEquals(LockAccessReason.ALLOWED_APP, decision.reason)
    }

    @Test
    fun `没有使用权限时白名单检测失败并保持锁定`() {
        val decision = decide(
            hasUsageAccess = false,
            foregroundPackage = "example.allowed",
            allowedPackages = setOf("example.allowed")
        )

        assertTrue(decision.shouldShowLockUi)
        assertEquals(LockAccessReason.HARD_LOCK, decision.reason)
    }

    @Test
    fun `白名单启动过程中前台仍是受限应用时保持锁定`() {
        val decision = decide(
            foregroundPackage = "example.blocked"
        )

        assertTrue(decision.shouldShowLockUi)
        assertEquals(LockAccessReason.HARD_LOCK, decision.reason)
    }

    private fun decide(
        isCallActive: Boolean = false,
        isCallUiConfirmed: Boolean = false,
        hasUsageAccess: Boolean = true,
        foregroundPackage: String? = "example.blocked",
        allowedPackages: Set<String> = setOf("example.allowed")
    ) = LockAccessPolicy.decide(
        isLockPhase = true,
        isCallActive = isCallActive,
        isCallUiConfirmed = isCallUiConfirmed,
        hasUsageAccess = hasUsageAccess,
        foregroundPackage = foregroundPackage,
        allowedPackages = allowedPackages
    )
}
