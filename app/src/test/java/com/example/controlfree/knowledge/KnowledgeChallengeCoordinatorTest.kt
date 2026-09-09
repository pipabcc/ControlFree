package com.example.controlfree.knowledge

import com.example.controlfree.LockPendingActionKind
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeChallengeCoordinatorTest {
    @Test
    fun `必须完成两轮全对才签发免成长值凭证且快速重复提交不会重签`() = runTest {
        val fixture = fixture()
        val first = fixture.coordinator.start(fixture.binding)
        val firstAnswers = answersWithCorrectCount(first, 3)

        val next = fixture.coordinator.submit(
            first.challengeId,
            firstAnswers
        ) as KnowledgeChallengeSubmissionResult.NextRound

        assertEquals(3, next.previousRoundScore)
        assertReviewMatches(first, firstAnswers, next.review)
        assertTrue(fixture.recordStore.load().isEmpty())

        val secondAnswers = answersWithCorrectCount(next.session, 3)
        val passed = fixture.coordinator.submit(
            first.challengeId,
            secondAnswers
        ) as KnowledgeChallengeSubmissionResult.Passed
        val repeated = fixture.coordinator.submit(
            first.challengeId,
            secondAnswers
        )

        assertEquals(6, passed.score)
        assertEquals(listOf(3, 3), passed.session.completedRoundScores)
        assertReviewMatches(next.session, secondAnswers, passed.review)
        assertTrue(repeated is KnowledgeChallengeSubmissionResult.AlreadyFinished)
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            fixture.passRepository.reserve(
                passed.pass.tokenId,
                ORDER_ID,
                fixture.binding
            )
        )
        assertEquals(
            KnowledgePassCommitResult.COMMITTED,
            fixture.passRepository.commit(
                passed.pass.tokenId,
                ORDER_ID,
                fixture.binding
            )
        )
    }

    @Test
    fun `任意一轮答错都会失败且不签发凭证`() = runTest {
        listOf(2 to 3, 3 to 2).forEach { (firstScore, secondScore) ->
            val fixture = fixture()
            val first = fixture.coordinator.start(fixture.binding)
            val firstAnswers = answersWithCorrectCount(first, firstScore)
            val next = fixture.coordinator.submit(
                first.challengeId,
                firstAnswers
            ) as KnowledgeChallengeSubmissionResult.NextRound
            val secondAnswers = answersWithCorrectCount(next.session, secondScore)

            val failed = fixture.coordinator.submit(
                first.challengeId,
                secondAnswers
            ) as KnowledgeChallengeSubmissionResult.Failed

            assertEquals(firstScore + secondScore, failed.score)
            assertReviewMatches(first, firstAnswers, next.review)
            assertReviewMatches(next.session, secondAnswers, failed.review)
            assertTrue(fixture.recordStore.load().isEmpty())

            val restarted = fixture.coordinator.start(fixture.binding)
            assertEquals(KnowledgeChallengeStatus.ACTIVE, restarted.status)
            assertEquals(1, restarted.currentRoundNumber)
        }
    }

    @Test
    fun `相同会话重复打开返回同一个活动挑战`() = runTest {
        val fixture = fixture()

        val first = fixture.coordinator.start(fixture.binding)
        val second = fixture.coordinator.start(fixture.binding)

        assertTrue(first === second)
        assertEquals(first.questions.map { it.options }, second.questions.map { it.options })
    }

    @Test
    fun `通关后返回再次进入会撤销旧凭证并创建全新挑战`() = runTest {
        val fixture = fixture()
        val first = fixture.coordinator.start(fixture.binding)
        val next = fixture.coordinator.submit(
            first.challengeId,
            answersWithCorrectCount(first, 3)
        ) as KnowledgeChallengeSubmissionResult.NextRound
        val passed = fixture.coordinator.submit(
            first.challengeId,
            answersWithCorrectCount(next.session, 3)
        ) as KnowledgeChallengeSubmissionResult.Passed

        val restarted = fixture.coordinator.start(fixture.binding)

        assertEquals(KnowledgeChallengeStatus.ACTIVE, restarted.status)
        assertEquals(1, restarted.currentRoundNumber)
        assertEquals(
            KnowledgePassReserveResult.NOT_FOUND,
            fixture.passRepository.reserve(
                passed.pass.tokenId,
                ORDER_ID,
                fixture.binding
            )
        )
    }

    @Test
    fun `显式重启会清理当前挑战并从第一轮重新开始`() = runTest {
        val fixture = fixture()
        val first = fixture.coordinator.start(fixture.binding)
        val next = fixture.coordinator.submit(
            first.challengeId,
            answersWithCorrectCount(first, 0)
        ) as KnowledgeChallengeSubmissionResult.NextRound

        val restarted = fixture.coordinator.restart(fixture.binding)

        assertEquals(2, next.session.currentRoundNumber)
        assertEquals(1, restarted.currentRoundNumber)
        assertEquals(KnowledgeChallengeStatus.ACTIVE, restarted.status)
    }

    @Test
    fun `创建挑战时固定当前题库快照且下一会话使用新快照`() = runTest {
        val bankA = OfflineKnowledgeBank.facts.map { fact ->
            fact.copy(localStem = "${fact.localStem} A版")
        }
        val bankB = OfflineKnowledgeBank.facts.map { fact ->
            fact.copy(localStem = "${fact.localStem} B版")
        }
        var activeBank = bankA
        val fallback = KnowledgeChallengeEngine(bankA)
        val fixture = fixture(
            fallbackEngine = fallback,
            runtimeProvider = KnowledgeChallengeRuntimeProvider {
                val snapshotEngine = KnowledgeChallengeEngine(activeBank)
                KnowledgeChallengeRuntime(
                    snapshotEngine,
                    KnowledgeQuestionPreparer(snapshotEngine, enhancer = null)
                )
            }
        )

        val first = fixture.coordinator.start(fixture.binding)
        activeBank = bankB
        val cached = fixture.coordinator.start(fixture.binding)
        val nextBinding = KnowledgeChallengeBinding(1_000L, LockPendingActionKind.PAUSE)
        val next = fixture.coordinator.start(nextBinding)

        assertTrue(first.questions.all { it.stem.endsWith(" A版") })
        assertTrue(cached === first)
        assertTrue(next.questions.all { it.stem.endsWith(" B版") })
    }

    private fun fixture(
        fallbackEngine: KnowledgeChallengeEngine = KnowledgeChallengeEngine(),
        runtimeProvider: KnowledgeChallengeRuntimeProvider? = null
    ): Fixture {
        val recordStore = InMemoryKnowledgePassRecordStore()
        var tokenCounter = 1
        val passRepository = SignedKnowledgePassRepository(
            recordStore = recordStore,
            signer = HmacSha256KnowledgePassSigner(
                SecretKeySpec(ByteArray(32) { 9 }, "HmacSHA256")
            ),
            nowElapsedMillis = { 1_000L },
            currentBootCount = { 3 },
            tokenIdFactory = {
                "10000000-0000-4000-8000-${tokenCounter++.toString().padStart(12, '0')}"
            },
            nonceFactory = { "BBBBBBBBBBBBBBBBBBBBBB" },
            ioDispatcher = Dispatchers.Unconfined
        )
        val binding = KnowledgeChallengeBinding(999L, LockPendingActionKind.PAUSE)
        return Fixture(
            binding = binding,
            recordStore = recordStore,
            passRepository = passRepository,
            coordinator = KnowledgeChallengeCoordinator(
                engine = fallbackEngine,
                questionPreparer = KnowledgeQuestionPreparer(fallbackEngine, enhancer = null),
                passRepository = passRepository,
                runtimeProvider = runtimeProvider ?: KnowledgeChallengeRuntimeProvider {
                    KnowledgeChallengeRuntime(
                        fallbackEngine,
                        KnowledgeQuestionPreparer(fallbackEngine, enhancer = null)
                    )
                }
            )
        )
    }

    private fun answersWithCorrectCount(
        session: KnowledgeChallengeSession,
        correctCount: Int
    ): Map<String, String> = session.questions.mapIndexed { index, question ->
        val selected = if (index < correctCount) {
            question.options.first { question.isCorrect(it.id) }
        } else {
            question.options.first { !question.isCorrect(it.id) }
        }
        question.id to selected.id
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
    }

    private data class Fixture(
        val binding: KnowledgeChallengeBinding,
        val recordStore: InMemoryKnowledgePassRecordStore,
        val passRepository: KnowledgePassRepository,
        val coordinator: KnowledgeChallengeCoordinator
    )

    private companion object {
        const val ORDER_ID = "30000000-0000-4000-8000-000000000001"
    }
}
