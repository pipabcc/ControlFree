package com.example.controlfree.ui.todo.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedCalendarViewModelMessageTest {
    @Test
    fun `一次性专注冲突提示同时覆盖监督和专注计划`() {
        assertEquals(
            "该推荐时段已被监督或专注锁占用，请等待刷新后重试",
            oneTimeFocusScheduleConflictMessage()
        )
    }

    @Test
    fun `推荐在开始时刻和结束后均隐藏`() {
        val suggestion = SmartScheduleSuggestionUi(
            todoId = "todo",
            expectedTodoUpdatedAtEpochMillis = 10L,
            todoTitle = "整理报告",
            startEpochMillis = 100L,
            endEpochMillis = 200L,
            reason = "本地推荐",
            isAiEnhanced = false
        )

        assertNull(activeScheduleSuggestion(suggestion, 100L))
        assertNull(activeScheduleSuggestion(suggestion, 200L))
        assertNull(activeScheduleSuggestion(suggestion, 201L))
        assertEquals(suggestion, activeScheduleSuggestion(suggestion, 99L))
    }
}
