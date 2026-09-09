package com.example.controlfree.knowledge

import com.example.controlfree.LockPendingActionKind
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class KnowledgePassRepositoryTest {
    private var nowElapsed = 10_000L
    private var bootCount = 7
    private var tokenCounter = 1
    private val recordStore = InMemoryKnowledgePassRecordStore()
    private val signer = HmacSha256KnowledgePassSigner(
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "HmacSHA256")
    )
    private val binding = KnowledgeChallengeBinding(42L, LockPendingActionKind.SKIP)
    private val challengeId = KnowledgeChallengeEngine().challengeIdFor(binding)

    @Test
    fun `通关凭证绑定会话动作和订单并且提交可幂等重试`() = runTest {
        val repository = repository()
        val pass = issue(repository)

        assertTrue(pass.isUsableAt(nowElapsed, bootCount))
        assertEquals(
            KnowledgePassReserveResult.BINDING_MISMATCH,
            repository.reserve(
                tokenId = pass.tokenId,
                orderId = ORDER_A,
                expectedBinding = KnowledgeChallengeBinding(42L, LockPendingActionKind.PAUSE)
            )
        )
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            repository.reserve(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.ALREADY_RESERVED,
            repository.reserve(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.RESERVATION_CONFLICT,
            repository.reserve(pass.tokenId, ORDER_B, binding)
        )
        assertEquals(
            KnowledgePassCommitResult.ORDER_MISMATCH,
            repository.commit(pass.tokenId, ORDER_B, binding)
        )
        assertEquals(
            KnowledgePassCommitResult.COMMITTED,
            repository.commit(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassCommitResult.ALREADY_COMMITTED,
            repository.commit(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.ALREADY_COMMITTED,
            repository.reserve(pass.tokenId, ORDER_A, binding)
        )
    }

    @Test
    fun `业务失败回滚后同一凭证可以重新预留`() = runTest {
        val repository = repository()
        val pass = issue(repository)

        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            repository.reserve(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassRollbackResult.ROLLED_BACK,
            repository.rollback(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            repository.reserve(pass.tokenId, ORDER_B, binding)
        )
    }

    @Test
    fun `预留后即使原始凭证到期仍可提交已完成的业务`() = runTest {
        val repository = repository()
        val pass = issue(repository)
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            repository.reserve(pass.tokenId, ORDER_A, binding)
        )
        nowElapsed += SignedKnowledgePassRepository.PASS_TTL_MILLIS

        assertEquals(
            KnowledgePassCommitResult.COMMITTED,
            repository.commit(pass.tokenId, ORDER_A, binding)
        )
    }

    @Test
    fun `两分钟到期和设备重启均使未预留凭证失效`() = runTest {
        val repository = repository()
        val expired = issue(repository)
        nowElapsed += SignedKnowledgePassRepository.PASS_TTL_MILLIS

        assertEquals(
            KnowledgePassReserveResult.EXPIRED,
            repository.reserve(expired.tokenId, ORDER_A, binding)
        )

        nowElapsed = 20_000L
        val previousBoot = issue(repository)
        bootCount++
        assertEquals(
            KnowledgePassReserveResult.BOOT_MISMATCH,
            repository.reserve(previousBoot.tokenId, ORDER_A, binding)
        )
    }

    @Test
    fun `进程内重建仓库后仍能恢复同一订单预留并回滚`() = runTest {
        val firstRepository = repository()
        val pass = issue(firstRepository)
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            firstRepository.reserve(pass.tokenId, ORDER_A, binding)
        )
        val recreatedRepository = repository()

        assertEquals(
            KnowledgePassReserveResult.ALREADY_RESERVED,
            recreatedRepository.reserve(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassRollbackResult.ROLLED_BACK,
            recreatedRepository.rollback(pass.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            recreatedRepository.reserve(pass.tokenId, ORDER_B, binding)
        )
    }

    @Test
    fun `篡改持久化会话字段后防伪校验失败`() = runTest {
        val repository = repository()
        val pass = issue(repository)
        val rawRecord = recordStore.load().single()
        recordStore.replace(setOf(rawRecord.replace("|42|skip|", "|43|skip|")))

        assertEquals(
            KnowledgePassReserveResult.INVALID_OR_TAMPERED,
            repository.reserve(pass.tokenId, ORDER_A, binding)
        )
    }

    @Test
    fun `凭证签发失败关闭且非法challengeId不会落盘`() = runTest {
        val unavailableSigner = KnowledgePassSigner { null }
        val repository = repository(signer = unavailableSigner)

        assertEquals(
            KnowledgePassIssueResult.InvalidChallenge,
            repository.issue("not-a-challenge", binding)
        )
        assertEquals(
            KnowledgePassIssueResult.StorageUnavailable,
            repository.issue(challengeId, binding)
        )
        assertTrue(recordStore.load().isEmpty())
    }

    @Test
    fun `撤销锁定会话会删除该会话全部凭证`() = runTest {
        val repository = repository()
        val first = issue(repository)
        val second = issue(repository)

        assertTrue(repository.revokeSession(binding.lockSessionId))
        assertEquals(
            KnowledgePassReserveResult.NOT_FOUND,
            repository.reserve(first.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.NOT_FOUND,
            repository.reserve(second.tokenId, ORDER_B, binding)
        )
    }

    @Test
    fun `撤销会话不会删除进行中的预留`() = runTest {
        val repository = repository()
        val reserved = issue(repository)
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            repository.reserve(reserved.tokenId, ORDER_A, binding)
        )
        val committed = issue(repository)
        assertEquals(
            KnowledgePassReserveResult.RESERVED,
            repository.reserve(committed.tokenId, ORDER_B, binding)
        )
        assertEquals(
            KnowledgePassCommitResult.COMMITTED,
            repository.commit(committed.tokenId, ORDER_B, binding)
        )
        val available = issue(repository)

        assertTrue(repository.revokeSession(binding.lockSessionId))
        assertEquals(
            KnowledgePassReserveResult.ALREADY_RESERVED,
            repository.reserve(reserved.tokenId, ORDER_A, binding)
        )
        assertEquals(
            KnowledgePassCommitResult.NOT_FOUND,
            repository.commit(committed.tokenId, ORDER_B, binding)
        )
        assertEquals(
            KnowledgePassReserveResult.NOT_FOUND,
            repository.reserve(available.tokenId, ORDER_B, binding)
        )
    }

    @Test
    fun `签名和持久化工作始终切换到指定IO线程`() = runTest {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "knowledge-pass-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        var signingThreadName = ""
        val observingSigner = KnowledgePassSigner { payload ->
            signingThreadName = Thread.currentThread().name
            signer.sign(payload)
        }

        try {
            val repository = repository(
                signer = observingSigner,
                ioDispatcher = dispatcher
            )
            issue(repository)

            assertTrue(signingThreadName.startsWith("knowledge-pass-io"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun repository(
        signer: KnowledgePassSigner = this.signer,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined
    ) = SignedKnowledgePassRepository(
        recordStore = recordStore,
        signer = signer,
        nowElapsedMillis = { nowElapsed },
        currentBootCount = { bootCount },
        tokenIdFactory = {
            "00000000-0000-4000-8000-${tokenCounter++.toString().padStart(12, '0')}"
        },
        nonceFactory = { "AAAAAAAAAAAAAAAAAAAAAA" },
        ioDispatcher = ioDispatcher
    )

    private suspend fun issue(repository: KnowledgePassRepository): KnowledgeChallengePass {
        val result = repository.issue(challengeId, binding)
        assertTrue(result is KnowledgePassIssueResult.Issued)
        return (result as KnowledgePassIssueResult.Issued).pass
    }

    private companion object {
        const val ORDER_A = "20000000-0000-4000-8000-000000000001"
        const val ORDER_B = "20000000-0000-4000-8000-000000000002"
    }
}
