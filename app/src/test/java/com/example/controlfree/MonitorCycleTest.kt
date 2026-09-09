package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorCycleTest {
    @Test
    fun `专注模式从完整锁定阶段开始`() {
        val cycle = MonitorCycle(
            usageDurationMillis = 60_000L,
            lockDurationMillis = 300_000L,
            initialPhase = MonitorSessionMode.FOCUS.initialPhase,
            lockCountsDownWhileInteractive = true
        )

        val started = cycle.start(nowElapsedMillis = 10L, bootCount = 1, isInteractive = true)

        assertEquals(MonitorPhase.LOCK, started.phase)
        assertEquals(300_000L, started.remainingMillis)
    }

    @Test
    fun `专注锁定阶段亮屏和熄屏都正常扣减`() {
        val focusCycle = MonitorCycle(
            usageDurationMillis = 60_000L,
            lockDurationMillis = 30_000L,
            initialPhase = MonitorPhase.LOCK,
            lockCountsDownWhileInteractive = true
        )
        val screenOn = focusCycle.advance(
            lockSnapshot(remainingMillis = 30_000L, isInteractive = true),
            nowElapsedMillis = 6_000L
        )
        val screenOff = focusCycle.advance(
            lockSnapshot(remainingMillis = 30_000L, isInteractive = false),
            nowElapsedMillis = 6_000L
        )

        assertEquals(25_000L, screenOn.remainingMillis)
        assertEquals(25_000L, screenOff.remainingMillis)
    }

    @Test
    fun `专注锁定阶段亮灭屏边沿不会中断倒计时`() {
        val focusCycle = MonitorCycle(
            usageDurationMillis = 60_000L,
            lockDurationMillis = 30_000L,
            initialPhase = MonitorPhase.LOCK,
            lockCountsDownWhileInteractive = true
        )
        val changedToOff = focusCycle.updateInteractiveState(
            snapshot = lockSnapshot(remainingMillis = 30_000L, isInteractive = true),
            nowElapsedMillis = 6_000L,
            isInteractive = false
        )
        val changedBackToOn = focusCycle.updateInteractiveState(
            snapshot = changedToOff,
            nowElapsedMillis = 11_000L,
            isInteractive = true
        )

        assertEquals(25_000L, changedToOff.remainingMillis)
        assertEquals(false, changedToOff.isInteractive)
        assertEquals(20_000L, changedBackToOn.remainingMillis)
        assertEquals(true, changedBackToOn.isInteractive)
    }

    @Test
    fun `只有专注锁定可跨越未知亮灭屏边沿结算`() {
        val focusCycle = MonitorCycle(
            usageDurationMillis = 60_000L,
            lockDurationMillis = 30_000L,
            lockCountsDownWhileInteractive = true
        )

        assertEquals(
            true,
            focusCycle.canSettleAcrossInteractiveStateChange(MonitorPhase.LOCK)
        )
        assertEquals(
            false,
            focusCycle.canSettleAcrossInteractiveStateChange(MonitorPhase.USAGE)
        )
        assertEquals(
            false,
            cycle.canSettleAcrossInteractiveStateChange(MonitorPhase.LOCK)
        )
    }

    @Test
    fun `专注锁定结束后玩机阶段仍只在亮屏时计时`() {
        val focusCycle = MonitorCycle(
            usageDurationMillis = 60_000L,
            lockDurationMillis = 30_000L,
            initialPhase = MonitorPhase.LOCK,
            lockCountsDownWhileInteractive = true
        )
        val usage = focusCycle.advance(
            lockSnapshot(remainingMillis = 5_000L, isInteractive = true),
            nowElapsedMillis = 6_000L
        )
        val screenOff = focusCycle.advance(
            usage.copy(isInteractive = false),
            nowElapsedMillis = 16_000L
        )

        assertEquals(MonitorPhase.USAGE, usage.phase)
        assertEquals(60_000L, usage.remainingMillis)
        assertEquals(60_000L, screenOff.remainingMillis)
    }

    private val cycle = MonitorCycle(
        usageDurationMillis = 60_000L,
        lockDurationMillis = 30_000L
    )

    @Test
    fun `启动时创建完整玩机阶段并记录运行环境`() {
        val started = cycle.start(
            nowElapsedMillis = 1_000L,
            bootCount = 7,
            isInteractive = true
        )

        assertEquals(MonitorPhase.USAGE, started.phase)
        assertEquals(60_000L, started.remainingMillis)
        assertEquals(1_000L, started.checkpointElapsedMillis)
        assertEquals(7, started.bootCount)
        assertEquals(true, started.isInteractive)
    }

    @Test
    fun `玩机阶段亮屏时正常扣减`() {
        val snapshot = usageSnapshot(remainingMillis = 60_000L, isInteractive = true)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 11_000L)

        assertEquals(MonitorPhase.USAGE, advanced.phase)
        assertEquals(50_000L, advanced.remainingMillis)
        assertEquals(11_000L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `玩机阶段熄屏不足五分钟不恢复额度`() {
        val snapshot = usageSnapshot(remainingMillis = 42_000L, isInteractive = false)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 31_000L)

        assertEquals(MonitorPhase.USAGE, advanced.phase)
        assertEquals(42_000L, advanced.remainingMillis)
        assertEquals(31_000L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `玩机阶段熄屏每满五分钟恢复一分钟`() {
        val longUsageCycle = MonitorCycle(
            usageDurationMillis = 30L * 60_000L,
            lockDurationMillis = 5L * 60_000L
        )
        val snapshot = MonitorCycleSnapshot(
            phase = MonitorPhase.USAGE,
            remainingMillis = 10L * 60_000L,
            checkpointElapsedMillis = 1_000L,
            bootCount = 7,
            isInteractive = false
        )

        val fourMinutes = longUsageCycle.advance(snapshot, 4L * 60_000L + 1_000L)
        val fiveMinutes = longUsageCycle.advance(fourMinutes, 5L * 60_000L + 1_000L)
        val fifteenMinutes = longUsageCycle.advance(fiveMinutes, 15L * 60_000L + 1_000L)

        assertEquals(10L * 60_000L, fourMinutes.remainingMillis)
        assertEquals(4L * 60_000L, fourMinutes.screenOffRecoveryRemainderMillis)
        assertEquals(11L * 60_000L, fiveMinutes.remainingMillis)
        assertEquals(0L, fiveMinutes.screenOffRecoveryRemainderMillis)
        assertEquals(13L * 60_000L, fifteenMinutes.remainingMillis)
    }

    @Test
    fun `熄屏恢复额度不会超过本阶段完整玩机时长`() {
        val snapshot = usageSnapshot(remainingMillis = 55_000L, isInteractive = false)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 10L * 60_000L + 1_000L)

        assertEquals(60_000L, advanced.remainingMillis)
    }

    @Test
    fun `多次短熄屏累计满五分钟后恢复一分钟`() {
        val longUsageCycle = MonitorCycle(
            usageDurationMillis = 30L * 60_000L,
            lockDurationMillis = 5L * 60_000L
        )
        val snapshot = MonitorCycleSnapshot(
            phase = MonitorPhase.USAGE,
            remainingMillis = 10L * 60_000L,
            checkpointElapsedMillis = 1_000L,
            bootCount = 7,
            isInteractive = false
        )

        val first = longUsageCycle.advance(snapshot, 3L * 60_000L + 1_000L)
        val screenOn = longUsageCycle.updateInteractiveState(
            first,
            3L * 60_000L + 1_000L,
            true
        )
        val screenOff = longUsageCycle.updateInteractiveState(
            screenOn,
            3L * 60_000L + 1_000L,
            false
        )
        val second = longUsageCycle.advance(screenOff, 5L * 60_000L + 1_000L)

        assertEquals(11L * 60_000L, second.remainingMillis)
        assertEquals(0L, second.screenOffRecoveryRemainderMillis)
    }

    @Test
    fun `普通监督锁定阶段亮屏时暂停倒计时`() {
        val snapshot = lockSnapshot(remainingMillis = 24_000L, isInteractive = true)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 21_000L)

        assertEquals(MonitorPhase.LOCK, advanced.phase)
        assertEquals(24_000L, advanced.remainingMillis)
        assertEquals(21_000L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `锁定阶段熄屏时正常扣减`() {
        val snapshot = lockSnapshot(remainingMillis = 30_000L, isInteractive = false)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 13_500L)

        assertEquals(MonitorPhase.LOCK, advanced.phase)
        assertEquals(17_500L, advanced.remainingMillis)
        assertEquals(13_500L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `从亮屏切到熄屏会先结算玩机时间`() {
        val snapshot = usageSnapshot(remainingMillis = 60_000L, isInteractive = true)

        val screenOff = cycle.updateInteractiveState(
            snapshot = snapshot,
            nowElapsedMillis = 11_000L,
            isInteractive = false
        )
        val stillOff = cycle.advance(screenOff, nowElapsedMillis = 31_000L)

        assertEquals(50_000L, screenOff.remainingMillis)
        assertEquals(false, screenOff.isInteractive)
        assertEquals(50_000L, stillOff.remainingMillis)
    }

    @Test
    fun `从熄屏切到亮屏会先结算普通监督锁定时间并随后暂停`() {
        val snapshot = lockSnapshot(remainingMillis = 30_000L, isInteractive = false)

        val screenOn = cycle.updateInteractiveState(
            snapshot = snapshot,
            nowElapsedMillis = 9_000L,
            isInteractive = true
        )
        val stillOn = cycle.advance(screenOn, nowElapsedMillis = 19_000L)

        assertEquals(22_000L, screenOn.remainingMillis)
        assertEquals(true, screenOn.isInteractive)
        assertEquals(22_000L, stillOn.remainingMillis)
    }

    @Test
    fun `玩机恰好到期并熄屏后新锁定立即开始按熄屏计时`() {
        val screenOff = cycle.updateInteractiveState(
            snapshot = usageSnapshot(remainingMillis = 10_000L),
            nowElapsedMillis = 11_000L,
            isInteractive = false
        )
        val oneMillisLater = cycle.advance(screenOff, 11_001L)

        assertEquals(MonitorPhase.LOCK, screenOff.phase)
        assertEquals(30_000L, screenOff.remainingMillis)
        assertEquals(false, screenOff.isInteractive)
        assertEquals(29_999L, oneMillisLater.remainingMillis)
    }

    @Test
    fun `锁定恰好到期并亮屏后新玩机立即开始按亮屏计时`() {
        val screenOn = cycle.updateInteractiveState(
            snapshot = lockSnapshot(remainingMillis = 10_000L),
            nowElapsedMillis = 11_000L,
            isInteractive = true
        )
        val oneMillisLater = cycle.advance(screenOn, 11_001L)

        assertEquals(MonitorPhase.USAGE, screenOn.phase)
        assertEquals(60_000L, screenOn.remainingMillis)
        assertEquals(true, screenOn.isInteractive)
        assertEquals(59_999L, oneMillisLater.remainingMillis)
    }

    @Test
    fun `重复屏幕事件不会重置或重复扣减`() {
        val snapshot = usageSnapshot(remainingMillis = 60_000L, isInteractive = true)

        val firstEvent = cycle.updateInteractiveState(snapshot, 6_000L, true)
        val repeatedEvent = cycle.updateInteractiveState(firstEvent, 11_000L, true)

        assertEquals(55_000L, firstEvent.remainingMillis)
        assertEquals(50_000L, repeatedEvent.remainingMillis)
        assertEquals(11_000L, repeatedEvent.checkpointElapsedMillis)
    }

    @Test
    fun `玩机阶段超时只切换一次并丢弃超额时间`() {
        val snapshot = usageSnapshot(remainingMillis = 10_000L, isInteractive = true)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 101_000L)

        assertEquals(MonitorPhase.LOCK, advanced.phase)
        assertEquals(30_000L, advanced.remainingMillis)
        assertEquals(101_000L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `锁定阶段超时只切换一次并丢弃超额时间`() {
        val snapshot = lockSnapshot(remainingMillis = 5_000L, isInteractive = false)

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 101_000L)

        assertEquals(MonitorPhase.USAGE, advanced.phase)
        assertEquals(60_000L, advanced.remainingMillis)
        assertEquals(101_000L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `单调时钟倒退时不扣时间并建立新锚点`() {
        val snapshot = usageSnapshot(
            remainingMillis = 45_000L,
            checkpointElapsedMillis = 20_000L,
            isInteractive = true
        )

        val advanced = cycle.advance(snapshot, nowElapsedMillis = 5_000L)

        assertEquals(MonitorPhase.USAGE, advanced.phase)
        assertEquals(45_000L, advanced.remainingMillis)
        assertEquals(5_000L, advanced.checkpointElapsedMillis)
    }

    @Test
    fun `单调时钟接近长整型上限时只扣真实差值`() {
        val snapshot = usageSnapshot(
            remainingMillis = 45_000L,
            checkpointElapsedMillis = Long.MAX_VALUE - 5L,
            isInteractive = true
        )

        val advanced = cycle.advance(snapshot, Long.MAX_VALUE)

        assertEquals(44_995L, advanced.remainingMillis)
    }

    @Test
    fun `剩余时间会被钳制到当前阶段合法范围`() {
        val tooLarge = usageSnapshot(remainingMillis = 90_000L, isInteractive = false)
        val negative = usageSnapshot(remainingMillis = -1L, isInteractive = false)

        val bounded = cycle.advance(tooLarge, nowElapsedMillis = 2_000L)
        val transitioned = cycle.advance(negative, nowElapsedMillis = 2_000L)

        assertEquals(60_000L, bounded.remainingMillis)
        assertEquals(MonitorPhase.LOCK, transitioned.phase)
        assertEquals(30_000L, transitioned.remainingMillis)
    }

    @Test
    fun `重建锚点保留合法剩余时间且不结算不可观测间隔`() {
        val snapshot = lockSnapshot(
            remainingMillis = 18_000L,
            checkpointElapsedMillis = 1_000L,
            bootCount = 3,
            isInteractive = false
        )

        val reanchored = cycle.reanchor(
            snapshot = snapshot,
            nowElapsedMillis = 500L,
            bootCount = 4,
            isInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, reanchored.phase)
        assertEquals(18_000L, reanchored.remainingMillis)
        assertEquals(500L, reanchored.checkpointElapsedMillis)
        assertEquals(4, reanchored.bootCount)
        assertEquals(true, reanchored.isInteractive)
    }

    @Test
    fun `重建锚点遇到零预算时直接进入完整下一阶段`() {
        val reanchored = cycle.reanchor(
            snapshot = usageSnapshot(remainingMillis = 0L),
            nowElapsedMillis = 5_000L,
            bootCount = 4,
            isInteractive = true
        )

        assertEquals(MonitorPhase.LOCK, reanchored.phase)
        assertEquals(30_000L, reanchored.remainingMillis)
    }

    @Test
    fun `锁动作候选提交时交互状态相同也会更新检查点`() {
        val candidate = lockSnapshot(
            remainingMillis = 18_000L,
            checkpointElapsedMillis = 1_000L,
            isInteractive = false
        )

        val committed = cycle.reanchor(
            snapshot = candidate,
            nowElapsedMillis = 9_000L,
            bootCount = candidate.bootCount,
            isInteractive = candidate.isInteractive
        )

        assertEquals(9_000L, committed.checkpointElapsedMillis)
        assertEquals(18_000L, committed.remainingMillis)
        assertEquals(candidate.isInteractive, committed.isInteractive)
    }

    @Test
    fun `倒计时秒数向上取整并钳制异常值`() {
        assertEquals(3, cycle.remainingSeconds(usageSnapshot(2_001L)))
        assertEquals(2, cycle.remainingSeconds(usageSnapshot(2_000L)))
        assertEquals(1, cycle.remainingSeconds(usageSnapshot(1L)))
        assertEquals(0, cycle.remainingSeconds(usageSnapshot(-1L)))
        assertEquals(60, cycle.remainingSeconds(usageSnapshot(90_000L)))
    }

    @Test
    fun `完整流程按亮屏玩机和普通监督熄屏锁定循环运行`() {
        val started = cycle.start(1_000L, bootCount = 9, isInteractive = true)

        val partlyUsed = cycle.advance(started, 31_000L)
        val usagePaused = cycle.updateInteractiveState(partlyUsed, 41_000L, false)
        val screenOffStillUsage = cycle.advance(usagePaused, 71_000L)
        val usageResumed = cycle.updateInteractiveState(screenOffStillUsage, 81_000L, true)
        val locked = cycle.advance(usageResumed, 101_000L)
        val lockStarted = cycle.updateInteractiveState(locked, 111_000L, false)
        val partlyLocked = cycle.advance(lockStarted, 126_000L)
        val nextUsage = cycle.advance(partlyLocked, 141_000L)

        assertEquals(30_000L, partlyUsed.remainingMillis)
        assertEquals(20_000L, usagePaused.remainingMillis)
        assertEquals(20_000L, screenOffStillUsage.remainingMillis)
        assertEquals(MonitorPhase.LOCK, locked.phase)
        assertEquals(30_000L, locked.remainingMillis)
        assertEquals(15_000L, partlyLocked.remainingMillis)
        assertEquals(MonitorPhase.USAGE, nextUsage.phase)
        assertEquals(60_000L, nextUsage.remainingMillis)
        assertEquals(false, nextUsage.isInteractive)
    }

    @Test
    fun `阶段时长查询返回构造参数`() {
        assertEquals(60_000L, cycle.durationMillis(MonitorPhase.USAGE))
        assertEquals(30_000L, cycle.durationMillis(MonitorPhase.LOCK))
    }

    @Test
    fun `普通监督熄屏锁定会给出单调时钟唤醒边界`() {
        val deadline = cycle.nextPhaseDeadlineElapsedMillis(
            lockSnapshot(
                remainingMillis = 18_000L,
                checkpointElapsedMillis = 5_000L,
                isInteractive = false
            )
        )

        assertEquals(23_000L, deadline)
    }

    @Test
    fun `普通监督亮屏锁定不安排错误的倒计时边界`() {
        val deadline = cycle.nextPhaseDeadlineElapsedMillis(
            lockSnapshot(remainingMillis = 18_000L, isInteractive = true)
        )

        assertEquals(null, deadline)
    }

    private fun usageSnapshot(
        remainingMillis: Long,
        checkpointElapsedMillis: Long = 1_000L,
        bootCount: Int = 7,
        isInteractive: Boolean = true
    ): MonitorCycleSnapshot = MonitorCycleSnapshot(
        phase = MonitorPhase.USAGE,
        remainingMillis = remainingMillis,
        checkpointElapsedMillis = checkpointElapsedMillis,
        bootCount = bootCount,
        isInteractive = isInteractive
    )

    private fun lockSnapshot(
        remainingMillis: Long,
        checkpointElapsedMillis: Long = 1_000L,
        bootCount: Int = 7,
        isInteractive: Boolean = false
    ): MonitorCycleSnapshot = MonitorCycleSnapshot(
        phase = MonitorPhase.LOCK,
        remainingMillis = remainingMillis,
        checkpointElapsedMillis = checkpointElapsedMillis,
        bootCount = bootCount,
        isInteractive = isInteractive
    )
}
