package com.example.controlfree.ui.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoSubTabTest {
    @Test
    fun `顶部标签均使用两个汉字的短标题`() {
        val labels = TodoSubTab.entries.map(TodoSubTab::displayName)
        assertEquals(6, TodoSubTab.entries.size)
        assertEquals(listOf("待办", "日程", "习惯", "账本", "时刻", "闪记"), labels)
        assertEquals("账本", TodoSubTab.LEDGER.displayName)
        assertTrue(TodoSubTab.entries.contains(TodoSubTab.LEDGER))
        assertEquals(labels.size, labels.toSet().size)
        assertFalse(TodoSubTab.entries.any { it.displayName.isBlank() })
        assertTrue(TodoSubTab.entries.all { it.displayName.length == 2 })
    }

    @Test
    fun `新日程显示在待办旁边`() {
        assertEquals(
            listOf("待办", "日程", "习惯", "账本", "时刻", "闪记"),
            checklistSubTabs.map(TodoSubTab::displayName)
        )
        assertTrue(TodoSubTab.CALENDAR.isVisibleInChecklist)
        assertEquals(TodoSubTab.CALENDAR, checklistSubTabs[1])
    }
}
