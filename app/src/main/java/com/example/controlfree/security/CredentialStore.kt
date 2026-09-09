package com.example.controlfree.security

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.ceil

enum class VerificationStatus {
    SUCCESS,
    FAILURE,
    LOCKED
}

data class VerificationResult(
    val status: VerificationStatus,
    val retryAfterSeconds: Int = 0
) {
    val isSuccess: Boolean
        get() = status == VerificationStatus.SUCCESS
}

enum class CredentialRemovalResult {
    REMOVED,
    NOT_CONFIGURED,
    LAST_CREDENTIAL
}

class CredentialStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun verifyPassword(password: String): VerificationResult = synchronized(CREDENTIAL_STATE_LOCK) {
        val legacyPassword = readString(LEGACY_PASSWORD).orEmpty()
        val hasCurrentHash = readStoredSecret(PASSWORD_SALT, PASSWORD_HASH) != null
        if (!hasCurrentHash && isValidLegacyPassword(legacyPassword)) {
            val remainingSeconds = getLockoutRemainingSeconds()
            if (remainingSeconds > 0) {
                return VerificationResult(VerificationStatus.LOCKED, remainingSeconds)
            }
            if (password == legacyPassword) {
                // 旧版明文只在后台验证任务中迁移，禁止构造 CredentialStore 时在主线程运行 PBKDF2。
                setPassword(password)
                return VerificationResult(VerificationStatus.SUCCESS)
            }
            return recordFailure(System.currentTimeMillis())
        }
        if (hasCurrentHash && legacyPassword.isNotBlank()) {
            // 新哈希已经是权威凭据，旧明文无需参与验证即可安全删除。
            prefs.edit().remove(LEGACY_PASSWORD).putBoolean(INITIAL_SETUP_REQUIRED, false).apply()
        }
        val result = verifySecret(password, PASSWORD_SALT, PASSWORD_HASH)
        if (result.isSuccess && getPasswordLength() == null) {
            // v2 只保存了不可逆哈希，首次成功验证后才能安全补齐长度元数据。
            prefs.edit().putInt(PASSWORD_LENGTH, password.length).apply()
        }
        result
    }

    fun setPassword(password: String) = synchronized(CREDENTIAL_STATE_LOCK) {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "应急密码至少需要 $MIN_PASSWORD_LENGTH 位"
        }
        require(password.length <= MAX_PASSWORD_LENGTH) {
            "应急密码最多支持 $MAX_PASSWORD_LENGTH 位"
        }
        require(password.all { it in '0'..'9' }) {
            "应急密码只能包含数字"
        }
        val hashed = SecretHasher.create(password)
        prefs.edit()
            .putString(PASSWORD_SALT, hashed.saltBase64)
            .putString(PASSWORD_HASH, hashed.hashBase64)
            .putInt(PASSWORD_LENGTH, password.length)
            .putBoolean(INITIAL_SETUP_REQUIRED, false)
            .remove(LEGACY_PASSWORD)
            .remove(FAILED_ATTEMPTS)
            .remove(LOCKOUT_UNTIL)
            .apply()
    }

    fun hasPassword(): Boolean =
        readStoredSecret(PASSWORD_SALT, PASSWORD_HASH) != null ||
            isValidLegacyPassword(readString(LEGACY_PASSWORD).orEmpty())

    fun clearPassword(): CredentialRemovalResult = synchronized(CREDENTIAL_STATE_LOCK) {
        if (!hasPassword()) return@synchronized CredentialRemovalResult.NOT_CONFIGURED

        check(
            prefs.edit()
                .remove(PASSWORD_SALT)
                .remove(PASSWORD_HASH)
                .remove(PASSWORD_LENGTH)
                .remove(LEGACY_PASSWORD)
                .remove(INITIAL_SETUP_REQUIRED)
                .commit()
        ) { "无法移除数字密码" }
        CredentialRemovalResult.REMOVED
    }

    fun hasAnyCredential(): Boolean = synchronized(CREDENTIAL_STATE_LOCK) {
        hasPassword() || hasGesture()
    }

    /** 只有没有任何可验证凭据时才允许进入初始化流程，不能用偏好标记绕过已有凭据。 */
    fun requiresInitialPasswordSetup(): Boolean = !hasAnyCredential()

    /**
     * 返回拨号键盘自动提交所需的密码长度。
     *
     * 已升级但尚未成功验证过的 v2 哈希没有该信息，此时返回 null，由界面提供一次性确认键。
     */
    fun getPasswordLength(): Int? {
        val storedLength = readIntOrDefault(PASSWORD_LENGTH, defaultValue = 0)
            .takeIf { it in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH }
        if (storedLength != null && readStoredSecret(PASSWORD_SALT, PASSWORD_HASH) != null) {
            return storedLength
        }
        return readString(LEGACY_PASSWORD)
            ?.takeIf { isValidLegacyPassword(it) }
            ?.length
    }

    fun hasGesture(): Boolean = readStoredSecret(GESTURE_SALT, GESTURE_HASH) != null

    fun verifyGesture(pattern: List<Int>): VerificationResult = synchronized(CREDENTIAL_STATE_LOCK) {
        verifySecret(pattern.toPatternSecret(), GESTURE_SALT, GESTURE_HASH)
    }

    fun setGesture(pattern: List<Int>) = synchronized(CREDENTIAL_STATE_LOCK) {
        require(pattern.distinct().size >= MIN_GESTURE_POINTS) {
            "手势密码至少连接 $MIN_GESTURE_POINTS 个点"
        }
        saveSecret(pattern.toPatternSecret(), GESTURE_SALT, GESTURE_HASH)
        clearFailures()
    }

    fun clearGesture(): CredentialRemovalResult = synchronized(CREDENTIAL_STATE_LOCK) {
        if (!hasGesture()) return@synchronized CredentialRemovalResult.NOT_CONFIGURED

        check(prefs.edit().remove(GESTURE_SALT).remove(GESTURE_HASH).commit()) {
            "无法移除手势密码"
        }
        CredentialRemovalResult.REMOVED
    }

    fun getLockoutRemainingSeconds(nowMillis: Long = System.currentTimeMillis()): Int {
        val remainingMillis = readLongOrDefault(LOCKOUT_UNTIL, 0L) - nowMillis
        return if (remainingMillis <= 0L) 0 else ceil(remainingMillis / 1_000.0).toInt()
    }

    private fun readStoredSecret(saltKey: String, hashKey: String): SecretHash? {
        val salt = readString(saltKey)?.takeIf { it.isNotBlank() } ?: return null
        val hash = readString(hashKey)?.takeIf { it.isNotBlank() } ?: return null
        return SecretHash(saltBase64 = salt, hashBase64 = hash)
            .takeIf { SecretHasher.isStructurallyValid(it) }
    }

    private fun readString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    private fun readIntOrDefault(key: String, defaultValue: Int): Int = try {
        prefs.getInt(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }

    private fun readLongOrDefault(key: String, defaultValue: Long): Long = try {
        prefs.getLong(key, defaultValue)
    } catch (_: ClassCastException) {
        defaultValue
    }

    private fun isValidLegacyPassword(password: String): Boolean =
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH &&
            password.all { it in '0'..'9' }

    private fun verifySecret(
        secret: String,
        saltKey: String,
        hashKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): VerificationResult {
        val remainingSeconds = getLockoutRemainingSeconds(nowMillis)
        if (remainingSeconds > 0) {
            return VerificationResult(VerificationStatus.LOCKED, remainingSeconds)
        }

        val stored = readStoredSecret(saltKey, hashKey)
            ?: return recordFailure(nowMillis)
        return if (SecretHasher.verify(secret, stored)) {
            clearFailures()
            VerificationResult(VerificationStatus.SUCCESS)
        } else {
            recordFailure(nowMillis)
        }
    }

    private fun recordFailure(nowMillis: Long): VerificationResult {
        val failures = readIntOrDefault(FAILED_ATTEMPTS, 0) + 1
        return if (failures >= MAX_FAILED_ATTEMPTS) {
            prefs.edit()
                .putInt(FAILED_ATTEMPTS, 0)
                .putLong(LOCKOUT_UNTIL, nowMillis + LOCKOUT_MILLIS)
                .apply()
            VerificationResult(
                status = VerificationStatus.LOCKED,
                retryAfterSeconds = (LOCKOUT_MILLIS / 1_000L).toInt()
            )
        } else {
            prefs.edit().putInt(FAILED_ATTEMPTS, failures).apply()
            VerificationResult(VerificationStatus.FAILURE)
        }
    }

    private fun clearFailures() {
        prefs.edit().remove(FAILED_ATTEMPTS).remove(LOCKOUT_UNTIL).apply()
    }

    private fun saveSecret(secret: String, saltKey: String, hashKey: String) {
        val hashed = SecretHasher.create(secret)
        prefs.edit()
            .putString(saltKey, hashed.saltBase64)
            .putString(hashKey, hashed.hashBase64)
            .putBoolean(INITIAL_SETUP_REQUIRED, false)
            .apply()
    }

    private fun List<Int>.toPatternSecret(): String = distinct().joinToString(separator = "-")

    companion object {
        /** 所有凭据实例共享同一把锁，避免多入口并发验证丢失失败次数。 */
        private val CREDENTIAL_STATE_LOCK = Any()
        private const val PREFS_NAME = "control_free_prefs"
        private const val LEGACY_PASSWORD = "passcode"
        private const val PASSWORD_SALT = "password_salt_v2"
        private const val PASSWORD_HASH = "password_hash_v2"
        private const val PASSWORD_LENGTH = "password_length_v3"
        private const val GESTURE_SALT = "gesture_salt_v2"
        private const val GESTURE_HASH = "gesture_hash_v2"
        private const val INITIAL_SETUP_REQUIRED = "initial_password_setup_required"
        private const val FAILED_ATTEMPTS = "authentication_failed_attempts"
        private const val LOCKOUT_UNTIL = "authentication_lockout_until"

        const val MIN_PASSWORD_LENGTH = 4
        const val MAX_PASSWORD_LENGTH = 64
        const val MIN_GESTURE_POINTS = 4
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_MILLIS = 30_000L
    }
}
