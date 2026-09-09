package com.example.controlfree.knowledge

import android.content.Context
import com.example.controlfree.ai.DeepSeekClient
import com.example.controlfree.security.AiApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface KnowledgeChallengeSubmissionResult {
    data class NextRound(
        val session: KnowledgeChallengeSession,
        val review: KnowledgeRoundReview
    ) : KnowledgeChallengeSubmissionResult {
        val previousRoundScore: Int
            get() = review.score
    }

    data class Passed(
        val session: KnowledgeChallengeSession,
        val score: Int,
        /** UI 只需把 tokenId 带到随后的密码/手势验证流程。 */
        val pass: KnowledgeChallengePass,
        val review: KnowledgeRoundReview
    ) : KnowledgeChallengeSubmissionResult

    data class Failed(
        val session: KnowledgeChallengeSession,
        val score: Int,
        val review: KnowledgeRoundReview
    ) : KnowledgeChallengeSubmissionResult

    data object SessionNotFound : KnowledgeChallengeSubmissionResult
    data object AlreadyFinished : KnowledgeChallengeSubmissionResult
    data object IncompleteAnswers : KnowledgeChallengeSubmissionResult
    data object PassStorageUnavailable : KnowledgeChallengeSubmissionResult
}

internal data class KnowledgeChallengeRuntime(
    val engine: KnowledgeChallengeEngine,
    val questionPreparer: KnowledgeQuestionPreparer
)

internal fun interface KnowledgeChallengeRuntimeProvider {
    /** 每次新建挑战时取一次题库快照；返回后该挑战不再跟随题库切换。 */
    fun create(): KnowledgeChallengeRuntime
}

/**
 * UI 与服务之间的百科挑战核心入口。并发提交被串行化，通关凭证只会签发一次。
 */
internal class KnowledgeChallengeCoordinator internal constructor(
    private val engine: KnowledgeChallengeEngine,
    private val questionPreparer: KnowledgeQuestionPreparer,
    private val passRepository: KnowledgePassRepository,
    private val runtimeProvider: KnowledgeChallengeRuntimeProvider =
        KnowledgeChallengeRuntimeProvider {
            KnowledgeChallengeRuntime(engine, questionPreparer)
        }
) {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, KnowledgeChallengeSession>()

    suspend fun start(binding: KnowledgeChallengeBinding): KnowledgeChallengeSession {
        // challengeId 与题库内容无关，先命中活动会话，避免每次回到挑战页都重新读取题包。
        val challengeId = engine.challengeIdFor(binding)
        val cached = mutex.withLock { sessions[challengeId] }
        if (cached?.status == KnowledgeChallengeStatus.ACTIVE) return cached
        if (cached != null) {
            // 终态不能继续提交。清掉未预留凭证后创建新挑战，覆盖失败、过期和用户返回场景。
            clearChallenge(binding)
        }

        val runtime = withContext(Dispatchers.IO) { runtimeProvider.create() }
        val prepared = runtime.questionPreparer.prepare(binding)
        return mutex.withLock {
            sessions[challengeId]?.let { return@withLock it }
            while (sessions.size >= MAX_ACTIVE_SESSIONS) {
                sessions.remove(sessions.keys.first())
            }
            sessions[challengeId] = prepared
            prepared
        }
    }

    suspend fun current(challengeId: String): KnowledgeChallengeSession? =
        mutex.withLock { sessions[challengeId] }

    suspend fun submit(
        challengeId: String,
        selectedOptions: Map<String, String>
    ): KnowledgeChallengeSubmissionResult = mutex.withLock {
        val current = sessions[challengeId]
            ?: return@withLock KnowledgeChallengeSubmissionResult.SessionNotFound
        if (current.status != KnowledgeChallengeStatus.ACTIVE) {
            return@withLock KnowledgeChallengeSubmissionResult.AlreadyFinished
        }
        val evaluation = try {
            engine.submitRound(current, selectedOptions)
        } catch (_: IllegalArgumentException) {
            return@withLock KnowledgeChallengeSubmissionResult.IncompleteAnswers
        } catch (_: IllegalStateException) {
            return@withLock KnowledgeChallengeSubmissionResult.AlreadyFinished
        }
        sessions[challengeId] = evaluation.session
        when (evaluation.session.status) {
            KnowledgeChallengeStatus.ACTIVE -> KnowledgeChallengeSubmissionResult.NextRound(
                session = evaluation.session,
                review = evaluation.review
            )
            KnowledgeChallengeStatus.FAILED -> {
                sessions.remove(challengeId)
                KnowledgeChallengeSubmissionResult.Failed(
                    session = evaluation.session,
                    score = evaluation.session.totalScore,
                    review = evaluation.review
                )
            }
            KnowledgeChallengeStatus.PASSED -> when (
                val issued = passRepository.issue(challengeId, evaluation.session.binding)
            ) {
                is KnowledgePassIssueResult.Issued -> KnowledgeChallengeSubmissionResult.Passed(
                    session = evaluation.session,
                    score = evaluation.session.totalScore,
                    pass = issued.pass,
                    review = evaluation.review
                )
                KnowledgePassIssueResult.InvalidChallenge,
                KnowledgePassIssueResult.StorageUnavailable -> {
                    sessions.remove(challengeId)
                    KnowledgeChallengeSubmissionResult.PassStorageUnavailable
                }
            }
        }
    }

    /** 放弃当前动作（包括密码页返回）时清理未预留凭证，之后可重新挑战。 */
    suspend fun clearChallenge(binding: KnowledgeChallengeBinding): Boolean {
        val challengeId = engine.challengeIdFor(binding)
        mutex.withLock { sessions.remove(challengeId) }
        return passRepository.revokeAvailable(binding)
    }

    suspend fun restart(binding: KnowledgeChallengeBinding): KnowledgeChallengeSession {
        clearChallenge(binding)
        return start(binding)
    }

    suspend fun clearSession(lockSessionId: Long): Boolean {
        mutex.withLock {
            sessions.entries.removeAll { (_, session) ->
                session.binding.lockSessionId == lockSessionId
            }
        }
        return passRepository.revokeSession(lockSessionId)
    }

    internal companion object {
        private const val MAX_ACTIVE_SESSIONS = 16

        @Volatile
        private var instance: KnowledgeChallengeCoordinator? = null

        fun getInstance(context: Context): KnowledgeChallengeCoordinator =
            instance ?: synchronized(this) {
                instance ?: createDefault(context.applicationContext).also { instance = it }
            }

        private fun createDefault(context: Context): KnowledgeChallengeCoordinator {
            val fallbackEngine = KnowledgeChallengeEngine()
            val enhancer = DeepSeekKnowledgeQuestionEnhancer(
                apiKeyStore = AiApiKeyStore.getInstance(context),
                gateway = DeepSeekClient.getInstance()
            )
            val bankRepository = AndroidKnowledgeBankRepository.getInstance(context)
            return KnowledgeChallengeCoordinator(
                engine = fallbackEngine,
                questionPreparer = KnowledgeQuestionPreparer(fallbackEngine, enhancer),
                passRepository = AndroidKnowledgePassRepository.getInstance(context),
                runtimeProvider = KnowledgeChallengeRuntimeProvider {
                    val snapshotEngine = KnowledgeChallengeEngine(bankRepository.snapshot().facts)
                    KnowledgeChallengeRuntime(
                        engine = snapshotEngine,
                        questionPreparer = KnowledgeQuestionPreparer(snapshotEngine, enhancer)
                    )
                }
            )
        }
    }
}
