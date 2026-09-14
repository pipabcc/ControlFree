package com.example.controlfree.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientSanitizerTest {

    @Test
    fun `sanitize keeps plain text unchanged`() {
        val raw = "你好，世界 Hello 123"
        assertEquals(raw, DeepSeekResponseContentSanitizer.sanitize(raw))
    }

    @Test
    fun `sanitize replaces line breaks and tabs with spaces`() {
        assertEquals("a b c", DeepSeekResponseContentSanitizer.sanitize("a\nb\tc"))
        assertEquals("a b", DeepSeekResponseContentSanitizer.sanitize("a\r\nb"))
    }

    @Test
    fun `sanitize drops other control characters`() {
        val raw = "a\u0000b\u0007c\u007fd"
        assertEquals("abcd", DeepSeekResponseContentSanitizer.sanitize(raw))
    }

    @Test
    fun `sanitize trims leading and trailing whitespace`() {
        assertEquals("content", DeepSeekResponseContentSanitizer.sanitize("\n  content \t"))
    }

    @Test
    fun `sanitize truncates long content without splitting code points`() {
        val emoji = "😀"
        val raw = emoji.repeat(10_000)
        val sanitized = DeepSeekResponseContentSanitizer.sanitize(raw)
        assertTrue(sanitized.codePointCount(0, sanitized.length) <= 8_192)
        // 截断不应把代理对拆成非法字符串
        assertFalse(sanitized.endsWith("�"))
    }

    @Test
    fun `advice sanitizer collapses whitespace and drops code fences`() {
        val raw = "```kotlin\n第一行\r\n  第二行\t\t第三行```"
        val sanitized = AiAdviceTextSanitizer.sanitize(raw)
        assertEquals("第一行 第二行 第三行", sanitized)
    }

    @Test
    fun `advice sanitizer caps length with ellipsis`() {
        val raw = "很长的建议".repeat(200)
        val sanitized = AiAdviceTextSanitizer.sanitize(raw)
        assertTrue(sanitized.endsWith("…"))
        assertTrue(sanitized.codePointCount(0, sanitized.length) <= 221)
    }
}

class AiCompanionPromptHistoryBoundedTest {

    private fun message(content: String) = AiChatMessage(role = "user", content = content)

    @Test
    fun `history beyond limit keeps only recent messages`() {
        val history = (1..40).map { index -> message("msg-$index") }
        val request = AiChatRequest(
            history = history,
            personality = AiPersonality.GENTLE,
            remainingSeconds = 60,
            eyeDistanceStatus = com.example.controlfree.sensor.EyeDistanceStatus.SAFE
        )
        val body = AiCompanionPrompt.buildChatRequest(request)
        // 只保留最近 12 条：早期消息（msg-1、msg-5 等）不应出现在请求体里
        assertFalse(body.contains("msg-1\""))
        assertFalse(body.contains("msg-5\""))
        assertTrue(body.contains("msg-29\""))
        assertTrue(body.contains("msg-40\""))
    }

    @Test
    fun `oversized message content is truncated and request stays under 16KB`() {
        val huge = "很长的消息内容".repeat(5_000)
        val history = List(20) { index -> message(if (index == 19) huge else "短消息-$index") }
        val request = AiChatRequest(
            history = history,
            personality = AiPersonality.STRICT,
            remainingSeconds = 10,
            eyeDistanceStatus = com.example.controlfree.sensor.EyeDistanceStatus.SAFE
        )
        val body = AiCompanionPrompt.buildChatRequest(request)
        // 客户端硬上限 16KB
        assertTrue(
            "请求体 ${body.toByteArray(Charsets.UTF_8).size} 字节，超出 16KB 上限",
            body.toByteArray(Charsets.UTF_8).size <= 16 * 1_024
        )
    }
}
