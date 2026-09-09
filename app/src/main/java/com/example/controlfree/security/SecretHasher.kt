package com.example.controlfree.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class SecretHash(
    val saltBase64: String,
    val hashBase64: String
)

object SecretHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    fun create(secret: String): SecretHash {
        require(secret.isNotEmpty()) { "凭据不能为空" }
        val salt = ByteArray(SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val derived = derive(secret, salt)
        return SecretHash(
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            hashBase64 = Base64.getEncoder().encodeToString(derived)
        )
    }

    fun verify(secret: String, stored: SecretHash): Boolean {
        if (secret.isEmpty()) return false
        return try {
            val salt = Base64.getDecoder().decode(stored.saltBase64)
            val expected = Base64.getDecoder().decode(stored.hashBase64)
            MessageDigest.isEqual(expected, derive(secret, salt))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * 检查持久化哈希是否具备可验证所需的完整结构。
     *
     * 仅存在 SharedPreferences 键并不代表凭据有效；损坏、空值或被截断的值必须被视为未配置，
     * 否则设置页会允许开启一个永远无法通过的验证开关。
     */
    fun isStructurallyValid(stored: SecretHash): Boolean {
        if (stored.saltBase64.isBlank() || stored.hashBase64.isBlank()) return false
        return try {
            val salt = Base64.getDecoder().decode(stored.saltBase64)
            val hash = Base64.getDecoder().decode(stored.hashBase64)
            salt.size == SALT_LENGTH_BYTES && hash.size == KEY_LENGTH_BITS / 8
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
