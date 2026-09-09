package com.example.controlfree

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSnapshotPersistenceWorkerTest {
    private var executor: ExecutorService? = null
    private var worker: MonitorSnapshotPersistenceWorker? = null

    @After
    fun tearDown() {
        worker?.close()
        executor?.shutdownNow()
    }

    @Test
    fun `活动暂停只能与锁定阶段快照一起持久化`() {
        val activePause = requireNotNull(
            MonitorPauseState.begin(
                previous = null,
                requestedMillis = 60_000L,
                nowEpochMillis = 1_000_000L,
                nowElapsedMillis = 100_000L,
                bootCount = 1
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            request(
                checkpoint = 1L,
                phase = MonitorPhase.USAGE,
                pauseState = activePause
            )
        }

        val lockRequest = request(
            checkpoint = 2L,
            phase = MonitorPhase.LOCK,
            pauseState = activePause
        )
        val usageRequestWithConsumedBudget = request(
            checkpoint = 3L,
            phase = MonitorPhase.USAGE,
            pauseState = activePause.clearActive()
        )

        assertEquals(activePause, lockRequest.pauseState)
        assertEquals(activePause.clearActive(), usageRequestWithConsumedBudget.pauseState)
    }

    @Test
    fun `磁盘写入不会阻塞调用线程`() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        val writeThread = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        val persistenceWorker = createWorker { request ->
            val snapshot = request.snapshot
            writeThread.set(Thread.currentThread())
            writeStarted.countDown()
            assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
            success(snapshot)
        }

        assertTrue(persistenceWorker.submit(request(1L)) { resultDelivered.countDown() })
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        assertNotSame(caller, writeThread.get())
        releaseWrite.countDown()
        assertTrue(resultDelivered.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `阻塞期间只保留最新待写快照`() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val twoResults = CountDownLatch(2)
        val writeCount = AtomicInteger()
        val lastCheckpoint = AtomicReference<Long>()
        val persistenceWorker = createWorker { request ->
            val snapshot = request.snapshot
            if (writeCount.incrementAndGet() == 1) {
                firstWriteStarted.countDown()
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS))
            }
            lastCheckpoint.set(snapshot.checkpointElapsedMillis)
            success(snapshot)
        }

        assertTrue(persistenceWorker.submit(request(1L)) { twoResults.countDown() })
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(persistenceWorker.submit(request(2L)) { twoResults.countDown() })
        assertTrue(persistenceWorker.submit(request(3L)) { twoResults.countDown() })
        releaseFirstWrite.countDown()

        assertTrue(twoResults.await(5, TimeUnit.SECONDS))
        assertEquals(2, writeCount.get())
        assertEquals(3L, lastCheckpoint.get())
    }

    @Test
    fun `合并快照时不会丢失已生效成长订单标记`() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val threeResults = CountDownLatch(3)
        val writeCount = AtomicInteger()
        val lastRequest = AtomicReference<MonitorSnapshotPersistRequest>()
        val persistenceWorker = createWorker { request ->
            if (writeCount.incrementAndGet() == 1) {
                firstWriteStarted.countDown()
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS))
            }
            lastRequest.set(request)
            success(request.snapshot)
        }

        assertTrue(persistenceWorker.submit(request(1L)) { threeResults.countDown() })
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            persistenceWorker.submit(
                request(2L, appliedGrowthOrderIds = setOf("order_pause_1234"))
            ) { threeResults.countDown() }
        )
        assertTrue(
            persistenceWorker.submit(
                request(3L, appliedGrowthOrderIds = setOf("order_skip_5678"))
            ) { threeResults.countDown() }
        )
        releaseFirstWrite.countDown()

        assertTrue(threeResults.await(5, TimeUnit.SECONDS))
        assertEquals(2, writeCount.get())
        assertEquals(3L, lastRequest.get().snapshot.checkpointElapsedMillis)
        assertEquals(
            setOf("order_pause_1234", "order_skip_5678"),
            lastRequest.get().appliedGrowthOrderIds
        )
    }

    @Test
    fun `清除操作在进行中写入之后执行并丢弃待写快照`() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val clearCompleted = CountDownLatch(1)
        val cancelledWriteCompleted = CountDownLatch(1)
        val cancelledWriteSucceeded = AtomicReference<Boolean>()
        val operations = mutableListOf<String>()
        val persistenceWorker = createWorker { request ->
            val snapshot = request.snapshot
            synchronized(operations) { operations += "write:${snapshot.checkpointElapsedMillis}" }
            firstWriteStarted.countDown()
            assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS))
            success(snapshot)
        }

        assertTrue(persistenceWorker.submit(request(1L)) {})
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            persistenceWorker.submit(request(2L)) { result ->
                cancelledWriteSucceeded.set(result.isSuccess)
                cancelledWriteCompleted.countDown()
            }
        )
        assertTrue(
            persistenceWorker.submitClear(
                clearOperation = {
                    synchronized(operations) { operations += "clear" }
                    MonitorStateClearResult(progressCleared = true, guardCleared = true)
                },
                onResult = { clearCompleted.countDown() }
            )
        )
        releaseFirstWrite.countDown()

        assertTrue(cancelledWriteCompleted.await(5, TimeUnit.SECONDS))
        assertEquals(false, cancelledWriteSucceeded.get())
        assertTrue(clearCompleted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("write:1", "clear"), synchronized(operations) { operations.toList() })
    }

    @Test
    fun `检查点已提交时清理失败不否定主快照事务`() {
        val result = MonitorSnapshotPersistResult(
            snapshot = snapshot(9L, MonitorPhase.USAGE),
            guardArmed = true,
            progressSaved = true,
            guardCleared = false,
            guardCommitted = true
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `新建的 worker 报告可用`() {
        val worker = createWorker { success(it.snapshot) }

        assertTrue(worker.isUsable)
    }

    @Test
    fun `close 后的 worker 不再报告可用`() {
        val worker = createWorker { success(it.snapshot) }

        worker.close()

        // 共享单例被复用前必须能识别出这种不可逆状态，否则本进程后续落盘永久失败。
        assertFalse(worker.isUsable)
    }

    @Test
    fun `submitClear 后的 worker 不再接受快照也不报告可用`() {
        val worker = createWorker { success(it.snapshot) }

        worker.submitClear(
            clearOperation = { MonitorStateClearResult(progressCleared = true, guardCleared = true) },
            onResult = {}
        )

        assertFalse(worker.isUsable)
    }

    @Test
    fun `清理IO尚未返回时worker持续拒绝新快照`() {
        val clearStarted = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val clearCompleted = CountDownLatch(1)
        val worker = createWorker { success(it.snapshot) }

        assertTrue(
            worker.submitClear(
                clearOperation = {
                    clearStarted.countDown()
                    assertTrue(releaseClear.await(5, TimeUnit.SECONDS))
                    MonitorStateClearResult(progressCleared = true, guardCleared = true)
                },
                onResult = { clearCompleted.countDown() }
            )
        )
        assertTrue(clearStarted.await(5, TimeUnit.SECONDS))

        assertFalse(worker.submit(request(99L)) {})
        assertFalse(worker.isUsable)

        releaseClear.countDown()
        assertTrue(clearCompleted.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `清理失败恢复后 worker 重新报告可用`() {
        val worker = createWorker { success(it.snapshot) }
        val cleared = CountDownLatch(1)
        worker.submitClear(
            clearOperation = {
                MonitorStateClearResult(progressCleared = false, guardCleared = false)
            },
            onResult = { cleared.countDown() }
        )
        // 清理请求出队后才能恢复接受快照，否则恢复调用会被待处理请求挡下。
        assertTrue(cleared.await(5, TimeUnit.SECONDS))

        worker.resumeSnapshotsAfterClearFailure()

        assertTrue(worker.isUsable)
    }

    private fun createWorker(
        operation: (MonitorSnapshotPersistRequest) -> MonitorSnapshotPersistResult
    ): MonitorSnapshotPersistenceWorker {
        val ioExecutor = Executors.newSingleThreadExecutor()
        executor = ioExecutor
        return MonitorSnapshotPersistenceWorker(
            persistOperation = operation,
            resultExecutor = Executor(Runnable::run),
            ioExecutor = ioExecutor
        ).also { worker = it }
    }

    private fun snapshot(
        checkpoint: Long,
        phase: MonitorPhase = MonitorPhase.LOCK
    ) = MonitorCycleSnapshot(
        phase = phase,
        remainingMillis = 60_000L,
        checkpointElapsedMillis = checkpoint,
        bootCount = 1,
        isInteractive = true
    )

    private fun request(
        checkpoint: Long,
        phase: MonitorPhase = MonitorPhase.LOCK,
        pauseState: MonitorPauseState? = null,
        appliedGrowthOrderIds: Set<String> = emptySet()
    ) = MonitorSnapshotPersistRequest(
        snapshot = snapshot(checkpoint, phase),
        scheduledOwner = null,
        sessionMode = MonitorSessionMode.SUPERVISION,
        usageMinutes = 30,
        lockMinutes = 5,
        pauseState = pauseState,
        appliedGrowthOrderIds = appliedGrowthOrderIds
    )

    private fun success(snapshot: MonitorCycleSnapshot) = MonitorSnapshotPersistResult(
        snapshot = snapshot,
        guardArmed = true,
        progressSaved = true,
        guardCleared = true
    )
}
