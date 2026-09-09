package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LockPendingActionTest {
    @Test
    fun `暂停动作只接受1到30分钟`() {
        assertEquals(1, LockPendingAction.pause(1).pauseMinutes)
        assertEquals(30, LockPendingAction.pause(30).pauseMinutes)
        assertThrows(IllegalArgumentException::class.java) { LockPendingAction.pause(0) }
        assertThrows(IllegalArgumentException::class.java) { LockPendingAction.pause(31) }
    }

    @Test
    fun `线协议拒绝未知动作和非法暂停时长`() {
        assertNull(LockPendingAction.fromWireValue("unknown", 5))
        assertNull(LockPendingAction.fromWireValue("pause", null))
        assertNull(LockPendingAction.fromWireValue("pause", 31))
    }

    @Test
    fun `跳过动作忽略无关暂停参数`() {
        assertEquals(
            LockPendingAction.Skip,
            LockPendingAction.fromWireValue("skip", 15)
        )
        assertEquals(
            LockActionContract.ACTION_SKIP_CURRENT_LOCK,
            LockPendingAction.Skip.serviceAction
        )
    }
}
