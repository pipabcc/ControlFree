package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorProgressRestorerTest {
    private val cycle = MonitorCycle(
        usageDurationMillis = 60_000L,
        lockDurationMillis = 30_000L
    )
    private val restorer = MonitorProgressRestorer(cycle)
    private val focusCycle = MonitorCycle(
        usageDurationMillis = 60_000L,
        lockDurationMillis = 30_000L,
        initialPhase = MonitorPhase.LOCK,
        lockCountsDownWhileInteractive = true
    )
    private val focusRestorer = MonitorProgressRestorer(focusCycle)

    @Test
    fun `同次开机且持续亮屏时结算缺席的玩机时间`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.USAGE, 60_000L, 10_000L, 8, true),
            nowElapsedMillis = 25_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.USAGE, restored.phase)
        assertEquals(45_000L, restored.remainingMillis)
        assertEquals(25_000L, restored.checkpointElapsedMillis)
        assertEquals(8, restored.bootCount)
        assertEquals(true, restored.isInteractive)
    }

    @Test
    fun `同次开机且持续熄屏时结算缺席的锁定时间`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 25_000L, 10_000L, 8, false),
            nowElapsedMillis = 20_000L,
            currentBootCount = 8,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(15_000L, restored.remainingMillis)
    }

    @Test
    fun `专注锁定恢复时亮屏变熄屏仍结算完整缺席时间`() {
        val restored = focusRestorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 25_000L, 10_000L, 8, true),
            nowElapsedMillis = 20_000L,
            currentBootCount = 8,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(15_000L, restored.remainingMillis)
        assertEquals(20_000L, restored.checkpointElapsedMillis)
        assertEquals(false, restored.isInteractive)
    }

    @Test
    fun `专注锁定恢复时熄屏变亮屏仍结算完整缺席时间`() {
        val restored = focusRestorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 25_000L, 10_000L, 8, false),
            nowElapsedMillis = 20_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(15_000L, restored.remainingMillis)
        assertEquals(true, restored.isInteractive)
    }

    @Test
    fun `专注锁定跨未知亮灭屏边沿到期时只进入完整玩机阶段`() {
        val restored = focusRestorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 5_000L, 10_000L, 8, true),
            nowElapsedMillis = 20_000L,
            currentBootCount = 8,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.USAGE, restored.phase)
        assertEquals(60_000L, restored.remainingMillis)
        assertEquals(20_000L, restored.checkpointElapsedMillis)
        assertEquals(false, restored.isInteractive)
    }

    @Test
    fun `同次开机时玩机熄屏恢复未满周期且普通锁定亮屏暂停`() {
        val usageWhileOff = restorer.restore(
            saved = snapshot(MonitorPhase.USAGE, 40_000L, 10_000L, 8, false),
            nowElapsedMillis = 25_000L,
            currentBootCount = 8,
            currentInteractive = false
        )
        val lockWhileOn = restorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 20_000L, 10_000L, 8, true),
            nowElapsedMillis = 25_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(40_000L, usageWhileOff.remainingMillis)
        assertEquals(20_000L, lockWhileOn.remainingMillis)
        assertEquals(25_000L, usageWhileOff.checkpointElapsedMillis)
        assertEquals(25_000L, lockWhileOn.checkpointElapsedMillis)
    }

    @Test
    fun `进程缺席期间屏幕状态变化则不猜测扣时`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.USAGE, 40_000L, 10_000L, 8, true),
            nowElapsedMillis = 50_000L,
            currentBootCount = 8,
            currentInteractive = false
        )

        assertEquals(40_000L, restored.remainingMillis)
        assertEquals(false, restored.isInteractive)
    }

    @Test
    fun `普通监督从已知熄屏恢复亮屏时结算缺席锁定时间`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 20_000L, 10_000L, 8, false),
            nowElapsedMillis = 25_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(5_000L, restored.remainingMillis)
        assertEquals(25_000L, restored.checkpointElapsedMillis)
        assertEquals(true, restored.isInteractive)
    }

    @Test
    fun `同次开机缺席区间超过剩余预算时只切换一个阶段`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.USAGE, 5_000L, 10_000L, 8, true),
            nowElapsedMillis = 100_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(30_000L, restored.remainingMillis)
        assertEquals(100_000L, restored.checkpointElapsedMillis)
    }

    @Test
    fun `系统重启后关机区间不计入任何阶段`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 20_000L, 90_000L, 8, false),
            nowElapsedMillis = 5_000L,
            currentBootCount = 9,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(20_000L, restored.remainingMillis)
        assertEquals(5_000L, restored.checkpointElapsedMillis)
    }

    @Test
    fun `无法读取开机计数时仍不补扣未知间隔`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 20_000L, 1_000L, -1, false),
            nowElapsedMillis = 100_000L,
            currentBootCount = -1,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(20_000L, restored.remainingMillis)
    }

    @Test
    fun `单调时钟倒退时不补扣并使用当前环境重建锚点`() {
        val restored = restorer.restore(
            saved = snapshot(MonitorPhase.USAGE, 40_000L, 50_000L, 8, true),
            nowElapsedMillis = 5_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.USAGE, restored.phase)
        assertEquals(40_000L, restored.remainingMillis)
        assertEquals(5_000L, restored.checkpointElapsedMillis)
    }

    @Test
    fun `专注锁定恢复时单调时钟倒退仍不补扣`() {
        val restored = focusRestorer.restore(
            saved = snapshot(MonitorPhase.LOCK, 20_000L, 50_000L, 8, false),
            nowElapsedMillis = 5_000L,
            currentBootCount = 8,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, restored.phase)
        assertEquals(20_000L, restored.remainingMillis)
        assertEquals(5_000L, restored.checkpointElapsedMillis)
        assertEquals(true, restored.isInteractive)
    }

    @Test
    fun `旧任务截止时间尚未来到则保留并钳制剩余值`() {
        val migrated = restorer.migrateLegacy(
            legacyPhase = MonitorPhase.USAGE,
            legacyPhaseEndsAtWallMillis = 200_000L,
            nowWallMillis = 100_000L,
            nowElapsedMillis = 4_000L,
            currentBootCount = 3,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.USAGE, migrated.phase)
        assertEquals(60_000L, migrated.remainingMillis)
    }

    @Test
    fun `旧任务恰好到期时进入完整下一阶段`() {
        val migrated = restorer.migrateLegacy(
            legacyPhase = MonitorPhase.USAGE,
            legacyPhaseEndsAtWallMillis = 100_000L,
            nowWallMillis = 100_000L,
            nowElapsedMillis = 4_000L,
            currentBootCount = 3,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, migrated.phase)
        assertEquals(30_000L, migrated.remainingMillis)
    }

    @Test
    fun `旧任务跨越多个周期时使用常数时间迁移`() {
        val migrated = restorer.migrateLegacy(
            legacyPhase = MonitorPhase.USAGE,
            legacyPhaseEndsAtWallMillis = 10_000L,
            nowWallMillis = 205_000L,
            nowElapsedMillis = 4_000L,
            currentBootCount = 3,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.LOCK, migrated.phase)
        assertEquals(15_000L, migrated.remainingMillis)
    }

    @Test
    fun `旧任务跨越十亿周期仍在常数时间恢复边界`() {
        val cycleDuration = 90_000L
        val legacyEnd = 1_000L
        val migrated = restorer.migrateLegacy(
            legacyPhase = MonitorPhase.USAGE,
            legacyPhaseEndsAtWallMillis = legacyEnd,
            nowWallMillis = legacyEnd + cycleDuration * 1_000_000_000L,
            nowElapsedMillis = 4_000L,
            currentBootCount = 3,
            currentInteractive = false
        )

        assertEquals(MonitorPhase.LOCK, migrated.phase)
        assertEquals(30_000L, migrated.remainingMillis)
    }

    @Test
    fun `损坏的旧截止时间保留当前阶段完整预算`() {
        val migrated = restorer.migrateLegacy(
            legacyPhase = MonitorPhase.LOCK,
            legacyPhaseEndsAtWallMillis = 0L,
            nowWallMillis = 100_000L,
            nowElapsedMillis = 4_000L,
            currentBootCount = -1,
            currentInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, migrated.phase)
        assertEquals(30_000L, migrated.remainingMillis)
    }

    private fun snapshot(
        phase: MonitorPhase,
        remainingMillis: Long,
        checkpointElapsedMillis: Long,
        bootCount: Int,
        isInteractive: Boolean
    ) = MonitorCycleSnapshot(
        phase = phase,
        remainingMillis = remainingMillis,
        checkpointElapsedMillis = checkpointElapsedMillis,
        bootCount = bootCount,
        isInteractive = isInteractive
    )
}
