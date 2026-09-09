package com.example.controlfree.knowledge

import com.example.controlfree.LockPendingActionKind
import java.text.Normalizer
import java.util.Locale

internal enum class KnowledgeCategory(
    val wireValue: String,
    val displayName: String
) {
    NATURAL_SCIENCE("natural_science", "自然科学"),
    HISTORY_CULTURE("history_culture", "历史文化"),
    GEOGRAPHY("geography", "地理"),
    LITERATURE_ART("literature_art", "文学艺术"),
    LIFE_KNOWLEDGE("life_knowledge", "生活常识"),
    TECHNOLOGY("technology", "科技")
}

internal enum class KnowledgeDifficulty(val level: Int) {
    EASY(1),
    STANDARD(2),
    CHALLENGING(3)
}

/**
 * 本地审核过的事实。正确答案只来自这里，云端永远没有修改答案的权限。
 */
internal data class KnowledgeFact(
    val id: String,
    val category: KnowledgeCategory,
    val difficulty: KnowledgeDifficulty,
    val localStem: String,
    val canonicalAnswer: String,
    val acceptedAliases: Set<String> = emptySet(),
    val localDistractors: List<String>,
    val localExplanation: String
) {
    init {
        KnowledgeContentPolicy.requireValidFact(this)
    }

    val allAcceptedAnswers: Set<String>
        get() = acceptedAliases + canonicalAnswer
}

internal enum class KnowledgeQuestionSource {
    LOCAL,
    DEEPSEEK
}

internal data class KnowledgeQuestionEnhancement(
    val factId: String,
    val stem: String,
    val distractors: List<String>,
    val explanation: String
)

internal data class KnowledgeOption(
    val id: String,
    val text: String
)

internal data class KnowledgeAnswerReview(
    val questionId: String,
    val category: KnowledgeCategory,
    val stem: String,
    val selectedOptionId: String,
    val selectedOptionText: String,
    val correctOptionId: String,
    val correctOptionText: String,
    val isCorrect: Boolean,
    val explanation: String
)

internal data class KnowledgeRoundReview(
    val roundNumber: Int,
    val answers: List<KnowledgeAnswerReview>
) {
    init {
        require(roundNumber in 1..KnowledgeChallengeSession.MAX_ROUNDS)
        require(answers.size == KnowledgeChallengeSession.QUESTIONS_PER_ROUND)
        require(answers.map(KnowledgeAnswerReview::questionId).toSet().size == answers.size)
    }

    val score: Int
        get() = answers.count(KnowledgeAnswerReview::isCorrect)
}

/**
 * 面向 UI 的四选一题。评分只比较不透明 option id，不接受文本或模型返回的判断。
 */
internal class KnowledgeQuestion internal constructor(
    val id: String,
    val factId: String,
    val category: KnowledgeCategory,
    val difficulty: KnowledgeDifficulty,
    val stem: String,
    val options: List<KnowledgeOption>,
    val explanation: String,
    val source: KnowledgeQuestionSource,
    private val correctOptionId: String
) {
    init {
        require(options.size == OPTION_COUNT) { "百科题必须恰好包含四个选项" }
        require(options.map(KnowledgeOption::id).toSet().size == OPTION_COUNT) {
            "百科题选项 ID 必须唯一"
        }
        require(options.any { it.id == correctOptionId }) { "正确选项必须存在于选项列表" }
    }

    internal fun isCorrect(selectedOptionId: String?): Boolean =
        selectedOptionId != null && selectedOptionId == correctOptionId

    internal fun review(selectedOptionId: String): KnowledgeAnswerReview? {
        val selectedOption = options.firstOrNull { it.id == selectedOptionId } ?: return null
        val correctOption = options.first { it.id == correctOptionId }
        return KnowledgeAnswerReview(
            questionId = id,
            category = category,
            stem = stem,
            selectedOptionId = selectedOption.id,
            selectedOptionText = selectedOption.text,
            correctOptionId = correctOption.id,
            correctOptionText = correctOption.text,
            isCorrect = selectedOption.id == correctOption.id,
            explanation = explanation
        )
    }

    internal companion object {
        const val OPTION_COUNT = 4
    }
}

internal data class KnowledgeChallengeBinding(
    val lockSessionId: Long,
    val actionKind: LockPendingActionKind
) {
    init {
        require(lockSessionId > 0L) { "锁定会话 ID 必须为正数" }
    }
}

internal enum class KnowledgeChallengeStatus {
    ACTIVE,
    PASSED,
    FAILED
}

/**
 * 不可变挑战状态。rounds 对调用方隐藏，避免 UI 自行改题或决定是否通关。
 */
