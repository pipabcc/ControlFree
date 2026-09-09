package com.example.controlfree.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionBankUpdateAccessPolicyTest {
    @Test
    fun `本地示例仍要求独立密码但不伪造激活状态`() {
        assertDenied(
            expected = QuestionBankUpdateAccessDenial.PASSWORD_NOT_CONFIGURED,
            source = QuestionBankUpdateSourceKind.BUNDLED_SAMPLE,
            configured = false,
            verified = false,
            entitlement = QuestionBankPaidEntitlement.TEST_SAMPLE_ONLY
        )
        assertDenied(
            expected = QuestionBankUpdateAccessDenial.PASSWORD_VERIFICATION_FAILED,
            source = QuestionBankUpdateSourceKind.BUNDLED_SAMPLE,
            configured = true,
            verified = false,
            entitlement = QuestionBankPaidEntitlement.TEST_SAMPLE_ONLY
        )
        assertEquals(
            QuestionBankUpdateAccessDecision.Allowed,
            QuestionBankUpdateAccessPolicy.decide(
                source = QuestionBankUpdateSourceKind.BUNDLED_SAMPLE,
                passwordConfigured = true,
                passwordVerified = true,
                entitlement = QuestionBankPaidEntitlement.TEST_SAMPLE_ONLY
            )
        )
    }

    @Test
    fun `在线更新必须同时通过密码与升级权益`() {
        assertDenied(
            expected = QuestionBankUpdateAccessDenial.PURCHASE_REQUIRED,
            source = QuestionBankUpdateSourceKind.PAID_ONLINE,
            configured = true,
            verified = true,
            entitlement = QuestionBankPaidEntitlement.INACTIVE
        )
        assertDenied(
            expected = QuestionBankUpdateAccessDenial.ENTITLEMENT_UNAVAILABLE,
            source = QuestionBankUpdateSourceKind.PAID_ONLINE,
            configured = true,
            verified = true,
            entitlement = QuestionBankPaidEntitlement.UNAVAILABLE
        )
        assertEquals(
            QuestionBankUpdateAccessDecision.Allowed,
            QuestionBankUpdateAccessPolicy.decide(
                source = QuestionBankUpdateSourceKind.PAID_ONLINE,
                passwordConfigured = true,
                passwordVerified = true,
                entitlement = QuestionBankPaidEntitlement.ACTIVE
            )
        )
    }

    private fun assertDenied(
        expected: QuestionBankUpdateAccessDenial,
        source: QuestionBankUpdateSourceKind,
        configured: Boolean,
        verified: Boolean,
        entitlement: QuestionBankPaidEntitlement
    ) {
        assertEquals(
            QuestionBankUpdateAccessDecision.Denied(expected),
            QuestionBankUpdateAccessPolicy.decide(
                source = source,
                passwordConfigured = configured,
                passwordVerified = verified,
                entitlement = entitlement
            )
        )
    }
}
