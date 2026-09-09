package com.example.controlfree.ui.todo.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.example.controlfree.ui.todo.anniversary.AnniversaryCalendarType
import com.example.controlfree.ui.todo.anniversary.AnniversaryEditorDraft
import com.example.controlfree.ui.todo.anniversary.AnniversaryRepeatRule
import com.example.controlfree.ui.todo.anniversary.AnniversaryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnniversaryLifecycleStateTest {
    @Test
    fun 待处理保存动作在状态重建后字段完整() {
        val draft = AnniversaryEditorDraft(
            id = "anniversary-1",
            title = "项目上线",
            type = AnniversaryType.COUNTDOWN,
            calendarType = AnniversaryCalendarType.LUNAR,
            year = 2027,
            month = 8,
            day = 15,
            hour = 20,
            minute = 30,
            second = 45,
            isLunarLeapMonth = true,
            repeatRule = AnniversaryRepeatRule.YEARLY,
            zoneId = "Asia/Shanghai",
            isPinned = true,
            showOnWidget = true,
            showOnLockScreen = true
        )
        val action = AnniversaryNotificationPermissionAction.SaveDraft(
            draft = draft,
            shouldRequestWidgetPin = true
        )
        val originalHandle = SavedStateHandle()
        AnniversaryNotificationPermissionActionStore(originalHandle).stage(action)

        val restoredHandle = SavedStateHandle(
            originalHandle.keys().associateWith { key -> originalHandle.get<Any>(key) }
        )
        val restoredStore = AnniversaryNotificationPermissionActionStore(restoredHandle)

        assertEquals(action, restoredStore.peek())
        assertEquals(action, restoredStore.consume())
        assertNull(restoredStore.peek())
    }

    @Test
    fun 待处理开启动作消费后不会重复执行() {
        val store = AnniversaryNotificationPermissionActionStore(SavedStateHandle())
        val action = AnniversaryNotificationPermissionAction.EnableExisting("anniversary-2")

        store.stage(action)

        assertEquals(action, store.consume())
        assertNull(store.consume())
    }

    @Test
    fun 组件请求队列按编号去重并保持容量上限() {
        val queue = listOf("a", "b")
            .enqueueBounded("b", maxSize = 3, unique = true)
            .enqueueBounded("c", maxSize = 3, unique = true)
            .enqueueBounded("d", maxSize = 3, unique = true)

        assertEquals(listOf("b", "c", "d"), queue)
        assertEquals(listOf("c", "d"), queue.consumeHead("b"))
        assertEquals(queue, queue.consumeHead("not-the-head"))
    }

    @Test
    fun 消息队列允许重复但只保留最新内容() {
        val queue = listOf("旧消息")
            .enqueueBounded("重复消息", maxSize = 2, unique = false)
            .enqueueBounded("重复消息", maxSize = 2, unique = false)

        assertEquals(listOf("重复消息", "重复消息"), queue)
    }

    @Test
    fun 持久化提示会去除空白并限制长度() {
        assertNull(normalizeAnniversaryMessage("   "))
        assertEquals("提示", normalizeAnniversaryMessage("  提示  "))
        assertEquals(400, normalizeAnniversaryMessage("错".repeat(600))?.length)
    }
}
