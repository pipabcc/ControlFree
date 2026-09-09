package com.example.controlfree.data

import android.content.Context
import android.content.SharedPreferences
import com.example.controlfree.LockActionFundingMethod

/** 独立开关。 */
class LockUnlockFeaturePreferences internal constructor(
    private val preferences: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    var growthUnlockEnabled: Boolean
        get() = preferences.getBoolean(GROWTH_UNLOCK_ENABLED, DEFAULT_GROWTH_UNLOCK_ENABLED)
        set(value) {
            check(preferences.edit().putBoolean(GROWTH_UNLOCK_ENABLED, value).commit()) {
                "成长值解锁开关保存失败"
            }
        }

    var knowledgeChallengeEnabled: Boolean
        get() = preferences.getBoolean(
            KNOWLEDGE_CHALLENGE_ENABLED,
            DEFAULT_KNOWLEDGE_CHALLENGE_ENABLED
        )
        set(value) {
            check(preferences.edit().putBoolean(KNOWLEDGE_CHALLENGE_ENABLED, value).commit()) {
                "百科挑战解锁开关保存失败"
            }
        }

    var requireAuthForGrowthUnlock: Boolean
        get() = preferences.getBoolean(
            REQUIRE_AUTH_FOR_GROWTH_UNLOCK,
            DEFAULT_REQUIRE_AUTH
        )
        set(value) {
            check(preferences.edit().putBoolean(REQUIRE_AUTH_FOR_GROWTH_UNLOCK, value).commit()) {
                "成长值解锁验证开关保存失败"
            }
        }

    var requireAuthForKnowledgeChallenge: Boolean
        get() = preferences.getBoolean(
            REQUIRE_AUTH_FOR_KNOWLEDGE_CHALLENGE,
            DEFAULT_REQUIRE_AUTH
        )
        set(value) {
            check(preferences.edit().putBoolean(REQUIRE_AUTH_FOR_KNOWLEDGE_CHALLENGE, value).commit()) {
                "百科挑战验证开关保存失败"
            }
        }

    companion object {
        const val DEFAULT_GROWTH_UNLOCK_ENABLED = true
        const val DEFAULT_KNOWLEDGE_CHALLENGE_ENABLED = true
        const val DEFAULT_REQUIRE_AUTH = false
        internal const val PREFERENCES_NAME = "lock_unlock_feature_preferences"
        private const val GROWTH_UNLOCK_ENABLED = "growth_unlock_enabled"
        private const val KNOWLEDGE_CHALLENGE_ENABLED = "knowledge_challenge_enabled"
        private const val REQUIRE_AUTH_FOR_GROWTH_UNLOCK = "require_auth_for_growth_unlock"
        private const val REQUIRE_AUTH_FOR_KNOWLEDGE_CHALLENGE = "require_auth_for_knowledge_challenge"
    }
}

internal fun isLockUnlockFundingMethodEnabled(
    fundingMethod: LockActionFundingMethod,
    growthUnlockEnabled: Boolean,
    knowledgeChallengeEnabled: Boolean
): Boolean = when (fundingMethod) {
    LockActionFundingMethod.GROWTH_POINTS -> growthUnlockEnabled
    LockActionFundingMethod.KNOWLEDGE_CHALLENGE -> knowledgeChallengeEnabled
}

/**
 * 只有已经配置数字密码或手势密码时，才能开启解锁身份验证要求。
 *
 * 关闭验证不需要凭据；设置页应在写入开关前调用该策略，避免出现“要求验证但没有凭据可验证”的死配置。
 */
internal fun canEnableUnlockAuthentication(
    enabled: Boolean,
    hasAnyCredential: Boolean
): Boolean = !enabled || hasAnyCredential
