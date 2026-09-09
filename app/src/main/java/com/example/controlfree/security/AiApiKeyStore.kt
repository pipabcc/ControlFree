package com.example.controlfree.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface AiApiKeyReadResult {
    data class Available(val apiKey: String) : AiApiKeyReadResult
    data object Missing : AiApiKeyReadResult
    data object StorageUnavailable : AiApiKeyReadResult
}

internal enum class AiApiKeyWriteResult {
    SAVED,
    INVALID_KEY,
    STORAGE_UNAVAILABLE
}

/**
 * 使用应用专属 AndroidKeyStore 密钥加密 DeepSeek API Key。
 *
 * 密钥不可导出，SharedPreferences 只保存 AES-GCM 的随机 IV 与密文。任何损坏或
 * KeyStore 异常都会失败关闭，绝不回退为明文保存。
 */
internal class AiApiKeyStore internal constructor(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = PREFERENCES_NAME
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun save(candidateKey: String): AiApiKeyWriteResult {
        val apiKey = AiApiKeyValidator.normalize(candidateKey)
            ?: return AiApiKeyWriteResult.INVALID_KEY
        val plaintext = apiKey.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            cipher.updateAAD(ASSOCIATED_DATA)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size !in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES || ciphertext.isEmpty()) {
                AiApiKeyWriteResult.STORAGE_UNAVAILABLE
            } else {
                val committed = preferences.edit()
                    .putInt(KEY_SCHEMA_VERSION, STORAGE_SCHEMA_VERSION)
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit()
                if (committed) AiApiKeyWriteResult.SAVED
                else AiApiKeyWriteResult.STORAGE_UNAVAILABLE
            }
        } catch (_: GeneralSecurityException) {
            AiApiKeyWriteResult.STORAGE_UNAVAILABLE
        } catch (_: RuntimeException) {
            AiApiKeyWriteResult.STORAGE_UNAVAILABLE
        } catch (_: Exception) {
            AiApiKeyWriteResult.STORAGE_UNAVAILABLE
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun read(): AiApiKeyReadResult {
        val metadata = try {
            val hasAnyStoredValue = preferences.contains(KEY_SCHEMA_VERSION) ||
                preferences.contains(KEY_IV) ||
                preferences.contains(KEY_CIPHERTEXT)
            if (!hasAnyStoredValue) return AiApiKeyReadResult.Missing
            StoredMetadata(
                version = preferences.getInt(KEY_SCHEMA_VERSION, -1),
                encodedIv = preferences.getString(KEY_IV, null),
                encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
            )
        } catch (_: RuntimeException) {
            clearCorruptedValues()
            return AiApiKeyReadResult.StorageUnavailable
        }
        val version = metadata.version
        val encodedIv = metadata.encodedIv
        val encodedCiphertext = metadata.encodedCiphertext
        if (
            version != STORAGE_SCHEMA_VERSION ||
            encodedIv.isNullOrBlank() ||
            encodedCiphertext.isNullOrBlank()
        ) {
            clearCorruptedValues()
            return AiApiKeyReadResult.StorageUnavailable
        }

        return try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            if (
                iv.size !in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES ||
                ciphertext.isEmpty() ||
                ciphertext.size > MAX_CIPHERTEXT_BYTES
            ) {
                clearCorruptedValues()
                return AiApiKeyReadResult.StorageUnavailable
            }
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            val plaintext = cipher.doFinal(ciphertext)
            try {
                val apiKey = plaintext.toString(Charsets.UTF_8)
                if (AiApiKeyValidator.normalize(apiKey) == apiKey) {
                    AiApiKeyReadResult.Available(apiKey)
                } else {
                    clearCorruptedValues()
                    AiApiKeyReadResult.StorageUnavailable
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (_: GeneralSecurityException) {
            clearCorruptedValues()
            AiApiKeyReadResult.StorageUnavailable
        } catch (_: IllegalArgumentException) {
            clearCorruptedValues()
            AiApiKeyReadResult.StorageUnavailable
        } catch (_: RuntimeException) {
            clearCorruptedValues()
            AiApiKeyReadResult.StorageUnavailable
        } catch (_: Exception) {
            clearCorruptedValues()
            AiApiKeyReadResult.StorageUnavailable
        }
    }

    fun hasSavedKey(): Boolean = try {
        preferences.contains(KEY_IV) && preferences.contains(KEY_CIPHERTEXT)
    } catch (_: RuntimeException) {
        false
    }

    @Synchronized
    fun clear(): Boolean = try {
        preferences.edit()
            .remove(KEY_SCHEMA_VERSION)
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(AES_KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadSecretKey(): SecretKey =
        (loadKeyStore().getKey(keyAlias, null) as? SecretKey)
            ?: throw GeneralSecurityException("AI API Key encryption key is unavailable")

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    private fun clearCorruptedValues() {
        try {
            preferences.edit()
                .remove(KEY_SCHEMA_VERSION)
                .remove(KEY_IV)
                .remove(KEY_CIPHERTEXT)
                .commit()
        } catch (_: RuntimeException) {
            // 存储不可用时保持失败关闭；调用方不会拿到密钥。
        }
    }

    private data class StoredMetadata(
        val version: Int,
        val encodedIv: String?,
        val encodedCiphertext: String?
    )

    companion object {
        internal const val PREFERENCES_NAME = "control_free_ai_secrets"
        internal const val DEFAULT_KEY_ALIAS = "control_free_deepseek_api_key_v1"

        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val STORAGE_SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_IV = "api_key_iv"
        private const val KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val MIN_GCM_IV_BYTES = 12
        private const val MAX_GCM_IV_BYTES = 16
        private const val MAX_CIPHERTEXT_BYTES = 1_024
        private val ASSOCIATED_DATA =
            "com.example.controlfree|deepseek-api-key|v1".toByteArray(Charsets.UTF_8)

        @Volatile
        private var instance: AiApiKeyStore? = null

        fun getInstance(context: Context): AiApiKeyStore =
            instance ?: synchronized(this) {
                instance ?: AiApiKeyStore(context.applicationContext).also { instance = it }
            }
    }
}

internal object AiApiKeyValidator {
    fun normalize(candidateKey: String): String? {
        val normalized = candidateKey.trim()
        if (normalized.length !in MIN_KEY_LENGTH..MAX_KEY_LENGTH) return null
        if (!normalized.startsWith(KEY_PREFIX)) return null
        if (normalized.any { character -> character.code !in ASCII_VISIBLE_RANGE }) return null
        return normalized
    }

    private const val KEY_PREFIX = "sk-"
    private const val MIN_KEY_LENGTH = 16
    private const val MAX_KEY_LENGTH = 256
    private val ASCII_VISIBLE_RANGE = 0x21..0x7e
}
