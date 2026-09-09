package com.example.controlfree.ai

import com.example.controlfree.LockPendingActionKind
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.sensor.EyeDistanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCompanionPolicyTest {
    @Test
    fun `角色陪聊请求只序列化聊天内容且不包含行为统计字段`() {
        val requestBody = AiCompanionPrompt.buildChatRequest(
            AiChatRequest(
                history = listOf(AiChatMessage("user", "陪我再坚持一分钟")),
                personality = AiPersonality.GENTLE,
                remainingSeconds = 600,
                eyeDistanceStatus = EyeDistanceStatus.SAFE
            )
        )

        assertTrue(requestBody.contains("陪我再坚持一分钟"))
        assertFalse(requestBody.contains("today_minutes"))
        assertFalse(requestBody.contains("top_app_shares"))
        assertFalse(requestBody.contains("supervision_seven_days"))
    }

    @Test
    fun `暂停建议保持本地来源且不泄露统计数据`() {
        val advice = OfflineInterventionAdvisor.createAdvice(sampleRequest())

        assertEquals(AiAdviceSource.LOCAL, advice.source)
        assertTrue(advice.message.contains("你选择暂停 10 分钟"))
        assertFalse(advice.message.contains("今天已使用"))
        assertFalse(advice.message.contains("近七日"))
        assertFalse(advice.message.contains(sampleRequest().lockSessionId.toString()))
    }

    @Test
    fun `跳过建议不携带暂停参数和剩余时间`() {
        val advice = OfflineInterventionAdvisor.createAdvice(
            sampleRequest().copy(
                actionKind = LockPendingActionKind.SKIP,
                requestedPauseMinutes = 30,
                remainingSeconds = 301
            )
        )

        assertEquals(AiAdviceSource.LOCAL, advice.source)
        assertFalse(advice.message.contains("你选择暂停"))
        assertFalse(advice.message.contains("还剩约"))
    }

    @Test
    fun `建议文本移除控制字符并按码点截断`() {
        val sanitized = AiAdviceTextSanitizer.sanitize(
            "  第一行\n第二行\u0000" + "陪".repeat(300)
        )

        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains('\u0000'))
        assertTrue(sanitized.endsWith("…"))
        assertTrue(sanitized.codePointCount(0, sanitized.length) <= 221)
    }

    private fun sampleRequest() = AiInterventionRequest(
        actionKind = LockPendingActionKind.PAUSE,
        requestedPauseMinutes = 10,
        sessionMode = MonitorSessionMode.FOCUS,
        remainingSeconds = 600,
        lockSessionId = 987_654_321L
    )

}
