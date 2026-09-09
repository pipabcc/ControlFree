package com.example.controlfree.diagnostics

enum class DiagnosticLevel {
    INFO,
    WARNING,
    ERROR
}

/**
 * 诊断事件只允许使用固定编号，避免把异常文本、密码或其他自由文本写入磁盘。
 */
enum class DiagnosticEventType(
    internal val persistedCode: Int,
    val level: DiagnosticLevel
) {
    APP_PROCESS_STARTED(1, DiagnosticLevel.INFO),
    APP_PROCESS_STOPPED(2, DiagnosticLevel.INFO),
    SERVICE_CREATED(3, DiagnosticLevel.INFO),
    SERVICE_DESTROYED(4, DiagnosticLevel.INFO),
    MONITOR_STARTED(10, DiagnosticLevel.INFO),
    MONITOR_STOPPED(11, DiagnosticLevel.INFO),
    SERVICE_INITIALIZATION_FAILED(12, DiagnosticLevel.ERROR),
    FOREGROUND_SERVICE_FAILED(13, DiagnosticLevel.ERROR),
    LOCK_PHASE_STARTED(20, DiagnosticLevel.INFO),
    LOCK_PHASE_FINISHED(21, DiagnosticLevel.INFO),
    LOCK_SURFACE_SHOWN(22, DiagnosticLevel.INFO),
    LOCK_SURFACE_HIDDEN(23, DiagnosticLevel.INFO),
    USAGE_QUERY_SUCCEEDED(30, DiagnosticLevel.INFO),
    USAGE_QUERY_TIMED_OUT(31, DiagnosticLevel.WARNING),
    USAGE_CIRCUIT_OPENED(32, DiagnosticLevel.WARNING),
    USAGE_WORKER_REPLACED(33, DiagnosticLevel.WARNING),
    USAGE_QUERY_FAILED(34, DiagnosticLevel.ERROR),
    ALLOWLIST_REFRESH_SUCCEEDED(40, DiagnosticLevel.INFO),
    ALLOWLIST_REFRESH_PARTIAL(41, DiagnosticLevel.WARNING),
    ALLOWLIST_REFRESH_FAILED(42, DiagnosticLevel.ERROR),
    ALLOWLIST_LAUNCH_FAILED(43, DiagnosticLevel.WARNING),
    ALLOWLIST_LAUNCH_SUCCEEDED(44, DiagnosticLevel.INFO),
    MEDIA_PLAYBACK_DETECTED(50, DiagnosticLevel.WARNING),
    MEDIA_PAUSE_DISPATCHED(51, DiagnosticLevel.INFO),
    MEDIA_REPLAY_DETECTED(52, DiagnosticLevel.WARNING),
    SNAPSHOT_WRITE_SUCCEEDED(60, DiagnosticLevel.INFO),
    SNAPSHOT_WRITE_FAILED(61, DiagnosticLevel.ERROR),
    SNAPSHOT_RESTORE_SUCCEEDED(62, DiagnosticLevel.INFO),
    SNAPSHOT_RESTORE_FAILED(63, DiagnosticLevel.ERROR),
    RECOVERY_GUARD_TRIGGERED(70, DiagnosticLevel.WARNING),
    BOOT_RECOVERY_RECEIVED(71, DiagnosticLevel.INFO),
    BOOT_RECOVERY_START_FAILED(72, DiagnosticLevel.ERROR),
    SELF_CHECK_STARTED(80, DiagnosticLevel.INFO),
    SELF_CHECK_COMPLETED(81, DiagnosticLevel.INFO);

    internal companion object {
        private val byPersistedCode = entries.associateBy(DiagnosticEventType::persistedCode)

        fun fromPersistedCode(code: Int): DiagnosticEventType? = byPersistedCode[code]
    }
}

class PackageNameToken private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PackageNameToken && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private val TOKEN_PATTERN = Regex("[0-9a-f]{$PERSISTED_TOKEN_CHARACTER_COUNT}")

        internal fun fromDigest(digest: ByteArray): PackageNameToken {
            require(digest.size == HMAC_SHA256_BYTE_COUNT)
            return PackageNameToken(digest.take(PERSISTED_TOKEN_BYTE_COUNT).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            })
        }

        internal fun parse(value: String): PackageNameToken? =
            value.takeIf(TOKEN_PATTERN::matches)?.let(::PackageNameToken)

        private const val HMAC_SHA256_BYTE_COUNT = 32
        private const val PERSISTED_TOKEN_BYTE_COUNT = 12
        private const val PERSISTED_TOKEN_CHARACTER_COUNT = PERSISTED_TOKEN_BYTE_COUNT * 2
    }
}

data class DiagnosticRecord(
    val occurredAtEpochMillis: Long,
    val type: DiagnosticEventType,
    val packageToken: PackageNameToken?
)

enum class DiagnosticWriteResult {
    WRITTEN,
    INVALID_TIMESTAMP,
    INVALID_PACKAGE_NAME,
    TOKENIZATION_FAILED,
    STORAGE_FAILURE,
    CLOSED
}

enum class DiagnosticClearResult {
    CLEARED,
    STORAGE_FAILURE,
    CLOSED
}

data class DiagnosticReadResult(
    val records: List<DiagnosticRecord>,
    val skippedRecordCount: Int,
    val inaccessibleFileCount: Int,
    val isClosed: Boolean = false
)
