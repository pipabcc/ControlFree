package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockActivityVisibilityCommandTest {
    @Test
    fun `当前会话的可见与隐藏事件都接受`() {
        assertTrue(
            shouldAcceptLockActivityVisibilityCommand(
                isCurrentSessionCommand = true,
                isVisible = true
            )
        )
        assertTrue(
            shouldAcceptLockActivityVisibilityCommand(
                isCurrentSessionCommand = true,
                isVisible = false
            )
        )
    }

    @Test
    fun `会话轮换期间前台锁定界面的可见事件必须接受`() {
        // 否则服务误判无 Activity 可见，把悬浮层叠加到锁定 Activity 之上形成双层锁屏。
        assertTrue(
            shouldAcceptLockActivityVisibilityCommand(
                isCurrentSessionCommand = false,
                isVisible = true
            )
        )
    }

    @Test
    fun `过期会话的隐藏事件不可清除新会话的可见状态`() {
        assertFalse(
            shouldAcceptLockActivityVisibilityCommand(
                isCurrentSessionCommand = false,
                isVisible = false
            )
        )
    }
}
