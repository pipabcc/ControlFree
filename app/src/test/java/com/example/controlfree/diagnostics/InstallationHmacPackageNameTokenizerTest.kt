package com.example.controlfree.diagnostics

import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationHmacPackageNameTokenizerTest {
    private val temporaryDirectories = mutableListOf<java.io.File>()

    @After
    fun tearDown() {
        temporaryDirectories.forEach { directory -> directory.deleteRecursively() }
    }

    @Test
    fun `使用安装密钥生成标准 HMAC SHA256 令牌`() {
        val directory = newTemporaryDirectory()
        val key = ByteArray(32) { index -> index.toByte() }
        val keyFile = directory.resolve("install.key").apply { writeBytes(key) }
        val tokenizer = InstallationHmacPackageNameTokenizer(keyFile)

        val token = tokenizer.tokenize("com.example.video")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val fullDigest = mac.doFinal("com.example.video".toByteArray(Charsets.UTF_8))
        val expected = PackageNameToken.fromDigest(fullDigest)
        val expectedPrefix = fullDigest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }.take(24)
        assertEquals(expected, token)
        assertEquals(expectedPrefix, token?.value)
        assertEquals(24, token?.value?.length)
        assertFalse(token?.value.orEmpty().contains("com.example.video"))
    }

    @Test
    fun `同一次安装令牌稳定且不同安装令牌不同`() {
        val firstDirectory = newTemporaryDirectory()
        val secondDirectory = newTemporaryDirectory()
        val firstKeyFile = firstDirectory.resolve("install.key")
        val secondKeyFile = secondDirectory.resolve("install.key")

        val firstToken = InstallationHmacPackageNameTokenizer(firstKeyFile)
            .tokenize("com.example.player")
        val reloadedToken = InstallationHmacPackageNameTokenizer(firstKeyFile)
            .tokenize("com.example.player")
        val secondInstallToken = InstallationHmacPackageNameTokenizer(secondKeyFile)
            .tokenize("com.example.player")

        assertNotNull(firstToken)
        assertEquals(firstToken, reloadedToken)
        assertNotEquals(firstToken, secondInstallToken)
        assertEquals(32L, firstKeyFile.length())
        assertEquals(32L, secondKeyFile.length())
    }

    @Test
    fun `已存在的安装密钥不会被覆盖`() {
        val directory = newTemporaryDirectory()
        val originalKey = ByteArray(32) { 7 }
        val keyFile = directory.resolve("install.key").apply { writeBytes(originalKey) }

        InstallationHmacPackageNameTokenizer(keyFile).tokenize("com.example.app")

        assertArrayEquals(originalKey, keyFile.readBytes())
    }

    @Test
    fun `空包名和不可用密钥目录安全失败`() {
        val directory = newTemporaryDirectory()
        val tokenizer = InstallationHmacPackageNameTokenizer(directory.resolve("install.key"))
        assertNull(tokenizer.tokenize("  "))

        val parentFile = directory.resolve("not-a-directory").apply { writeText("occupied") }
        val unavailableTokenizer = InstallationHmacPackageNameTokenizer(
            parentFile.resolve("install.key")
        )
        assertNull(unavailableTokenizer.tokenize("com.example.app"))
        assertTrue(parentFile.isFile)
    }

    private fun newTemporaryDirectory(): java.io.File =
        Files.createTempDirectory("controlfree-tokenizer-").toFile().also(temporaryDirectories::add)
}
