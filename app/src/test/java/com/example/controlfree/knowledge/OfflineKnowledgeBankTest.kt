package com.example.controlfree.knowledge

import com.example.controlfree.LockPendingActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineKnowledgeBankTest {
    @Test
    fun `离线题库覆盖六个领域且每类难度完整`() {
        val facts = OfflineKnowledgeBank.facts

        assertEquals(KnowledgeCategory.entries.toSet(), facts.map { it.category }.toSet())
        KnowledgeCategory.entries.forEach { category ->
            val categoryFacts = facts.filter { it.category == category }
            assertTrue(categoryFacts.size >= 6)
            assertEquals(
                KnowledgeDifficulty.entries.toSet(),
                categoryFacts.map { it.difficulty }.toSet()
            )
        }
        assertEquals(facts.size, facts.map { it.id }.toSet().size)
    }

    @Test
    fun `本地事实的正确答案与三个干扰项互不等同`() {
        OfflineKnowledgeBank.facts.forEach { fact ->
            val accepted = fact.allAcceptedAnswers
                .map(KnowledgeContentPolicy::comparisonKey)
                .toSet()
            val distractors = fact.localDistractors
                .map(KnowledgeContentPolicy::comparisonKey)

            assertEquals(3, distractors.toSet().size)
            assertFalse(distractors.any(accepted::contains))
        }
    }

    @Test
    fun `相同锁定会话的题目和选项顺序稳定且不同动作会分离`() {
        val engine = KnowledgeChallengeEngine()
        val skipBinding = KnowledgeChallengeBinding(42L, LockPendingActionKind.SKIP)

        val first = engine.createSession(skipBinding)
        val recreated = engine.createSession(skipBinding)
        val pause = engine.createSession(
            KnowledgeChallengeBinding(42L, LockPendingActionKind.PAUSE)
        )

        assertEquals(first.challengeId, recreated.challengeId)
        assertEquals(
            first.questions.map { it.factId to it.options },
            recreated.questions.map { it.factId to it.options }
        )
        assertNotEquals(first.challengeId, pause.challengeId)
    }
}
