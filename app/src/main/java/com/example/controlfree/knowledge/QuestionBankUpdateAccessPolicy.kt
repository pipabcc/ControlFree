package com.example.controlfree.knowledge

/** 题库内容来源 */
internal enum class QuestionBankUpdateSourceKind {
    BUNDLED_SAMPLE,
    PAID_ONLINE
}

/**
 * 题库模块
 * `TEST_SAMPLE_ONLY` 仅允许安装随 App 交付的测试题包。
 */
internal enum class QuestionBankPaidEntitlement {
    TEST_SAMPLE_ONLY,
    ACTIVE,
    INACTIVE,
    UNAVAILABLE
}

internal fun interface QuestionBankPaidEntitlementProvider {
    fun currentEntitlement(): QuestionBankPaidEntitlement
}

/**
 * 默认只允许随 APK 交付的测试包
 */
internal object QuestionBankPaidEntitlementRegistry {
    @Volatile
    private var provider = QuestionBankPaidEntitlementProvider {
        QuestionBankPaidEntitlement.TEST_SAMPLE_ONLY
    }

    fun currentEntitlement(): QuestionBankPaidEntitlement = try {
        provider.currentEntitlement()
    } catch (_: RuntimeException) {
        QuestionBankPaidEntitlement.UNAVAILABLE
    }

    fun install(provider: QuestionBankPaidEntitlementProvider) {
        this.provider = provider
    }
}

internal enum class QuestionBankUpdateAccessDenial {
    PASSWORD_NOT_CONFIGURED,
    PASSWORD_VERIFICATION_FAILED,
    PURCHASE_REQUIRED,
    ENTITLEMENT_UNAVAILABLE
}

internal sealed interface QuestionBankUpdateAccessDecision {
    data object Allowed : QuestionBankUpdateAccessDecision
    data class Denied(val reason: QuestionBankUpdateAccessDenial) : QuestionBankUpdateAccessDecision
}

/** 独立密码 */
internal object QuestionBankUpdateAccessPolicy {
    fun decide(
        source: QuestionBankUpdateSourceKind,
        passwordConfigured: Boolean,
        passwordVerified: Boolean,
        entitlement: QuestionBankPaidEntitlement
    ): QuestionBankUpdateAccessDecision {
        if (!passwordConfigured) {
            return QuestionBankUpdateAccessDecision.Denied(
                QuestionBankUpdateAccessDenial.PASSWORD_NOT_CONFIGURED
            )
        }
        if (!passwordVerified) {
            return QuestionBankUpdateAccessDecision.Denied(
                QuestionBankUpdateAccessDenial.PASSWORD_VERIFICATION_FAILED
            )
        }
        if (source == QuestionBankUpdateSourceKind.BUNDLED_SAMPLE) {
            return QuestionBankUpdateAccessDecision.Allowed
        }
        return when (entitlement) {
            QuestionBankPaidEntitlement.ACTIVE -> QuestionBankUpdateAccessDecision.Allowed
            QuestionBankPaidEntitlement.INACTIVE,
            QuestionBankPaidEntitlement.TEST_SAMPLE_ONLY -> QuestionBankUpdateAccessDecision.Denied(
                QuestionBankUpdateAccessDenial.PURCHASE_REQUIRED
            )
            QuestionBankPaidEntitlement.UNAVAILABLE -> QuestionBankUpdateAccessDecision.Denied(
                QuestionBankUpdateAccessDenial.ENTITLEMENT_UNAVAILABLE
            )
        }
    }
}
