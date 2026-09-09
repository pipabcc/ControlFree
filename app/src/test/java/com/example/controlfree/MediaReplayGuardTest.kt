package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReplayGuardTest {
    @Test
    fun `只在真实锁层可见且无白名单和通话豁免时启用`() {
        assertTrue(
            shouldEnableMediaReplayGuard(
                isLockPhase = true,
                isLockSurfaceVisible = true,
                isAllowedAppMediaTransition = false,
                isCallActive = false,
                isInteractive = true
            )
        )
        assertFalse(shouldEnableMediaReplayGuard(false, true, false, false, true))
        assertFalse(shouldEnableMediaReplayGuard(true, false, false, false, true))
        assertFalse(shouldEnableMediaReplayGuard(true, true, true, false, true))
        assertFalse(shouldEnableMediaReplayGuard(true, true, false, true, true))
    }

    @Test
    fun `熄屏锁定不依赖 Activity 或悬浮层可见状态`() {
        assertTrue(
            shouldEnableMediaReplayGuard(
                isLockPhase = true,
                isLockSurfaceVisible = false,
                isAllowedAppMediaTransition = false,
                isCallActive = false,
                isInteractive = false
            )
        )
    }

    @Test
    fun `常规启用在一秒后首次探测`() {
        val guard = MediaReplayGuard()

        guard.setEnabled(shouldEnable = true, nowElapsedMillis = 10_000L)

        assertFalse(guard.shouldProbe(10_999L))
        assertTrue(guard.shouldProbe(11_000L))
    }

    @Test
    fun `强制启用与已启用时都能立即探测`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(
            shouldEnable = true,
            nowElapsedMillis = 2_000L,
            probeImmediately = true
        )
        assertTrue(guard.shouldProbe(2_000L))

        guard.recordProbeResult(
            nowElapsedMillis = 2_000L,
            isMediaPlaying = false,
            quietProbeIntervalMillis = 8_000L
        )
        assertFalse(guard.shouldProbe(2_001L))

        guard.requestImmediateProbe(2_001L)
        assertTrue(guard.shouldProbe(2_001L))
    }

    @Test
    fun `主动暂停后至少等待最小间隔再探测`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(true, nowElapsedMillis = 0L, probeImmediately = true)

        guard.deferProbeAfterPause(100L)

        assertFalse(guard.shouldProbe(1_099L))
        assertTrue(guard.shouldProbe(1_100L))
    }

    @Test
    fun `亮屏立即探测不能突破主动暂停冷却`() {
        val guard = MediaReplayGuard()
        guard.deferProbeAfterPause(0L)
        guard.setEnabled(true, nowElapsedMillis = 0L)

        guard.requestImmediateProbe(500L)

        assertFalse(guard.shouldProbe(999L))
        assertTrue(guard.shouldProbe(1_000L))
    }

    @Test
    fun `静默时按调用方间隔安排下一次探测`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(true, nowElapsedMillis = 0L, probeImmediately = true)

        val action = guard.recordProbeResult(
            nowElapsedMillis = 100L,
            isMediaPlaying = false,
            quietProbeIntervalMillis = 8_000L
        )

        assertEquals(MediaReplayAction.NONE, action)
        assertEquals(8_100L, guard.nextProbeElapsedMillisForTest())
    }

    @Test
    fun `持续播放按一二四八秒逐级退避并重复暂停`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(true, nowElapsedMillis = 0L, probeImmediately = true)

        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L)
        var now = 0L
        expectedDelays.forEach { expectedDelay ->
            assertEquals(
                MediaReplayAction.REAPPLY_MEDIA_PAUSE,
                guard.recordProbeResult(
                    nowElapsedMillis = now,
                    isMediaPlaying = true,
                    quietProbeIntervalMillis = 1_000L
                )
            )
            assertEquals(now + expectedDelay, guard.nextProbeElapsedMillisForTest())
            now += expectedDelay
        }
    }

    @Test
    fun `一次静默会重置播放退避`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(true, nowElapsedMillis = 0L, probeImmediately = true)
        guard.recordProbeResult(0L, true, 1_000L)
        guard.recordProbeResult(1_000L, true, 1_000L)

        guard.recordProbeResult(3_000L, false, 1_000L)
        val action = guard.recordProbeResult(4_000L, true, 1_000L)

        assertEquals(MediaReplayAction.REAPPLY_MEDIA_PAUSE, action)
        assertEquals(5_000L, guard.nextProbeElapsedMillisForTest())
    }

    @Test
    fun `停用后取消待执行探测并忽略迟到结果`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(true, nowElapsedMillis = 0L, probeImmediately = true)

        guard.disable()

        assertFalse(guard.isEnabled)
        assertFalse(guard.shouldProbe(Long.MAX_VALUE))
        assertEquals(
            MediaReplayAction.NONE,
            guard.recordProbeResult(1_000L, true, 1_000L)
        )
    }

    @Test
    fun `接近长整型上限时调度不会溢出`() {
        val guard = MediaReplayGuard()
        guard.setEnabled(true, Long.MAX_VALUE - 500L)

        assertEquals(Long.MAX_VALUE, guard.nextProbeElapsedMillisForTest())
        assertTrue(guard.shouldProbe(Long.MAX_VALUE))
    }
}
