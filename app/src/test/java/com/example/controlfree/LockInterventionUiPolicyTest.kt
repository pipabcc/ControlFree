package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockInterventionUiPolicyTest {
    @Test
    fun `AI关闭时仍展示本地宠物但不请求云端建议`() {
        assertTrue(shouldShowLockPetIntervention(aiEnabled = false))
        assertTrue(shouldShowLockPetIntervention(aiEnabled = true))
        assertFalse(shouldRequestLockPetAiAdvice(aiEnabled = false))
        assertTrue(shouldRequestLockPetAiAdvice(aiEnabled = true))
    }

    @Test
    fun `暂停提供约定的快捷时长`() {
        assertEquals(listOf(5, 10, 15), LOCK_PAUSE_PRESET_MINUTES)
    }

    @Test
    fun `加载阶段立即提供本地建议且保留原动作`() {
        val action = LockPendingAction.pause(8)
        val state = initialLockPetInterventionUiState(
            requestId = 7L,
            lockSessionId = 42L,
            action = action,
            sessionMode = MonitorSessionMode.SUPERVISION,
            remainingSeconds = 90
        )

        assertTrue(state.isLoading)
        assertEquals(action, state.action)
        assertTrue(state.message.isNotBlank())
    }

    @Test
    fun `AI关闭时初始建议立即完成加载`() {
        val state = initialLockPetInterventionUiState(
            requestId = 7L,
            lockSessionId = 42L,
            action = LockPendingAction.Skip,
            sessionMode = MonitorSessionMode.SUPERVISION,
            remainingSeconds = 90,
            requestAiAdvice = false
        )

        assertFalse(state.isLoading)
        assertTrue(state.message.isNotBlank())
        assertEquals(state.message, state.chatHistory.single().content)
    }

    @Test
    fun `只接纳当前请求和当前锁定会话的AI结果`() {
        val initial = initialLockPetInterventionUiState(
            requestId = 7L,
            lockSessionId = 42L,
            action = LockPendingAction.Skip,
            sessionMode = MonitorSessionMode.FOCUS,
            remainingSeconds = 90
        )

        assertEquals(
            initial,
            completeLockPetInterventionUiState(initial, 8L, 42L, "迟到结果")
        )
        assertEquals(
            initial,
            completeLockPetInterventionUiState(initial, 7L, 43L, "旧会话结果")
        )
        val completed = completeLockPetInterventionUiState(
            initial,
            requestId = 7L,
            lockSessionId = 42L,
            message = "  再坚持这一分钟，你正在守住自己的计划。  "
        )
        assertFalse(completed.isLoading)
        assertEquals("再坚持这一分钟，你正在守住自己的计划。", completed.message)
    }

    @Test
    fun `空白AI结果沿用当前本地兜底且不再保持加载`() {
        val initial = initialLockPetInterventionUiState(
            requestId = 1L,
            lockSessionId = 2L,
            action = LockPendingAction.Skip,
            sessionMode = MonitorSessionMode.SUPERVISION,
            remainingSeconds = 90
        )
        val completed = completeLockPetInterventionUiState(
            initial,
            requestId = 1L,
            lockSessionId = 2L,
            message = "   "
        )

        assertFalse(completed.isLoading)
        assertEquals(initial.message, completed.message)
        assertEquals(initial.message, completed.chatHistory.single().content)
    }

    @Test
    fun `过长AI文案在UI边界被截断`() {
        val initial = initialLockPetInterventionUiState(
            requestId = 1L,
            lockSessionId = 2L,
            action = LockPendingAction.Skip,
            sessionMode = MonitorSessionMode.SUPERVISION,
            remainingSeconds = 90
        )
        val completed = completeLockPetInterventionUiState(
            initial,
            requestId = 1L,
            lockSessionId = 2L,
            message = "建议".repeat(200)
        )

        assertEquals(
            LOCK_PET_ADVICE_MAX_CODE_POINTS,
            completed.message.codePointCount(0, completed.message.length)
        )
    }

    @Test
    fun `宠物触摸限频并避免连续重复本地回应`() {
        val first = resolveLockPetTouch(-1, null, 1_000L)
        val throttled = resolveLockPetTouch(first.responseIndex, 1_000L, 1_200L)
        val second = resolveLockPetTouch(first.responseIndex, 1_000L, 1_500L)

        assertTrue(first.accepted)
        assertFalse(throttled.accepted)
        assertEquals(first.responseIndex, throttled.responseIndex)
        assertTrue(second.accepted)
        assertTrue(second.message?.isNotBlank() == true)
        assertTrue(second.responseIndex != first.responseIndex)
    }

    @Test
    fun `系统单调时钟回退后触摸不会永久失效`() {
        val responses = listOf("回应一", "回应二", "回应三", "回应四")
        val decision = resolveLockPetTouch(2, 5_000L, 120L, responses)
        val corruptIndex = resolveLockPetTouch(Int.MAX_VALUE, null, 120L, responses)

        assertTrue(decision.accepted)
        assertTrue(decision.responseIndex in responses.indices)
        assertTrue(decision.responseIndex != 2)
        assertEquals(responses[decision.responseIndex], decision.message)
        assertTrue(corruptIndex.responseIndex in responses.indices)
        assertEquals(responses[corruptIndex.responseIndex], corruptIndex.message)
    }

    @Test
    fun `宠物区域按矮屏和大字体切换紧凑布局且高度有界`() {
        assertEquals(
            LockCompanionLayoutMode.COMPACT,
            resolveLockCompanionLayoutMode(screenHeightDp = 480, fontScale = 1f)
        )
        assertEquals(
            LockCompanionLayoutMode.COMPACT,
            resolveLockCompanionLayoutMode(screenHeightDp = 800, fontScale = 1.6f)
        )
        assertEquals(
            LockCompanionLayoutMode.STANDARD,
            resolveLockCompanionLayoutMode(screenHeightDp = 800, fontScale = 1.2f)
        )
        assertEquals(88, resolveLockCompanionMaxHeightDp(360, 1f))
        assertTrue(resolveLockCompanionMaxHeightDp(800, 1f) <= 800 * 50 / 100)
    }
}
