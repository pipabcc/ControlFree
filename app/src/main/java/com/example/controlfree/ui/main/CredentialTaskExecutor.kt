package com.example.controlfree.ui.main

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 串行执行耗时的凭据操作，并只向仍有效的界面代次交付结果。
 *
 * PBKDF2 不保证响应线程中断，因此失效操作不依赖强制终止工作线程；旧任务即使稍后完成，
 * 也无法再更新已经切换或销毁的界面。
 */
internal class CredentialTaskExecutor(
    private val resultExecutor: Executor,
    private val workerExecutor: ExecutorService = createWorkerExecutor()
) : AutoCloseable {
    private val stateLock = Any()
    private var generation = 0L
    private var isBusy = false
    private var isClosed = false

    fun <T> submit(
        task: () -> T,
        onResult: (Result<T>) -> Unit
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

    /** 使当前操作的迟到结果失效，并允许新界面代次提交下一项操作。 */
    fun invalidate() {
        synchronized(stateLock) {
            if (isClosed) return
            generation = nextGeneration(generation)
            isBusy = false
        }
    }

    private fun <T> publishResult(
        taskGeneration: Long,
        result: Result<T>,
        onResult: (Result<T>) -> Unit
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
                Thread(task, "controlfree-credential").apply { isDaemon = true }
            }
        )

        fun nextGeneration(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }
}
