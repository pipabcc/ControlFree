package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockActionAuthorizationTest {
    @Test
    fun `成长值与挑战授权严格区分各自字段`() {
        val growth = LockActionAuthorization.fromWireValues(
            orderId = "order_123456",
            fundingMethod = "growth_points",
            challengeTokenId = null
        )
        val challenge = LockActionAuthorization.fromWireValues(
            orderId = "order_654321",
            fundingMethod = "knowledge_challenge",
            challengeTokenId = "token_123456"
        )

        assertEquals(LockActionFundingMethod.GROWTH_POINTS, growth?.fundingMethod)
        assertEquals("token_123456", challenge?.challengeTokenId)
    }

    @Test
    fun `缺失挑战凭证或成长值路径夹带凭证时拒绝命令`() {
        assertNull(
            LockActionAuthorization.fromWireValues(
                "order_123456",
                "knowledge_challenge",
                null
            )
        )
        assertNull(
            LockActionAuthorization.fromWireValues(
                "order_123456",
                "growth_points",
                "token_123456"
            )
        )
    }
}
