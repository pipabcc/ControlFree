package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedLockDecayCalculatorTest {
    @Test
    fun `普通监督熄屏区间按单调时钟递减`() {
        val settled = settle(
            decay = decay(remainingMillis = 60_000L, settledInteractive = false),
            nowElapsedMillis = NOW + 3_000L,
            isInteractive = false
        )

        assertEquals(57_000L, settled.remainingMillis)
        assertEquals(NOW + 3_000L, settled.settledAtElapsedMillis)
    }

    @Test
    fun `普通监督亮屏且心跳连续时不递减`() {
        val settled = settle(
            decay = decay(remainingMillis = 60_000L, settledInteractive = true),
            nowElapsedMillis = NOW + 3_000L,
            isInteractive = true
        )

        // 亮屏锁定阶段本就不计时；杀掉服务不能成为加速解锁的手段。
        assertEquals(60_000L, settled.remainingMillis)
    }

    @Test
    fun `专注模式亮屏也递减`() {
        val settled = settle(
            decay = decay(remainingMillis = 60_000L, settledInteractive = true),
            nowElapsedMillis = NOW + 3_000L,
            isInteractive = true,
            lockCountsDownWhileInteractive = true
        )

        assertEquals(57_000L, settled.remainingMillis)
    }

    @Test
    fun `观察心跳断档的区间按熄屏结算避免倒计时冻结`() {
        val settled = settle(
            decay = decay(remainingMillis = 600_000L, settledInteractive = true),
            // 心跳只在界面 RESUMED 时运行，断档远超容忍间隔即视为熄屏。
            nowElapsedMillis = NOW + 120_000L,
            isInteractive = true
        )

        assertEquals(480_000L, settled.remainingMillis)
    }

    @Test
    fun `容忍间隔内的亮屏断档仍按亮屏结算`() {
        val settled = settle(
            decay = decay(remainingMillis = 60_000L, settledInteractive = true),
            nowElapsedMillis =
                NOW + RetainedLockDecayCalculator.OBSERVATION_GAP_TOLERANCE_MILLIS,
            isInteractive = true
        )

        assertEquals(60_000L, settled.remainingMillis)
    }

    @Test
    fun `剩余时间不会跌破零`() {
        val settled = settle(
            decay = decay(remainingMillis = 1_000L, settledInteractive = false),
            nowElapsedMillis = NOW + 90_000L,
            isInteractive = false
        )

        assertEquals(0L, settled.remainingMillis)
    }

    @Test
    fun `单调时钟回退时不产生负向结算`() {
        val settled = settle(
            decay = decay(remainingMillis = 60_000L, settledInteractive = false),
            nowElapsedMillis = NOW - 5_000L,
            isInteractive = false
        )

        assertEquals(60_000L, settled.remainingMillis)
        assertEquals(NOW - 5_000L, settled.settledAtElapsedMillis)
    }

    @Test
    fun `结算后记录本次观察到的屏幕状态`() {
        val settled = settle(
            decay = decay(remainingMillis = 60_000L, settledInteractive = false),
            nowElapsedMillis = NOW + 1_000L,
            isInteractive = true
        )

        assertTrue(settled.settledInteractive)
    }

    @Test
    fun `绝对上限以运行时失联时刻为锚点`() {
        val decay = decay(remainingMillis = 600_000L, settledInteractive = false)
            .copy(ceilingMillis = 1_200_000L)

        assertFalse(
            RetainedLockDecayCalculator.isCeilingReached(decay, NOW + 1_199_000L)
        )
        assertTrue(
            RetainedLockDecayCalculator.isCeilingReached(decay, NOW + 1_200_000L)
        )
    }

    @Test
    fun `兜底上限取两倍锁定时长并夹在可感知区间内`() {
        assertEquals(
            20L * 60_000L,
            RetainedLockDecayCalculator.ceilingMillisFor(10L * 60_000L)
        )
        // 过短的锁定时长仍需给出可感知的兜底窗口。
        assertEquals(
            5L * 60_000L,
            RetainedLockDecayCalculator.ceilingMillisFor(60_000L)
        )
        // 过长的锁定时长不能让逃生舱形同虚设。
        assertEquals(
            60L * 60_000L,
            RetainedLockDecayCalculator.ceilingMillisFor(10L * 60L * 60_000L)
        )
    }

    private fun decay(
        remainingMillis: Long,
        settledInteractive: Boolean
    ) = RetainedLockDecay(
        unavailableSinceElapsedMillis = NOW,
        settledAtElapsedMillis = NOW,
        settledInteractive = settledInteractive,
        remainingMillis = remainingMillis,
        ceilingMillis = 1_200_000L
    )

    private fun settle(
        decay: RetainedLockDecay,
        nowElapsedMillis: Long,
        isInteractive: Boolean,
        lockCountsDownWhileInteractive: Boolean = false
    ) = RetainedLockDecayCalculator.settle(
        decay = decay,
        nowElapsedMillis = nowElapsedMillis,
        isInteractive = isInteractive,
        lockCountsDownWhileInteractive = lockCountsDownWhileInteractive
    )

    private companion object {
        const val NOW = 10_000_000L
    }
}
