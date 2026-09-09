package com.example.controlfree.growth

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductivityRewardRequestTest {
    @Test
    fun `接受用户确认的待办习惯和闪念奖励`() {
        val todo = ProductivityRewardRequest("todo:1", GrowthLedgerReason.TODO_COMPLETED, 1, occurredAtEpochMillis = 1L)
        val habit = ProductivityRewardRequest("habit:1:2026-07-22", GrowthLedgerReason.HABIT_CHECKED_IN, 2, occurredAtEpochMillis = 1L)
        val note = ProductivityRewardRequest("note:1", GrowthLedgerReason.QUICK_NOTE_CAPTURED, 1, occurredAtEpochMillis = 1L)

        assertEquals(1, todo.rewardPoints)
        assertEquals(2, habit.rewardPoints)
        assertEquals(1, note.rewardPoints)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝把解锁流水伪装成生产力奖励`() {
        ProductivityRewardRequest("bad", GrowthLedgerReason.UNLOCK_APPLIED, 1, occurredAtEpochMillis = 1L)
    }
}
