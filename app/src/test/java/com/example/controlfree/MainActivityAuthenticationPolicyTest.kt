package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAuthenticationPolicyTest {
    @Test
    fun `configured app without supervision opens without verification`() {
        assertFalse(
            shouldRequireAppAuthentication(
                isMonitorActive = false,
                isRecoveryLockRequired = false,
                isMonitorServiceRunning = false
            )
        )
    }

    @Test
    fun `active supervision requires verification`() {
        assertTrue(
            shouldRequireAppAuthentication(
                isMonitorActive = true,
                isRecoveryLockRequired = false,
                isMonitorServiceRunning = false
            )
        )
    }

    @Test
    fun `recovery lock requires verification`() {
        assertTrue(
            shouldRequireAppAuthentication(
                isMonitorActive = false,
                isRecoveryLockRequired = true,
                isMonitorServiceRunning = false
            )
        )
    }

    @Test
    fun `首次安装没有凭据时直接进入App`() {
        assertFalse(
            shouldRequireAppAuthentication(
                isMonitorActive = false,
                isRecoveryLockRequired = false,
                isMonitorServiceRunning = false,
                hasCredential = false
            )
        )
    }

    @Test
    fun `监督状态异常残留但没有凭据时不会显示无法完成的验证页`() {
        assertFalse(
            shouldRequireAppAuthentication(
                isMonitorActive = true,
                isRecoveryLockRequired = true,
                isMonitorServiceRunning = true,
                isAppSupervisionActive = true,
                hasCredential = false
            )
        )
    }

    @Test
    fun `live service requires verification even if persistence is unavailable`() {
        assertTrue(
            shouldRequireAppAuthentication(
                isMonitorActive = false,
                isRecoveryLockRequired = false,
                isMonitorServiceRunning = true
            )
        )
    }

    @Test
    fun `App独立监督处于活动时段时要求验证`() {
        assertTrue(
            shouldRequireAppAuthentication(
                isMonitorActive = false,
                isRecoveryLockRequired = false,
                isMonitorServiceRunning = false,
                isAppSupervisionActive = true
            )
        )
    }
}