internal class KnowledgeChallengeSession internal constructor(
    val challengeId: String,
    val binding: KnowledgeChallengeBinding,
    private val rounds: List<List<KnowledgeQuestion>>,
    val currentRoundNumber: Int,
    val status: KnowledgeChallengeStatus,
    completedRoundScores: List<Int> = emptyList()
) {
    val completedRoundScores: List<Int> = completedRoundScores.toList()

    init {
        require(rounds.size == MAX_ROUNDS)
        require(rounds.all { it.size == QUESTIONS_PER_ROUND })
        require(currentRoundNumber in 1..MAX_ROUNDS)
        require(this.completedRoundScores.all { it in 0..QUESTIONS_PER_ROUND })
        when (status) {
            KnowledgeChallengeStatus.ACTIVE -> {
                require(this.completedRoundScores.size == currentRoundNumber - 1)
            }
            KnowledgeChallengeStatus.PASSED,
            KnowledgeChallengeStatus.FAILED -> {
                require(currentRoundNumber == MAX_ROUNDS)
                require(this.completedRoundScores.size == MAX_ROUNDS)
            }
        }
    }

    val questions: List<KnowledgeQuestion>
        get() = rounds[currentRoundNumber - 1]

    val lastScore: Int?
        get() = completedRoundScores.lastOrNull()

    val totalScore: Int
        get() = completedRoundScores.sum()

    internal fun withRoundResult(score: Int): KnowledgeChallengeSession {
        require(status == KnowledgeChallengeStatus.ACTIVE)
        require(score in 0..QUESTIONS_PER_ROUND)
        val scores = completedRoundScores + score
        val isLastRound = currentRoundNumber == MAX_ROUNDS
        val nextStatus = if (!isLastRound) {
            KnowledgeChallengeStatus.ACTIVE
        } else if (scores.sum() == REQUIRED_TOTAL_SCORE) {
            KnowledgeChallengeStatus.PASSED
        } else {
            KnowledgeChallengeStatus.FAILED
        }
        return KnowledgeChallengeSession(
            challengeId = challengeId,
            binding = binding,
            rounds = rounds,
            currentRoundNumber = if (isLastRound) currentRoundNumber else currentRoundNumber + 1,
            status = nextStatus,
            completedRoundScores = scores
        )
    }

    internal companion object {
        const val QUESTIONS_PER_ROUND = 3
        const val MAX_ROUNDS = 2
        const val REQUIRED_TOTAL_SCORE = QUESTIONS_PER_ROUND * MAX_ROUNDS
    }
}

internal object KnowledgeContentPolicy {
    private val factIdPattern = Regex("[a-z][a-z0-9_]{2,47}")
    private val whitespace = Regex("\\s+")
    private val linkOrMarkdown = Regex("(?i)(https?://|```|\\[[^]]*]\\()")

    fun requireValidFact(fact: KnowledgeFact) {
        require(factIdPattern.matches(fact.id)) { "事实 ID 格式非法: ${fact.id}" }
        require(isSafeDisplayText(fact.localStem, 6, 80)) { "事实题干非法: ${fact.id}" }
        require(isSafeDisplayText(fact.canonicalAnswer, 1, 32)) { "事实答案非法: ${fact.id}" }
        require(isSafeDisplayText(fact.localExplanation, 6, 120)) { "事实解释非法: ${fact.id}" }
        require(fact.localDistractors.size == 3) { "本地事实必须有三个干扰项: ${fact.id}" }
        require(fact.localDistractors.all { isSafeDisplayText(it, 1, 32) }) {
            "事实干扰项非法: ${fact.id}"
        }
        require(fact.acceptedAliases.all { isSafeDisplayText(it, 1, 32) }) {
            "答案别名非法: ${fact.id}"
        }
        val accepted = fact.allAcceptedAnswers.map(::comparisonKey).toSet()
        require(accepted.isNotEmpty()) { "事实答案不能为空: ${fact.id}" }
        val distractors = fact.localDistractors.map(::comparisonKey)
        require(distractors.toSet().size == 3) { "事实干扰项重复: ${fact.id}" }
        require(distractors.none(accepted::contains)) { "干扰项不得等同正确答案: ${fact.id}" }
    }

    fun validateEnhancement(
        fact: KnowledgeFact,
        candidate: KnowledgeQuestionEnhancement
    ): KnowledgeQuestionEnhancement? {
        if (candidate.factId != fact.id) return null
        if (!isSafeDisplayText(candidate.stem, 6, 56)) return null
        if (!isSafeDisplayText(candidate.explanation, 6, 80)) return null
        if (candidate.distractors.size != 3) return null
        if (candidate.distractors.any { !isSafeDisplayText(it, 1, 24) }) return null

        val accepted = fact.allAcceptedAnswers.map(::comparisonKey).toSet()
        val candidateDistractors = candidate.distractors.map(::comparisonKey)
        if (candidateDistractors.toSet().size != 3) return null
        if (candidateDistractors.any(accepted::contains)) return null

        // 至少两个字符的答案或别名不得直接泄漏在题干中。
        val normalizedStem = comparisonKey(candidate.stem)
        if (accepted.any { it.length >= 2 && normalizedStem.contains(it) }) return null
        return candidate.copy(
            stem = sanitizeDisplayText(candidate.stem),
            distractors = candidate.distractors.map(::sanitizeDisplayText),
            explanation = sanitizeDisplayText(candidate.explanation)
        )
    }

    fun comparisonKey(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(whitespace, "")
        .trim()

    fun sanitizeDisplayText(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .filterNot { it.code < 0x20 || it.code == 0x7f }
        .replace(whitespace, " ")
        .trim()

    private fun isSafeDisplayText(value: String, minCodePoints: Int, maxCodePoints: Int): Boolean {
        if (value != value.trim()) return false
        if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
        val safe = sanitizeDisplayText(value)
        if (linkOrMarkdown.containsMatchIn(safe)) return false
        val codePoints = safe.codePointCount(0, safe.length)
        return codePoints in minCodePoints..maxCodePoints
    }
}
