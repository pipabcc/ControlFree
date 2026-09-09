package com.example.controlfree.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun interface PackageNameTokenizer {
    fun tokenize(packageName: String): PackageNameToken?
}

/**
 * 密钥只保存在 noBackup 私有目录，卸载重装后会自然变化，备份恢复也不会复用旧令牌。
 */
internal class InstallationHmacPackageNameTokenizer(
    private val keyFile: File,
    private val secureRandom: SecureRandom = SecureRandom()
) : PackageNameTokenizer {
    override fun tokenize(packageName: String): PackageNameToken? {
        if (packageName.isBlank()) return null
        val key = loadOrCreateKey() ?: return null
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
            PackageNameToken.fromDigest(mac.doFinal(packageName.toByteArray(Charsets.UTF_8)))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun loadOrCreateKey(): ByteArray? = synchronized(KEY_CREATION_LOCK) {
        readValidKey()?.let { return it }
        val directory = keyFile.parentFile ?: return null
        if (!directory.exists() && !directory.mkdirs()) return null
        val generatedKey = ByteArray(KEY_BYTE_COUNT).also(secureRandom::nextBytes)
        val temporaryFile = File(directory, "${keyFile.name}.tmp")
        try {
            FileOutputStream(temporaryFile, false).use { stream ->
                stream.write(generatedKey)
                stream.fd.sync()
            }
            restrictToOwner(temporaryFile)
            if (keyFile.exists() && !keyFile.delete()) return null
            if (!temporaryFile.renameTo(keyFile)) return null
            restrictToOwner(keyFile)
            generatedKey
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    private fun readValidKey(): ByteArray? = try {
        if (!keyFile.isFile || keyFile.length() != KEY_BYTE_COUNT.toLong()) {
            null
        } else {
            keyFile.readBytes().takeIf { it.size == KEY_BYTE_COUNT }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val KEY_BYTE_COUNT = 32
        val KEY_CREATION_LOCK = Any()
    }
}
