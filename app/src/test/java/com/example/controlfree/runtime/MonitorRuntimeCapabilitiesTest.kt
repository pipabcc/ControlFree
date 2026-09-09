package com.example.controlfree.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorRuntimeCapabilitiesTest {
    @Test
    fun `关键能力齐全时允许启动监督`() {
        assertTrue(readyCapabilities().canStartMonitor)
    }

    @Test
    fun `精确定时和电池建议项不阻止启动`() {
        val capabilities = readyCapabilities().copy(
            exactAlarmGranted = false,
            fullScreenIntentGranted = false,
            batteryUnrestricted = false
        )

        assertTrue(capabilities.canStartMonitor)
    }

    @Test
    fun `缺少关键能力时阻止启动`() {
        assertFalse(readyCapabilities().copy(usageAccessGranted = false).canStartMonitor)
        assertFalse(readyCapabilities().copy(overlayGranted = false).canStartMonitor)
        assertFalse(readyCapabilities().copy(notificationsEnabled = false).canStartMonitor)
        assertFalse(readyCapabilities().copy(serviceChannelEnabled = false).canStartMonitor)
    }

    @Test
    fun `无电话硬件时电话权限不阻止启动`() {
        val capabilities = readyCapabilities().copy(
            telephonySupported = false,
            phoneStateGranted = false
        )

        assertTrue(capabilities.canStartMonitor)
    }

    @Test
    fun `App独立监督不因电话权限和全局锁恢复频道缺失而停用`() {
        val capabilities = readyCapabilities().copy(
            phoneStateGranted = false,
            lockRecoveryChannelEnabled = false
        )

        assertFalse(capabilities.canStartMonitor)
        assertTrue(capabilities.canStartAppSupervision)
    }

    @Test
    fun `锁定恢复频道属于备用能力不阻断全局监督`() {
        assertTrue(
            readyCapabilities()
                .copy(lockRecoveryChannelEnabled = false)
                .canStartMonitor
        )
    }

    private fun readyCapabilities() = MonitorRuntimeCapabilities(
        usageAccessGranted = true,
        overlayGranted = true,
        telephonySupported = true,
        phoneStateGranted = true,
        notificationRuntimeGranted = true,
        notificationsEnabled = true,
        notificationChannelsSupported = true,
        serviceChannelEnabled = true,
        lockRecoveryChannelEnabled = true,
        fullScreenIntentRequired = true,
        fullScreenIntentGranted = true,
        exactAlarmRequired = true,
        exactAlarmGranted = true,
        batteryUnrestricted = true,
        backgroundPopupGranted = true
    )
}
