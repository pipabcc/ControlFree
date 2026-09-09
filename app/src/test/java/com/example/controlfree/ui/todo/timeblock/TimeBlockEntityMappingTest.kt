package com.example.controlfree.ui.todo.timeblock

import com.example.controlfree.todo.TodoItemEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeBlockEntityMappingTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 8, 1)

    @Test
    fun `历史零时长排程降级为全天任务而不击穿日程页`() {
        val startAt = epochMillis(date, 3 * 60 + 30, zoneId)
        val entry = todo(
            scheduledStart = startAt,
            scheduledEnd = startAt
        ).toTimeBlockEntry()

        assertNull(entry.scheduledStartEpochMillis)
        assertNull(entry.scheduledEndEpochMillis)
        assertEquals(TimeBlockEntryKind.ALL_DAY_TASK, entry.kind(zoneId))
    }

    @Test
    fun `历史不完整排程同样降级为全天任务`() {
        val entry = todo(
            scheduledStart = epochMillis(date, 3 * 60 + 30, zoneId),
            scheduledEnd = null
        ).toTimeBlockEntry()

        assertNull(entry.scheduledStartEpochMillis)
        assertNull(entry.scheduledEndEpochMillis)
        assertEquals(TimeBlockEntryKind.ALL_DAY_TASK, entry.kind(zoneId))
    }

    private fun todo(scheduledStart: Long?, scheduledEnd: Long?) = TodoItemEntity(
        id = "todo-1",
        title = "宝宝",
        description = null,
        dueDateEpochMillis = date.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli(),
        priority = 0,
        isCompleted = false,
        completedAtEpochMillis = null,
        category = "默认",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = 1L,
        scheduledStartEpochMillis = scheduledStart,
        scheduledEndEpochMillis = scheduledEnd,
        estimatedFocusMinutes = 25,
        updatedAtEpochMillis = 2L
    )
}
