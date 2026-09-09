package com.example.controlfree.todo.habit.runtime

import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitReminderModelsTest {
    @Test
    fun `提醒时间未到时安排在当天`() {
        val now = ZonedDateTime.of(2026, 7, 23, 19, 59, 0, 0, ZoneId.of("Asia/Shanghai"))

        val trigger = HabitReminderPlanner.nextTrigger(now, 20 * 60)

        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant(),
            trigger
        )
    }

    @Test
    fun `提醒时间已过时安排到下一天`() {
        val now = ZonedDateTime.of(2026, 7, 23, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

        val trigger = HabitReminderPlanner.nextTrigger(now, 20 * 60)

        assertEquals(
            ZonedDateTime.of(2026, 7, 24, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant(),
            trigger
        )
    }

    @Test
    fun `只提醒今日计划中且尚未达标的习惯`() {
        val today = LocalDate.of(2026, 7, 23)
        val pending = habit("pending", "阅读", createdAt = 1L)
        val completed = habit("completed", "运动", createdAt = 2L)
        val archived = habit("archived", "归档", createdAt = 3L, archived = true)
        val future = habit("future", "未来", createdAt = 4L, startDate = today.plusDays(1))
        val records = listOf(
            HabitRecordEntity(
                id = "record-completed",
                habitId = completed.id,
                completedDate = today.toString(),
                note = null,
                rewardPoints = 2,
                createdAtEpochMillis = 1L,
                completionCount = 1
            )
        )

        val result = HabitReminderPlanner.pendingHabits(
            habits = listOf(pending, completed, archived, future),
            records = records,
            date = today
        )

        assertEquals(listOf("pending"), result.map { it.habit.id })
        assertEquals(0, result.single().completionCount)
    }

    @Test
    fun `多次记录按最高完成次数判断目标`() {
        val today = LocalDate.of(2026, 7, 23)
        val habit = habit("water", "喝水", createdAt = 1L, targetCount = 3)
        val records = listOf(
            record("a", habit.id, today, count = 1),
            record("b", habit.id, today, count = 3)
        )

        val result = HabitReminderPlanner.pendingHabits(listOf(habit), records, today)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `每周目标达到后本周不再重复提醒`() {
        val today = LocalDate.of(2026, 7, 23)
        val habit = habit(
            id = "weekly",
            name = "每周运动",
            createdAt = 1L,
            targetCount = 1,
            frequencyType = "WEEKLY_TARGET",
            weeklyTargetDays = 3
        )
        val records = listOf(
            record("monday", habit.id, LocalDate.of(2026, 7, 20), count = 1),
            record("tuesday", habit.id, LocalDate.of(2026, 7, 21), count = 1),
            record("wednesday", habit.id, LocalDate.of(2026, 7, 22), count = 1)
        )

        assertTrue(HabitReminderPlanner.pendingHabits(listOf(habit), records, today).isEmpty())
    }

    @Test
    fun `每周目标尚未达到且今日未打卡时继续提醒`() {
        val today = LocalDate.of(2026, 7, 23)
        val habit = habit(
            id = "weekly-pending",
            name = "每周阅读",
            createdAt = 1L,
            targetCount = 1,
            frequencyType = "WEEKLY_TARGET",
            weeklyTargetDays = 3
        )
        val records = listOf(
            record("monday", habit.id, LocalDate.of(2026, 7, 20), count = 1),
            record("tuesday", habit.id, LocalDate.of(2026, 7, 21), count = 1)
        )

        assertEquals(
            listOf(habit.id),
            HabitReminderPlanner.pendingHabits(listOf(habit), records, today)
                .map { it.habit.id }
        )
    }

    private fun habit(
        id: String,
        name: String,
        createdAt: Long,
        targetCount: Int = 1,
        archived: Boolean = false,
        startDate: LocalDate = LocalDate.of(2026, 1, 1),
        frequencyType: String = "DAILY",
        weeklyTargetDays: Int = 3
    ) = HabitItemEntity(
        id = id,
        name = name,
        iconRes = "check",
        colorHex = "#00897B",
        frequencyType = frequencyType,
        targetCountPerDay = targetCount,
        currentStreak = 0,
        bestStreak = 0,
        isArchived = archived,
        createdAtEpochMillis = createdAt,
        weeklyTargetDays = weeklyTargetDays,
        startDate = startDate.toString()
    )

    private fun record(
        id: String,
        habitId: String,
        date: LocalDate,
        count: Int
    ) = HabitRecordEntity(
        id = id,
        habitId = habitId,
        completedDate = date.toString(),
        note = null,
        rewardPoints = 0,
        createdAtEpochMillis = 1L,
        completionCount = count
    )
}
