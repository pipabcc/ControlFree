package com.example.controlfree.knowledge

import java.security.MessageDigest
import kotlin.random.Random

internal data class KnowledgeRoundEvaluation(
    val session: KnowledgeChallengeSession,
    val review: KnowledgeRoundReview
) {
    val score: Int
        get() = review.score

    val passed: Boolean
        get() = session.status == KnowledgeChallengeStatus.PASSED

    val canTryNextRound: Boolean
        get() = session.status == KnowledgeChallengeStatus.ACTIVE
}

internal class InsufficientKnowledgeQuestionsException(
    val availableCount: Int,
    val requiredCount: Int = KnowledgeChallengeSession.QUESTIONS_PER_ROUND *
        KnowledgeChallengeSession.MAX_ROUNDS
) : IllegalStateException("可用百科题不足：至少需要 $requiredCount 题，当前仅 $availableCount 题")

/**
 * 确定性的本地出题与评分引擎。
 *
 * 相同锁定会话与动作会得到相同 challengeId、题目和选项顺序；模型增强只影响展示
 * 文案和干扰项，正确选项始终由 [KnowledgeFact.canonicalAnswer] 生成。
 */
internal class KnowledgeChallengeEngine(
    private val factBank: List<KnowledgeFact> = OfflineKnowledgeBank.facts
) {
    init {
        require(factBank.map(KnowledgeFact::id).toSet().size == factBank.size)
        if (factBank.size < MIN_CHALLENGE_FACTS) {
            throw InsufficientKnowledgeQuestionsException(factBank.size)
        }
    }

    fun challengeIdFor(binding: KnowledgeChallengeBinding): String {
        val digest = sha256(
            "$CHALLENGE_SCHEMA|${binding.lockSessionId}|${binding.actionKind.wireValue}"
        )
        return "kc$CHALLENGE_SCHEMA-${digest.toHex().take(CHALLENGE_ID_HASH_CHARS)}"
    }

    /** 返回两轮共六条互不重复的事实，供云端文案预取使用。 */
    fun selectedFacts(binding: KnowledgeChallengeBinding): List<KnowledgeFact> =
        selectRoundFacts(challengeIdFor(binding)).flatten()

    fun createSession(
        binding: KnowledgeChallengeBinding,
        enhancements: Map<String, KnowledgeQuestionEnhancement> = emptyMap()
    ): KnowledgeChallengeSession {
        val challengeId = challengeIdFor(binding)
        val rounds = selectRoundFacts(challengeId).mapIndexed { roundIndex, facts ->
            facts.map { fact ->
                createQuestion(
                    challengeId = challengeId,
                    roundNumber = roundIndex + 1,
                    fact = fact,
                    enhancement = enhancements[fact.id]
                )
            }
        }
        return KnowledgeChallengeSession(
            challengeId = challengeId,
            binding = binding,
            rounds = rounds,
            currentRoundNumber = 1,
            status = KnowledgeChallengeStatus.ACTIVE
        )
    }

    fun submitRound(
        session: KnowledgeChallengeSession,
        selectedOptions: Map<String, String>
    ): KnowledgeRoundEvaluation {
        require(session.status == KnowledgeChallengeStatus.ACTIVE) { "挑战已经结束" }
        val expectedQuestionIds = session.questions.map(KnowledgeQuestion::id).toSet()
        require(selectedOptions.keys == expectedQuestionIds) { "必须完整回答本轮三道题" }
        val review = KnowledgeRoundReview(
            roundNumber = session.currentRoundNumber,
            answers = session.questions.map { question ->
                val selectedOptionId = requireNotNull(selectedOptions[question.id])
                requireNotNull(question.review(selectedOptionId)) { "提交的选项不属于当前题目" }
            }
        )
        return KnowledgeRoundEvaluation(
            session = session.withRoundResult(review.score),
            review = review
        )
    }

    private fun selectRoundFacts(challengeId: String): List<List<KnowledgeFact>> {
        val usedFactIds = mutableSetOf<String>()
        return ROUND_DIFFICULTIES.mapIndexed { roundIndex, difficulties ->
            val preferredCategories = deterministicShuffle(
                KnowledgeCategory.entries,
                seedFor(challengeId, "categories:${roundIndex + 1}")
            ).filter { category ->
                factBank.any { it.category == category && it.id !in usedFactIds }
            }
            val selected = mutableListOf<KnowledgeFact>()
            preferredCategories.forEach { category ->
                if (selected.size >= KnowledgeChallengeSession.QUESTIONS_PER_ROUND) return@forEach
                val questionIndex = selected.size
                val preferredDifficulty = difficulties[questionIndex]
                val exactCandidates = factBank.filter { fact ->
                    fact.category == category &&
                        fact.difficulty == preferredDifficulty &&
                        fact.id !in usedFactIds
                }
                val candidates = exactCandidates.ifEmpty {
                    factBank.filter { fact ->
                        fact.category == category && fact.id !in usedFactIds
                    }
                }
                val shuffled = deterministicShuffle(
                    candidates,
                    seedFor(challengeId, "fact:${roundIndex + 1}:$questionIndex")
                )
                shuffled.firstOrNull()?.also { fact ->
                    selected += fact
                    usedFactIds += fact.id
                }
            }
            if (selected.size < KnowledgeChallengeSession.QUESTIONS_PER_ROUND) {
                val remaining = deterministicShuffle(
                    factBank.filterNot { it.id in usedFactIds },
                    seedFor(challengeId, "fallback:${roundIndex + 1}")
                )
                remaining.take(KnowledgeChallengeSession.QUESTIONS_PER_ROUND - selected.size)
                    .forEach { fact ->
                        selected += fact
                        usedFactIds += fact.id
                    }
            }
            check(selected.size == KnowledgeChallengeSession.QUESTIONS_PER_ROUND)
            selected
        }
    }

    private fun createQuestion(
        challengeId: String,
        roundNumber: Int,
        fact: KnowledgeFact,
        enhancement: KnowledgeQuestionEnhancement?
    ): KnowledgeQuestion {
        val acceptedEnhancement = enhancement?.let { candidate ->
            KnowledgeContentPolicy.validateEnhancement(fact, candidate)
        }
        val stem = acceptedEnhancement?.stem ?: fact.localStem
        val distractors = acceptedEnhancement?.distractors ?: fact.localDistractors
        val explanation = acceptedEnhancement?.explanation ?: fact.localExplanation
        val source = if (acceptedEnhancement == null) {
            KnowledgeQuestionSource.LOCAL
        } else {
            KnowledgeQuestionSource.DEEPSEEK
        }

        val answerTexts = listOf(fact.canonicalAnswer) + distractors
        val options = answerTexts.mapIndexed { originalIndex, text ->
            KnowledgeOption(
                id = optionId(challengeId, fact.id, originalIndex, text),
                text = text
            )
        }
        val correctOptionId = options.first().id
        val shuffledOptions = deterministicShuffle(
            options,
            seedFor(challengeId, "options:${fact.id}")
        )
        return KnowledgeQuestion(
            id = "${challengeId}-r$roundNumber-${fact.id}",
            factId = fact.id,
            category = fact.category,
            difficulty = fact.difficulty,
            stem = stem,
            options = shuffledOptions,
            explanation = explanation,
            source = source,
            correctOptionId = correctOptionId
        )
    }

    private fun optionId(
        challengeId: String,
        factId: String,
        originalIndex: Int,
        text: String
    ): String = sha256(
        "$challengeId|$factId|$originalIndex|${KnowledgeContentPolicy.comparisonKey(text)}"
    ).toHex().take(OPTION_ID_HASH_CHARS)

    private fun seedFor(challengeId: String, purpose: String): Long {
        val bytes = sha256("$challengeId|$purpose")
        var seed = 0L
        repeat(Long.SIZE_BYTES) { index ->
            seed = (seed shl 8) or (bytes[index].toLong() and 0xffL)
        }
        return seed
    }

    private fun <T> deterministicShuffle(values: List<T>, seed: Long): List<T> =
        values.shuffled(Random(seed))

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val MIN_CHALLENGE_FACTS = KnowledgeChallengeSession.QUESTIONS_PER_ROUND *
            KnowledgeChallengeSession.MAX_ROUNDS
        const val CHALLENGE_SCHEMA = 1
        const val CHALLENGE_ID_HASH_CHARS = 24
        const val OPTION_ID_HASH_CHARS = 16

        val ROUND_DIFFICULTIES = listOf(
            listOf(
                KnowledgeDifficulty.EASY,
                KnowledgeDifficulty.STANDARD,
                KnowledgeDifficulty.STANDARD
            ),
            listOf(
                KnowledgeDifficulty.STANDARD,
                KnowledgeDifficulty.CHALLENGING,
                KnowledgeDifficulty.CHALLENGING
            )
        )
    }
}
