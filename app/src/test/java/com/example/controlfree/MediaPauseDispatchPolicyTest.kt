package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPauseDispatchPolicyTest {
    @Test
    fun `同一锁定阶段只发送一次暂停`() {
        val policy = MediaPauseDispatchPolicy()

        assertTrue(policy.enterLockStage())
        assertFalse(policy.enterLockStage())
        assertFalse(policy.enterLockStage())
    }

    @Test
    fun `允许播放后下一锁定阶段重新发送暂停`() {
        val policy = MediaPauseDispatchPolicy()

        assertTrue(policy.enterLockStage())
        policy.allowMediaPlayback()

        assertTrue(policy.enterLockStage())
        assertFalse(policy.enterLockStage())
    }

    @Test
    fun `重复允许播放不影响下一次锁定转换`() {
        val policy = MediaPauseDispatchPolicy()

        policy.allowMediaPlayback()
        policy.allowMediaPlayback()

        assertTrue(policy.enterLockStage())
    }
}
