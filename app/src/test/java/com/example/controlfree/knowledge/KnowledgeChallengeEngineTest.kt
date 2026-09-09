package com.example.controlfree.knowledge

import com.example.controlfree.LockPendingActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KnowledgeChallengeEngineTest {
    private val engine = KnowledgeChallengeEngine()
    private val binding = KnowledgeChallengeBinding(8_008L, LockPendingActionKind.SKIP)

    @Test
    fun `第一轮全对也必须进入第二轮`() {
        val first = engine.createSession(binding)
        val answers = answersWithCorrectCount(first, 3)

        val evaluation = engine.submitRound(first, answers)

        assertEquals(3, evaluation.score)
        assertFalse(evaluation.passed)
        assertTrue(evaluation.canTryNextRound)
        assertEquals(KnowledgeChallengeStatus.ACTIVE, evaluation.session.status)
        assertEquals(2, evaluation.session.currentRoundNumber)
        assertEquals(listOf(3), evaluation.session.completedRoundScores)
        assertEquals(3, evaluation.session.totalScore)
        assertReviewMatches(first, answers, evaluation.review)
    }

    @Test
    fun `只有两轮六题全部答对才通关`() {
        val first = engine.createSession(binding)
        val firstAnswers = answersWithCorrectCount(first, 3)
        val firstEvaluation = engine.submitRound(first, firstAnswers)
        val second = firstEvaluation.session
        val secondAnswers = answersWithCorrectCount(second, 3)

        val secondEvaluation = engine.submitRound(second, secondAnswers)

        assertTrue(secondEvaluation.passed)
        assertFalse(secondEvaluation.canTryNextRound)
        assertEquals(KnowledgeChallengeStatus.PASSED, secondEvaluation.session.status)
        assertEquals(listOf(3, 3), secondEvaluation.session.completedRoundScores)
        assertEquals(6, secondEvaluation.session.totalScore)
        assertReviewMatches(first, firstAnswers, firstEvaluation.review)
        assertReviewMatches(second, secondAnswers, secondEvaluation.review)
    }

    @Test
    fun `任意一轮出现错误都会在第二轮结束后失败`() {
        listOf(2 to 3, 3 to 2, 0 to 3).forEachIndexed { index, (firstScore, secondScore) ->
            val scenarioBinding = KnowledgeChallengeBinding(
                lockSessionId = 8_100L + index,
                actionKind = LockPendingActionKind.SKIP
            )
            val first = engine.createSession(scenarioBinding)
            val firstAnswers = answersWithCorrectCount(first, firstScore)
            val firstEvaluation = engine.submitRound(first, firstAnswers)
            val second = firstEvaluation.session
            val secondAnswers = answersWithCorrectCount(second, secondScore)

            val secondEvaluation = engine.submitRound(second, secondAnswers)

            assertTrue(firstEvaluation.canTryNextRound)
            assertEquals(KnowledgeChallengeStatus.FAILED, secondEvaluation.session.status)
            assertFalse(secondEvaluation.passed)
            assertFalse(secondEvaluation.canTryNextRound)
            assertEquals(firstScore + secondScore, secondEvaluation.session.totalScore)
            assertReviewMatches(first, firstAnswers, firstEvaluation.review)
            assertReviewMatches(second, secondAnswers, secondEvaluation.review)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `缺少任意题目答案时拒绝评分`() {
        val session = engine.createSession(binding)

        engine.submitRound(
            session,
            mapOf(session.questions.first().id to session.questions.first().options.first().id)
        )
    }

    @Test
    fun `两轮事实互不重复并且模型增强不参与正确答案判定`() {
        val original = engine.createSession(binding)
        val firstFact = OfflineKnowledgeBank.get(original.questions.first().factId)!!
        val enhancement = KnowledgeQuestionEnhancement(
            factId = firstFact.id,
            stem = "经过润色后，这道题所询问的正确对象是哪一个？",
            distractors = listOf("虚构干扰甲", "虚构干扰乙", "虚构干扰丙"),
            explanation = "这段说明只改善展示，答案仍由本地事实库提供。"
        )
        val enhanced = engine.createSession(binding, mapOf(firstFact.id to enhancement))
        val firstRoundIds = enhanced.questions.map(KnowledgeQuestion::factId).toSet()
        val next = engine.submitRound(enhanced, answersWithCorrectCount(enhanced, 0)).session

        assertEquals(KnowledgeQuestionSource.DEEPSEEK, enhanced.questions.first().source)
        assertTrue(enhanced.questions.first().options.any { it.text == firstFact.canonicalAnswer })
        assertTrue(firstRoundIds.intersect(next.questions.map(KnowledgeQuestion::factId).toSet()).isEmpty())
    }

    @Test
    fun `仅一个分类仍可用六题完成挑战`() {
        val facts = OfflineKnowledgeBank.facts
            .filter { it.category == KnowledgeCategory.NATURAL_SCIENCE }
        val session = KnowledgeChallengeEngine(facts).createSession(binding)

        assertEquals(3, session.questions.size)
        assertTrue(session.questions.all { it.category == KnowledgeCategory.NATURAL_SCIENCE })
        val second = KnowledgeChallengeEngine(facts)
            .submitRound(session, answersWithCorrectCount(session, 0)).session
        assertTrue(
            session.questions.map(KnowledgeQuestion::factId).toSet()
                .intersect(second.questions.map(KnowledgeQuestion::factId).toSet())
                .isEmpty()
        )
    }

    @Test
    fun `不足六题时返回明确的题库不足异常`() {
        try {
            KnowledgeChallengeEngine(OfflineKnowledgeBank.facts.take(5))
            fail("应拒绝不足六题的挑战题库")
        } catch (error: InsufficientKnowledgeQuestionsException) {
            assertEquals(5, error.availableCount)
            assertEquals(6, error.requiredCount)
        }
    }

    private fun answersWithCorrectCount(
        session: KnowledgeChallengeSession,
        correctCount: Int
    ): Map<String, String> = session.questions.mapIndexed { index, question ->
        val option = if (index < correctCount) {
            question.options.first { question.isCorrect(it.id) }
        } else {
            question.options.first { !question.isCorrect(it.id) }
        }
        question.id to option.id
    }.toMap()

    private fun assertReviewMatches(
        submittedSession: KnowledgeChallengeSession,
        selectedOptions: Map<String, String>,
        review: KnowledgeRoundReview
    ) {
        assertEquals(submittedSession.currentRoundNumber, review.roundNumber)
        assertEquals(KnowledgeChallengeSession.QUESTIONS_PER_ROUND, review.answers.size)
        assertEquals(submittedSession.questions.map { it.id }, review.answers.map { it.questionId })
        submittedSession.questions.zip(review.answers).forEach { (question, answerReview) ->
            val selected = question.options.single { it.id == selectedOptions.getValue(question.id) }
            val correct = question.options.single { question.isCorrect(it.id) }
            assertEquals(question.category, answerReview.category)
            assertEquals(question.stem, answerReview.stem)
            assertEquals(selected.id, answerReview.selectedOptionId)
            assertEquals(selected.text, answerReview.selectedOptionText)
            assertEquals(correct.id, answerReview.correctOptionId)
            assertEquals(correct.text, answerReview.correctOptionText)
            assertEquals(question.isCorrect(selected.id), answerReview.isCorrect)
            assertEquals(question.explanation, answerReview.explanation)
        }
        assertEquals(
            selectedOptions.values.count { selectedId ->
                submittedSession.questions.any { question -> question.isCorrect(selectedId) }
            },
            review.score
        )
    }
}
