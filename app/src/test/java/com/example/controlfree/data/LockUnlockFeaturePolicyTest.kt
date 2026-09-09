package com.example.controlfree.data

import com.example.controlfree.LockActionFundingMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockUnlockFeaturePolicyTest {
    @Test
    fun `两个开关的默认语义都是开启`() {
        assertTrue(LockUnlockFeaturePreferences.DEFAULT_GROWTH_UNLOCK_ENABLED)
        assertTrue(LockUnlockFeaturePreferences.DEFAULT_KNOWLEDGE_CHALLENGE_ENABLED)
    }

    @Test
    fun `两个身份验证开关默认关闭`() {
        assertFalse(LockUnlockFeaturePreferences.DEFAULT_REQUIRE_AUTH)
    }

    @Test
    fun `服务端按资金方式独立执行两个开关`() {
        assertFalse(
            isLockUnlockFundingMethodEnabled(
                LockActionFundingMethod.GROWTH_POINTS,
                growthUnlockEnabled = false,
                knowledgeChallengeEnabled = true
            )
        )
        assertTrue(
            isLockUnlockFundingMethodEnabled(
                LockActionFundingMethod.KNOWLEDGE_CHALLENGE,
                growthUnlockEnabled = false,
                knowledgeChallengeEnabled = true
            )
        )
        assertFalse(
            isLockUnlockFundingMethodEnabled(
                LockActionFundingMethod.KNOWLEDGE_CHALLENGE,
                growthUnlockEnabled = true,
                knowledgeChallengeEnabled = false
            )
        )
    }

    @Test
    fun `开启解锁验证前必须已经配置密码或手势`() {
        assertFalse(canEnableUnlockAuthentication(enabled = true, hasAnyCredential = false))
        assertTrue(canEnableUnlockAuthentication(enabled = true, hasAnyCredential = true))
        assertTrue(canEnableUnlockAuthentication(enabled = false, hasAnyCredential = false))
    }
}
