package com.example.controlfree.supervision.app

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestOnlySnapshotWriterTest {
    @Test
    fun `写入进行中只保留最新快照`() {
        val executor = QueuedExecutor()
        val writes = mutableListOf<Int>()
        val writer = LatestOnlySnapshotWriter<Int>(
            executor = executor,
            write = { value ->
                writes += value
                true
            },
            clear = { true }
        )

        assertTrue(writer.submit(1))
        assertTrue(writer.submit(2))
        assertTrue(writer.submit(3))
        assertEquals(1, executor.size)

        executor.runNext()

        assertEquals(listOf(3), writes)
        writer.close()
    }

    @Test
    fun `清理会丢弃排队快照并等待当前写入后执行`() {
        val executor = QueuedExecutor()
        val operations = mutableListOf<String>()
        var clearResult = false
        val writer = LatestOnlySnapshotWriter<Int>(
            executor = executor,
            write = {
                operations += "write"
                true
            },
            clear = {
                operations += "clear"
                clearResult = true
                true
            }
        )

        assertTrue(writer.submit(1))
        assertTrue(writer.submit(2))
        var callbackResult: Boolean? = null
        assertTrue(writer.requestClear { callbackResult = it })
        assertEquals(1, executor.size)

        executor.runNext()

        assertEquals(listOf("clear"), operations)
        assertTrue(clearResult)
        assertEquals(true, callbackResult)
        writer.close()
    }

    @Test
    fun `关闭后拒绝新写入但排空未执行清理回调`() {
        val executor = QueuedExecutor()
        val writer = LatestOnlySnapshotWriter<Int>(
            executor = executor,
            write = { true },
            clear = { true }
        )
        var callbackResult: Boolean? = null
        assertTrue(writer.requestClear { callbackResult = it })
        writer.close()

        assertFalse(writer.submit(1))
        assertNull(callbackResult)
        executor.runNext()
        assertEquals(true, callbackResult)
    }

    @Test
    fun `关闭会排空慢存储期间排队的最新快照`() {
        val executor = QueuedExecutor()
        val writes = mutableListOf<Int>()
        val writer = LatestOnlySnapshotWriter<Int>(
            executor = executor,
            write = {
                writes += it
                true
            },
            clear = { true }
        )

        assertTrue(writer.submit(1))
        assertTrue(writer.submit(2))
        writer.close()
        assertFalse(writer.submit(3))

        executor.runNext()

        assertEquals(listOf(2), writes)
    }

    @Test
    fun `慢写入完成后先丢弃旧快照再串行清理`() {
        val executor = Executors.newSingleThreadExecutor()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val clearCompleted = CountDownLatch(1)
        val operations = mutableListOf<String>()
        val writer = LatestOnlySnapshotWriter<Int>(
            executor = executor,
            write = { value ->
                synchronized(operations) { operations += "write:$value" }
                writeStarted.countDown()
                releaseWrite.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                true
            },
            clear = {
                synchronized(operations) { operations += "clear" }
                true
            }
        )

        try {
            assertTrue(writer.submit(1))
            assertTrue(writeStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(writer.submit(2))
            assertTrue(writer.requestClear { clearCompleted.countDown() })

            releaseWrite.countDown()
            assertTrue(clearCompleted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf("write:1", "clear"), synchronized(operations) {
                operations.toList()
            })
        } finally {
            releaseWrite.countDown()
            writer.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `慢写入期间关闭仍会排空最后一个快照`() {
        val executor = Executors.newSingleThreadExecutor()
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val secondWriteCompleted = CountDownLatch(1)
        val writes = mutableListOf<Int>()
        val writer = LatestOnlySnapshotWriter<Int>(
            executor = executor,
            write = { value ->
                synchronized(writes) { writes += value }
                if (value == 1) {
                    firstWriteStarted.countDown()
                    releaseFirstWrite.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } else {
                    secondWriteCompleted.countDown()
                }
                true
            },
            clear = { true }
        )

        try {
            assertTrue(writer.submit(1))
            assertTrue(firstWriteStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(writer.submit(2))
            writer.close()
            assertFalse(writer.submit(3))

            releaseFirstWrite.countDown()
            assertTrue(secondWriteCompleted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), synchronized(writes) { writes.toList() })
        } finally {
            releaseFirstWrite.countDown()
            writer.close()
            executor.shutdownNow()
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val size: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            check(tasks.isNotEmpty())
            tasks.removeFirst().run()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 5L
    }
}
