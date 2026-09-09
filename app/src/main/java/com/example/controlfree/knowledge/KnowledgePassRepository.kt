package com.example.controlfree.knowledge

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.controlfree.LockPendingActionKind
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class KnowledgePassState(val wireValue: String) {
    AVAILABLE("available"),
    RESERVED("reserved"),
    COMMITTED("committed");

    companion object {
        fun fromWireValue(value: String?): KnowledgePassState? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal data class KnowledgeChallengePass(
    val tokenId: String,
    val challengeId: String,
    val binding: KnowledgeChallengeBinding,
    val issuedAtElapsedMillis: Long,
    val expiresAtElapsedMillis: Long,
    val bootCount: Int,
    internal val state: KnowledgePassState,
    internal val reservationOrderId: String?,
    internal val nonce: String,
    internal val authenticationTag: String
) {
    init {
        require((state == KnowledgePassState.AVAILABLE) == (reservationOrderId == null)) {
            "只有可用凭证可以不绑定订单"
        }
    }

    fun isUsableAt(nowElapsedMillis: Long, currentBootCount: Int): Boolean =
        currentBootCount == bootCount &&
            nowElapsedMillis >= issuedAtElapsedMillis &&
            nowElapsedMillis < expiresAtElapsedMillis
}

internal sealed interface KnowledgePassIssueResult {
    data class Issued(val pass: KnowledgeChallengePass) : KnowledgePassIssueResult
    data object StorageUnavailable : KnowledgePassIssueResult
    data object InvalidChallenge : KnowledgePassIssueResult
}

internal enum class KnowledgePassConsumeResult {
    CONSUMED,
    NOT_FOUND,
    EXPIRED,
    BOOT_MISMATCH,
    BINDING_MISMATCH,
    INVALID_OR_TAMPERED,
    STORAGE_UNAVAILABLE
}

internal enum class KnowledgePassReserveResult {
    RESERVED,
    ALREADY_RESERVED,
    ALREADY_COMMITTED,
    NOT_FOUND,
    EXPIRED,
    BOOT_MISMATCH,
    BINDING_MISMATCH,
    RESERVATION_CONFLICT,
    INVALID_ORDER_ID,
    INVALID_OR_TAMPERED,
    STORAGE_UNAVAILABLE
}

internal enum class KnowledgePassCommitResult {
    COMMITTED,
    ALREADY_COMMITTED,
    NOT_FOUND,
    NOT_RESERVED,
    BINDING_MISMATCH,
    ORDER_MISMATCH,
    INVALID_ORDER_ID,
    INVALID_OR_TAMPERED,
    STORAGE_UNAVAILABLE
}

internal enum class KnowledgePassRollbackResult {
    ROLLED_BACK,
    ALREADY_AVAILABLE,
    ALREADY_COMMITTED,
    NOT_FOUND,
    EXPIRED,
    BOOT_MISMATCH,
    BINDING_MISMATCH,
    ORDER_MISMATCH,
    INVALID_ORDER_ID,
    INVALID_OR_TAMPERED,
    STORAGE_UNAVAILABLE
}

internal interface KnowledgePassRepository {
    /** 仅应在本地评分确认挑战通关后调用；实现保证阻塞存储和 KeyStore 工作在 IO。 */
    suspend fun issue(
        challengeId: String,
        binding: KnowledgeChallengeBinding
    ): KnowledgePassIssueResult

    /**
     * 把凭证原子绑定到成长订单。相同 token/order 可幂等重试，不同订单不能抢占。
     */
    suspend fun reserve(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassReserveResult

    /** 业务状态持久化成功后提交；提交记录保留到清理，以区分幂等重试与未知 token。 */
    suspend fun commit(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassCommitResult

    /** 业务状态未落盘时回滚，恢复同一凭证供后续重试。 */
    suspend fun rollback(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassRollbackResult

    /** 删除该动作尚未预留的旧凭证；进行中的预留和已提交记录不受影响。 */
    suspend fun revokeAvailable(binding: KnowledgeChallengeBinding): Boolean

    /** 兼容旧调用方；新代码必须使用 reserve/commit/rollback。 */
    @Deprecated("改用 reserve/commit/rollback，避免业务失败后丢失凭证")
    suspend fun consume(
        tokenId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassConsumeResult

    suspend fun revokeSession(lockSessionId: Long): Boolean
}

internal class SignedKnowledgePassRepository internal constructor(
    private val recordStore: KnowledgePassRecordStore,
    private val signer: KnowledgePassSigner,
    private val nowElapsedMillis: () -> Long,
    private val currentBootCount: () -> Int,
    private val tokenIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val nonceFactory: () -> String = {
        ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).toBase64Url()
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : KnowledgePassRepository {
    private val stateLock = Any()

    override suspend fun issue(
        challengeId: String,
        binding: KnowledgeChallengeBinding
    ): KnowledgePassIssueResult = withContext(ioDispatcher) {
        synchronized(stateLock) { issueBlocking(challengeId, binding) }
    }

    override suspend fun reserve(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassReserveResult = withContext(ioDispatcher) {
        synchronized(stateLock) { reserveBlocking(tokenId, orderId, expectedBinding) }
    }

    override suspend fun commit(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassCommitResult = withContext(ioDispatcher) {
        synchronized(stateLock) { commitBlocking(tokenId, orderId, expectedBinding) }
    }

    override suspend fun rollback(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassRollbackResult = withContext(ioDispatcher) {
        synchronized(stateLock) { rollbackBlocking(tokenId, orderId, expectedBinding) }
    }

    override suspend fun revokeAvailable(binding: KnowledgeChallengeBinding): Boolean =
        withContext(ioDispatcher) {
            synchronized(stateLock) { revokeAvailableBlocking(binding) }
        }

    @Deprecated("改用 reserve/commit/rollback，避免业务失败后丢失凭证")
    override suspend fun consume(
        tokenId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassConsumeResult = withContext(ioDispatcher) {
        synchronized(stateLock) { consumeBlocking(tokenId, expectedBinding) }
    }

    override suspend fun revokeSession(lockSessionId: Long): Boolean = withContext(ioDispatcher) {
        synchronized(stateLock) { revokeSessionBlocking(lockSessionId) }
    }

    private fun issueBlocking(
        challengeId: String,
        binding: KnowledgeChallengeBinding
    ): KnowledgePassIssueResult {
        if (!CHALLENGE_ID_PATTERN.matches(challengeId)) {
            return KnowledgePassIssueResult.InvalidChallenge
        }
        val now = nowElapsedMillis()
        val bootCount = currentBootCount()
        if (now < 0L || bootCount < 0 || now > Long.MAX_VALUE - PASS_TTL_MILLIS) {
            return KnowledgePassIssueResult.StorageUnavailable
        }
        val loaded = loadRecords() ?: return KnowledgePassIssueResult.StorageUnavailable
        val tokenId = tokenIdFactory()
        val nonce = nonceFactory()
        if (!TOKEN_ID_PATTERN.matches(tokenId) || !NONCE_PATTERN.matches(nonce)) {
            return KnowledgePassIssueResult.StorageUnavailable
        }
        val unsigned = UnsignedPass(
            tokenId = tokenId,
            challengeId = challengeId,
            binding = binding,
            issuedAtElapsedMillis = now,
            expiresAtElapsedMillis = now + PASS_TTL_MILLIS,
            bootCount = bootCount,
            state = KnowledgePassState.AVAILABLE,
            reservationOrderId = null,
            nonce = nonce
        )
        val issuedPass = sign(unsigned) ?: return KnowledgePassIssueResult.StorageUnavailable
        val retained = loaded.validPasses
            .asSequence()
            .filter { pass ->
                pass.bootCount == bootCount &&
                    (pass.state != KnowledgePassState.AVAILABLE || pass.expiresAtElapsedMillis > now)
            }
            .filterNot { pass ->
                pass.binding == binding && pass.state == KnowledgePassState.AVAILABLE
            }
            .sortedByDescending(KnowledgeChallengePass::issuedAtElapsedMillis)
            .take(MAX_STORED_PASSES - 1)
            .toMutableList()
            .apply { add(issuedPass) }
        if (!recordStore.replace(retained.map(::encodeRecord).toSet())) {
            return KnowledgePassIssueResult.StorageUnavailable
        }
        return KnowledgePassIssueResult.Issued(issuedPass)
    }

    private fun reserveBlocking(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassReserveResult {
        if (!TOKEN_ID_PATTERN.matches(tokenId)) return KnowledgePassReserveResult.NOT_FOUND
        if (!ORDER_ID_PATTERN.matches(orderId)) return KnowledgePassReserveResult.INVALID_ORDER_ID
        val rawRecords = recordStore.load()
            ?: return KnowledgePassReserveResult.STORAGE_UNAVAILABLE
        val matchingRaw = rawRecords.firstOrNull { raw -> rawTokenId(raw) == tokenId }
            ?: return KnowledgePassReserveResult.NOT_FOUND
        val pass = decodeAndVerify(matchingRaw)
            ?: return KnowledgePassReserveResult.INVALID_OR_TAMPERED
        val bootCount = currentBootCount()
        if (bootCount < 0) return KnowledgePassReserveResult.STORAGE_UNAVAILABLE
        if (pass.bootCount != bootCount) return KnowledgePassReserveResult.BOOT_MISMATCH
        val now = nowElapsedMillis()
        if (now < pass.issuedAtElapsedMillis || now >= pass.expiresAtElapsedMillis) {
            return if (removeRawRecord(rawRecords, matchingRaw)) {
                KnowledgePassReserveResult.EXPIRED
            } else {
                KnowledgePassReserveResult.STORAGE_UNAVAILABLE
            }
        }
        if (pass.binding != expectedBinding) {
            return KnowledgePassReserveResult.BINDING_MISMATCH
        }
        return when (pass.state) {
            KnowledgePassState.AVAILABLE -> if (
                replacePass(
                    rawRecords = rawRecords,
                    matchingRaw = matchingRaw,
                    pass = pass,
                    state = KnowledgePassState.RESERVED,
                    orderId = orderId
                )
            ) {
                KnowledgePassReserveResult.RESERVED
            } else {
                KnowledgePassReserveResult.STORAGE_UNAVAILABLE
            }
            KnowledgePassState.RESERVED -> if (pass.reservationOrderId == orderId) {
                KnowledgePassReserveResult.ALREADY_RESERVED
            } else {
                KnowledgePassReserveResult.RESERVATION_CONFLICT
            }
            KnowledgePassState.COMMITTED -> if (pass.reservationOrderId == orderId) {
                KnowledgePassReserveResult.ALREADY_COMMITTED
            } else {
                KnowledgePassReserveResult.RESERVATION_CONFLICT
            }
        }
    }

    private fun commitBlocking(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassCommitResult {
        if (!TOKEN_ID_PATTERN.matches(tokenId)) return KnowledgePassCommitResult.NOT_FOUND
        if (!ORDER_ID_PATTERN.matches(orderId)) return KnowledgePassCommitResult.INVALID_ORDER_ID
        val rawRecords = recordStore.load()
            ?: return KnowledgePassCommitResult.STORAGE_UNAVAILABLE
        val matchingRaw = rawRecords.firstOrNull { raw -> rawTokenId(raw) == tokenId }
            ?: return KnowledgePassCommitResult.NOT_FOUND
        val pass = decodeAndVerify(matchingRaw)
            ?: return KnowledgePassCommitResult.INVALID_OR_TAMPERED
        if (pass.binding != expectedBinding) return KnowledgePassCommitResult.BINDING_MISMATCH
        return when (pass.state) {
            KnowledgePassState.AVAILABLE -> KnowledgePassCommitResult.NOT_RESERVED
            KnowledgePassState.RESERVED -> {
                if (pass.reservationOrderId != orderId) {
                    KnowledgePassCommitResult.ORDER_MISMATCH
                } else if (
                    replacePass(
                        rawRecords = rawRecords,
                        matchingRaw = matchingRaw,
                        pass = pass,
                        state = KnowledgePassState.COMMITTED,
                        orderId = orderId
                    )
                ) {
                    KnowledgePassCommitResult.COMMITTED
                } else {
                    KnowledgePassCommitResult.STORAGE_UNAVAILABLE
                }
            }
            KnowledgePassState.COMMITTED -> if (pass.reservationOrderId == orderId) {
                KnowledgePassCommitResult.ALREADY_COMMITTED
            } else {
                KnowledgePassCommitResult.ORDER_MISMATCH
            }
        }
    }

    private fun rollbackBlocking(
        tokenId: String,
        orderId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassRollbackResult {
        if (!TOKEN_ID_PATTERN.matches(tokenId)) return KnowledgePassRollbackResult.NOT_FOUND
        if (!ORDER_ID_PATTERN.matches(orderId)) return KnowledgePassRollbackResult.INVALID_ORDER_ID
        val rawRecords = recordStore.load()
            ?: return KnowledgePassRollbackResult.STORAGE_UNAVAILABLE
        val matchingRaw = rawRecords.firstOrNull { raw -> rawTokenId(raw) == tokenId }
            ?: return KnowledgePassRollbackResult.NOT_FOUND
        val pass = decodeAndVerify(matchingRaw)
            ?: return KnowledgePassRollbackResult.INVALID_OR_TAMPERED
        if (pass.binding != expectedBinding) return KnowledgePassRollbackResult.BINDING_MISMATCH
        if (pass.reservationOrderId != null && pass.reservationOrderId != orderId) {
            return KnowledgePassRollbackResult.ORDER_MISMATCH
        }
        return when (pass.state) {
            KnowledgePassState.AVAILABLE -> KnowledgePassRollbackResult.ALREADY_AVAILABLE
            KnowledgePassState.COMMITTED -> KnowledgePassRollbackResult.ALREADY_COMMITTED
            KnowledgePassState.RESERVED -> {
                val bootCount = currentBootCount()
                if (bootCount < 0) return KnowledgePassRollbackResult.STORAGE_UNAVAILABLE
                if (pass.bootCount != bootCount) return KnowledgePassRollbackResult.BOOT_MISMATCH
                val now = nowElapsedMillis()
                if (now < pass.issuedAtElapsedMillis || now >= pass.expiresAtElapsedMillis) {
                    return if (removeRawRecord(rawRecords, matchingRaw)) {
                        KnowledgePassRollbackResult.EXPIRED
                    } else {
                        KnowledgePassRollbackResult.STORAGE_UNAVAILABLE
                    }
                }
                if (
                    replacePass(
                        rawRecords = rawRecords,
                        matchingRaw = matchingRaw,
                        pass = pass,
                        state = KnowledgePassState.AVAILABLE,
                        orderId = null
                    )
                ) {
                    KnowledgePassRollbackResult.ROLLED_BACK
                } else {
                    KnowledgePassRollbackResult.STORAGE_UNAVAILABLE
                }
            }
        }
    }

    private fun consumeBlocking(
        tokenId: String,
        expectedBinding: KnowledgeChallengeBinding
    ): KnowledgePassConsumeResult {
        if (!TOKEN_ID_PATTERN.matches(tokenId)) return KnowledgePassConsumeResult.NOT_FOUND
        val rawRecords = recordStore.load()
            ?: return KnowledgePassConsumeResult.STORAGE_UNAVAILABLE
        val matchingRaw = rawRecords.firstOrNull { raw -> rawTokenId(raw) == tokenId }
            ?: return KnowledgePassConsumeResult.NOT_FOUND
        val pass = decodeAndVerify(matchingRaw)
            ?: return KnowledgePassConsumeResult.INVALID_OR_TAMPERED
        if (pass.state != KnowledgePassState.AVAILABLE) return KnowledgePassConsumeResult.NOT_FOUND
        val bootCount = currentBootCount()
        if (bootCount < 0) return KnowledgePassConsumeResult.STORAGE_UNAVAILABLE
        if (pass.bootCount != bootCount) return KnowledgePassConsumeResult.BOOT_MISMATCH
        val now = nowElapsedMillis()
        if (now < pass.issuedAtElapsedMillis || now >= pass.expiresAtElapsedMillis) {
            return if (removeRawRecord(rawRecords, matchingRaw)) {
                KnowledgePassConsumeResult.EXPIRED
            } else {
                KnowledgePassConsumeResult.STORAGE_UNAVAILABLE
            }
        }
        if (pass.binding != expectedBinding) return KnowledgePassConsumeResult.BINDING_MISMATCH

        return if (removeRawRecord(rawRecords, matchingRaw)) {
            KnowledgePassConsumeResult.CONSUMED
        } else {
            KnowledgePassConsumeResult.STORAGE_UNAVAILABLE
        }
    }

    private fun revokeAvailableBlocking(binding: KnowledgeChallengeBinding): Boolean {
        val rawRecords = recordStore.load() ?: return false
        val retained = rawRecords.filterNot { raw ->
            decodeAndVerify(raw)?.let { pass ->
                pass.binding == binding && pass.state == KnowledgePassState.AVAILABLE
            } == true
        }.toSet()
        return retained == rawRecords || recordStore.replace(retained)
    }

    private fun revokeSessionBlocking(lockSessionId: Long): Boolean {
        if (lockSessionId <= 0L) return false
        val rawRecords = recordStore.load() ?: return false
        val retained = rawRecords.filterNot { raw ->
            decodeAndVerify(raw)?.let { pass ->
                pass.binding.lockSessionId == lockSessionId &&
                    pass.state != KnowledgePassState.RESERVED
            } == true
        }.toSet()
        return retained == rawRecords || recordStore.replace(retained)
    }

    private fun loadRecords(): LoadedRecords? {
        val rawRecords = recordStore.load() ?: return null
        return LoadedRecords(
            validPasses = rawRecords.mapNotNull(::decodeAndVerify),
            hadInvalidRecords = rawRecords.any { decodeAndVerify(it) == null }
        )
    }

    private fun removeRawRecord(rawRecords: Set<String>, target: String): Boolean =
        recordStore.replace(rawRecords - target)

    private fun replacePass(
        rawRecords: Set<String>,
        matchingRaw: String,
        pass: KnowledgeChallengePass,
        state: KnowledgePassState,
        orderId: String?
    ): Boolean {
        val unsigned = pass.toUnsigned(state, orderId)
        val signed = sign(unsigned) ?: return false
        return recordStore.replace((rawRecords - matchingRaw) + encodeRecord(signed))
    }

    private fun sign(unsigned: UnsignedPass): KnowledgeChallengePass? =
        signer.sign(unsigned.canonicalPayload().toByteArray(Charsets.UTF_8))
            ?.toBase64Url()
            ?.let(unsigned::toPass)

    private fun encodeRecord(pass: KnowledgeChallengePass): String =
        "${pass.toUnsigned().canonicalPayload()}|${pass.authenticationTag}"

    private fun rawTokenId(record: String): String? {
        val fields = record.split(RECORD_SEPARATOR, limit = RECORD_FIELD_COUNT)
        if (fields.size != RECORD_FIELD_COUNT) return null
        return fields[TOKEN_ID_INDEX].takeIf(TOKEN_ID_PATTERN::matches)
    }

    private fun decodeAndVerify(record: String): KnowledgeChallengePass? {
        if (record.length > MAX_RECORD_CHARS) return null
        val fields = record.split(RECORD_SEPARATOR, limit = RECORD_FIELD_COUNT)
        if (fields.size != RECORD_FIELD_COUNT) return null
        if (fields[SCHEMA_INDEX] != PASS_SCHEMA.toString()) return null
        val tokenId = fields[TOKEN_ID_INDEX].takeIf(TOKEN_ID_PATTERN::matches) ?: return null
        val challengeId = fields[CHALLENGE_ID_INDEX]
            .takeIf(CHALLENGE_ID_PATTERN::matches) ?: return null
        val lockSessionId = fields[LOCK_SESSION_ID_INDEX].toLongOrNull()
            ?.takeIf { it > 0L } ?: return null
        val actionKind = LockPendingActionKind.fromWireValue(fields[ACTION_KIND_INDEX])
            ?: return null
        val issuedAt = fields[ISSUED_AT_INDEX].toLongOrNull()
            ?.takeIf { it >= 0L } ?: return null
        val expiresAt = fields[EXPIRES_AT_INDEX].toLongOrNull()
            ?.takeIf { it > issuedAt && it - issuedAt == PASS_TTL_MILLIS } ?: return null
        val bootCount = fields[BOOT_COUNT_INDEX].toIntOrNull()
            ?.takeIf { it >= 0 } ?: return null
        val state = KnowledgePassState.fromWireValue(fields[STATE_INDEX]) ?: return null
        val reservationOrderId = fields[ORDER_ID_INDEX].let { rawOrderId ->
            when (state) {
                KnowledgePassState.AVAILABLE -> if (rawOrderId == NO_ORDER_ID) null else return null
                KnowledgePassState.RESERVED,
                KnowledgePassState.COMMITTED -> rawOrderId.takeIf(ORDER_ID_PATTERN::matches)
                    ?: return null
            }
        }
        val nonce = fields[NONCE_INDEX].takeIf(NONCE_PATTERN::matches) ?: return null
        val authenticationTag = fields[AUTH_TAG_INDEX]
            .takeIf(AUTH_TAG_PATTERN::matches) ?: return null
        val unsigned = UnsignedPass(
            tokenId = tokenId,
            challengeId = challengeId,
            binding = KnowledgeChallengeBinding(lockSessionId, actionKind),
            issuedAtElapsedMillis = issuedAt,
            expiresAtElapsedMillis = expiresAt,
            bootCount = bootCount,
            state = state,
            reservationOrderId = reservationOrderId,
            nonce = nonce
        )
        val expectedTag = authenticationTag.fromBase64Url() ?: return null
        if (!signer.verify(unsigned.canonicalPayload().toByteArray(Charsets.UTF_8), expectedTag)) {
            return null
        }
        return unsigned.toPass(authenticationTag)
    }

    private data class LoadedRecords(
        val validPasses: List<KnowledgeChallengePass>,
        val hadInvalidRecords: Boolean
    )

    private data class UnsignedPass(
        val tokenId: String,
        val challengeId: String,
        val binding: KnowledgeChallengeBinding,
        val issuedAtElapsedMillis: Long,
        val expiresAtElapsedMillis: Long,
        val bootCount: Int,
        val state: KnowledgePassState,
        val reservationOrderId: String?,
        val nonce: String
    ) {
        fun canonicalPayload(): String = listOf(
            PASS_SCHEMA,
            tokenId,
            challengeId,
            binding.lockSessionId,
            binding.actionKind.wireValue,
            issuedAtElapsedMillis,
            expiresAtElapsedMillis,
            bootCount,
            state.wireValue,
            reservationOrderId ?: NO_ORDER_ID,
            nonce
        ).joinToString(RECORD_SEPARATOR.toString())

        fun toPass(authenticationTag: String) = KnowledgeChallengePass(
            tokenId = tokenId,
            challengeId = challengeId,
            binding = binding,
            issuedAtElapsedMillis = issuedAtElapsedMillis,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
            bootCount = bootCount,
            state = state,
            reservationOrderId = reservationOrderId,
            nonce = nonce,
            authenticationTag = authenticationTag
        )
    }

    private fun KnowledgeChallengePass.toUnsigned(
        nextState: KnowledgePassState = state,
        nextOrderId: String? = reservationOrderId
    ) = UnsignedPass(
        tokenId = tokenId,
        challengeId = challengeId,
        binding = binding,
        issuedAtElapsedMillis = issuedAtElapsedMillis,
        expiresAtElapsedMillis = expiresAtElapsedMillis,
        bootCount = bootCount,
        state = nextState,
        reservationOrderId = nextOrderId,
        nonce = nonce
    )

    internal companion object {
        const val PASS_TTL_MILLIS = 2 * 60 * 1_000L
        private const val PASS_SCHEMA = 2
        private const val MAX_STORED_PASSES = 16
        private const val NONCE_BYTES = 16
        private const val MAX_RECORD_CHARS = 1_024
        private const val RECORD_SEPARATOR = '|'
        private const val RECORD_FIELD_COUNT = 12
        private const val NO_ORDER_ID = "-"
        private const val SCHEMA_INDEX = 0
        private const val TOKEN_ID_INDEX = 1
        private const val CHALLENGE_ID_INDEX = 2
        private const val LOCK_SESSION_ID_INDEX = 3
        private const val ACTION_KIND_INDEX = 4
        private const val ISSUED_AT_INDEX = 5
        private const val EXPIRES_AT_INDEX = 6
        private const val BOOT_COUNT_INDEX = 7
        private const val STATE_INDEX = 8
        private const val ORDER_ID_INDEX = 9
        private const val NONCE_INDEX = 10
        private const val AUTH_TAG_INDEX = 11
        private val CHALLENGE_ID_PATTERN = Regex("kc1-[a-f0-9]{24}")
        private val TOKEN_ID_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            RegexOption.IGNORE_CASE
        )
        private val ORDER_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,160}")
        private val NONCE_PATTERN = Regex("[A-Za-z0-9_-]{22}")
        private val AUTH_TAG_PATTERN = Regex("[A-Za-z0-9_-]{43}")
    }
}

internal interface KnowledgePassRecordStore {
    fun load(): Set<String>?
    fun replace(records: Set<String>): Boolean
}

internal class InMemoryKnowledgePassRecordStore : KnowledgePassRecordStore {
    private var records: Set<String> = emptySet()

    @Synchronized
    override fun load(): Set<String> = records.toSet()

    @Synchronized
    override fun replace(records: Set<String>): Boolean {
        this.records = records.toSet()
        return true
    }
}

internal fun interface KnowledgePassSigner {
    fun sign(payload: ByteArray): ByteArray?

    fun verify(payload: ByteArray, expectedTag: ByteArray): Boolean {
        val actual = sign(payload) ?: return false
        return MessageDigest.isEqual(actual, expectedTag)
    }
}

internal class HmacSha256KnowledgePassSigner(
    private val secretKey: SecretKey
) : KnowledgePassSigner {
    override fun sign(payload: ByteArray): ByteArray? = try {
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(secretKey)
            doFinal(payload)
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

internal class SharedPreferencesKnowledgePassRecordStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : KnowledgePassRecordStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): Set<String>? = try {
        preferences.getStringSet(KEY_RECORDS, emptySet())?.toSet()
    } catch (_: RuntimeException) {
        null
    }

    override fun replace(records: Set<String>): Boolean = try {
        preferences.edit().putStringSet(KEY_RECORDS, records.toSet()).commit()
    } catch (_: RuntimeException) {
        false
    }

    internal companion object {
        const val PREFERENCES_NAME = "control_free_knowledge_passes"
        private const val KEY_RECORDS = "signed_pass_records_v1"
    }
}

/** AndroidKeyStore 中独立于 DeepSeek API Key 的不可导出 HMAC 密钥。 */
internal class AndroidKeyStoreKnowledgePassSigner(
    private val keyAlias: String = KEY_ALIAS
) : KnowledgePassSigner {
    override fun sign(payload: ByteArray): ByteArray? = try {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(getOrCreateSecretKey())
        mac.doFinal(payload)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "control_free_knowledge_pass_hmac_v1"
    }
}

internal object AndroidKnowledgePassRepository {
    @Volatile
    private var instance: KnowledgePassRepository? = null

    fun getInstance(context: Context): KnowledgePassRepository =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): KnowledgePassRepository =
        SignedKnowledgePassRepository(
            recordStore = SharedPreferencesKnowledgePassRecordStore(context),
            signer = AndroidKeyStoreKnowledgePassSigner(),
            nowElapsedMillis = SystemClock::elapsedRealtime,
            currentBootCount = {
                try {
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.BOOT_COUNT,
                        -1
                    )
                } catch (_: RuntimeException) {
                    -1
                }
            }
        )
}

private fun ByteArray.toBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.fromBase64Url(): ByteArray? = try {
    Base64.getUrlDecoder().decode(this)
} catch (_: IllegalArgumentException) {
    null
}
