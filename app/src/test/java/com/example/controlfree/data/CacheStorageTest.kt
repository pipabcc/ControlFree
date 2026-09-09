package com.example.controlfree.data

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class CacheStorageTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val symbolicLinks = mutableListOf<Path>()

    @After
    fun tearDown() {
        symbolicLinks.asReversed().forEach { link -> Files.deleteIfExists(link) }
        temporaryRoots.asReversed().forEach { root ->
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `统计嵌套缓存并清理内容但保留根目录`() {
        val root = Files.createTempDirectory("controlfree-cache-test")
        temporaryRoots.add(root)
        root.resolve("nested").createDirectories()
        root.resolve("one.bin").writeText("12345")
        root.resolve("nested/two.bin").writeText("67890")

        val storage = CacheStorage(listOf(root.toFile()))

        assertEquals(10L, storage.calculateSize())
        assertTrue(storage.clear())
        assertTrue(Files.exists(root))
        assertEquals(0L, storage.calculateSize())
    }

    @Test
    fun `不存在的缓存目录视为已清理`() {
        val root = Files.createTempDirectory("controlfree-cache-missing")
        temporaryRoots.add(root)
        root.toFile().deleteRecursively()

        val storage = CacheStorage(listOf(root.toFile()))

        assertEquals(0L, storage.calculateSize())
        assertTrue(storage.clear())
    }

    @Test
    fun `清理缓存不会触碰根目录外的普通文件`() {
        val root = temporaryDirectory("controlfree-cache-root")
        val sibling = temporaryDirectory("controlfree-cache-sibling")
        root.resolve("cache.bin").writeText("cache")
        val persistentFile = sibling.resolve("persistent.bin")
        persistentFile.writeText("keep")

        val storage = CacheStorage(listOf(root.toFile()))

        assertTrue(storage.clear())
        assertTrue(Files.exists(persistentFile))
        assertEquals("keep", persistentFile.toFile().readText())
    }

    @Test
    fun `缓存目录中的符号链接只删除链接而不遍历目标`() {
        val root = temporaryDirectory("controlfree-cache-link-root")
        val outside = temporaryDirectory("controlfree-cache-link-target")
        val persistentFile = outside.resolve("persistent.bin")
        persistentFile.writeText("do-not-delete")
        val link = root.resolve("outside-link")
        createSymbolicLinkOrSkip(link, outside)
        symbolicLinks.add(link)
        val storage = CacheStorage(listOf(root.toFile()))

        assertEquals(0L, storage.calculateSize())
        assertTrue(storage.clear())
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(persistentFile))
        assertEquals("do-not-delete", persistentFile.toFile().readText())
    }

    private fun temporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(temporaryRoots::add)

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (error: Exception) {
            assumeNoException(error)
        }
    }
}
