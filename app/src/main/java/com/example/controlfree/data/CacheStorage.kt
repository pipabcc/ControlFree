package com.example.controlfree.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 只管理应用缓存目录，并清理已废弃的 AI 建议缓存；不触碰 Room 数据或恢复标记。
 * 目录遍历和删除由调用方放到 IO 调度器执行。
 */
class CacheStorage internal constructor(
    roots: List<File>,
    private val additionalCacheClearer: () -> Boolean = { true }
) {
    private val roots = roots.distinctBy { root ->
        root.toPath().toAbsolutePath().normalize().toString()
    }

    constructor(context: Context) : this(
        roots = listOfNotNull(context.cacheDir, context.externalCacheDir),
        additionalCacheClearer = {
            CacheStorage.clearLegacyAiAdviceCache(context.applicationContext)
        }
    )

    fun calculateSize(): Long = roots.fold(0L) { total, root ->
        saturatedAdd(total, cacheTreeSize(root.toPath()))
    }

    /** 清空缓存目录下的内容并保留目录本身，返回是否全部删除成功。 */
    fun clear(): Boolean {
        val rootsCleared = roots.fold(true) { allCleared, root ->
            clearRoot(root.toPath()) && allCleared
        }
        val additionalCachesCleared = try {
            additionalCacheClearer()
        } catch (_: RuntimeException) {
            false
        }
        return rootsCleared && additionalCachesCleared
    }

    private fun cacheTreeSize(root: Path): Long {
        val trustedRoot = trustedRoot(root) ?: return 0L
        var total = 0L
        return try {
            Files.walkFileTree(root.toAbsolutePath().normalize(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult = if (isContained(trustedRoot, directory)) {
                    FileVisitResult.CONTINUE
                } else {
                    FileVisitResult.SKIP_SUBTREE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    if (
                        isContained(trustedRoot, file) &&
                        attributes.isRegularFile &&
                        !attributes.isSymbolicLink
                    ) {
                        total = saturatedAdd(total, attributes.size().coerceAtLeast(0L))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
            total
        } catch (_: IOException) {
            total
        } catch (_: SecurityException) {
            total
        }
    }

    private fun clearRoot(root: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return true
        val trustedRoot = trustedRoot(normalizedRoot) ?: return false
        var success = true
        return try {
            Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    if (!isContained(trustedRoot, directory)) {
                        success = false
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    if (!isContained(trustedRoot, file) || !deletePath(file)) success = false
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    success = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: IOException?
                ): FileVisitResult {
                    if (error != null) success = false
                    if (
                        directory != normalizedRoot &&
                        (!isContained(trustedRoot, directory) || !deletePath(directory))
                    ) {
                        success = false
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            success
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun trustedRoot(root: Path): Path? = try {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (
            Files.isSymbolicLink(normalizedRoot) ||
            !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            null
        } else {
            normalizedRoot.toRealPath()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun isContained(trustedRoot: Path, candidate: Path): Boolean = try {
        candidate.toAbsolutePath()
            .normalize()
            .toRealPath(LinkOption.NOFOLLOW_LINKS)
            .startsWith(trustedRoot)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun deletePath(path: Path): Boolean = try {
        Files.deleteIfExists(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        internal const val LEGACY_AI_ADVICE_CACHE_PREFERENCES =
            "control_free_ai_advice_cache"

        internal fun clearLegacyAiAdviceCache(context: Context): Boolean = try {
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences(
                LEGACY_AI_ADVICE_CACHE_PREFERENCES,
                Context.MODE_PRIVATE
            )
            val hadEntries = preferences.all.isNotEmpty()
            appContext.deleteSharedPreferences(LEGACY_AI_ADVICE_CACHE_PREFERENCES) || !hadEntries
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
