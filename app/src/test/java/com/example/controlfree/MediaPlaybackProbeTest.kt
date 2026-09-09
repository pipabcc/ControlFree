package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackProbeTest {
    @Test
    fun `返回底层媒体播放状态`() {
        assertTrue(MediaPlaybackProbe { true }.probe().isAvailable)
        assertTrue(MediaPlaybackProbe { true }.isMediaPlaying())
        assertFalse(MediaPlaybackProbe { false }.isMediaPlaying())
    }

    @Test
    fun `权限异常安全降级为未播放`() {
        val probe = MediaPlaybackProbe { throw SecurityException("denied") }

        assertFalse(probe.probe().isAvailable)
    }

    @Test
    fun `音频服务运行时异常安全降级为未播放`() {
        val probe = MediaPlaybackProbe { throw IllegalStateException("audio service unavailable") }

        assertFalse(probe.probe().isAvailable)
    }
}
