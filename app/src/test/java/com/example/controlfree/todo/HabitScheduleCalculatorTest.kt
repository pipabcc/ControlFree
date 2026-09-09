package com.example.controlfree.todo

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HabitScheduleCalculatorTest {
    @Test
    fun `下一计划日覆盖每日指定星期和长间隔`() {
        val start = LocalDate.of(2026, 7, 22)

        assertEquals(
            start.plusDays(1),
            HabitScheduleCalculator.nextScheduledDate(habit(start), start)
        )
        assertEquals(
            LocalDate.of(2026, 7, 26),
            HabitScheduleCalculator.nextScheduledDate(
                habit(
                    start = start,
                    frequency = HabitFrequencyType.SPECIFIC_WEEKDAYS,
                    weekdaysMask = 1 shl 6
                ),
                start
            )
        )
        assertEquals(
            start.plusDays(366),
            HabitScheduleCalculator.nextScheduledDate(
                habit(
                    start = start,
                    frequency = HabitFrequencyType.INTERVAL,
                    intervalDays = 366
                ),
                start
            )
        )
    }

    @Test
    fun `未来开始的习惯直接返回首个计划日`() {
        val today = LocalDate.of(2026, 7, 22)
        val futureStart = LocalDate.of(2028, 1, 1)

        assertEquals(
            futureStart,
            HabitScheduleCalculator.nextScheduledDate(habit(futureStart), today)
        )
    }

    private fun habit(
        start: LocalDate,
        frequency: HabitFrequencyType = HabitFrequencyType.DAILY,
        weekdaysMask: Int = 0b1111111,
        intervalDays: Int = 1
    ) = HabitItemEntity(
        id = "habit-1",
        name = "测试习惯",
        iconRes = "check",
        colorHex = "#2196F3",
        frequencyType = frequency.storedValue,
        targetCountPerDay = 1,
        currentStreak = 0,
        bestStreak = 0,
        isArchived = false,
        createdAtEpochMillis = 1L,
        weekdaysMask = weekdaysMask,
        intervalDays = intervalDays,
        startDate = start.toString()
    )
}
