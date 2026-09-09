package com.example.controlfree.knowledge

import com.example.controlfree.ai.DeepSeekCallResult
import com.example.controlfree.ai.DeepSeekGateway
import com.example.controlfree.security.AiApiKeyReadResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekKnowledgeQuestionEnhancerTest {
    private val fact = OfflineKnowledgeBank.facts.first()

    @Test
    fun `请求启用严格JSON模式且不携带锁定会话信息`() {
        val request = KnowledgeDeepSeekPrompt.buildRequest(listOf(fact))

        assertTrue(request.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(request.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(request.contains("\\\"fact_id\\\":\\\"${fact.id}\\\""))
        assertTrue(request.contains("\\\"canonical_answer\\\""))
        assertFalse(request.contains("lock_session"))
        assertFalse(request.contains("correct_option_id"))
    }

    @Test
    fun `有效模型结果只增强文案且正确答案仍由本地加入`() = runTest {
        val engine = KnowledgeChallengeEngine()
        val selectedFact = engine.selectedFacts(KnowledgeChallengeBindingForTest.binding).first()
        val enhancer = enhancerReturning(validResponse(selectedFact))

        val enhancements = enhancer.enhance(listOf(selectedFact))
        val session = engine.createSession(KnowledgeChallengeBindingForTest.binding, enhancements)
        val secondRoundQuestions = engine
            .submitRound(session, wrongAnswers(session))
            .session
            .questions
        val enhancedQuestion = (session.questions + secondRoundQuestions)
            .firstOrNull { it.factId == selectedFact.id }

        assertEquals(setOf(selectedFact.id), enhancements.keys)
        assertTrue(enhancedQuestion != null)
        assertEquals(KnowledgeQuestionSource.DEEPSEEK, enhancedQuestion!!.source)
        assertTrue(enhancedQuestion.options.any { it.text == selectedFact.canonicalAnswer })
    }

    @Test
    fun `空响应错误结构与答案等价干扰项全部回退本地`() = runTest {
        val malformed = "{\"schema_version\":1,\"locale\":\"zh-CN\",\"items\":[]}"
        val equivalentDistractor = validResponse(
            fact,
            distractors = listOf(fact.canonicalAnswer, "错误乙", "错误丙")
        )

        assertTrue(enhancerReturning("").enhance(listOf(fact)).isEmpty())
        assertTrue(enhancerReturning(malformed).enhance(listOf(fact)).isEmpty())
        assertTrue(enhancerReturning(equivalentDistractor).enhance(listOf(fact)).isEmpty())
    }

    @Test
    fun `无密钥网络错误和超时都立即返回离线结果`() = runTest {
        var noKeyCalls = 0
        val noKey = DeepSeekKnowledgeQuestionEnhancer(
            apiKeyReader = { AiApiKeyReadResult.Missing },
            gateway = DeepSeekGateway { _, _ ->
                noKeyCalls++
                DeepSeekCallResult.Success(validResponse(fact))
            }
        )
        val networkFailure = DeepSeekKnowledgeQuestionEnhancer(
            apiKeyReader = { availableKey() },
            gateway = DeepSeekGateway { _, _ -> DeepSeekCallResult.NetworkUnavailable }
        )
        val timeout = DeepSeekKnowledgeQuestionEnhancer(
            apiKeyReader = { availableKey() },
            gateway = DeepSeekGateway { _, _ ->
                delay(100)
                DeepSeekCallResult.Success(validResponse(fact))
            },
            timeoutMillis = 10
        )

        assertTrue(noKey.enhance(listOf(fact)).isEmpty())
        assertEquals(0, noKeyCalls)
        assertTrue(networkFailure.enhance(listOf(fact)).isEmpty())
        assertTrue(timeout.enhance(listOf(fact)).isEmpty())
    }

    @Test
    fun `重复JSON字段和额外字段被严格拒绝`() {
        val duplicate = validResponse(fact).replace(
            "\"schema_version\":1",
            "\"schema_version\":1,\"schema_version\":1"
        )
        val extra = validResponse(fact).replace(
            "\"explanation\":\"只重写本地解释，不改变本地正确答案。\"",
            "\"explanation\":\"只重写本地解释，不改变本地正确答案。\",\"answer\":\"伪造答案\""
        )

        assertTrue(KnowledgeDeepSeekResponseParser.parse(duplicate, listOf(fact)).isEmpty())
        assertTrue(KnowledgeDeepSeekResponseParser.parse(extra, listOf(fact)).isEmpty())
    }

    private fun enhancerReturning(response: String) = DeepSeekKnowledgeQuestionEnhancer(
        apiKeyReader = { availableKey() },
        gateway = DeepSeekGateway { _, _ -> DeepSeekCallResult.Success(response) }
    )

    private fun validResponse(
        fact: KnowledgeFact,
        distractors: List<String> = listOf("错误选项甲", "错误选项乙", "错误选项丙")
    ): String = """
        {
          "schema_version":1,
          "locale":"zh-CN",
          "items":[{
            "fact_id":"${fact.id}",
            "category":"${fact.category.wireValue}",
            "difficulty":${fact.difficulty.level},
            "stem":"经过润色后，这道题询问的对象是哪一个？",
            "distractors":["${distractors[0]}","${distractors[1]}","${distractors[2]}"],
            "explanation":"只重写本地解释，不改变本地正确答案。"
          }]
        }
    """.trimIndent()

    private fun availableKey() = AiApiKeyReadResult.Available("sk-test-key-1234567890")

    private fun wrongAnswers(session: KnowledgeChallengeSession): Map<String, String> =
        session.questions.associate { question ->
            question.id to question.options.first { !question.isCorrect(it.id) }.id
        }

    private object KnowledgeChallengeBindingForTest {
        val binding = KnowledgeChallengeBinding(
            lockSessionId = 66L,
            actionKind = com.example.controlfree.LockPendingActionKind.SKIP
        )
    }
}
