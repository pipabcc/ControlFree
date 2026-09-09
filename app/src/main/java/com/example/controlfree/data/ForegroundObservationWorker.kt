package com.example.controlfree.data

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * 前台观察数据源。窗口控制必须只修改内存状态，不能执行系统查询。
 */
interface ForegroundObservationSource {
    fun beginObservationWindow(anchorWallMillis: Long)

    fun resetObservationWindow()

    /** 捕获与本次查询绑定的 source revision；迟到任务不得重新取得新窗口的 revision。 */
    fun captureObservationLease(): Long

    fun getForegroundObservation(
        nowMillis: Long,
        observationLease: Long
    ): ForegroundObservation
}

data class ForegroundObservationWorkerHealth(
    val circuit: ForegroundObservationCircuitSnapshot,
    val executorEpoch: Long,
    val retiredExecutorCount: Int,
    val queryInFlight: Boolean
)

class ForegroundObservationRetirementRegistry internal constructor() {
    private val lock = Any()
    private val retiredExecutors = ArrayDeque<ExecutorService>()

    internal fun add(executor: ExecutorService) = synchronized(lock) {
        if (retiredExecutors.none { it === executor }) retiredExecutors.addLast(executor)
    }

    internal fun purge() = synchronized(lock) {
        val iterator = retiredExecutors.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isTerminated) iterator.remove()
        }
    }

    internal fun count(): Int = synchronized(lock) { retiredExecutors.size }
}

/**
 * 在专用线程查询 UsageStats，并用逻辑超时、熔断和执行器轮换从厂商 Binder 卡死中恢复。
 *
 * `Future.cancel(true)` 不能保证中断 Binder，所以超时后会立即让旧 source revision 失效、退役
 * 旧执行器并在新线程继续。迟到结果只有 attemptId、generation 和 executorEpoch 都匹配时才可发布。
 */
