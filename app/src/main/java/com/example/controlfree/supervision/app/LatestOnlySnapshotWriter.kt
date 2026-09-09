package com.example.controlfree.supervision.app

import java.util.concurrent.Executor

/**
 * 单写入通道的 latest-only 队列。
 *
 * 快照是可替换状态，不需要把每个中间版本都写入磁盘。写入进行期间只保留最新版本；
 * 清理请求会丢弃尚未开始的快照，并在正在进行的写入结束后串行执行。关闭后拒绝新提交，
 * 但会排空已经排队的最新快照或清理操作。
 */
class LatestOnlySnapshotWriter<T>(
    private val executor: Executor,
    private val write: (T) -> Boolean,
    private val clear: () -> Boolean,
    private val onWriteFailure: () -> Unit = {}
) : AutoCloseable {
    private val lock = Any()
    private var pendingValue: T? = null
    private var hasPendingValue = false
    private var clearCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var draining = false
    private var closed = false

    /** 返回 false 表示队列已关闭或正在等待清理。 */
    fun submit(value: T): Boolean {
        val shouldDispatch = synchronized(lock) {
            if (closed || clearCallbacks.isNotEmpty()) return@synchronized null
            pendingValue = value
            hasPendingValue = true
            val dispatch = !draining
            draining = true
            dispatch
        } ?: return false

        if (shouldDispatch && !dispatchDrain()) return false
        return true
    }

    /** 清理会覆盖排队中的旧快照，但会等待当前正在进行的写入完成。 */
    fun requestClear(onComplete: (Boolean) -> Unit = {}): Boolean {
        val shouldDispatch = synchronized(lock) {
            if (closed) return@synchronized null
            pendingValue = null
            hasPendingValue = false
            clearCallbacks += onComplete
            val dispatch = !draining
            draining = true
            dispatch
        } ?: return false

        if (shouldDispatch && !dispatchDrain()) return false
        return true
    }

    private fun dispatchDrain(): Boolean = try {
        executor.execute(::drain)
        true
    } catch (_: RuntimeException) {
        val callbacks = synchronized(lock) {
            pendingValue = null
            hasPendingValue = false
            draining = false
            val pendingCallbacks = clearCallbacks
            clearCallbacks = mutableListOf()
            pendingCallbacks
        }
        onWriteFailure()
        callbacks.forEach { callback -> invokeCallback(callback, false) }
        false
    }

    private fun drain() {
        while (true) {
            var value: T? = null
            var shouldWrite = false
            val callbacks: List<(Boolean) -> Unit>
            synchronized(lock) {
                if (hasPendingValue) {
                    value = pendingValue
                    pendingValue = null
                    hasPendingValue = false
                    shouldWrite = true
                    callbacks = emptyList()
                } else if (clearCallbacks.isNotEmpty()) {
                    callbacks = clearCallbacks
                    clearCallbacks = mutableListOf()
                } else {
                    draining = false
                    return
                }
            }

            if (shouldWrite) {
                val succeeded = try {
                    @Suppress("UNCHECKED_CAST")
                    write(value as T)
                } catch (_: Exception) {
                    false
                }
                if (!succeeded) onWriteFailure()
            } else {
                val succeeded = try {
                    clear()
                } catch (_: Exception) {
                    false
                }
                callbacks.forEach { callback -> invokeCallback(callback, succeeded) }
            }
        }
    }

    private fun invokeCallback(callback: (Boolean) -> Unit, result: Boolean) {
        try {
            callback(result)
        } catch (_: RuntimeException) {
            // 回调通常只是把结果投递回主线程；单个调用方异常不能终止写入队列。
        }
    }

    override fun close() {
        val shouldDispatch = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                if (!draining && (hasPendingValue || clearCallbacks.isNotEmpty())) {
                    draining = true
                    true
                } else {
                    false
                }
            }
        }
        if (shouldDispatch) dispatchDrain()
    }
}
