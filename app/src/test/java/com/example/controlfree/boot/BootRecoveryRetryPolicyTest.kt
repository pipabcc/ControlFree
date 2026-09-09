package com.example.controlfree.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRecoveryRetryPolicyTest {
    @Test
    fun `活动运行时使用有界的多次恢复窗口`() {
        val plan = BootRecoveryRetryPolicy.createPlan(
            BootRecoveryHint(
                monitorRecoveryRequired = true,
                appSupervisionRecoveryRequired = false
            )
        )

        assertEquals(listOf(0L, 2_000L, 10_000L, 30_000L), plan.dispatchDelaysMillis)
        assertEquals(5_000L, plan.stopGraceMillis)
        assertTrue(plan.dispatchDelaysMillis.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun `没有活动运行时时仍保留一次计划重建重试`() {
        val plan = BootRecoveryRetryPolicy.createPlan(
            BootRecoveryHint(
                monitorRecoveryRequired = false,
                appSupervisionRecoveryRequired = false
            )
        )

        assertEquals(listOf(0L, 8_000L), plan.dispatchDelaysMillis)
        assertEquals(3_000L, plan.stopGraceMillis)
    }

    @Test
    fun `提示读取失败时采用失败安全恢复窗口`() {
        val plan = BootRecoveryRetryPolicy.createPlan(
            BootRecoveryHint(
                monitorRecoveryRequired = false,
                appSupervisionRecoveryRequired = false,
                readFailed = true
            )
        )

        assertEquals(4, plan.dispatchDelaysMillis.size)
        assertEquals(30_000L, plan.dispatchDelaysMillis.last())
    }
}
