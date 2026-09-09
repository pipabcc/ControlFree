package com.example.controlfree.todo.anniversary.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryWidgetRefreshGenerationGateTest {
    @Test
    fun 较新的刷新开始后旧快照不能提交() {
        val gate = WidgetRefreshGenerationGate()
        val oldSnapshot = gate.begin(appWidgetId = 11)
        val newSnapshot = gate.begin(appWidgetId = 11)

        assertFalse(gate.commitIfCurrent(oldSnapshot) {})
        assertTrue(gate.commitIfCurrent(newSnapshot) {})
    }

    @Test
    fun 删除组件只失效对应组件的在途刷新() {
        val gate = WidgetRefreshGenerationGate()
        val deletedWidget = gate.begin(appWidgetId = 11)
        val retainedWidget = gate.begin(appWidgetId = 22)

        gate.invalidate(appWidgetId = 11)

        assertFalse(gate.commitIfCurrent(deletedWidget) {})
        assertTrue(gate.commitIfCurrent(retainedWidget) {})
    }

    @Test
    fun 禁用后在途和迟到刷新均不能提交直到再次启用() {
        val gate = WidgetRefreshGenerationGate()
        val inFlight = gate.begin(appWidgetId = 11)

        gate.disable()
        val lateRefresh = gate.begin(appWidgetId = 11)

        assertFalse(gate.commitIfCurrent(inFlight) {})
        assertFalse(gate.commitIfCurrent(lateRefresh) {})

        gate.enable()
        val current = gate.begin(appWidgetId = 11)
        assertTrue(gate.commitIfCurrent(current) {})
    }
}
