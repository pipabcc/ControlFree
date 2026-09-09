package com.example.controlfree.ui.todo.habit

import org.junit.Assert.assertEquals
import org.junit.Test

class HabitAchievementDisplayTest {
    @Test
    fun `撤销历史打卡后持久化徽章仍保持解锁`() {
        val tiers = resolveHabitBadgeTiers(
            calculatedBestStreak = 3,
            persistedBadgeTiers = setOf(7, 21)
        )

        assertEquals(setOf(7, 21), tiers)
    }

    @Test
    fun `只展示产品支持的持久化徽章档位`() {
        val tiers = resolveHabitBadgeTiers(
            calculatedBestStreak = 100,
            persistedBadgeTiers = setOf(1, 999)
        )

        assertEquals(setOf(7, 21, 100), tiers)
    }
}
