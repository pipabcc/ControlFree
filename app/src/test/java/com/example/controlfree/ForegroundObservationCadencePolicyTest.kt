package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundObservationCadencePolicyTest {
    private val policy = ForegroundObservationCadencePolicy()

    @Test
    fun `熄屏或非锁定阶段停止观察`() {
        assertNull(
            policy.nextDelayMillis(
                input(now = 1_000L, isLockPhase = false, isInteractive = true)
            )
        )
        assertNull(
            policy.nextDelayMillis(
                input(now = 1_000L, isLockPhase = true, isInteractive = false)
            )
        )
    }

    @Test
    fun `亮屏 Home 最近任务和白名单启动都会进入250毫秒快速观察`() {
        ForegroundObservationCadenceTrigger.entries.forEachIndexed { index, trigger ->
            val triggerElapsed = 10_000L + index * 10_000L
            policy.reset()
            policy.onTrigger(trigger, triggerElapsed)

            assertEquals(
                ForegroundObservationCadencePolicy.RAPID_INTERVAL_MILLIS,
                policy.nextDelayMillis(input(now = triggerElapsed))
            )
            assertEquals(
                ForegroundObservationCadencePolicy.RAPID_INTERVAL_MILLIS,
                policy.nextDelayMillis(
                    input(
                        now = triggerElapsed +
                            ForegroundObservationCadencePolicy.RAPID_CADENCE_WINDOW_MILLIS - 1L
                    )
                )
            )
        }
    }

    @Test
    fun `白名单启动期间始终保持250毫秒观察`() {
        assertEquals(
            250L,
            policy.nextDelayMillis(
                input(
                    now = 1_000L,
                    mode = ForegroundObservationCadenceMode.ALLOWLIST_LAUNCHING
                )
            )
        )
        assertEquals(
            250L,
            policy.nextDelayMillis(
                input(
                    now = 20_000L,
                    mode = ForegroundObservationCadenceMode.ALLOWLIST_LAUNCHING
                )
            )
        )
    }

    @Test
    fun `白名单前台确认后先500毫秒再降频到1000毫秒`() {
        assertEquals(
            500L,
            policy.nextDelayMillis(
                input(now = 1_000L, mode = ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE)
            )
        )
        assertEquals(
            500L,
            policy.nextDelayMillis(
                input(now = 5_999L, mode = ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE)
            )
        )
        assertEquals(
            1_000L,
            policy.nextDelayMillis(
                input(now = 6_000L, mode = ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE)
            )
        )
    }

    @Test
    fun `普通锁层先1500毫秒再稳定到2000毫秒`() {
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 1_000L)))
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 10_999L)))
        assertEquals(2_000L, policy.nextDelayMillis(input(now = 11_000L)))
    }

    @Test
    fun `稳定锁层收到导航事件后立即从低频切回快速观察`() {
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 1_000L)))
        assertEquals(2_000L, policy.nextDelayMillis(input(now = 20_000L)))

        policy.onTrigger(
            ForegroundObservationCadenceTrigger.SYSTEM_NAVIGATION,
            nowElapsedMillis = 20_001L
        )

        assertEquals(250L, policy.nextDelayMillis(input(now = 20_001L)))
        assertEquals(250L, policy.nextDelayMillis(input(now = 22_000L)))
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 22_001L)))
        assertEquals(2_000L, policy.nextDelayMillis(input(now = 30_001L)))
    }

    @Test
    fun `通话切换期保持250毫秒且稳定后降到1000毫秒`() {
        assertEquals(
            250L,
            policy.nextDelayMillis(
                input(now = 1_000L, mode = ForegroundObservationCadenceMode.CALL_ACTIVE)
            )
        )
        assertEquals(
            1_000L,
            policy.nextDelayMillis(
                input(now = 20_000L, mode = ForegroundObservationCadenceMode.CALL_ACTIVE)
            )
        )
    }

    @Test
    fun `快速观察连续失败按指数退避且不超过8000毫秒`() {
        policy.onTrigger(ForegroundObservationCadenceTrigger.SCREEN_ON, 1_000L)
        val expectedDelays = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L)

        expectedDelays.forEach { expected ->
            policy.recordObservationResult(isAvailable = false)
            assertEquals(expected, policy.nextDelayMillis(input(now = 1_001L)))
        }
    }

    @Test
    fun `普通锁层失败同样指数退避且成功立即恢复基础频率`() {
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 1_000L)))
        policy.recordObservationResult(isAvailable = false)
        assertEquals(3_000L, policy.nextDelayMillis(input(now = 1_001L)))
        policy.recordObservationResult(isAvailable = false)
        assertEquals(6_000L, policy.nextDelayMillis(input(now = 1_002L)))
        policy.recordObservationResult(isAvailable = false)
        assertEquals(8_000L, policy.nextDelayMillis(input(now = 1_003L)))

        policy.recordObservationResult(isAvailable = true)

        assertEquals(1_500L, policy.nextDelayMillis(input(now = 1_004L)))
    }

    @Test
    fun `停止后清除退避和旧模式稳定时间`() {
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 1_000L)))
        policy.recordObservationResult(isAvailable = false)
        assertEquals(3_000L, policy.nextDelayMillis(input(now = 1_001L)))

        assertNull(
            policy.nextDelayMillis(
                input(now = 2_000L, isLockPhase = false, isInteractive = true)
            )
        )

        assertEquals(1_500L, policy.nextDelayMillis(input(now = 30_000L)))
    }

    @Test
    fun `从稳定低频切换状态后重新采用新状态的活跃频率`() {
        assertEquals(1_500L, policy.nextDelayMillis(input(now = 1_000L)))
        assertEquals(2_000L, policy.nextDelayMillis(input(now = 20_000L)))

        assertEquals(
            500L,
            policy.nextDelayMillis(
                input(now = 20_001L, mode = ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE)
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝负数单调时间`() {
        policy.nextDelayMillis(input(now = -1L))
    }

    private fun input(
        now: Long,
        isLockPhase: Boolean = true,
        isInteractive: Boolean = true,
        mode: ForegroundObservationCadenceMode = ForegroundObservationCadenceMode.LOCKED
    ) = ForegroundObservationCadenceInput(
        nowElapsedMillis = now,
        isLockPhase = isLockPhase,
        isInteractive = isInteractive,
        mode = mode
    )
}
