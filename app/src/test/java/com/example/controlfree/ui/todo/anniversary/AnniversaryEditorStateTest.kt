package com.example.controlfree.ui.todo.anniversary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnniversaryEditorStateTest {
    @Test
    fun 编辑草稿保存状态往返后字段完整() {
        val draft = AnniversaryEditorDraft(
            id = "anniversary-1",
            title = "相识纪念",
            type = AnniversaryType.COUNT_UP,
            calendarType = AnniversaryCalendarType.LUNAR,
            year = 2025,
            month = 6,
            day = 18,
            hour = 23,
            minute = 58,
            second = 59,
            isLunarLeapMonth = true,
            repeatRule = AnniversaryRepeatRule.NONE,
            zoneId = "Asia/Shanghai",
            isPinned = true,
            showOnWidget = true,
            showOnLockScreen = true
        )

        assertEquals(
            draft,
            anniversaryEditorDraftFromSaveableValues(draft.toSaveableValues())
        )
    }

    @Test
    fun 损坏或版本不匹配的草稿状态不会崩溃恢复() {
        assertNull(anniversaryEditorDraftFromSaveableValues(emptyList()))
        assertNull(
            anniversaryEditorDraftFromSaveableValues(
                List(17) { "invalid" }
            )
        )
    }
}
