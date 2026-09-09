package com.example.controlfree.ai

import com.example.controlfree.LockPendingActionKind
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.security.AiApiKeyReadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiInterventionCoordinatorTest {
    @Test
    fun `行为建议始终在本机生成且不联网`() = runTest {
        var networkCalls = 0
        val coordinator = coordinator(
            gateway = DeepSeekGateway { _, _ ->
                networkCalls++
                DeepSeekCallResult.Success("不应调用")
            }
        )

        val advice = coordinator.getAdvice(sampleRequest())

        assertEquals(AiAdviceSource.LOCAL, advice.source)
        assertEquals(0, networkCalls)
    }

    @Test
    fun `重复生成行为建议不会触发网络`() = runTest {
        var networkCalls = 0
        val coordinator = coordinator(
            gateway = DeepSeekGateway { _, _ ->
                networkCalls++
                DeepSeekCallResult.Success("我看见你正在犹豫。先放低手机二十秒，慢慢呼吸四次，再决定是否继续验证。")
            }
        )

        val first = coordinator.getAdvice(sampleRequest())
        val second = coordinator.getAdvice(sampleRequest())

        assertEquals(AiAdviceSource.LOCAL, first.source)
        assertEquals(AiAdviceSource.LOCAL, second.source)
        assertEquals(0, networkCalls)
    }

    @Test
    fun `无密钥或网络失败均保留可操作的本地建议`() = runTest {
        val noKey = coordinator(
            keyResult = AiApiKeyReadResult.Missing
        ).getAdvice(sampleRequest())
        val offline = coordinator(
            gateway = DeepSeekGateway { _, _ -> DeepSeekCallResult.NetworkUnavailable }
        ).getAdvice(sampleRequest())

        assertEquals(AiAdviceSource.LOCAL, noKey.source)
        assertEquals(AiAdviceSource.LOCAL, offline.source)
        assertTrue(noKey.message.isNotBlank())
        assertTrue(offline.message.isNotBlank())
    }

    @Test
    fun `连接测试不会发送统计并正确映射密钥状态`() = runTest {
        var receivedBody = ""
        val success = coordinator(
            gateway = DeepSeekGateway { _, body ->
                receivedBody = body
                DeepSeekCallResult.Success("连接成功")
            }
        ).testConnection(candidateKey = "sk-test-key-1234567890")
        val missing = coordinator(
            keyResult = AiApiKeyReadResult.Missing
        ).testConnection(candidateKey = null)

        assertEquals(AiConnectionTestResult.SUCCESS, success)
        assertEquals(AiConnectionTestResult.MISSING_API_KEY, missing)
        assertTrue(receivedBody.contains("只回复"))
        assertTrue(!receivedBody.contains("today_minutes"))
    }

    private fun coordinator(
        keyResult: AiApiKeyReadResult = AiApiKeyReadResult.Available(
            "sk-test-key-1234567890"
        ),
        gateway: DeepSeekGateway = DeepSeekGateway { _, _ ->
            DeepSeekCallResult.Success("连接成功")
        }
    ) = AiInterventionCoordinator(
        companionEnabledReader = { true },
        apiKeyReader = { keyResult },
        deepSeekGateway = gateway
    )

    private fun sampleRequest() = AiInterventionRequest(
        actionKind = LockPendingActionKind.SKIP,
        requestedPauseMinutes = null,
        sessionMode = MonitorSessionMode.SUPERVISION,
        remainingSeconds = 300,
        lockSessionId = 42L
    )

}
