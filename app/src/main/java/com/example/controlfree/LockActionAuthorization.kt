package com.example.controlfree

import android.content.Intent
import java.util.UUID

internal enum class LockActionFundingMethod(val wireValue: String) {
    GROWTH_POINTS("growth_points"),
    KNOWLEDGE_CHALLENGE("knowledge_challenge");

    companion object {
        fun fromWireValue(value: String?): LockActionFundingMethod? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 凭据验证之后交给监督服务的完整动作命令。
 *
 * [authorization] 只描述成长值资金来源；数字密码或手势仍由锁屏界面独立验证，
 * 服务还会重新核对锁定会话、挑战凭证、订单 ID 和当前阶段。
 */
internal data class AuthorizedLockAction(
    val action: LockPendingAction,
    val authorization: LockActionAuthorization
)

@ConsistentCopyVisibility
internal data class LockActionAuthorization private constructor(
    val orderId: String,
    val fundingMethod: LockActionFundingMethod,
    val challengeTokenId: String?
) {
    init {
        require(COMMAND_ID_PATTERN.matches(orderId)) { "成长值订单 ID 无效" }
        when (fundingMethod) {
            LockActionFundingMethod.GROWTH_POINTS ->
                require(challengeTokenId == null) { "成长值支付不能携带挑战凭证" }
            LockActionFundingMethod.KNOWLEDGE_CHALLENGE ->
                require(
                    challengeTokenId != null && COMMAND_ID_PATTERN.matches(challengeTokenId)
                ) { "百科挑战凭证无效" }
        }
    }

    companion object {
        private val COMMAND_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,160}")

        fun generateOrderIdForAction(action: LockPendingAction): String {
            val randomSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
            return when (action.kind) {
                LockPendingActionKind.PAUSE -> "ord_pause_${action.pauseMinutes ?: 5}_$randomSuffix"
                LockPendingActionKind.SKIP -> "ord_skip_$randomSuffix"
            }
        }

        fun growthPoints(orderId: String = UUID.randomUUID().toString()) =
            LockActionAuthorization(
                orderId = orderId,
                fundingMethod = LockActionFundingMethod.GROWTH_POINTS,
                challengeTokenId = null
            )

        fun knowledgeChallenge(
            challengeTokenId: String,
            orderId: String = UUID.randomUUID().toString()
        ) = LockActionAuthorization(
            orderId = orderId,
            fundingMethod = LockActionFundingMethod.KNOWLEDGE_CHALLENGE,
            challengeTokenId = challengeTokenId
        )

        fun fromWireValues(
            orderId: String?,
            fundingMethod: String?,
            challengeTokenId: String?
        ): LockActionAuthorization? {
            val safeOrderId = orderId?.takeIf(COMMAND_ID_PATTERN::matches) ?: return null
            return when (LockActionFundingMethod.fromWireValue(fundingMethod)) {
                LockActionFundingMethod.GROWTH_POINTS -> if (challengeTokenId == null) {
                    growthPoints(safeOrderId)
                } else {
                    null
                }
                LockActionFundingMethod.KNOWLEDGE_CHALLENGE -> challengeTokenId
                    ?.takeIf(COMMAND_ID_PATTERN::matches)
                    ?.let { knowledgeChallenge(it, safeOrderId) }
                null -> null
            }
        }
    }
}

internal object LockActionAuthorizationContract {
    private const val EXTRA_ORDER_ID = "com.example.controlfree.extra.GROWTH_ORDER_ID"
    private const val EXTRA_FUNDING_METHOD =
        "com.example.controlfree.extra.LOCK_ACTION_FUNDING_METHOD"
    private const val EXTRA_CHALLENGE_TOKEN_ID =
        "com.example.controlfree.extra.KNOWLEDGE_CHALLENGE_TOKEN_ID"

    fun write(intent: Intent, authorization: LockActionAuthorization) {
        intent.putExtra(EXTRA_ORDER_ID, authorization.orderId)
        intent.putExtra(EXTRA_FUNDING_METHOD, authorization.fundingMethod.wireValue)
        authorization.challengeTokenId?.let { intent.putExtra(EXTRA_CHALLENGE_TOKEN_ID, it) }
    }

    fun read(intent: Intent): LockActionAuthorization? =
        LockActionAuthorization.fromWireValues(
            orderId = intent.getStringExtra(EXTRA_ORDER_ID),
            fundingMethod = intent.getStringExtra(EXTRA_FUNDING_METHOD),
            challengeTokenId = intent.getStringExtra(EXTRA_CHALLENGE_TOKEN_ID)
        )

    fun consume(intent: Intent): LockActionAuthorization? = read(intent).also {
        intent.removeExtra(EXTRA_ORDER_ID)
        intent.removeExtra(EXTRA_FUNDING_METHOD)
        intent.removeExtra(EXTRA_CHALLENGE_TOKEN_ID)
    }
}

/** Overlay 仅在用户主动点击百科入口时把未验证动作交给 LockActivity 展示题目。 */
internal object LockKnowledgeChallengeContract {
    private const val EXTRA_ACTION_KIND =
        "com.example.controlfree.extra.KNOWLEDGE_REQUEST_ACTION_KIND"
    private const val EXTRA_PAUSE_MINUTES =
        "com.example.controlfree.extra.KNOWLEDGE_REQUEST_PAUSE_MINUTES"

    fun writeRequest(intent: Intent, action: LockPendingAction) {
        intent.putExtra(EXTRA_ACTION_KIND, action.kind.wireValue)
        action.pauseMinutes?.let { intent.putExtra(EXTRA_PAUSE_MINUTES, it) }
    }

    fun consumeRequest(intent: Intent): LockPendingAction? {
        val kind = intent.getStringExtra(EXTRA_ACTION_KIND)
        val pauseMinutes = if (intent.hasExtra(EXTRA_PAUSE_MINUTES)) {
            intent.getIntExtra(EXTRA_PAUSE_MINUTES, Int.MIN_VALUE)
        } else {
            null
        }
        intent.removeExtra(EXTRA_ACTION_KIND)
        intent.removeExtra(EXTRA_PAUSE_MINUTES)
        return LockPendingAction.fromWireValue(kind, pauseMinutes)
    }
}
