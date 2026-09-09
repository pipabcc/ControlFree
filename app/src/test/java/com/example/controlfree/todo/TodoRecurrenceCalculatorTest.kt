package com.example.controlfree.todo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoRecurrenceCalculatorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `工作日重复跳过周末`() {
        val friday = LocalDate.of(2026, 7, 24)
        val next = next(todo(friday, TodoRecurrenceType.WEEKDAYS), completedOn = friday)

        assertEquals(LocalDate.of(2026, 7, 27), next.dueDate())
    }

    @Test
    fun `每周指定日选择同一周期内的下一个执行日`() {
        val monday = LocalDate.of(2026, 7, 20)
        val mask = dayMask(DayOfWeek.WEDNESDAY) or dayMask(DayOfWeek.FRIDAY)
        val next = next(
            todo(monday, TodoRecurrenceType.WEEKLY_DAYS).copy(recurrenceDaysMask = mask),
            completedOn = monday
        )

        assertEquals(LocalDate.of(2026, 7, 22), next.dueDate())
    }

    @Test
    fun `月末重复回退到目标月最后一天`() {
        val january31 = LocalDate.of(2026, 1, 31)
        val next = next(
            todo(january31, TodoRecurrenceType.MONTHLY_DAY).copy(recurrenceDayOfMonth = 31),
            completedOn = january31
        )

        assertEquals(LocalDate.of(2026, 2, 28), next.dueDate())
    }

    @Test
    fun `按完成日间隔从实际完成日期计算`() {
        val planned = LocalDate.of(2026, 1, 1)
        val completed = LocalDate.of(2026, 1, 10)
        val next = next(
            todo(planned, TodoRecurrenceType.COMPLETION_INTERVAL).copy(recurrenceInterval = 3),
            completedOn = completed
        )

        assertEquals(LocalDate.of(2026, 1, 13), next.dueDate())
    }

    private fun next(todo: TodoItemEntity, completedOn: LocalDate): TodoItemEntity =
        requireNotNull(
            TodoRecurrenceCalculator.nextOccurrence(
                todo = todo,
                completedAt = completedOn.atTime(12, 0).atZone(zoneId).toInstant(),
                zoneId = zoneId,
                nextId = "next"
            )
        )

    private fun todo(date: LocalDate, recurrence: TodoRecurrenceType) = TodoItemEntity(
        id = "todo",
        title = "重复任务",
        description = null,
        dueDateEpochMillis = date.atTime(9, 0).atZone(zoneId).toInstant().toEpochMilli(),
        priority = 0,
        isCompleted = true,
        completedAtEpochMillis = date.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli(),
        category = "测试",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = 1L,
        recurrenceType = recurrence.storedValue
    )

    private fun TodoItemEntity.dueDate(): LocalDate = Instant.ofEpochMilli(
        requireNotNull(dueDateEpochMillis)
    ).atZone(zoneId).toLocalDate()

    private fun dayMask(day: DayOfWeek): Int = 1 shl (day.value - 1)
}
