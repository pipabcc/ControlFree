package com.example.controlfree.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogStoreTest {
    private val stores = mutableListOf<DiagnosticLogStore>()
    private val temporaryDirectories = mutableListOf<File>()

    @After
    fun tearDown() {
        stores.forEach(DiagnosticLogStore::close)
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `异步写入按提交顺序读取且磁盘不含原始包名`() {
        val directory = newTemporaryDirectory()
        val store = newStore(directory, nowEpochMillis = { 999L })

        val firstWrite = store.record(
            type = DiagnosticEventType.MONITOR_STARTED,
            packageName = "com.example.private"
        )
        val secondWrite = store.record(
            type = DiagnosticEventType.MEDIA_PAUSE_DISPATCHED,
            occurredAtEpochMillis = 1_000L
        )
        val readResult = store.read().get(5, TimeUnit.SECONDS)

        assertEquals(DiagnosticWriteResult.WRITTEN, firstWrite.get(5, TimeUnit.SECONDS))
        assertEquals(DiagnosticWriteResult.WRITTEN, secondWrite.get(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(
                DiagnosticEventType.MONITOR_STARTED,
                DiagnosticEventType.MEDIA_PAUSE_DISPATCHED
            ),
            readResult.records.map(DiagnosticRecord::type)
        )
        assertEquals(listOf(999L, 1_000L), readResult.records.map { it.occurredAtEpochMillis })
        assertEquals(24, readResult.records.first().packageToken?.value?.length)
        assertFalse(
            directory.resolve(DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
                .readText()
                .contains("com.example.private")
        )
    }

    @Test
    fun `令牌计算和磁盘写入均在专用单线程执行`() {
        val directory = newTemporaryDirectory()
        val tokenizerThread = AtomicReference<String>()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diagnostics-test-worker")
        }
        val token = PackageNameToken.parse("a".repeat(24))!!
        val store = DiagnosticLogStore(
            directory = directory,
            tokenizer = PackageNameTokenizer {
                tokenizerThread.set(Thread.currentThread().name)
                token
            },
            executor = executor
        ).also(stores::add)

        assertEquals(
            DiagnosticWriteResult.WRITTEN,
            store.record(DiagnosticEventType.APP_PROCESS_STARTED, "com.example.app")
                .get(5, TimeUnit.SECONDS)
        )

        assertEquals("diagnostics-test-worker", tokenizerThread.get())
        assertEquals(1, store.read().get(5, TimeUnit.SECONDS).records.size)
    }

    @Test
    fun `达到上限后保留前后两个日志文件并维持总大小边界`() {
        val directory = newTemporaryDirectory()
        val activeFile = directory.resolve(DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
        val validLine = "1\t1\t1\t${"b".repeat(24)}\n".toByteArray(Charsets.UTF_8)
        val repeatCount = (DiagnosticLogStore.MAX_FILE_BYTES / validLine.size).toInt()
        activeFile.outputStream().use { stream ->
            repeat(repeatCount) { stream.write(validLine) }
        }
        val originalRecordCount = repeatCount
        val store = newStore(directory)

        assertEquals(
            DiagnosticWriteResult.WRITTEN,
            store.record(DiagnosticEventType.MONITOR_STOPPED, occurredAtEpochMillis = 2L)
                .get(5, TimeUnit.SECONDS)
        )

        val archiveFile = directory.resolve(DiagnosticLogStore.ARCHIVE_LOG_FILE_NAME)
        assertTrue(activeFile.length() <= DiagnosticLogStore.MAX_FILE_BYTES)
        assertTrue(archiveFile.length() <= DiagnosticLogStore.MAX_FILE_BYTES)
        assertTrue(
            activeFile.length() + archiveFile.length() <=
                DiagnosticLogStore.MAX_TOTAL_LOG_BYTES
        )
        val readResult = store.read().get(5, TimeUnit.SECONDS)
        assertEquals(originalRecordCount + 1, readResult.records.size)
        assertEquals(DiagnosticEventType.MONITOR_STOPPED, readResult.records.last().type)
    }

    @Test
    fun `再次轮转会替换最旧文件而不会生成第三个日志文件`() {
        val directory = newTemporaryDirectory()
        val activeFile = directory.resolve(DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
        val archiveFile = directory.resolve(DiagnosticLogStore.ARCHIVE_LOG_FILE_NAME)
        val validLine = "1\t1\t1\t${"c".repeat(24)}\n".toByteArray(Charsets.UTF_8)
        val repeatCount = (DiagnosticLogStore.MAX_FILE_BYTES / validLine.size).toInt()
        archiveFile.writeBytes(validLine)
        activeFile.outputStream().use { stream ->
            repeat(repeatCount) { stream.write(validLine) }
        }
        val store = newStore(directory)

        store.record(
            DiagnosticEventType.LOCK_SURFACE_SHOWN,
            packageName = "com.example.rotated",
            occurredAtEpochMillis = 3L
        )
            .get(5, TimeUnit.SECONDS)

        val logFiles = directory.listFiles().orEmpty().filter { it.extension == "log" }
        assertEquals(2, logFiles.size)
        assertEquals(repeatCount.toLong() * validLine.size, archiveFile.length())
    }

    @Test
    fun `读取跳过损坏未知和未完成记录并保留有效记录`() {
        val directory = newTemporaryDirectory()
        val activeFile = directory.resolve(DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
        activeFile.writeText(
            buildString {
                append("1\t100\t10\t-\n")
                append("broken\n")
                append("1\t101\t9999\t-\n")
                append("1\t102\t11\tnot-a-token\n")
                append("1\t")
            }
        )
        val store = newStore(directory)

        val result = store.read().get(5, TimeUnit.SECONDS)

        assertEquals(1, result.records.size)
        assertEquals(DiagnosticEventType.MONITOR_STARTED, result.records.single().type)
        assertEquals(4, result.skippedRecordCount)
        assertEquals(0, result.inaccessibleFileCount)
    }

    @Test
    fun `单个日志文件不可读时仍返回另一个文件的记录`() {
        val directory = newTemporaryDirectory()
        directory.resolve(DiagnosticLogStore.ARCHIVE_LOG_FILE_NAME).mkdir()
        directory.resolve(DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
            .writeText("1\t200\t20\t-\n")
        val store = newStore(directory)

        val result = store.read().get(5, TimeUnit.SECONDS)

        assertEquals(1, result.records.size)
        assertEquals(DiagnosticEventType.LOCK_PHASE_STARTED, result.records.single().type)
        assertEquals(1, result.inaccessibleFileCount)
    }

    @Test
    fun `清空只删除日志并保留安装令牌稳定性`() {
        val directory = newTemporaryDirectory()
        val store = newStore(directory)
        store.record(
            DiagnosticEventType.ALLOWLIST_REFRESH_SUCCEEDED,
            "com.example.allowed",
            300L
        ).get(5, TimeUnit.SECONDS)
        val firstToken = store.read().get(5, TimeUnit.SECONDS)
            .records.single().packageToken

        assertEquals(DiagnosticClearResult.CLEARED, store.clear().get(5, TimeUnit.SECONDS))
        assertTrue(store.read().get(5, TimeUnit.SECONDS).records.isEmpty())
        assertTrue(directory.resolve(DiagnosticLogStore.HMAC_KEY_FILE_NAME).isFile)

        store.record(
            DiagnosticEventType.ALLOWLIST_REFRESH_SUCCEEDED,
            "com.example.allowed",
            301L
        ).get(5, TimeUnit.SECONDS)
        val secondToken = store.read().get(5, TimeUnit.SECONDS)
            .records.single().packageToken
        assertEquals(firstToken, secondToken)
    }

    @Test
    fun `非法输入和令牌失败不会写入记录`() {
        val directory = newTemporaryDirectory()
        val store = DiagnosticLogStore(
            directory = directory,
            tokenizer = PackageNameTokenizer { null },
            executor = newExecutor()
        ).also(stores::add)

        assertEquals(
            DiagnosticWriteResult.INVALID_TIMESTAMP,
            store.record(DiagnosticEventType.MONITOR_STARTED, occurredAtEpochMillis = -1L).get()
        )
        assertEquals(
            DiagnosticWriteResult.INVALID_PACKAGE_NAME,
            store.record(DiagnosticEventType.MONITOR_STARTED, packageName = " ").get()
        )
        assertEquals(
            DiagnosticWriteResult.TOKENIZATION_FAILED,
            store.record(DiagnosticEventType.MONITOR_STARTED, packageName = "com.example.app")
                .get(5, TimeUnit.SECONDS)
        )
        assertTrue(store.read().get(5, TimeUnit.SECONDS).records.isEmpty())
    }

    @Test
    fun `令牌实现异常被转换为固定失败结果`() {
        val directory = newTemporaryDirectory()
        val store = DiagnosticLogStore(
            directory = directory,
            tokenizer = PackageNameTokenizer { error("不得传播或持久化此文本") },
            executor = newExecutor()
        ).also(stores::add)

        assertEquals(
            DiagnosticWriteResult.STORAGE_FAILURE,
            store.record(DiagnosticEventType.MONITOR_STARTED, "com.example.app")
                .get(5, TimeUnit.SECONDS)
        )
        assertFalse(
            directory.resolve(DiagnosticLogStore.ACTIVE_LOG_FILE_NAME)
                .takeIf(File::exists)
                ?.readText()
                .orEmpty()
                .contains("不得传播或持久化此文本")
        )
    }

    @Test
    fun `存储目录被文件占用时安全返回失败`() {
        val parent = newTemporaryDirectory()
        val occupiedPath = parent.resolve("diagnostics").apply { writeText("occupied") }
        val store = newStore(occupiedPath)

        assertEquals(
            DiagnosticWriteResult.STORAGE_FAILURE,
            store.record(DiagnosticEventType.APP_PROCESS_STARTED, occurredAtEpochMillis = 1L)
                .get(5, TimeUnit.SECONDS)
        )
        assertTrue(occupiedPath.isFile)
    }

    @Test
    fun `关闭后拒绝新操作但已提交写入仍会完成`() {
        val directory = newTemporaryDirectory()
        val store = newStore(directory)
        val acceptedWrite = store.record(
            DiagnosticEventType.APP_PROCESS_STOPPED,
            occurredAtEpochMillis = 5L
        )

        store.close()

        assertEquals(DiagnosticWriteResult.WRITTEN, acceptedWrite.get(5, TimeUnit.SECONDS))
        assertEquals(
            DiagnosticWriteResult.CLOSED,
            store.record(DiagnosticEventType.APP_PROCESS_STARTED).get()
        )
        assertEquals(DiagnosticClearResult.CLOSED, store.clear().get())
        assertTrue(store.read().get().isClosed)
    }

    @Test
    fun `不同事件使用稳定且唯一的持久化编号`() {
        val codes = DiagnosticEventType.entries.map(DiagnosticEventType::persistedCode)

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it > 0 })
        DiagnosticEventType.entries.forEach { type ->
            assertEquals(type, DiagnosticEventType.fromPersistedCode(type.persistedCode))
        }
        assertNotEquals(
            DiagnosticEventType.MONITOR_STARTED.persistedCode,
            DiagnosticEventType.MONITOR_STOPPED.persistedCode
        )
    }

    private fun newStore(
        directory: File,
        nowEpochMillis: () -> Long = System::currentTimeMillis
    ): DiagnosticLogStore = DiagnosticLogStore.create(directory, nowEpochMillis).also(stores::add)

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor()

    private fun newTemporaryDirectory(): File =
        Files.createTempDirectory("controlfree-diagnostics-").toFile()
            .also(temporaryDirectories::add)
}
