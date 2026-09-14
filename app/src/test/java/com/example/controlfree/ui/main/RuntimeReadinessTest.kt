package com.example.controlfree.ui.main

import com.example.controlfree.MonitorNotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReadinessTest {
    @Test
    fun `关键条件全部就绪时允许启动`() {
        val readiness = evaluateRuntimeReadiness(readySignals())

        assertTrue(readiness.canStart)
        assertTrue(readiness.blockers.isEmpty())
    }

    @Test
    fun `任一关键权限缺失时阻止启动`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(
                overlayGranted = false,
                serviceChannelEnabled = false
            )
        )

        assertFalse(readiness.canStart)
        assertEquals(
            setOf(RuntimeRequirementKey.OVERLAY, RuntimeRequirementKey.SERVICE_CHANNEL),
            readiness.blockers.map { it.key }.toSet()
        )
    }

    @Test
    fun `无电话硬件时电话权限不阻止启动`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(
                telephonySupported = false,
                phoneStateGranted = false
            )
        )

        assertTrue(readiness.canStart)
        assertEquals(
            RuntimeRequirementState.NOT_APPLICABLE,
            readiness.requirements.first { it.key == RuntimeRequirementKey.PHONE_STATE }.state
        )
    }

    @Test
    fun `电池未设为无限制只提示建议`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(batteryUnrestricted = false)
        )

        assertTrue(readiness.canStart)
        assertEquals(
            RuntimeRequirementState.RECOMMENDED,
            readiness.requirements.first { it.key == RuntimeRequirementKey.BATTERY_UNRESTRICTED }.state
        )
    }

    @Test
    fun `系统拒绝全屏通知时仍允许使用主锁层`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(fullScreenIntentGranted = false)
        )

        assertTrue(readiness.canStart)
        assertEquals(
            RuntimeRequirementState.RECOMMENDED,
            readiness.requirements.first { it.key == RuntimeRequirementKey.FULL_SCREEN_INTENT }.state
        )
    }

    @Test
    fun `未授权精确定时时保留降级执行并给出建议`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(exactAlarmGranted = false)
        )

        assertTrue(readiness.canStart)
        assertEquals(
            RuntimeRequirementState.RECOMMENDED,
            readiness.requirements.first { it.key == RuntimeRequirementKey.EXACT_ALARM }.state
        )
    }

    @Test
    fun `通知运行时权限未授予时不重复要求处理应用通知总开关`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(
                notificationRuntimeGranted = false,
                notificationsEnabled = false
            )
        )

        assertFalse(readiness.canStart)
        assertEquals(
            listOf(RuntimeRequirementKey.NOTIFICATION_PERMISSION),
            readiness.blockers
                .filter { it.key.isNotificationDeliveryRequirement() }
                .map { it.key }
        )
        assertFalse(
            readiness.requirements.any {
                it.key == RuntimeRequirementKey.NOTIFICATIONS_ENABLED
            }
        )
    }

    @Test
    fun `锁定恢复频道关闭仅提示建议而不阻断监督`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(lockRecoveryChannelEnabled = false)
        )

        assertTrue(readiness.canStart)
        val requirement = readiness.requirements.first {
            it.key == RuntimeRequirementKey.LOCK_RECOVERY_CHANNEL
        }
        assertEquals(RuntimeRequirementState.RECOMMENDED, requirement.state)
        assertFalse(requirement.blocksStart)
    }

    @Test
    fun `通知频道设置目标与运行条件严格对应`() {
        assertEquals(
            MonitorNotificationChannels.SERVICE_CHANNEL_ID,
            notificationChannelIdForRequirement(RuntimeRequirementKey.SERVICE_CHANNEL)
        )
        assertEquals(
            MonitorNotificationChannels.LOCK_RECOVERY_CHANNEL_ID,
            notificationChannelIdForRequirement(RuntimeRequirementKey.LOCK_RECOVERY_CHANNEL)
        )
        assertEquals(
            null,
            notificationChannelIdForRequirement(RuntimeRequirementKey.NOTIFICATIONS_ENABLED)
        )
    }

    @Test
    fun `App监督不被电话与锁定恢复权限阻断`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(
                phoneStateGranted = false,
                lockRecoveryChannelEnabled = false
            )
        )

        assertFalse(readiness.canStart)
        assertTrue(readiness.forAppSupervision().canStart)
        assertEquals(
            setOf(
                RuntimeRequirementKey.USAGE_ACCESS,
                RuntimeRequirementKey.OVERLAY,
                RuntimeRequirementKey.BACKGROUND_POPUP,
                RuntimeRequirementKey.NOTIFICATION_PERMISSION,
                RuntimeRequirementKey.NOTIFICATIONS_ENABLED,
                RuntimeRequirementKey.SERVICE_CHANNEL,
                RuntimeRequirementKey.EXACT_ALARM,
                RuntimeRequirementKey.BATTERY_UNRESTRICTED
            ),
            readiness.forAppSupervision().requirements.map { it.key }.toSet()
        )
    }

    @Test
    fun `App监督仍被自身关键权限阻断`() {
        val readiness = evaluateRuntimeReadiness(
            readySignals().copy(usageAccessGranted = false)
        ).forAppSupervision()

        assertFalse(readiness.canStart)
        assertEquals(
            listOf(RuntimeRequirementKey.USAGE_ACCESS),
            readiness.blockers.map { it.key }
        )
    }

    private fun readySignals() = RuntimeReadinessSignals(
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
