package com.example.controlfree

import com.example.controlfree.security.VerificationResult
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 串行执行锁屏凭据校验，并仅向仍有效的界面代次交付结果。
 */
internal class LockAuthenticationExecutor(
    private val resultExecutor: Executor,
    private val workerExecutor: ExecutorService = createWorkerExecutor()
) : AutoCloseable {
    private val stateLock = Any()
    private var generation = 0L
    private var isBusy = false
    private var isClosed = false

    fun submit(
        task: () -> VerificationResult,
        onResult: (Result<VerificationResult>) -> Unit
    ): Boolean {
        val taskGeneration = synchronized(stateLock) {
            if (isClosed || isBusy) return false
            isBusy = true
            generation
        }

        return try {
            workerExecutor.execute {
                val result = try {
                    Result.success(task())
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    Result.failure(error)
                }
                publishResult(taskGeneration, result, onResult)
            }
            true
        } catch (_: RuntimeException) {
            synchronized(stateLock) {
                if (!isClosed && generation == taskGeneration) isBusy = false
            }
            false
        }
    }

    /** 使当前任务的迟到结果失效，同时允许新界面代次提交任务。 */
    fun invalidate() {
        synchronized(stateLock) {
            if (isClosed) return
            generation = nextGeneration(generation)
            isBusy = false
        }
    }

    private fun publishResult(
        taskGeneration: Long,
        result: Result<VerificationResult>,
        onResult: (Result<VerificationResult>) -> Unit
    ) {
        try {
            resultExecutor.execute {
                val shouldDeliver = synchronized(stateLock) {
                    if (isClosed || generation != taskGeneration) {
                        false
                    } else {
                        isBusy = false
                        true
                    }
                }
                if (shouldDeliver) onResult(result)
            }
        } catch (_: RuntimeException) {
            synchronized(stateLock) {
                if (!isClosed && generation == taskGeneration) isBusy = false
            }
        }
    }

    override fun close() {
        val shouldShutdown = synchronized(stateLock) {
            if (isClosed) {
                false
            } else {
                isClosed = true
                generation = nextGeneration(generation)
                isBusy = false
                true
            }
        }
        if (shouldShutdown) workerExecutor.shutdownNow()
    }

    private companion object {
        fun createWorkerExecutor(): ExecutorService = Executors.newSingleThreadExecutor(
            ThreadFactory { task ->
                Thread(task, "controlfree-lock-authentication").apply { isDaemon = true }
            }
        )

        fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}
