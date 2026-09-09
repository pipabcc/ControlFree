package com.example.controlfree.ui.todo.viewmodel

import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitHistorySampleFactoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `多次打卡习惯使用达标时刻和计划状态生成推荐信号`() {
        val firstCheckInAt = ZonedDateTime.of(2026, 7, 20, 8, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        val targetReachedAt = ZonedDateTime.of(2026, 7, 20, 16, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        val habit = HabitItemEntity(
            id = "habit-1",
            name = "阅读",
            iconRes = "book",
            colorHex = "#087939",
            frequencyType = "DAILY",
            targetCountPerDay = 2,
            currentStreak = 0,
            bestStreak = 0,
            isArchived = false,
            createdAtEpochMillis = firstCheckInAt,
            startDate = "2026-07-01"
        )
        val record = HabitRecordEntity(
            id = "record-1",
            habitId = habit.id,
            completedDate = "2026-07-20",
            note = null,
            rewardPoints = 2,
            createdAtEpochMillis = firstCheckInAt,
            completionCount = 2,
            updatedAtEpochMillis = targetReachedAt
        )

        val sample = HabitHistorySampleFactory.create(listOf(habit), listOf(record)).single()

        assertEquals(targetReachedAt, sample.checkedInAtEpochMillis)
        assertTrue(sample.targetReached)
        assertTrue(sample.wasScheduled)
    }

    @Test
    fun `旧数据更新时间早于创建时间时回退首次打卡时间`() {
        val firstCheckInAt = ZonedDateTime.of(2026, 7, 20, 8, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        val habit = HabitItemEntity(
            id = "habit-legacy",
            name = "阅读",
            iconRes = "book",
            colorHex = "#087939",
            frequencyType = "DAILY",
            targetCountPerDay = 1,
            currentStreak = 0,
            bestStreak = 0,
            isArchived = false,
            createdAtEpochMillis = firstCheckInAt,
            startDate = "2026-07-01"
        )
        val record = HabitRecordEntity(
            id = "record-legacy",
            habitId = habit.id,
            completedDate = "2026-07-20",
            note = null,
            rewardPoints = 2,
            createdAtEpochMillis = firstCheckInAt,
            completionCount = 1,
            updatedAtEpochMillis = 0L
        )

        val sample = HabitHistorySampleFactory.create(listOf(habit), listOf(record)).single()

        assertEquals(firstCheckInAt, sample.checkedInAtEpochMillis)
    }
}
