package com.example.controlfree.ai

import com.example.controlfree.security.AiApiKeyReadResult
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductivityAiCoordinatorTest {
    private val now = ZonedDateTime.of(2026, 7, 26, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun `AI 助理未启用时拒绝分析且不调用网关`() = runTest {
        var networkCalls = 0
        val coordinator = coordinator(
            enabled = false,
            gateway = DeepSeekGateway { _, _ ->
                networkCalls++
                DeepSeekCallResult.Success(VALID_RESPONSE)
            }
        )

        val message = failureMessage { coordinator.analyzeQuickNote("下午三点去打球", now) }

        assertTrue(message.contains("AI 建议未启用"))
        assertEquals(0, networkCalls)
    }

    @Test
    fun `AI 助理缺少密钥时拒绝分析且不调用网关`() = runTest {
        var networkCalls = 0
        val coordinator = coordinator(
            keyResult = AiApiKeyReadResult.Missing,
            gateway = DeepSeekGateway { _, _ ->
                networkCalls++
                DeepSeekCallResult.Success(VALID_RESPONSE)
            }
        )

        val message = failureMessage { coordinator.analyzeQuickNote("下午三点去打球", now) }

        assertTrue(message.contains("API Key"))
        assertEquals(0, networkCalls)
    }

    @Test
    fun `AI 助理网络失败时明确报错而不生成本地结果`() = runTest {
        val coordinator = coordinator(
            gateway = DeepSeekGateway { _, _ -> DeepSeekCallResult.NetworkUnavailable }
        )

        val message = failureMessage { coordinator.analyzeQuickNote("下午三点去打球", now) }

        assertTrue(message.contains("连接超时或调用失败"))
        assertTrue(message.contains("网络设置"))
    }

    @Test
    fun `AI 助理拒绝格式错误的远端响应`() = runTest {
        val coordinator = coordinator(
            gateway = DeepSeekGateway { _, _ -> DeepSeekCallResult.Success("{}") }
        )

        val message = failureMessage { coordinator.analyzeQuickNote("下午三点去打球", now) }

        assertTrue(message.contains("响应格式不匹配"))
    }

    @Test
    fun `AI 助理只返回远端协议解析结果`() = runTest {
        val analysis = coordinator().analyzeQuickNote("今天先完成最重要的一步", now)

        assertEquals(listOf("先处理最重要且可立即执行的一步。"), analysis.advice)
        assertEquals(0, analysis.actionableCount)
        assertTrue(analysis.warnings.isEmpty())
    }

    @Test
    fun `普通闪记转换仍保留本地解析能力`() = runTest {
        var networkCalls = 0
        val parsed = coordinator(
            enabled = false,
            gateway = DeepSeekGateway { _, _ ->
                networkCalls++
                DeepSeekCallResult.NetworkUnavailable
            }
        ).parseQuickNote("专注20分钟阅读论文", now)

        assertEquals(20, parsed.estimatedDurationMinutes)
        assertEquals(0, networkCalls)
    }

    private fun coordinator(
        enabled: Boolean = true,
        keyResult: AiApiKeyReadResult = AiApiKeyReadResult.Available("sk-test-key-1234567890"),
        gateway: DeepSeekGateway = DeepSeekGateway { _, _ -> DeepSeekCallResult.Success(VALID_RESPONSE) }
    ) = ProductivityAiCoordinator(
        enabledReader = { enabled },
        apiKeyReader = { keyResult },
        gateway = gateway
    )

    private suspend fun failureMessage(block: suspend () -> Unit): String = try {
        block()
        throw AssertionError("预期 AI 助理分析抛出异常")
    } catch (error: IllegalStateException) {
        error.message.orEmpty()
    }

    private companion object {
        val VALID_RESPONSE = """
            {
              "schema":"controlfree.quick-note-assistant.v1",
              "ledger_entries":[],
              "todo_items":[],
              "habits":[],
              "focus_sessions":[],
              "anniversaries":[],
              "advice":["先处理最重要且可立即执行的一步。"],
              "warnings":[]
            }
        """.trimIndent()
    }
}
