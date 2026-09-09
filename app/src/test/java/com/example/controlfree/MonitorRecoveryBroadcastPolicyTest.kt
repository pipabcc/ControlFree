package com.example.controlfree

import com.example.controlfree.boot.BootRecoveryContract
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.controlfree.supervision.runtime.SupervisionReconciliationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorRecoveryBroadcastPolicyTest {
    @Test
    fun `开机完成广播会触发监督恢复`() {
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                "android.intent.action.BOOT_COMPLETED"
            )
        )
    }

    @Test
    fun `锁定启动等待用户解锁且解锁广播触发恢复`() {
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
        )
        assertTrue(
            MonitorRecoveryBroadcastPolicy.mustWaitForUserUnlock(
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
        )
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                "android.intent.action.USER_UNLOCKED"
            )
        )
        assertFalse(
            MonitorRecoveryBroadcastPolicy.mustWaitForUserUnlock(
                "android.intent.action.USER_UNLOCKED"
            )
        )
    }

    @Test
    fun `开机完成或用户解锁都能留下本次开机证据`() {
        assertTrue(
            MonitorRecoveryBroadcastPolicy.recordsBootEvidence(
                "android.intent.action.BOOT_COMPLETED"
            )
        )
        assertTrue(
            MonitorRecoveryBroadcastPolicy.recordsBootEvidence(
                "android.intent.action.USER_UNLOCKED"
            )
        )
        assertFalse(
            MonitorRecoveryBroadcastPolicy.recordsBootEvidence(
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
        )
    }

    @Test
    fun `覆盖安装完成广播会触发监督恢复`() {
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                "android.intent.action.MY_PACKAGE_REPLACED"
            )
        )
    }

    @Test
    fun `Direct Boot首次分发记录证据而后续重试只恢复运行时`() {
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY
            )
        )
        assertTrue(
            MonitorRecoveryBroadcastPolicy.recordsBootEvidence(
                BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY
            )
        )
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY_RETRY
            )
        )
        assertTrue(
            MonitorRecoveryBroadcastPolicy.isRetryOnlyAction(
                BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY_RETRY
            )
        )
        assertFalse(
            MonitorRecoveryBroadcastPolicy.recordsBootEvidence(
                BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY_RETRY
            )
        )
    }

    @Test
    fun `无关或空广播不会启动监督服务`() {
        assertFalse(MonitorRecoveryBroadcastPolicy.isRecoveryAction(null))
        assertFalse(
            MonitorRecoveryBroadcastPolicy.isRecoveryAction(
                "android.intent.action.PACKAGE_REPLACED"
            )
        )
    }

    @Test
    fun `没有活动监督和恢复标记时不启动服务`() {
        val starts = AtomicInteger()
        coordinator(
            isActive = false,
            requiresLock = false,
            onStart = { starts.incrementAndGet() }
        ).handle("android.intent.action.MY_PACKAGE_REPLACED")

        assertEquals(0, starts.get())
    }

    @Test
    fun `锁定启动阶段不会提前读取状态或启动服务`() {
        val stateReads = AtomicInteger()
        val starts = AtomicInteger()
        val coordinator = MonitorRecoveryBroadcastCoordinator(
            isMonitorActive = {
                stateReads.incrementAndGet()
                true
            },
            requiresRecoveryLock = { true },
            startMonitorService = { starts.incrementAndGet() },
            armRecoveryLock = {},
            isMonitorServiceRunning = { false }
        )

        coordinator.handle("android.intent.action.LOCKED_BOOT_COMPLETED")

        assertEquals(0, stateReads.get())
        assertEquals(0, starts.get())
    }

    @Test
    fun `重复开机事件不会重启已运行的监督服务`() {
        val starts = AtomicInteger()
        val coordinator = MonitorRecoveryBroadcastCoordinator(
            isMonitorActive = { true },
            requiresRecoveryLock = { false },
            startMonitorService = { starts.incrementAndGet() },
            armRecoveryLock = {},
            isMonitorServiceRunning = { true }
        )

        coordinator.handle("android.intent.action.USER_UNLOCKED")

        assertEquals(0, starts.get())
    }

    @Test
    fun `活动监督或独立锁定标记任一存在时都会启动恢复`() {
        val starts = AtomicInteger()
        coordinator(
            isActive = true,
            requiresLock = false,
            onStart = { starts.incrementAndGet() }
        ).handle("android.intent.action.BOOT_COMPLETED")
        coordinator(
            isActive = false,
            requiresLock = true,
            onStart = { starts.incrementAndGet() }
        ).handle("android.intent.action.MY_PACKAGE_REPLACED")

        assertEquals(2, starts.get())
    }

    @Test
    fun `服务启动失败时启用独立锁定恢复标记`() {
        val guardArmed = AtomicBoolean()
        val coordinator = MonitorRecoveryBroadcastCoordinator(
            isMonitorActive = { true },
            requiresRecoveryLock = { false },
            startMonitorService = { throw IllegalStateException("background start rejected") },
            armRecoveryLock = { guardArmed.set(true) },
            isMonitorServiceRunning = { false }
        )

        coordinator.handle("android.intent.action.MY_PACKAGE_REPLACED")

        assertTrue(guardArmed.get())
    }

    @Test
    fun `状态读取异常时仍按活动监督进行失败安全恢复`() {
        val starts = AtomicInteger()
        val coordinator = MonitorRecoveryBroadcastCoordinator(
            isMonitorActive = { throw IllegalStateException("preferences unavailable") },
            requiresRecoveryLock = { false },
            startMonitorService = { starts.incrementAndGet() },
            armRecoveryLock = {},
            isMonitorServiceRunning = { false }
        )

        coordinator.handle("android.intent.action.BOOT_COMPLETED")

        assertEquals(1, starts.get())
    }

    @Test
    fun `计划库异常时只恢复仍在有效期限内的持久化定时监督`() {
        assertTrue(
            shouldRestorePersistedScheduledMonitor(
                monitorActive = true,
                serviceRunning = false,
                nowEpochMillis = 1_000L,
                activeUntilEpochMillis = 2_000L
            )
        )
        assertFalse(
            shouldRestorePersistedScheduledMonitor(
                monitorActive = true,
                serviceRunning = false,
                nowEpochMillis = 2_000L,
                activeUntilEpochMillis = 2_000L
            )
        )
        assertFalse(
            shouldRestorePersistedScheduledMonitor(
                monitorActive = false,
                serviceRunning = false,
                nowEpochMillis = 1_000L,
                activeUntilEpochMillis = 2_000L
            )
        )
        assertFalse(
            shouldRestorePersistedScheduledMonitor(
                monitorActive = true,
                serviceRunning = true,
                nowEpochMillis = 1_000L,
                activeUntilEpochMillis = 2_000L
            )
        )
    }

    @Test
    fun `计划运行命令失败时使用持久化状态回退恢复`() {
        assertTrue(
            requiresPersistedBootFallback(
                SupervisionReconciliationResult.RuntimeCommandFailed
            )
        )
        assertTrue(
            requiresPersistedBootFallback(
                SupervisionReconciliationResult.PlanStorageUnavailable
            )
        )
        assertTrue(
            requiresPersistedBootFallback(
                SupervisionReconciliationResult.RuntimePrerequisitesMissing
            )
        )
    }

    private fun coordinator(
        isActive: Boolean,
        requiresLock: Boolean,
        onStart: () -> Unit
    ) = MonitorRecoveryBroadcastCoordinator(
        isMonitorActive = { isActive },
        requiresRecoveryLock = { requiresLock },
        startMonitorService = onStart,
        armRecoveryLock = {},
        isMonitorServiceRunning = { false }
    )
}
