package com.example.controlfree.data

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundObservationWorkerTest {
    private val workers = CopyOnWriteArrayList<ForegroundObservationWorker>()
    private val retirementRegistry = ForegroundObservationRetirementRegistry()

    @After
    fun tearDown() {
        workers.forEach(ForegroundObservationWorker::close)
    }

    @Test
    fun `阻塞系统查询不会阻塞调用线程且结果来自后台线程`() {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val callbackDelivered = CountDownLatch(1)
        val queryThread = AtomicReference<Thread>()
        val callingThread = Thread.currentThread()
        val observer = createWorker(source { nowMillis ->
            queryThread.set(Thread.currentThread())
            queryStarted.countDown()
            releaseQuery.awaitOrFail()
            availableObservation("example.allowed", nowMillis)
        })
        val generation = observer.beginObservationWindow(1_000L)

        assertTrue(observer.requestObservation(generation, 2_000L) {
            callbackDelivered.countDown()
        })

        assertTrue(queryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertNotSame(callingThread, queryThread.get())
        assertEquals(1L, callbackDelivered.count)
        releaseQuery.countDown()
        assertTrue(callbackDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `切换 generation 会在新线程查询且旧结果不会回调`() {
        val firstQueryStarted = CountDownLatch(1)
        val releaseFirstQuery = CountDownLatch(1)
        val firstQueryFinished = CountDownLatch(1)
        val oldCallbackCount = AtomicInteger()
        val queryCount = AtomicInteger()
        val observer = createWorker(source { nowMillis ->
            if (queryCount.incrementAndGet() == 1) {
                firstQueryStarted.countDown()
                releaseFirstQuery.awaitIgnoringInterrupt()
                firstQueryFinished.countDown()
                availableObservation("example.stale", nowMillis)
            } else {
                availableObservation("example.current", nowMillis)
            }
        })
        val oldGeneration = observer.beginObservationWindow(1_000L)
        assertTrue(observer.requestObservation(oldGeneration, 2_000L) {
            oldCallbackCount.incrementAndGet()
        })
        assertTrue(firstQueryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val newGeneration = observer.beginObservationWindow(3_000L)
        val latest = requestAndAwait(observer, newGeneration, 4_000L)

        assertEquals("example.current", latest.packageName)
        releaseFirstQuery.countDown()
        assertTrue(firstQueryFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, oldCallbackCount.get())
    }

    @Test
    fun `查询异常发布失败关闭结果`() {
        val observer = createWorker(source { throw IllegalStateException("query failed") })
        val generation = observer.beginObservationWindow(1_000L)

        val result = requestAndAwait(observer, generation, 2_000L)

        assertEquals(ForegroundObservationStatus.QUERY_FAILED, result.status)
        assertFalse(result.isAvailable)
    }

    @Test
    fun `阻塞期间只允许一个当前查询`() {
        val firstQueryStarted = CountDownLatch(1)
        val releaseFirstQuery = CountDownLatch(1)
        val firstCallback = CountDownLatch(1)
        val queryCount = AtomicInteger()
        val observer = createWorker(source { nowMillis ->
            queryCount.incrementAndGet()
            firstQueryStarted.countDown()
            releaseFirstQuery.awaitOrFail()
            availableObservation("example.allowed", nowMillis)
        })
        val generation = observer.beginObservationWindow(1_000L)

        assertTrue(observer.requestObservation(generation, 2_000L) {
            firstCallback.countDown()
        })
        assertTrue(firstQueryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(observer.requestObservation(generation, 2_100L) {})
        assertFalse(observer.requestObservation(generation, 2_200L) {})
        assertEquals(1, queryCount.get())

        releaseFirstQuery.countDown()
        assertTrue(firstCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `超时发布一次终态并在新线程恢复查询`() {
        val firstQueryStarted = CountDownLatch(1)
        val releaseFirstQuery = CountDownLatch(1)
        val firstQueryFinished = CountDownLatch(1)
        val firstCallbackCount = AtomicInteger()
        val queryCount = AtomicInteger()
        val queryThreadNames = CopyOnWriteArrayList<String>()
        val createdExecutors = AtomicInteger()
        val observer = createWorker(
            source = source { nowMillis ->
                queryThreadNames += Thread.currentThread().name
                if (queryCount.incrementAndGet() == 1) {
                    firstQueryStarted.countDown()
                    releaseFirstQuery.awaitIgnoringInterrupt()
                    firstQueryFinished.countDown()
                    availableObservation("example.stale", nowMillis)
                } else {
                    availableObservation("example.current", nowMillis)
                }
            },
            queryExecutorFactory = namedExecutorFactory(createdExecutors),
            queryTimeoutMillis = SHORT_TIMEOUT_MILLIS
        )
        val generation = observer.beginObservationWindow(1_000L)
        val firstResult = AtomicReference<ForegroundObservation>()
        val firstCallback = CountDownLatch(1)

        assertTrue(observer.requestObservation(generation, 2_000L) { observation ->
            firstCallbackCount.incrementAndGet()
            firstResult.set(observation)
            firstCallback.countDown()
        })
        assertTrue(firstQueryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(firstCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(ForegroundObservationStatus.TIMEOUT, firstResult.get().status)

        val recovered = requestAndAwait(observer, generation, 3_000L)

        assertEquals(ForegroundObservationStatus.AVAILABLE, recovered.status)
        assertEquals("example.current", recovered.packageName)
        assertEquals(2, createdExecutors.get())
        assertEquals(2, queryThreadNames.size)
        assertNotEquals(queryThreadNames[0], queryThreadNames[1])

        releaseFirstQuery.countDown()
        assertTrue(firstQueryFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, firstCallbackCount.get())
    }

    @Test
    fun `超时会使旧数据源 revision 失效`() {
        val revision = AtomicInteger()
        val queryCount = AtomicInteger()
        val oldCommitAccepted = AtomicBoolean(true)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val revisionSource = object : ForegroundObservationSource {
            override fun beginObservationWindow(anchorWallMillis: Long) {
                revision.incrementAndGet()
            }

            override fun resetObservationWindow() {
                revision.incrementAndGet()
            }

            override fun captureObservationLease(): Long = revision.get().toLong()

            override fun getForegroundObservation(
                nowMillis: Long,
                observationLease: Long
            ): ForegroundObservation {
                return if (queryCount.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    releaseFirst.awaitIgnoringInterrupt()
                    oldCommitAccepted.set(revision.get().toLong() == observationLease)
                    firstFinished.countDown()
                    availableObservation("example.stale", nowMillis)
                } else {
                    availableObservation("example.current", nowMillis)
                }
            }
        }
        val observer = createWorker(
            source = revisionSource,
            queryTimeoutMillis = SHORT_TIMEOUT_MILLIS
        )
        val generation = observer.beginObservationWindow(1_000L)

        assertEquals(
            ForegroundObservationStatus.TIMEOUT,
            requestAndAwait(observer, generation, 2_000L).status
        )
        assertEquals(
            ForegroundObservationStatus.AVAILABLE,
            requestAndAwait(observer, generation, 3_000L).status
        )

        releaseFirst.countDown()
        assertTrue(firstFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(oldCommitAccepted.get())
    }

    @Test
    fun `已入队但超时后才执行的旧 attempt 不能取得新 lease`() {
        val delayedExecutor = DelayedExecutorService()
        val revision = AtomicLong()
        val staleLeaseRejected = AtomicBoolean(false)
        val source = object : ForegroundObservationSource {
            override fun beginObservationWindow(anchorWallMillis: Long) {
                revision.incrementAndGet()
            }

            override fun resetObservationWindow() {
                revision.incrementAndGet()
            }

            override fun captureObservationLease(): Long = revision.get()

            override fun getForegroundObservation(
                nowMillis: Long,
                observationLease: Long
            ): ForegroundObservation {
                if (observationLease != revision.get()) {
                    staleLeaseRejected.set(true)
                    return ForegroundObservation.unavailable(
                        ForegroundObservationStatus.QUERY_FAILED
                    )
                }
                return availableObservation("example.current", nowMillis)
            }
        }
        val observer = createWorker(
            source = source,
            queryExecutor = delayedExecutor,
            queryExecutorFactory = { Executors.newSingleThreadExecutor() },
            queryTimeoutMillis = SHORT_TIMEOUT_MILLIS
        )
        val generation = observer.beginObservationWindow(1_000L)

        assertEquals(
            ForegroundObservationStatus.TIMEOUT,
            requestAndAwait(observer, generation, 2_000L).status
        )
        delayedExecutor.runPendingTask()

        assertTrue(staleLeaseRejected.get())
        assertEquals(
            ForegroundObservationStatus.AVAILABLE,
            requestAndAwait(observer, generation, 3_000L).status
        )
    }

    @Test
    fun `连续失败打开熔断且冷却后半开成功恢复`() {
        val elapsedClock = AtomicLong(0L)
        val queryCount = AtomicInteger()
        val observer = createWorker(
            source = source { nowMillis ->
                if (queryCount.incrementAndGet() <= 3) {
                    ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
                } else {
                    availableObservation("example.recovered", nowMillis)
                }
            },
            monotonicClockMillis = elapsedClock::get,
            circuitOpenMillis = 5_000L
        )
        val generation = observer.beginObservationWindow(1_000L)

        repeat(3) {
            assertEquals(
                ForegroundObservationStatus.QUERY_FAILED,
                requestAndAwait(observer, generation, 2_000L + it).status
            )
        }
        assertEquals(
            ForegroundObservationStatus.CIRCUIT_OPEN,
            requestAndAwait(observer, generation, 3_000L).status
        )
        assertEquals(3, queryCount.get())

        elapsedClock.set(5_000L)
        assertEquals(
            ForegroundObservationStatus.AVAILABLE,
            requestAndAwait(observer, generation, 4_000L).status
        )
        assertEquals(ForegroundObservationCircuitState.CLOSED, observer.healthSnapshot().circuit.state)
        assertEquals(
            ForegroundObservationStatus.AVAILABLE,
            requestAndAwait(observer, generation, 5_000L).status
        )
        assertEquals(5, queryCount.get())
    }

    @Test
    fun `观察窗口切换不能清除熔断冷却`() {
        val elapsedClock = AtomicLong(0L)
        val queryCount = AtomicInteger()
        val observer = createWorker(
            source = source {
                queryCount.incrementAndGet()
                ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
            },
            monotonicClockMillis = elapsedClock::get,
            circuitOpenMillis = 5_000L
        )
        var generation = observer.beginObservationWindow(1_000L)
        repeat(3) {
            assertEquals(
                ForegroundObservationStatus.QUERY_FAILED,
                requestAndAwait(observer, generation, 2_000L + it).status
            )
        }

        observer.resetObservationWindow()
        generation = observer.beginObservationWindow(3_000L)

        assertEquals(
            ForegroundObservationStatus.CIRCUIT_OPEN,
            requestAndAwait(observer, generation, 4_000L).status
        )
        assertEquals(3, queryCount.get())
    }

    @Test
    fun `权限拒绝不会触发熔断或轮换查询线程`() {
        val createdExecutors = AtomicInteger()
        val observer = createWorker(
            source = source {
                ForegroundObservation.unavailable(ForegroundObservationStatus.ACCESS_DENIED)
            },
            queryExecutorFactory = namedExecutorFactory(createdExecutors)
        )
        val generation = observer.beginObservationWindow(1_000L)

        repeat(5) {
            assertEquals(
                ForegroundObservationStatus.ACCESS_DENIED,
                requestAndAwait(observer, generation, 2_000L + it).status
            )
        }

        val health = observer.healthSnapshot()
        assertEquals(ForegroundObservationCircuitState.CLOSED, health.circuit.state)
        assertEquals(0, health.circuit.consecutiveFailures)
        assertEquals(1, createdExecutors.get())
    }

    @Test
    fun `达到遗留线程预算后安全熔断且不无限创建线程`() {
        val releaseQuery = CountDownLatch(1)
        val queryStarted = CountDownLatch(1)
        val queryFinished = CountDownLatch(1)
        val createdExecutors = AtomicInteger()
        val observer = createWorker(
            source = source { nowMillis ->
                queryStarted.countDown()
                releaseQuery.awaitIgnoringInterrupt()
                queryFinished.countDown()
                availableObservation("example.stale", nowMillis)
            },
            queryExecutorFactory = namedExecutorFactory(createdExecutors),
            queryTimeoutMillis = SHORT_TIMEOUT_MILLIS,
            maxRetiredExecutors = 1
        )
        val generation = observer.beginObservationWindow(1_000L)

        assertEquals(1L, queryStarted.count)
        assertEquals(
            ForegroundObservationStatus.TIMEOUT,
            requestAndAwait(observer, generation, 2_000L).status
        )
        assertEquals(
            ForegroundObservationStatus.CIRCUIT_OPEN,
            requestAndAwait(observer, generation, 3_000L).status
        )
        assertEquals(1, createdExecutors.get())

        releaseQuery.countDown()
        assertTrue(queryFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        awaitCondition { observer.healthSnapshot().retiredExecutorCount == 0 }
    }

    @Test
    fun `服务重建仍共享进程级遗留线程预算`() {
        val releaseQuery = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val createdExecutors = AtomicInteger()
        val firstWorker = createWorker(
            source = source { nowMillis ->
                releaseQuery.awaitIgnoringInterrupt()
                firstFinished.countDown()
                availableObservation("example.stale", nowMillis)
            },
            queryExecutorFactory = namedExecutorFactory(createdExecutors),
            queryTimeoutMillis = SHORT_TIMEOUT_MILLIS,
            maxRetiredExecutors = 1
        )
        val firstGeneration = firstWorker.beginObservationWindow(1_000L)
        assertEquals(
            ForegroundObservationStatus.TIMEOUT,
            requestAndAwait(firstWorker, firstGeneration, 2_000L).status
        )

        val rebuiltWorker = createWorker(
            source = source { nowMillis -> availableObservation("example.current", nowMillis) },
            queryExecutorFactory = namedExecutorFactory(createdExecutors),
            maxRetiredExecutors = 1
        )
        val rebuiltGeneration = rebuiltWorker.beginObservationWindow(1_000L)

        assertEquals(
            ForegroundObservationStatus.CIRCUIT_OPEN,
            requestAndAwait(rebuiltWorker, rebuiltGeneration, 2_000L).status
        )
        assertEquals(1, createdExecutors.get())

        releaseQuery.countDown()
        assertTrue(firstFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        awaitCondition { rebuiltWorker.healthSnapshot().retiredExecutorCount == 0 }
    }

    @Test
    fun `执行器拒绝任务后会轮换并恢复`() {
        val fallbackCreated = AtomicInteger()
        val observer = createWorker(
            source = source { nowMillis -> availableObservation("example.current", nowMillis) },
            queryExecutor = RejectingExecutorService(),
            queryExecutorFactory = namedExecutorFactory(fallbackCreated)
        )
        val generation = observer.beginObservationWindow(1_000L)

        assertEquals(
            ForegroundObservationStatus.QUERY_FAILED,
            requestAndAwait(observer, generation, 2_000L).status
        )
        assertEquals(
            ForegroundObservationStatus.AVAILABLE,
            requestAndAwait(observer, generation, 3_000L).status
        )
        assertEquals(1, fallbackCreated.get())
    }

    @Test
    fun `watchdog 拒绝调度不会留下永久 in flight`() {
        val watchdog = Executors.newSingleThreadScheduledExecutor().apply { shutdownNow() }
        val queryCount = AtomicInteger()
        val observer = createWorker(
            source = source { nowMillis ->
                queryCount.incrementAndGet()
                availableObservation("example.current", nowMillis)
            },
            watchdogExecutor = watchdog
        )
        val generation = observer.beginObservationWindow(1_000L)

        repeat(2) {
            assertEquals(
                ForegroundObservationStatus.QUERY_FAILED,
                requestAndAwait(observer, generation, 2_000L + it).status
            )
            assertFalse(observer.healthSnapshot().queryInFlight)
        }
        assertEquals(0, queryCount.get())
    }

    @Test
    fun `异步结果乱序时只交付最新终态`() {
        val queuedResults = QueueingExecutor()
        val queryCount = AtomicInteger()
        val deliveredPackages = CopyOnWriteArrayList<String?>()
        val observer = createWorker(
            source = source { nowMillis ->
                val packageName = if (queryCount.incrementAndGet() == 1) {
                    "example.first"
                } else {
                    "example.second"
                }
                availableObservation(packageName, nowMillis)
            },
            resultExecutor = queuedResults
        )
        val generation = observer.beginObservationWindow(1_000L)

        assertTrue(observer.requestObservation(generation, 2_000L) {
            deliveredPackages += it.packageName
        })
        awaitCondition { !observer.healthSnapshot().queryInFlight && queuedResults.size == 1 }
        assertTrue(observer.requestObservation(generation, 3_000L) {
            deliveredPackages += it.packageName
        })
        awaitCondition { !observer.healthSnapshot().queryInFlight && queuedResults.size == 2 }

        queuedResults.runAt(1)
        queuedResults.runAt(0)

        assertEquals(listOf("example.second"), deliveredPackages.toList())
    }

    @Test
    fun `结果已排队后关闭也不能穿透生命周期回调`() {
        val queuedResults = QueueingExecutor()
        val callbackCount = AtomicInteger()
        val observer = createWorker(
            source = source { nowMillis -> availableObservation("example.current", nowMillis) },
            resultExecutor = queuedResults
        )
        val generation = observer.beginObservationWindow(1_000L)
        assertTrue(observer.requestObservation(generation, 2_000L) {
            callbackCount.incrementAndGet()
        })
        awaitCondition { !observer.healthSnapshot().queryInFlight && queuedResults.size == 1 }

        observer.close()
        queuedResults.runAt(0)

        assertEquals(0, callbackCount.get())
    }

    @Test
    fun `关闭后忽略进行中结果并拒绝新查询`() {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val queryFinished = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val observer = createWorker(source { nowMillis ->
            queryStarted.countDown()
            releaseQuery.awaitIgnoringInterrupt()
            queryFinished.countDown()
            availableObservation("example.allowed", nowMillis)
        })
        val generation = observer.beginObservationWindow(1_000L)
        assertTrue(observer.requestObservation(generation, 2_000L) {
            callbackCount.incrementAndGet()
        })
        assertTrue(queryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        observer.close()
        releaseQuery.countDown()

        assertTrue(queryFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, callbackCount.get())
        assertFalse(observer.requestObservation(generation, 3_000L) {
            callbackCount.incrementAndGet()
        })
    }

    private fun createWorker(
        source: ForegroundObservationSource,
        resultExecutor: Executor = Executor(Runnable::run),
        queryExecutor: ExecutorService? = null,
        queryExecutorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() },
        watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
        monotonicClockMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        queryTimeoutMillis: Long = 2_000L,
        circuitOpenMillis: Long = 5_000L,
        maxRetiredExecutors: Int = 2
    ): ForegroundObservationWorker = ForegroundObservationWorker(
        source = source,
        resultExecutor = resultExecutor,
        queryExecutor = queryExecutor,
        queryExecutorFactory = queryExecutorFactory,
        watchdogExecutor = watchdogExecutor,
        monotonicClockMillis = monotonicClockMillis,
        queryTimeoutMillis = queryTimeoutMillis,
        circuitOpenMillis = circuitOpenMillis,
        maxRetiredExecutors = maxRetiredExecutors,
        retirementRegistry = retirementRegistry
    ).also(workers::add)

    private fun source(
        query: (Long) -> ForegroundObservation
    ): ForegroundObservationSource = object : ForegroundObservationSource {
        private val lease = AtomicLong()

        override fun beginObservationWindow(anchorWallMillis: Long) = Unit

        override fun resetObservationWindow() = Unit

        override fun captureObservationLease(): Long = lease.incrementAndGet()

        override fun getForegroundObservation(
            nowMillis: Long,
            observationLease: Long
        ): ForegroundObservation =
            query(nowMillis)
    }

    private fun requestAndAwait(
        observer: ForegroundObservationWorker,
        generation: Long,
        nowWallMillis: Long
    ): ForegroundObservation {
        val result = AtomicReference<ForegroundObservation>()
        val delivered = CountDownLatch(1)
        assertTrue(observer.requestObservation(generation, nowWallMillis) { observation ->
            result.set(observation)
            delivered.countDown()
        })
        assertTrue(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return result.get()
    }

    private fun namedExecutorFactory(created: AtomicInteger): () -> ExecutorService = {
        val index = created.incrementAndGet()
        Executors.newSingleThreadExecutor { task -> Thread(task, "query-$index") }
    }

    private fun availableObservation(
        packageName: String,
        nowMillis: Long
    ) = ForegroundObservation(
        packageName = packageName,
        transitionTimestampMillis = nowMillis,
        newEvents = emptyList(),
        status = ForegroundObservationStatus.AVAILABLE
    )

    private fun CountDownLatch.awaitOrFail() {
        assertTrue(await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun CountDownLatch.awaitIgnoringInterrupt() {
        while (count > 0L) {
            try {
                await(25L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // 模拟无法被 Future.cancel(true) 终止的 Binder 调用。
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.yield()
        }
        assertTrue("condition was not met before timeout", false)
    }

    private class QueueingExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        val size: Int
            get() = synchronized(tasks) { tasks.size }

        override fun execute(command: Runnable) {
            synchronized(tasks) { tasks += command }
        }

        fun runAt(index: Int) {
            val task = synchronized(tasks) { tasks[index] }
            task.run()
        }
    }

    private class DelayedExecutorService : AbstractExecutorService() {
        private val pending = mutableListOf<Runnable>()
        private val shutdown = AtomicBoolean(false)
        private val running = AtomicBoolean(false)

        override fun shutdown() {
            shutdown.set(true)
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown.set(true)
            // 故意保留已接收任务，模拟 shutdownNow 后仍可能进入 Binder 的厂商执行器。
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown.get()

        override fun isTerminated(): Boolean =
            shutdown.get() && synchronized(pending) { pending.isEmpty() } && !running.get()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

        override fun execute(command: Runnable) {
            synchronized(pending) { pending += command }
        }

        fun runPendingTask() {
            val task = synchronized(pending) { pending.removeAt(0) }
            running.set(true)
            try {
                task.run()
            } finally {
                running.set(false)
            }
        }
    }

    private class RejectingExecutorService : AbstractExecutorService() {
        private val shutdown = AtomicBoolean(false)

        override fun shutdown() {
            shutdown.set(true)
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown.set(true)
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown.get()

        override fun isTerminated(): Boolean = shutdown.get()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown.get()

        override fun execute(command: Runnable) {
            throw RejectedExecutionException("rejected for test")
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val SHORT_TIMEOUT_MILLIS = 500L
    }
}
