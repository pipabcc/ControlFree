package com.example.controlfree.knowledge

import com.example.controlfree.ai.DeepSeekCallResult
import com.example.controlfree.ai.DeepSeekClient
import com.example.controlfree.ai.DeepSeekGateway
import com.example.controlfree.security.AiApiKeyReadResult
import com.example.controlfree.security.AiApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface KnowledgeQuestionEnhancer {
    /** 返回经过本地校验的增强文案；空映射表示整批回退离线题。 */
    suspend fun enhance(facts: List<KnowledgeFact>): Map<String, KnowledgeQuestionEnhancement>
}

/**
 * DeepSeek 只改写题干、生成干扰项与解释。正确答案不在响应协议中，评分仍由本地事实库完成。
 */
internal class DeepSeekKnowledgeQuestionEnhancer internal constructor(
    private val apiKeyReader: suspend () -> AiApiKeyReadResult,
    private val gateway: DeepSeekGateway,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS
) : KnowledgeQuestionEnhancer {
    internal constructor(
        apiKeyStore: AiApiKeyStore,
        gateway: DeepSeekGateway = DeepSeekClient.getInstance()
    ) : this(
        apiKeyReader = { withContext(Dispatchers.IO) { apiKeyStore.read() } },
        gateway = gateway
    )

    override suspend fun enhance(
        facts: List<KnowledgeFact>
    ): Map<String, KnowledgeQuestionEnhancement> {
        if (facts.isEmpty() || facts.size > MAX_BATCH_FACTS) return emptyMap()
        val apiKey = try {
            when (val result = apiKeyReader()) {
                is AiApiKeyReadResult.Available -> result.apiKey
                AiApiKeyReadResult.Missing,
                AiApiKeyReadResult.StorageUnavailable -> return emptyMap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyMap()
        }

        val requestBody = KnowledgeDeepSeekPrompt.buildRequest(facts)
        val callResult = try {
            withTimeoutOrNull(timeoutMillis) {
                gateway.complete(apiKey, requestBody)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val rawJson = (callResult as? DeepSeekCallResult.Success)?.message
            ?: return emptyMap()
        return KnowledgeDeepSeekResponseParser.parse(rawJson, facts)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 5_000L
        const val MAX_BATCH_FACTS = KnowledgeChallengeSession.QUESTIONS_PER_ROUND *
            KnowledgeChallengeSession.MAX_ROUNDS
    }
}

internal object KnowledgeDeepSeekPrompt {
    const val SCHEMA_VERSION = 1

    private val systemPrompt = """
        你是百科题编辑器。你会收到经过人工审核的事实、正确答案和解释。
        你只能润色题干、为每题生成恰好三个错误但可信的干扰项，并压缩已有解释；不得改变、补充或裁定正确答案，不得加入时效性、政治、医疗、法律、投资或主观内容。
        必须只输出一个 JSON 对象，不要 Markdown 或额外文字。根对象只能含 schema_version、locale、items。每个 item 只能含 fact_id、category、difficulty、stem、distractors、explanation。
        fact_id、category、difficulty 必须原样返回；locale 必须为 zh-CN。stem 最多 56 个字符且不能直接出现答案；distractors 必须恰好三个、互不等同且每项最多 24 个字符；explanation 最多 80 个字符，只能改写输入解释。
        JSON 示例：{"schema_version":1,"locale":"zh-CN","items":[{"fact_id":"sample_id","category":"technology","difficulty":1,"stem":"示例问题？","distractors":["错误项甲","错误项乙","错误项丙"],"explanation":"基于给定事实的简短解释。"}]}
    """.trimIndent()

    fun buildRequest(facts: List<KnowledgeFact>): String = buildString(4_096) {
        append('{')
        appendJsonString("model", DeepSeekClient.MODEL_NAME)
        append(",\"messages\":[{")
        appendJsonString("role", "system")
        append(',')
        appendJsonString("content", systemPrompt)
        append("},{")
        appendJsonString("role", "user")
        append(',')
        appendJsonString("content", buildFactPayload(facts))
        append("}],\"stream\":false,\"temperature\":0.2,")
        appendJsonNumber("max_tokens", MAX_TOKENS)
        append(",\"thinking\":{\"type\":\"disabled\"}")
        append(",\"response_format\":{\"type\":\"json_object\"}")
        append('}')
    }

    private fun buildFactPayload(facts: List<KnowledgeFact>): String = buildString(3_000) {
        append('{')
        appendJsonNumber("schema_version", SCHEMA_VERSION)
        append(",\"locale\":\"zh-CN\",\"facts\":[")
        facts.forEachIndexed { index, fact ->
            if (index > 0) append(',')
            append('{')
            appendJsonString("fact_id", fact.id)
            append(',')
            appendJsonString("category", fact.category.wireValue)
            append(',')
            appendJsonNumber("difficulty", fact.difficulty.level)
            append(',')
            appendJsonString("local_stem", fact.localStem)
            append(',')
            appendJsonString("canonical_answer", fact.canonicalAnswer)
            append(",\"accepted_aliases\":[")
            fact.acceptedAliases.sorted().forEachIndexed { aliasIndex, alias ->
                if (aliasIndex > 0) append(',')
                appendJsonEscaped(alias)
            }
            append("],")
            appendJsonString("local_explanation", fact.localExplanation)
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendJsonString(name: String, value: String) {
        appendJsonEscaped(name)
        append(':')
        appendJsonEscaped(value)
    }

    private fun StringBuilder.appendJsonNumber(name: String, value: Int) {
        appendJsonEscaped(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.appendJsonEscaped(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private const val MAX_TOKENS = 1_100
}

internal object KnowledgeDeepSeekResponseParser {
    private val rootKeys = setOf("schema_version", "locale", "items")
    private val itemKeys = setOf(
        "fact_id",
        "category",
        "difficulty",
        "stem",
        "distractors",
        "explanation"
    )

    fun parse(
        rawJson: String,
        expectedFacts: List<KnowledgeFact>
    ): Map<String, KnowledgeQuestionEnhancement> {
        val root = StrictKnowledgeJson.parseObject(rawJson) ?: return emptyMap()
        if (root.keys != rootKeys) return emptyMap()
        if (root.strictInt("schema_version") != KnowledgeDeepSeekPrompt.SCHEMA_VERSION) {
            return emptyMap()
        }
        if (root.strictString("locale") != "zh-CN") return emptyMap()
        val items = root.strictArray("items") ?: return emptyMap()
        if (items.size != expectedFacts.size) return emptyMap()

        val expectedById = expectedFacts.associateBy(KnowledgeFact::id)
        if (expectedById.size != expectedFacts.size) return emptyMap()
        val parsed = linkedMapOf<String, KnowledgeQuestionEnhancement>()
        for (value in items) {
            val item = value.strictObject() ?: return emptyMap()
            if (item.keys != itemKeys) return emptyMap()
            val factId = item.strictString("fact_id") ?: return emptyMap()
            val fact = expectedById[factId] ?: return emptyMap()
            if (factId in parsed) return emptyMap()
            if (item.strictString("category") != fact.category.wireValue) return emptyMap()
            if (item.strictInt("difficulty") != fact.difficulty.level) return emptyMap()
            val distractorValues = item.strictArray("distractors") ?: return emptyMap()
            val distractors = distractorValues.map { distractor ->
                (distractor as? StrictKnowledgeJson.Value.StringValue)?.value
                    ?: return emptyMap()
            }
            val candidate = KnowledgeQuestionEnhancement(
                factId = factId,
                stem = item.strictString("stem") ?: return emptyMap(),
                distractors = distractors,
                explanation = item.strictString("explanation") ?: return emptyMap()
            )
            val validated = KnowledgeContentPolicy.validateEnhancement(fact, candidate)
                ?: return emptyMap()
            parsed[factId] = validated
        }
        return parsed.takeIf { it.keys == expectedById.keys } ?: emptyMap()
    }
}

/**
 * 统一准备六道题。云端不可用、超时或任一字段校验失败时，整轮安全回退本地题库。
 */
internal class KnowledgeQuestionPreparer(
    private val engine: KnowledgeChallengeEngine,
    private val enhancer: KnowledgeQuestionEnhancer?
) {
    suspend fun prepare(
        binding: KnowledgeChallengeBinding
    ): KnowledgeChallengeSession {
        return engine.createSession(binding)
    }
}