class ForegroundObservationWorker(
    private val source: ForegroundObservationSource,
    private val resultExecutor: Executor,
    queryExecutor: ExecutorService? = null,
    private val queryExecutorFactory: () -> ExecutorService = ::createQueryExecutor,
    private val watchdogExecutor: ScheduledExecutorService = createWatchdogExecutor(),
    private val monotonicClockMillis: () -> Long = ::monotonicNowMillis,
    private val queryTimeoutMillis: Long = DEFAULT_QUERY_TIMEOUT_MILLIS,
    circuitFailureThreshold: Int = DEFAULT_CIRCUIT_FAILURE_THRESHOLD,
    circuitOpenMillis: Long = DEFAULT_CIRCUIT_OPEN_MILLIS,
    private val maxRetiredExecutors: Int = DEFAULT_MAX_RETIRED_EXECUTORS,
    private val retirementRegistry: ForegroundObservationRetirementRegistry =
        processRetirementRegistry
) : AutoCloseable {
    private val stateLock = Any()
    private val circuitBreaker = ForegroundObservationCircuitBreaker(
        failureThreshold = circuitFailureThreshold,
        openDurationMillis = circuitOpenMillis
    )
    private var generation = 0L
    private var nextAttemptId = 0L
    private var latestTerminalAttemptId = 0L
    private var executorEpoch = if (queryExecutor == null) 0L else 1L
    private var activeWindowAnchorWallMillis: Long? = null
    private var currentExecutor: ExecutorService? = queryExecutor
    private var currentAttempt: QueryAttempt? = null
    private var isClosed = false

    init {
        require(queryTimeoutMillis > 0L) { "queryTimeoutMillis must be positive" }
        require(maxRetiredExecutors > 0) { "maxRetiredExecutors must be positive" }
    }

    /** 开启新窗口，并使上一窗口的查询、超时和回调全部失效。 */
    fun beginObservationWindow(anchorWallMillis: Long): Long {
        require(anchorWallMillis >= 0L) { "anchorWallMillis must not be negative" }

        return synchronized(stateLock) {
            check(!isClosed) { "ForegroundObservationWorker is closed" }
            generation = nextGeneration(generation)
            cancelCurrentAttemptAndRetireExecutorLocked()
            activeWindowAnchorWallMillis = null
            try {
                source.beginObservationWindow(anchorWallMillis)
                activeWindowAnchorWallMillis = anchorWallMillis
            } catch (error: RuntimeException) {
                activeWindowAnchorWallMillis = null
                throw error
            }
            generation
        }
    }

    /** 关闭当前窗口；迟到查询不会再发布，也不会污染下一窗口。 */
    fun resetObservationWindow() {
        synchronized(stateLock) {
            if (isClosed) return
            generation = nextGeneration(generation)
            cancelCurrentAttemptAndRetireExecutorLocked()
            activeWindowAnchorWallMillis = null
            source.resetObservationWindow()
        }
    }

    /**
     * 返回 false 表示窗口无效、已关闭或已有查询；返回 true 的路径最多异步回调一次终态。
     */
    fun requestObservation(
        expectedGeneration: Long,
        nowWallMillis: Long = System.currentTimeMillis(),
        onResult: (ForegroundObservation) -> Unit
    ): Boolean {
        require(nowWallMillis >= 0L) { "nowWallMillis must not be negative" }

        var immediateResult: ForegroundObservation? = null
        var immediateAttemptId = 0L
        val accepted = synchronized(stateLock) {
            if (
                isClosed ||
                activeWindowAnchorWallMillis == null ||
                expectedGeneration != generation ||
                currentAttempt != null
            ) {
                return@synchronized false
            }

            val nowElapsedMillis = monotonicNowLocked()
            val attemptId = nextAttemptId()
            if (
                circuitBreaker.beforeRequest(nowElapsedMillis) ==
                ForegroundObservationCircuitDecision.REJECT
            ) {
                latestTerminalAttemptId = attemptId
                immediateAttemptId = attemptId
                immediateResult = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.CIRCUIT_OPEN
                )
                return@synchronized true
            }

            purgeRetiredExecutorsLocked()
            val executor = try {
                ensureQueryExecutorLocked()
            } catch (_: RuntimeException) {
                circuitBreaker.recordFailure(nowElapsedMillis)
                latestTerminalAttemptId = attemptId
                immediateAttemptId = attemptId
                immediateResult = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.QUERY_FAILED
                )
                return@synchronized true
            }
            if (executor == null) {
                circuitBreaker.forceOpen(nowElapsedMillis)
                latestTerminalAttemptId = attemptId
                immediateAttemptId = attemptId
                immediateResult = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.CIRCUIT_OPEN
                )
                return@synchronized true
            }

            val observationLease = try {
                source.captureObservationLease()
            } catch (_: RuntimeException) {
                circuitBreaker.recordFailure(nowElapsedMillis)
                latestTerminalAttemptId = attemptId
                immediateAttemptId = attemptId
                immediateResult = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.QUERY_FAILED
                )
                return@synchronized true
            }

            val attempt = QueryAttempt(
                id = attemptId,
                generation = generation,
                executorEpoch = executorEpoch,
                executor = executor,
                observationLease = observationLease,
                onResult = onResult
            )
            currentAttempt = attempt
            try {
                attempt.timeoutFuture = watchdogExecutor.schedule(
                    { handleTimeout(attempt) },
                    queryTimeoutMillis,
                    TimeUnit.MILLISECONDS
                )
            } catch (_: RuntimeException) {
                currentAttempt = null
                circuitBreaker.recordFailure(nowElapsedMillis)
                latestTerminalAttemptId = attempt.id
                immediateAttemptId = attempt.id
                immediateResult = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.QUERY_FAILED
                )
                return@synchronized true
            }
            try {
                executor.execute { performQuery(attempt, nowWallMillis) }
            } catch (_: RuntimeException) {
                attempt.timeoutFuture?.cancel(false)
                currentAttempt = null
                circuitBreaker.recordFailure(nowElapsedMillis)
                retireExecutorLocked(executor)
                latestTerminalAttemptId = attempt.id
                immediateAttemptId = attempt.id
                immediateResult = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.QUERY_FAILED
                )
            }
            true
        }

        immediateResult?.let { result ->
            publishResult(expectedGeneration, immediateAttemptId, result, onResult)
        }
        return accepted
    }

    fun healthSnapshot(): ForegroundObservationWorkerHealth = synchronized(stateLock) {
        purgeRetiredExecutorsLocked()
        ForegroundObservationWorkerHealth(
            circuit = circuitBreaker.snapshot(),
            executorEpoch = executorEpoch,
            retiredExecutorCount = processRetiredExecutorCountLocked(),
            queryInFlight = currentAttempt != null
        )
    }

    private fun performQuery(attempt: QueryAttempt, nowWallMillis: Long) {
        val observation = try {
            source.getForegroundObservation(nowWallMillis, attempt.observationLease)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
        }
        completeAttempt(attempt, observation)
    }

    private fun completeAttempt(
        attempt: QueryAttempt,
        observation: ForegroundObservation
    ) {
        val shouldPublish = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized false
            currentAttempt = null
            attempt.timeoutFuture?.cancel(false)
            latestTerminalAttemptId = attempt.id
            val nowElapsedMillis = monotonicNowLocked()
            when (observation.status) {
                ForegroundObservationStatus.AVAILABLE,
                ForegroundObservationStatus.ACCESS_DENIED ->
                    circuitBreaker.recordHealthyResponse()

                ForegroundObservationStatus.QUERY_FAILED,
                ForegroundObservationStatus.TIMEOUT ->
                    circuitBreaker.recordFailure(nowElapsedMillis)

                ForegroundObservationStatus.CIRCUIT_OPEN ->
                    circuitBreaker.forceOpen(nowElapsedMillis)
            }
            !isClosed && activeWindowAnchorWallMillis != null && attempt.generation == generation
        }
        if (shouldPublish) {
            publishResult(attempt.generation, attempt.id, observation, attempt.onResult)
        }
    }

    private fun handleTimeout(attempt: QueryAttempt) {
        val shouldPublish = synchronized(stateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized false
            currentAttempt = null
            latestTerminalAttemptId = attempt.id
            val nowElapsedMillis = monotonicNowLocked()
            circuitBreaker.recordFailure(nowElapsedMillis)
            invalidateSourceRevisionLocked()
            retireExecutorLocked(attempt.executor)
            !isClosed && activeWindowAnchorWallMillis != null && attempt.generation == generation
        }
        if (shouldPublish) {
            publishResult(
                attempt.generation,
                attempt.id,
                ForegroundObservation.unavailable(ForegroundObservationStatus.TIMEOUT),
                attempt.onResult
            )
        }
    }

    private fun invalidateSourceRevisionLocked() {
        val anchor = activeWindowAnchorWallMillis ?: return
        try {
            source.beginObservationWindow(anchor)
        } catch (_: RuntimeException) {
            // 数据源窗口无法重建时仍保持失败关闭；下一次查询会再次返回明确失败。
        }
    }

    private fun isCurrentAttemptLocked(attempt: QueryAttempt): Boolean {
        val current = currentAttempt ?: return false
        return !isClosed &&
            current.id == attempt.id &&
            current.generation == attempt.generation &&
            current.executorEpoch == attempt.executorEpoch
    }

    private fun ensureQueryExecutorLocked(): ExecutorService? {
        currentExecutor?.let { executor ->
            if (!executor.isShutdown && !executor.isTerminated) return executor
            currentExecutor = null
        }
        purgeRetiredExecutorsLocked()
        if (processRetiredExecutorCountLocked() >= maxRetiredExecutors) return null
        val executor = queryExecutorFactory()
        executorEpoch = nextGeneration(executorEpoch)
        currentExecutor = executor
        return executor
    }

    private fun cancelCurrentAttemptAndRetireExecutorLocked() {
        val attempt = currentAttempt ?: return
        currentAttempt = null
        attempt.timeoutFuture?.cancel(false)
        retireExecutorLocked(attempt.executor)
    }

    private fun retireExecutorLocked(executor: ExecutorService) {
        if (currentExecutor === executor) currentExecutor = null
        executor.shutdownNow()
        if (!executor.isTerminated) {
            retirementRegistry.add(executor)
        }
    }

    private fun purgeRetiredExecutorsLocked() {
        retirementRegistry.purge()
    }

    private fun processRetiredExecutorCountLocked(): Int = retirementRegistry.count()

    private fun publishResult(
        queryGeneration: Long,
        terminalAttemptId: Long,
        observation: ForegroundObservation,
        onResult: (ForegroundObservation) -> Unit
    ) {
        try {
            resultExecutor.execute {
                synchronized(stateLock) {
                    if (
                        isClosed ||
                        activeWindowAnchorWallMillis == null ||
                        queryGeneration != generation ||
                        terminalAttemptId != latestTerminalAttemptId
                    ) {
                        return@synchronized
                    }
                    // 与 begin/reset/close 共用同一锁，交付在线性化点之后不可被旧生命周期穿透。
                    onResult(observation)
                }
            }
        } catch (_: RuntimeException) {
            // 结果执行器已关闭时不能回退到查询线程，否则会破坏线程边界。
        }
    }

    override fun close() {
        val idleExecutorsToClose = mutableListOf<ExecutorService>()
        synchronized(stateLock) {
            if (isClosed) return
            isClosed = true
            generation = nextGeneration(generation)
            val attempt = currentAttempt
            attempt?.timeoutFuture?.cancel(false)
            currentAttempt = null
            activeWindowAnchorWallMillis = null
            if (attempt != null) {
                retireExecutorLocked(attempt.executor)
            } else {
                currentExecutor?.let(idleExecutorsToClose::add)
            }
            currentExecutor = null
            try {
                source.resetObservationWindow()
            } catch (_: RuntimeException) {
                // close 必须继续释放线程资源。
            }
        }
        idleExecutorsToClose.distinct().forEach(ExecutorService::shutdownNow)
        retirementRegistry.purge()
        watchdogExecutor.shutdownNow()
    }

    private fun monotonicNowLocked(): Long = monotonicClockMillis().coerceAtLeast(0L)

    private fun nextAttemptId(): Long {
        nextAttemptId = nextGeneration(nextAttemptId)
        return nextAttemptId
    }

    private data class QueryAttempt(
        val id: Long,
        val generation: Long,
        val executorEpoch: Long,
        val executor: ExecutorService,
        val observationLease: Long,
        val onResult: (ForegroundObservation) -> Unit,
        var timeoutFuture: ScheduledFuture<*>? = null
    )

    companion object {
        const val DEFAULT_QUERY_TIMEOUT_MILLIS = 2_000L
        const val DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 3
        const val DEFAULT_CIRCUIT_OPEN_MILLIS = 5_000L
        const val DEFAULT_MAX_RETIRED_EXECUTORS = 3

        private val processRetirementRegistry = ForegroundObservationRetirementRegistry()

        private fun createQueryExecutor(): ExecutorService = Executors.newSingleThreadExecutor(
            ThreadFactory { task ->
                Thread(task, "controlfree-foreground-observer").apply { isDaemon = true }
            }
        )

        private fun createWatchdogExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(
                ThreadFactory { task ->
                    Thread(task, "controlfree-foreground-watchdog").apply { isDaemon = true }
                }
            )

        private fun monotonicNowMillis(): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime()).coerceAtLeast(0L)

        private fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}
