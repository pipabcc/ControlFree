package com.example.controlfree.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticLogStore internal constructor(
    directory: File,
    private val tokenizer: PackageNameTokenizer,
    private val executor: ExecutorService,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val files = BoundedDiagnosticFiles(directory)
    private val isClosed = AtomicBoolean(false)

    fun record(
        type: DiagnosticEventType,
        packageName: String? = null,
        occurredAtEpochMillis: Long = nowEpochMillis()
    ): CompletableFuture<DiagnosticWriteResult> {
        if (occurredAtEpochMillis < 0L) {
            return CompletableFuture.completedFuture(DiagnosticWriteResult.INVALID_TIMESTAMP)
        }
        if (packageName != null && packageName.isBlank()) {
            return CompletableFuture.completedFuture(DiagnosticWriteResult.INVALID_PACKAGE_NAME)
        }
        return submit(
            closedResult = DiagnosticWriteResult.CLOSED,
            failureResult = DiagnosticWriteResult.STORAGE_FAILURE
        ) {
            val packageToken = if (packageName == null) {
                null
            } else {
                tokenizer.tokenize(packageName)
                    ?: return@submit DiagnosticWriteResult.TOKENIZATION_FAILED
            }
            if (
                files.append(
                    DiagnosticRecord(
                        occurredAtEpochMillis = occurredAtEpochMillis,
                        type = type,
                        packageToken = packageToken
                    )
                )
            ) {
                DiagnosticWriteResult.WRITTEN
            } else {
                DiagnosticWriteResult.STORAGE_FAILURE
            }
        }
    }

    fun read(): CompletableFuture<DiagnosticReadResult> =
        submit(
            closedResult = DiagnosticReadResult(
                records = emptyList(),
                skippedRecordCount = 0,
                inaccessibleFileCount = 0,
                isClosed = true
            ),
            failureResult = DiagnosticReadResult(
                records = emptyList(),
                skippedRecordCount = 0,
                inaccessibleFileCount = LOG_FILE_COUNT
            )
        ) {
            files.read()
        }

    fun clear(): CompletableFuture<DiagnosticClearResult> =
        submit(
            closedResult = DiagnosticClearResult.CLOSED,
            failureResult = DiagnosticClearResult.STORAGE_FAILURE
        ) {
            if (files.clear()) {
                DiagnosticClearResult.CLEARED
            } else {
                DiagnosticClearResult.STORAGE_FAILURE
            }
        }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) executor.shutdown()
    }

    internal fun isClosedForFactory(): Boolean = isClosed.get()

    internal fun isTerminatedForFactory(): Boolean = executor.isTerminated

    private fun <T> submit(
        closedResult: T,
        failureResult: T,
        task: () -> T
    ): CompletableFuture<T> {
        if (isClosed.get()) return CompletableFuture.completedFuture(closedResult)
        val future = CompletableFuture<T>()
        try {
            executor.execute {
                val result = try {
                    task()
                } catch (_: RuntimeException) {
                    failureResult
                }
                future.complete(result)
            }
        } catch (_: RejectedExecutionException) {
            future.complete(closedResult)
        }
        return future
    }

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024L
        const val MAX_TOTAL_LOG_BYTES = MAX_FILE_BYTES * 2L

        internal fun create(
            directory: File,
            nowEpochMillis: () -> Long = System::currentTimeMillis,
            keyFile: File = File(directory, HMAC_KEY_FILE_NAME)
        ): DiagnosticLogStore = DiagnosticLogStore(
            directory = directory,
            tokenizer = InstallationHmacPackageNameTokenizer(keyFile),
            executor = Executors.newSingleThreadExecutor(
                ThreadFactory { task ->
                    Thread(task, "controlfree-diagnostics").apply { isDaemon = true }
                }
            ),
            nowEpochMillis = nowEpochMillis
        )

        internal const val ACTIVE_LOG_FILE_NAME = "diagnostics-current.log"
        internal const val ARCHIVE_LOG_FILE_NAME = "diagnostics-previous.log"
        internal const val HMAC_KEY_FILE_NAME = "package-name-hmac.key"
        private const val LOG_FILE_COUNT = 2
    }
}

