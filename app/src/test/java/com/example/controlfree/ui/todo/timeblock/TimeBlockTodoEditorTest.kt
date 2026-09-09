package com.example.controlfree.ui.todo.timeblock

import com.example.controlfree.ui.todo.todo.TodoUrgencyMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeBlockTodoEditorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 7, 31)

    @Test
    fun `日期入口创建当天截止的待办草稿`() {
        val draft = todoDraftForDeadlineDate(date, zoneId)

        val dueAt = Instant.ofEpochMilli(requireNotNull(draft.dueAtEpochMillis)).atZone(zoneId)
        assertEquals(date, dueAt.toLocalDate())
        assertEquals(LocalTime.of(23, 59), dueAt.toLocalTime())
        assertNull(draft.scheduledStartEpochMillis)
        assertNull(draft.scheduledEndEpochMillis)
    }

    @Test
    fun `时间格入口完整预填排程和预计专注时长`() {
        val draft = todoDraftForSchedule(date, 9 * 60 + 30, 11 * 60, zoneId)

        assertEquals(epochMillis(date, 9 * 60 + 30, zoneId), draft.scheduledStartEpochMillis)
        assertEquals(epochMillis(date, 11 * 60, zoneId), draft.scheduledEndEpochMillis)
        assertEquals(90, draft.estimatedFocusMinutes)
        assertNull(draft.dueAtEpochMillis)
    }

    @Test
    fun `旧日程新建草稿转换为完整待办排程`() {
        val draft = TimeBlockEditorDraft(
            editorType = TimeBlockEditorType.EVENT,
            title = "评审",
            description = "准备材料",
            date = date,
            project = "工作",
            highPriority = true,
            allDay = false,
            startMinute = 14 * 60,
            endMinuteExclusive = 15 * 60 + 30
        ).toTodoEditorDraft(zoneId)

        assertEquals("评审", draft.title)
        assertEquals("准备材料", draft.description)
        assertEquals(epochMillis(date, 14 * 60, zoneId), draft.scheduledStartEpochMillis)
        assertEquals(epochMillis(date, 15 * 60 + 30, zoneId), draft.scheduledEndEpochMillis)
        assertEquals(90, draft.estimatedFocusMinutes)
        assertTrue(draft.isImportant)
        assertEquals(TodoUrgencyMode.URGENT, draft.urgencyMode)
        assertNull(draft.dueAtEpochMillis)
    }

    @Test
    fun `重复投影编辑和操作都解析到基础待办`() {
        val recurring = timeBlockEntry("todo-1_recur_2026-08-01")
        val regular = timeBlockEntry("todo-2")
        val event = timeBlockEntry("event-1", TimeBlockSource.EVENT)

        assertEquals("todo-1", recurring.baseTodoId())
        assertEquals("todo-2", regular.baseTodoId())
        assertEquals("todo:todo-1", recurring.focusTimerStableKey())
        assertEquals("event:event-1", event.focusTimerStableKey())
        assertFalse(regular.isCompleted)
    }

    private fun timeBlockEntry(
        id: String,
        source: TimeBlockSource = TimeBlockSource.TODO
    ) = TimeBlockEntry(
        source = source,
        id = id,
        title = "任务",
        project = "工作",
        priority = 0,
        isCompleted = false,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )
}
