package com.example.controlfree.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshCoordinatorTest {
    @Test
    fun `待办和闪记数据变化只刷新集合内容`() {
        val plan = widgetRefreshPlanForTables(setOf("todo_items", "quick_notes"))

        assertTrue(plan.fullTargets.isEmpty())
        assertEquals(
            setOf(WidgetRefreshTarget.TODO, WidgetRefreshTarget.QUICK_NOTE),
            plan.collectionTargets
        )
    }

    @Test
    fun `计划和时刻变化继续完整渲染组件`() {
        val plan = widgetRefreshPlanForTables(
            setOf("supervision_plans", "anniversary_items")
        )

        assertEquals(
            setOf(
                WidgetRefreshTarget.SUPERVISION,
                WidgetRefreshTarget.FOCUS,
                WidgetRefreshTarget.ANNIVERSARY
            ),
            plan.fullTargets
        )
        assertTrue(plan.collectionTargets.isEmpty())
    }
}
