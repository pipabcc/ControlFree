package com.example.controlfree.widget.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoWidgetActionReceiverTest {
    @Test
    fun `完成动作保留明确目标状态以保证重复执行幂等`() {
        val complete = resolveTodoWidgetRowAction(
            TodoWidgetRowOperation.SET_COMPLETION.name,
            " todo-1 ",
            true
        )
        val restore = resolveTodoWidgetRowAction(
            TodoWidgetRowOperation.SET_COMPLETION.name,
            "todo-1",
            false
        )

        assertEquals("todo-1", complete?.itemId)
        assertTrue(complete?.targetCompleted == true)
        assertEquals(false, restore?.targetCompleted)
    }

    @Test
    fun `打开动作拒绝完成状态而完成动作必须携带目标状态`() {
        assertNull(
            resolveTodoWidgetRowAction(
                TodoWidgetRowOperation.OPEN_ITEM.name,
                "todo-1",
                true
            )
        )
        assertNull(
            resolveTodoWidgetRowAction(
                TodoWidgetRowOperation.SET_COMPLETION.name,
                "todo-1",
                null
            )
        )
        assertEquals(
            TodoWidgetRowOperation.OPEN_ITEM,
            resolveTodoWidgetRowAction(
                TodoWidgetRowOperation.OPEN_ITEM.name,
                "todo-1",
                null
            )?.operation
        )
    }

    @Test
    fun `非法操作和记录编号不会进入组件业务层`() {
        assertNull(resolveTodoWidgetRowAction("UNKNOWN", "todo-1", null))
        assertNull(
            resolveTodoWidgetRowAction(
                TodoWidgetRowOperation.OPEN_ITEM.name,
                "   ",
                null
            )
        )
        assertNull(
            resolveTodoWidgetRowAction(
                TodoWidgetRowOperation.OPEN_ITEM.name,
                "todo\u0000id",
                null
            )
        )
    }
}
