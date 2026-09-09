package com.example.controlfree.widget.list

import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.todo.TodoItemEntity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListWidgetModelsTest {
    @Test
    fun `待办组件把未完成高优先任务排在前面`() {
        val completed = todo("completed", priority = 3, completed = true)
        val ordinary = todo("ordinary", priority = 1, completed = false)
        val important = todo("important", priority = 3, completed = false)

        val rows = todoWidgetRows(listOf(completed, ordinary, important), ZoneId.of("UTC"))

        assertEquals(listOf("important", "ordinary", "completed"), rows.map { it.id })
        assertEquals("○", rows.first().marker)
        assertFalse(rows.first().isMuted)
        assertFalse(rows.first().isCompleted)
        assertEquals("✓", rows.last().marker)
        assertTrue(rows.last().isMuted)
        assertTrue(rows.last().isCompleted)
    }

    @Test
    fun `闪记组件按创建时间倒序并标记图片`() {
        val rows = quickNoteWidgetRows(
            listOf(
                note("old", 1_000L, null),
                note("new", 2_000L, "content://image")
            ),
            ZoneId.of("UTC")
        )

        assertEquals(listOf("new", "old"), rows.map { it.id })
        assertTrue(rows.first().subtitle.startsWith("含图片"))
    }

    private fun todo(id: String, priority: Int, completed: Boolean) = TodoItemEntity(
        id = id,
        title = id,
        description = null,
        dueDateEpochMillis = null,
        priority = priority,
        isCompleted = completed,
        completedAtEpochMillis = if (completed) 2L else null,
        category = "",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = 1L
    )

    private fun note(id: String, createdAt: Long, mediaUri: String?) = QuickNoteEntity(
        id = id,
        content = id,
        mediaUri = mediaUri,
        status = "RAW",
        createdAtEpochMillis = createdAt
    )
}