private class BoundedDiagnosticFiles(private val directory: File) {
    private val activeFile = File(directory, DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
    private val archiveFile = File(directory, DiagnosticLogStore.ARCHIVE_LOG_FILE_NAME)

    fun append(record: DiagnosticRecord): Boolean {
        val encodedRecord = DiagnosticRecordCodec.encode(record)
        if (encodedRecord.size > DiagnosticLogStore.MAX_FILE_BYTES) return false
        return try {
            if (!ensureDirectory()) return false
            if (!removeOversizedFile(activeFile) || !removeOversizedFile(archiveFile)) return false
            if (
                activeFile.exists() &&
                activeFile.length() + encodedRecord.size > DiagnosticLogStore.MAX_FILE_BYTES &&
                !rotate()
            ) {
                return false
            }
            FileOutputStream(activeFile, true).use { stream -> stream.write(encodedRecord) }
            activeFile.length() <= DiagnosticLogStore.MAX_FILE_BYTES &&
                activeFile.length() + archiveFile.safeLength() <=
                DiagnosticLogStore.MAX_TOTAL_LOG_BYTES
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun read(): DiagnosticReadResult {
        var skippedRecordCount = 0
        var inaccessibleFileCount = 0
        val records = buildList {
            listOf(archiveFile, activeFile).forEach { file ->
                if (!file.exists()) return@forEach
                try {
                    file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val record = DiagnosticRecordCodec.decode(line)
                            if (record == null) {
                                skippedRecordCount += 1
                            } else {
                                add(record)
                            }
                        }
                    }
                } catch (_: IOException) {
                    inaccessibleFileCount += 1
                } catch (_: SecurityException) {
                    inaccessibleFileCount += 1
                }
            }
        }
        return DiagnosticReadResult(
            records = records,
            skippedRecordCount = skippedRecordCount,
            inaccessibleFileCount = inaccessibleFileCount
        )
    }

    fun clear(): Boolean = try {
        val activeDeleted = deleteIfPresent(activeFile)
        val archiveDeleted = deleteIfPresent(archiveFile)
        activeDeleted && archiveDeleted
    } catch (_: SecurityException) {
        false
    }

    private fun ensureDirectory(): Boolean =
        directory.isDirectory || (!directory.exists() && directory.mkdirs())

    private fun removeOversizedFile(file: File): Boolean =
        file.length() <= DiagnosticLogStore.MAX_FILE_BYTES || deleteIfPresent(file)

    private fun rotate(): Boolean {
        if (!deleteIfPresent(archiveFile)) return false
        if (!activeFile.exists()) return true
        if (activeFile.renameTo(archiveFile)) return true
        return try {
            activeFile.copyTo(archiveFile, overwrite = false)
            if (activeFile.delete()) {
                true
            } else {
                archiveFile.delete()
                false
            }
        } catch (_: IOException) {
            archiveFile.delete()
            false
        } catch (_: SecurityException) {
            archiveFile.delete()
            false
        }
    }

    private fun deleteIfPresent(file: File): Boolean = !file.exists() || file.delete()

    private fun File.safeLength(): Long = if (exists()) length() else 0L
}

private object DiagnosticRecordCodec {
    private const val FORMAT_VERSION = 1
    private const val NO_PACKAGE_TOKEN = "-"
    private const val FIELD_SEPARATOR = '\t'

    fun encode(record: DiagnosticRecord): ByteArray {
        val token = record.packageToken?.value ?: NO_PACKAGE_TOKEN
        return buildString {
            append(FORMAT_VERSION)
            append(FIELD_SEPARATOR)
            append(record.occurredAtEpochMillis)
            append(FIELD_SEPARATOR)
            append(record.type.persistedCode)
            append(FIELD_SEPARATOR)
            append(token)
            append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(line: String): DiagnosticRecord? {
        val fields = line.split(FIELD_SEPARATOR, limit = 4)
        if (fields.size != 4 || fields[0].toIntOrNull() != FORMAT_VERSION) return null
        val timestamp = fields[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val type = fields[2].toIntOrNull()?.let(DiagnosticEventType::fromPersistedCode)
            ?: return null
        val token = when (fields[3]) {
            NO_PACKAGE_TOKEN -> null
            else -> PackageNameToken.parse(fields[3]) ?: return null
        }
        return DiagnosticRecord(
            occurredAtEpochMillis = timestamp,
            type = type,
            packageToken = token
        )
    }
}
