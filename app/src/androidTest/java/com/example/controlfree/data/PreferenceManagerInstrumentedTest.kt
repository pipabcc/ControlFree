package com.example.controlfree.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.MonitorCycleSnapshot
import com.example.controlfree.MonitorPhase
import com.example.controlfree.MonitorPauseState
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import com.example.controlfree.supervision.runtime.ScheduledOwnerReadResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceManagerInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPreferences().edit().clear().commit()
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
    }

    @Test
    fun 缺少主题偏好时默认使用亮色() {
        assertFalse(PreferenceManager(context).isDarkThemeEnabled())
    }

    @Test
    fun 已保存的深色和亮色偏好均保持不变() {
        val manager = PreferenceManager(context)

        assertTrue(manager.setDarkThemeEnabled(true))
        assertTrue(PreferenceManager(context).isDarkThemeEnabled())

        assertTrue(manager.setDarkThemeEnabled(false))
        assertFalse(PreferenceManager(context).isDarkThemeEnabled())
    }

    @Test
    fun 主题偏好类型损坏时回退到亮色() {
        rawPreferences().edit()
            .putString(KEY_DARK_THEME_ENABLED, "invalid")
            .commit()

        assertFalse(PreferenceManager(context).isDarkThemeEnabled())
    }

    @Test
    fun schema5快照可以完整往返并拒绝字段篡改() {
        val manager = PreferenceManager(context)
        val snapshot = snapshot()

        assertTrue(manager.saveMonitorProgress(snapshot))
        assertEquals(5, manager.getMonitorSchemaVersion())
        assertEquals(snapshot, manager.loadMonitorProgress())

        rawPreferences().edit()
            .putLong(KEY_REMAINING_MILLIS, snapshot.remainingMillis - 1L)
            .commit()

        assertNull(manager.loadMonitorProgress())
        assertEquals(
            MonitorProgressReadStatus.CORRUPTED,
            manager.inspectMonitorProgress().status
        )
        assertTrue(manager.isMonitorActive())
    }

    @Test
    fun schema5缺少熄屏恢复余量字段时拒绝不完整快照() {
        val manager = PreferenceManager(context)
        assertTrue(manager.saveMonitorProgress(snapshot()))

        rawPreferences().edit()
            .remove(KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS)
            .commit()

        assertNull(manager.loadMonitorProgress())
        assertEquals(MonitorProgressReadStatus.MISSING, manager.inspectMonitorProgress().status)
    }

    @Test
    fun v6的schema2快照可读取并在下一次保存时升级为schema5() {
        val legacyV6Snapshot = snapshot().copy(screenOffRecoveryRemainderMillis = 0L)
        rawPreferences().edit()
            .putBoolean(KEY_MONITOR_ACTIVE, true)
            .putInt(KEY_MONITOR_SCHEMA, 2)
            .putString(KEY_MONITOR_PHASE, legacyV6Snapshot.phase.storedValue)
            .putLong(KEY_REMAINING_MILLIS, legacyV6Snapshot.remainingMillis)
            .putLong(KEY_CHECKPOINT_ELAPSED_MILLIS, legacyV6Snapshot.checkpointElapsedMillis)
            .putInt(KEY_BOOT_COUNT, legacyV6Snapshot.bootCount)
            .putBoolean(KEY_INTERACTIVE, legacyV6Snapshot.isInteractive)
            .commit()

        val manager = PreferenceManager(context)
        val loaded = manager.loadMonitorProgress()
        assertEquals(legacyV6Snapshot, loaded)

        assertTrue(manager.saveMonitorProgress(requireNotNull(loaded)))
        assertEquals(5, manager.getMonitorSchemaVersion())
        assertTrue(rawPreferences().contains(KEY_SNAPSHOT_CHECKSUM))
    }

    @Test
    fun schema3缺少摘要时默认保留活动标志但拒绝损坏快照() {
        val value = snapshot()
        rawPreferences().edit()
            .putBoolean(KEY_MONITOR_ACTIVE, true)
            .putInt(KEY_MONITOR_SCHEMA, 3)
            .putString(KEY_MONITOR_PHASE, value.phase.storedValue)
            .putLong(KEY_REMAINING_MILLIS, value.remainingMillis)
            .putLong(KEY_CHECKPOINT_ELAPSED_MILLIS, value.checkpointElapsedMillis)
            .putInt(KEY_BOOT_COUNT, value.bootCount)
            .putBoolean(KEY_INTERACTIVE, value.isInteractive)
            .commit()

        val manager = PreferenceManager(context)

        assertNull(manager.loadMonitorProgress())
        assertEquals(MonitorProgressReadStatus.MISSING, manager.inspectMonitorProgress().status)
        assertTrue(manager.isMonitorActive())
    }

    @Test
    fun schema5暂停状态与监督快照原子往返() {
        val manager = PreferenceManager(context)
        val pause = MonitorPauseState(
            accumulatedPauseMillis = 300_000L,
            startedAtEpochMillis = 1_000_000L,
            untilEpochMillis = 1_300_000L,
            deadlineElapsedMillis = 400_000L,
            bootCount = 5
        )

        assertTrue(manager.saveMonitorProgress(snapshot(), pause))

        val result = manager.inspectMonitorProgress()
        assertEquals(MonitorProgressReadStatus.AVAILABLE, result.status)
        assertEquals(snapshot(), result.snapshot)
        assertEquals(pause, result.pauseState)
    }

    @Test
    fun 已生效成长订单标记与快照原子保存且不随监督清除() {
        val manager = PreferenceManager(context)
        val firstOrder = "pause_order_1234"
        val secondOrder = "skip_order_5678"

        assertTrue(
            manager.saveMonitorProgress(
                snapshot = snapshot(),
                appliedGrowthOrderIds = setOf(firstOrder)
            )
        )
        assertTrue(
            manager.saveMonitorProgress(
                snapshot = snapshot(),
                appliedGrowthOrderIds = setOf(secondOrder)
            )
        )
        assertEquals(setOf(firstOrder, secondOrder), manager.getAppliedGrowthOrderIds())

        assertTrue(manager.clearMonitorProgress())
        assertEquals(setOf(firstOrder, secondOrder), manager.getAppliedGrowthOrderIds())
        assertTrue(manager.clearAppliedGrowthOrderId(firstOrder))
        assertEquals(setOf(secondOrder), manager.getAppliedGrowthOrderIds())
        assertTrue(manager.clearAppliedGrowthOrderId(secondOrder))
        assertTrue(manager.getAppliedGrowthOrderIds().isEmpty())
    }

    @Test
    fun 非法负数快照不会覆盖已有恢复状态() {
        val manager = PreferenceManager(context)

        assertFalse(manager.saveMonitorProgress(snapshot().copy(remainingMillis = -1L)))
        assertFalse(manager.isMonitorActive())
        assertNull(manager.loadMonitorProgress())
        assertEquals(MonitorProgressReadStatus.INACTIVE, manager.inspectMonitorProgress().status)
    }

    @Test
    fun 定时监督来源与快照同步清除但本次跳过状态保留() {
        val manager = PreferenceManager(context)
        val owner = ScheduledMonitorOwner("task-a", 10L, 1_000_000L)

        assertTrue(
            manager.saveMonitorProgress(
                snapshot = snapshot(),
                scheduledOwner = owner,
                usageMinutes = 30,
                lockMinutes = 5
            )
        )
        assertEquals(
            owner,
            (manager.inspectScheduledMonitorOwner() as ScheduledOwnerReadResult.Available).owner
        )
        assertTrue(manager.suppressScheduledOccurrence(owner))

        assertTrue(manager.clearMonitorProgress())
        assertEquals(ScheduledOwnerReadResult.None, manager.inspectScheduledMonitorOwner())
        assertEquals(owner.planId, manager.getScheduledOccurrenceSuppression(0L)?.planId)
    }

    @Test
    fun 定时监督来源字段不完整时拒绝误判为普通来源() {
        rawPreferences().edit()
            .putString(KEY_MONITOR_ORIGIN, "scheduled")
            .putString(KEY_SCHEDULED_PLAN_ID, "task-a")
            .commit()

        assertEquals(
            ScheduledOwnerReadResult.Corrupted,
            PreferenceManager(context).inspectScheduledMonitorOwner()
        )
    }

    @Test
    fun 手动监督快照会原子替换定时来源和计时参数() {
        val manager = PreferenceManager(context)
        val owner = ScheduledMonitorOwner("task-a", 10L, 1_000_000L)
        assertTrue(manager.saveMonitorProgress(snapshot(), owner, 30, 5))

        assertTrue(manager.saveMonitorProgress(snapshot(), null, 45, 10))

        assertEquals(ScheduledOwnerReadResult.None, manager.inspectScheduledMonitorOwner())
        assertEquals(45, manager.getUsageTime())
        assertEquals(10, manager.getLockTime())
    }

    @Test
    fun 专注快照独立保存模式和时长并在结束后保留任务参数() {
        val manager = PreferenceManager(context)

        assertTrue(
            manager.saveMonitorProgress(
                snapshot = snapshot(),
                scheduledOwner = null,
                usageMinutes = 3,
                lockMinutes = 15,
                sessionMode = MonitorSessionMode.FOCUS
            )
        )

        assertEquals(MonitorSessionMode.FOCUS, manager.getMonitorSessionMode())
        assertEquals(15, manager.getFocusLockTime())
        assertEquals(3, manager.getFocusPlayTime())
        assertEquals(30, manager.getUsageTime())
        assertEquals(5, manager.getLockTime())

        assertTrue(manager.clearMonitorProgress())
        assertEquals(MonitorSessionMode.SUPERVISION, manager.getMonitorSessionMode())
        assertEquals(15, manager.getFocusLockTime())
        assertEquals(3, manager.getFocusPlayTime())
    }

    private fun rawPreferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun snapshot() = MonitorCycleSnapshot(
        phase = MonitorPhase.LOCK,
        remainingMillis = 42_000L,
        checkpointElapsedMillis = 123_000L,
        bootCount = 5,
        isInteractive = false,
        screenOffRecoveryRemainderMillis = 120_000L
    )

    private companion object {
        const val PREFERENCES_NAME = "control_free_prefs"
        const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"
        const val KEY_MONITOR_ACTIVE = "monitor_active"
        const val KEY_MONITOR_SCHEMA = "monitor_schema"
        const val KEY_MONITOR_PHASE = "monitor_phase"
        const val KEY_REMAINING_MILLIS = "monitor_remaining_millis"
        const val KEY_CHECKPOINT_ELAPSED_MILLIS = "monitor_checkpoint_elapsed_millis"
        const val KEY_BOOT_COUNT = "monitor_boot_count"
        const val KEY_INTERACTIVE = "monitor_interactive"
        const val KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS =
            "monitor_screen_off_recovery_remainder_millis"
        const val KEY_SNAPSHOT_CHECKSUM = "monitor_snapshot_checksum"
        const val KEY_MONITOR_ORIGIN = "monitor_origin"
        const val KEY_SCHEDULED_PLAN_ID = "scheduled_plan_id"
    }
}
