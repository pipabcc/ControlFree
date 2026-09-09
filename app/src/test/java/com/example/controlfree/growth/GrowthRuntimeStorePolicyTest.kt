package com.example.controlfree.growth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthRuntimeStorePolicyTest {
    @Test
    fun `继续自律必须满足同一启动周期和完整单调时长`() {
        val commitment = GrowthContinuationCommitment(
            armedAtElapsedMillis = 10_000L,
            requiredSeconds = 300L,
            bootCount = 7
        )

        assertFalse(continuationReached(commitment, 309_999L, 7))
        assertTrue(continuationReached(commitment, 310_000L, 7))
        assertFalse(continuationReached(commitment, 400_000L, 8))
    }

    @Test
    fun `单调时钟回退时不会错误发放奖励`() {
        val commitment = GrowthContinuationCommitment(
            armedAtElapsedMillis = 50_000L,
            requiredSeconds = 60L,
            bootCount = 2
        )

        assertFalse(continuationReached(commitment, 1_000L, 2))
    }
}
