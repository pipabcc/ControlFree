package com.example.controlfree.widget.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanWidgetUpdateGateTest {
    @Test
    fun `新刷新开始后旧计划快照不能覆盖`() {
        val gate = PlanWidgetUpdateGate()
        val old = gate.begin(1)
        val current = gate.begin(1)

        assertFalse(gate.commitIfCurrent(old) {})
        assertTrue(gate.commitIfCurrent(current) {})
    }

    @Test
    fun `删除组件会失效在途刷新`() {
        val gate = PlanWidgetUpdateGate()
        val token = gate.begin(2)

        gate.invalidate(2)

        assertFalse(gate.commitIfCurrent(token) {})
    }
}
