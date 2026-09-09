package com.example.controlfree.ui.todo.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitDomainTest {
    @Test
    fun 每天两次需要第二次打卡后才算完成() {
        val today = LocalDate.of(2026, 7, 22)
        val schedule = HabitSchedule(
            frequency = HabitFrequencyType.DAILY,
            targetCountPerDay = 2,
            startDate = today.minusDays(1)
        )

        val statistics = HabitProgressCalculator.calculateMonthStatistics(
            schedule = schedule,
            progress = listOf(
                HabitDayProgress(today.minusDays(1), 2),
                HabitDayProgress(today, 1)
            ),
            month = YearMonth.from(today),
            today = today
        )

        assertEquals(3, statistics.completedUnits)
        assertEquals(4, statistics.requiredUnits)
        assertEquals(1, statistics.completedDays)
        assertEquals(0.75f, statistics.completionRate, 0.001f)
    }

    @Test
    fun 指定星期会跳过未安排日期并保持连击() {
        val monday = LocalDate.of(2026, 7, 20)
        val wednesday = monday.plusDays(2)
        val schedule = HabitSchedule(
            frequency = HabitFrequencyType.SPECIFIC_WEEKDAYS,
            weekdaysMask = weekdayMaskOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            startDate = monday
        )

        assertTrue(HabitProgressCalculator.isScheduled(schedule, monday))
        assertFalse(HabitProgressCalculator.isScheduled(schedule, monday.plusDays(1)))
        assertEquals(
            HabitStreak(2, 2, HabitStreakUnit.DAY),
            HabitProgressCalculator.calculateStreak(
                schedule,
                listOf(HabitDayProgress(monday, 1), HabitDayProgress(wednesday, 1)),
                wednesday
            )
        )
    }

    @Test
    fun 今天未打卡不会提前清空昨天的连续记录() {
        val today = LocalDate.of(2026, 7, 22)
        val schedule = HabitSchedule(HabitFrequencyType.DAILY, startDate = today.minusDays(3))
        val streak = HabitProgressCalculator.calculateStreak(
            schedule,
            listOf(
                HabitDayProgress(today.minusDays(3), 1),
                HabitDayProgress(today.minusDays(2), 1),
                HabitDayProgress(today.minusDays(1), 1)
            ),
            today
        )

        assertEquals(3, streak.current)
        assertEquals(3, streak.best)
    }

    @Test
    fun 间隔计划只计算锚点后的有效日期() {
        val start = LocalDate.of(2026, 7, 1)
        val schedule = HabitSchedule(
            frequency = HabitFrequencyType.INTERVAL,
            intervalDays = 3,
            startDate = start
        )

        assertTrue(HabitProgressCalculator.isScheduled(schedule, start))
        assertFalse(HabitProgressCalculator.isScheduled(schedule, start.plusDays(2)))
        assertTrue(HabitProgressCalculator.isScheduled(schedule, start.plusDays(3)))
    }

    @Test
    fun 每周目标按达标周计算连击() {
        val monday = LocalDate.of(2026, 7, 6)
        val schedule = HabitSchedule(
            frequency = HabitFrequencyType.WEEKLY_TARGET,
            weeklyTargetDays = 3,
            startDate = monday
        )
        val progress = listOf(
            HabitDayProgress(monday, 1),
            HabitDayProgress(monday.plusDays(2), 1),
            HabitDayProgress(monday.plusDays(4), 1),
            HabitDayProgress(monday.plusWeeks(1), 1),
            HabitDayProgress(monday.plusWeeks(1).plusDays(1), 1),
            HabitDayProgress(monday.plusWeeks(1).plusDays(3), 1)
        )

        val streak = HabitProgressCalculator.calculateStreak(
            schedule,
            progress,
            monday.plusWeeks(2).plusDays(1)
        )

        assertEquals(HabitStreakUnit.WEEK, streak.unit)
        assertEquals(2, streak.current)
        assertEquals(2, streak.best)
    }

    @Test
    fun 当前周部分完成不会提前增加每周目标连击() {
        val firstMonday = LocalDate.of(2026, 7, 6)
        val currentMonday = firstMonday.plusWeeks(1)
        val schedule = HabitSchedule(
            frequency = HabitFrequencyType.WEEKLY_TARGET,
            weeklyTargetDays = 3,
            startDate = firstMonday
        )
        val progress = listOf(
            HabitDayProgress(firstMonday, 1),
            HabitDayProgress(firstMonday.plusDays(2), 1),
            HabitDayProgress(firstMonday.plusDays(4), 1),
            HabitDayProgress(currentMonday, 1)
        )

        val streak = HabitProgressCalculator.calculateStreak(
            schedule = schedule,
            progress = progress,
            today = currentMonday
        )

        assertEquals(HabitStreak(1, 1, HabitStreakUnit.WEEK), streak)
    }

    @Test
    fun 热力等级按目标比例封顶() {
        assertEquals(0, HabitProgressCalculator.heatLevel(0, 8))
        assertEquals(1, HabitProgressCalculator.heatLevel(1, 8))
        assertEquals(2, HabitProgressCalculator.heatLevel(4, 8))
        assertEquals(4, HabitProgressCalculator.heatLevel(8, 8))
        assertEquals(4, HabitProgressCalculator.heatLevel(12, 8))
    }

    @Test
    fun 徽章跨过阈值时包含所有已获得阶段() {
        assertEquals(emptySet<Int>(), HabitProgressCalculator.unlockedBadges(6))
        assertEquals(setOf(7), HabitProgressCalculator.unlockedBadges(7))
        assertEquals(setOf(7, 21), HabitProgressCalculator.unlockedBadges(21))
        assertEquals(setOf(7, 21, 100), HabitProgressCalculator.unlockedBadges(120))
    }
}
