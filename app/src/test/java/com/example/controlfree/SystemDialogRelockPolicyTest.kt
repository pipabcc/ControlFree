package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemDialogRelockPolicyTest {
    @Test
    fun `Home 键信号要求立即回锁`() {
        assertTrue(SystemDialogRelockPolicy.shouldRelock("homekey"))
    }

    @Test
    fun `最近任务信号要求立即回锁`() {
        assertTrue(SystemDialogRelockPolicy.shouldRelock("recentapps"))
    }

    @Test
    fun `其他系统对话框原因不触发回锁`() {
        listOf("assist", "globalactions", "dream", "homekey ", "HOMEKEY").forEach { reason ->
            assertFalse(SystemDialogRelockPolicy.shouldRelock(reason))
        }
    }

    @Test
    fun `空原因不触发回锁`() {
        assertFalse(SystemDialogRelockPolicy.shouldRelock(null))
        assertFalse(SystemDialogRelockPolicy.shouldRelock(""))
    }
}
