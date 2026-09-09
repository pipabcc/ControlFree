package com.example.controlfree.supervision.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSupervisionServiceRecoveryPolicyTest {
    @Test
    fun `FGS或初始化失败即使尚未加载规则也必须安排恢复`() {
        assertTrue(
            AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                startupFailed = true,
                isStoppingIntentionally = false,
                activeRuleCount = 0
            )
        )
    }

    @Test
    fun `携带活动规则的非主动销毁必须安排恢复`() {
        assertTrue(
            AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                startupFailed = false,
                isStoppingIntentionally = false,
                activeRuleCount = 2
            )
        )
    }

    @Test
    fun `活动防拖延窗口即使没有常规规则也必须安排恢复`() {
        assertTrue(
            AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                startupFailed = false,
                isStoppingIntentionally = false,
                activeRuleCount = 0,
                hasActiveCommitment = true
            )
        )
    }

    @Test
    fun `主动停止或空闲实例不会重新拉起服务`() {
        assertFalse(
            AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                startupFailed = false,
                isStoppingIntentionally = true,
                activeRuleCount = 1
            )
        )
        assertFalse(
            AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                startupFailed = false,
                isStoppingIntentionally = false,
                activeRuleCount = 0
            )
        )
    }

    @Test
    fun `损坏快照的保守休息只覆盖处于执行时段的规则`() {
        val executionRule = rule("execution", isExecutionWindowActive = true)
        val disabledOnlyRule = rule("disabled", isExecutionWindowActive = false)

        assertEquals(
            listOf(executionRule),
            AppSupervisionServiceRecoveryPolicy.failSafeRestRules(
                listOf(executionRule, disabledOnlyRule)
            )
        )
    }

    private fun rule(
        id: String,
        isExecutionWindowActive: Boolean
    ) = AppSupervisionRule(
        planId = id,
        planUpdatedAtEpochMillis = 1L,
        planName = "计划-$id",
        packageName = "example.$id",
        occurrenceEndEpochMillis = 100_000L,
        usageAllowanceMillis = 60_000L,
        restDurationMillis = 60_000L,
        isExecutionWindowActive = isExecutionWindowActive,
        cycleOccurrenceEndEpochMillis = if (isExecutionWindowActive) 100_000L else 0L,
        disabledUntilEpochMillis = if (isExecutionWindowActive) 0L else 100_000L
    )
}
