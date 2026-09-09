package com.example.controlfree.security

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.ceil

/**
 * 使用的独立数字密码。
 *
 * 该凭据与 App 解锁密码分仓保存、独立限流，避免任一功能的失败次数或改密操作影响另一方。
 */
class QuestionBankUpgradeCredentialStore internal constructor(
    private val preferences: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    @Synchronized
    fun setPassword(password: String) {
        require(password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "题库升级密码必须为 $MIN_PASSWORD_LENGTH 到 $MAX_PASSWORD_LENGTH 位"
        }
        require(password.all { it in '0'..'9' }) { "题库升级密码只能包含数字" }

        val hash = SecretHasher.create(password)
        check(
            preferences.edit()
                .putString(PASSWORD_SALT, hash.saltBase64)
                .putString(PASSWORD_HASH, hash.hashBase64)
                .putInt(PASSWORD_LENGTH, password.length)
                .remove(FAILED_ATTEMPTS)
                .remove(LOCKOUT_UNTIL)
                .commit()
        ) { "无法保存题库升级密码" }
    }

    fun isConfigured(): Boolean =
        preferences.contains(PASSWORD_SALT) && preferences.contains(PASSWORD_HASH)

    fun passwordLength(): Int? = preferences.getInt(PASSWORD_LENGTH, 0)
        .takeIf { it in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH }

    @Synchronized
    fun verifyPassword(
        password: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): VerificationResult {
        val remainingSeconds = lockoutRemainingSeconds(nowEpochMillis)
        if (remainingSeconds > 0) {
            return VerificationResult(VerificationStatus.LOCKED, remainingSeconds)
        }
        if (!isConfigured()) return VerificationResult(VerificationStatus.FAILURE)

        val hash = SecretHash(
            saltBase64 = preferences.getString(PASSWORD_SALT, "").orEmpty(),
            hashBase64 = preferences.getString(PASSWORD_HASH, "").orEmpty()
        )
        return if (SecretHasher.verify(password, hash)) {
            clearFailures()
            VerificationResult(VerificationStatus.SUCCESS)
        } else {
            recordFailure(nowEpochMillis)
        }
    }

    fun lockoutRemainingSeconds(nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val remainingMillis = preferences.getLong(LOCKOUT_UNTIL, 0L) - nowEpochMillis
        return if (remainingMillis <= 0L) 0 else ceil(remainingMillis / 1_000.0).toInt()
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "无法清除题库升级密码" }
    }

    private fun recordFailure(nowEpochMillis: Long): VerificationResult {
        val failures = preferences.getInt(FAILED_ATTEMPTS, 0) + 1
        return if (failures >= MAX_FAILED_ATTEMPTS) {
            preferences.edit()
                .putInt(FAILED_ATTEMPTS, 0)
                .putLong(LOCKOUT_UNTIL, nowEpochMillis + LOCKOUT_MILLIS)
                .apply()
            VerificationResult(
                status = VerificationStatus.LOCKED,
                retryAfterSeconds = (LOCKOUT_MILLIS / 1_000L).toInt()
            )
        } else {
            preferences.edit().putInt(FAILED_ATTEMPTS, failures).apply()
            VerificationResult(VerificationStatus.FAILURE)
        }
    }

    private fun clearFailures() {
        preferences.edit().remove(FAILED_ATTEMPTS).remove(LOCKOUT_UNTIL).apply()
    }

    internal companion object {
        private const val PREFERENCES_NAME = "question_bank_upgrade_credentials"
        private const val PASSWORD_SALT = "password_salt_v1"
        private const val PASSWORD_HASH = "password_hash_v1"
        private const val PASSWORD_LENGTH = "password_length_v1"
        private const val FAILED_ATTEMPTS = "failed_attempts"
        private const val LOCKOUT_UNTIL = "lockout_until"

        const val MIN_PASSWORD_LENGTH = 6
        const val MAX_PASSWORD_LENGTH = 12
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 30_000L
    }
}
