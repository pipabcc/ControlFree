package com.example.controlfree

import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

data class MonitorSnapshotPersistResult(
    val snapshot: MonitorCycleSnapshot,
    val pauseState: MonitorPauseState? = null,
    val guardArmed: Boolean,
    val progressSaved: Boolean,
    val guardCleared: Boolean,
    val guardCommitted: Boolean = guardCleared
) {
    val isSuccess: Boolean
        get() = guardArmed && progressSaved && guardCommitted
}

data class MonitorSnapshotPersistRequest(
    val snapshot: MonitorCycleSnapshot,
    val scheduledOwner: ScheduledMonitorOwner?,
    val sessionMode: MonitorSessionMode,
    val usageMinutes: Int,
    val lockMinutes: Int,
    val pauseState: MonitorPauseState? = null,
    val appliedGrowthOrderIds: Set<String> = emptySet(),
    val focusTaskTitle: String? = null,
    val focusSourceTodoId: String? = null
) {
    init {
        require(usageMinutes in 1..1_440)
        require(lockMinutes in 1..1_440)
        require(pauseState == null || pauseState.isStructurallyValid)
        require(pauseState?.isActive != true || snapshot.phase == MonitorPhase.LOCK)
        require(appliedGrowthOrderIds.size <= 64)
        require(appliedGrowthOrderIds.all(::isValidGrowthOrderId))
        require(focusTaskTitle == null || focusTaskTitle.isValidFocusMetadata(80))
        require(focusSourceTodoId == null || focusSourceTodoId.isValidFocusMetadata(160))
        require(sessionMode == MonitorSessionMode.FOCUS ||
            (focusTaskTitle == null && focusSourceTodoId == null))
        require(focusSourceTodoId == null || focusTaskTitle != null)
    }
}

private fun isValidGrowthOrderId(orderId: String): Boolean =
    orderId.length in 8..160 && orderId.all { character ->
        character.isLetterOrDigit() || character == '-' || character == '_'
    }

private fun String.isValidFocusMetadata(maxLength: Int): Boolean =
    isNotBlank() && this == trim() && length <= maxLength

data class MonitorStateClearResult(
    val progressCleared: Boolean,
    val guardCleared: Boolean
) {
    val isSuccess: Boolean
        get() = progressCleared && guardCleared
}

/**
 * 将频繁的监督快照写入合并到单一后台线程，避免 fsync 阻塞锁屏交互和屏幕广播。
 */
class MonitorSnapshotPersistenceWorker(
    private val persistOperation: (MonitorSnapshotPersistRequest) -> MonitorSnapshotPersistResult,
    private val resultExecutor: Executor,
    private val ioExecutor: ExecutorService = createIoExecutor()
) : AutoCloseable {
    private val stateLock = Any()
    private var pendingRequest: PersistRequest? = null
    private var pendingClearRequest: ClearRequest? = null
    private var isDrainScheduled = false
    private var isClosed = false
    private var acceptsSnapshots = true

    /**
     * 已关闭或已停止接受快照的 worker 不能被后续 Service 实例复用：这两个状态都
     * 不可逆，复用会让本进程之后的所有落盘永久失败，表现为"只能重启手机"。
     */
    val isUsable: Boolean
        get() = synchronized(stateLock) { !isClosed && acceptsSnapshots }

    fun submit(
        request: MonitorSnapshotPersistRequest,
        onResult: (MonitorSnapshotPersistResult) -> Unit
    ): Boolean {
        val shouldSchedule = synchronized(stateLock) {
            if (isClosed || !acceptsSnapshots) return false
            val previous = pendingRequest
            val callbacks = previous?.onResults.orEmpty() + onResult
            val mergedAppliedOrderIds =
                previous?.request?.appliedGrowthOrderIds.orEmpty() +
                    request.appliedGrowthOrderIds
            // order ID 集合异常膨胀时截断保留最近的部分继续落盘，
            // 绝不因防御上限丢弃整份快照（否则恢复时会按旧进度重锁）。
            val boundedAppliedOrderIds =
                if (mergedAppliedOrderIds.size > MAX_MERGED_APPLIED_ORDER_IDS) {
                    mergedAppliedOrderIds.toList().takeLast(MAX_MERGED_APPLIED_ORDER_IDS).toSet()
                } else {
                    mergedAppliedOrderIds
                }
            val mergedRequest = if (previous == null) {
                request
            } else {
                request.copy(
                    appliedGrowthOrderIds = boundedAppliedOrderIds
                )
            }
            pendingRequest = PersistRequest(mergedRequest, callbacks)
            if (isDrainScheduled) {
                false
            } else {
                isDrainScheduled = true
                true
            }
        }
        if (!shouldSchedule) return true

        return scheduleDrain()
    }

    /** 丢弃未开始的快照，并在当前写入之后串行清除监督状态。 */
    fun submitClear(
        clearOperation: () -> MonitorStateClearResult,
        onResult: (MonitorStateClearResult) -> Unit
    ): Boolean {
        var cancelledPersistRequest: PersistRequest? = null
        val shouldSchedule = synchronized(stateLock) {
            if (isClosed || pendingClearRequest != null) return false
            acceptsSnapshots = false
            cancelledPersistRequest = pendingRequest
            pendingRequest = null
            pendingClearRequest = ClearRequest(clearOperation, onResult)
            if (isDrainScheduled) {
                false
            } else {
                isDrainScheduled = true
                true
            }
        }
        cancelledPersistRequest?.let(::publishCancelledPersistResult)
        if (!shouldSchedule) return true
        return scheduleDrain()
    }

    fun resumeSnapshotsAfterClearFailure() {
        synchronized(stateLock) {
            if (!isClosed && pendingClearRequest == null) acceptsSnapshots = true
        }
    }

    private fun drain() {
        while (true) {
            val work = synchronized(stateLock) {
                pendingClearRequest?.also { pendingClearRequest = null }
                    ?: pendingRequest?.also { pendingRequest = null }
            } ?: run {
                synchronized(stateLock) {
                    if (pendingRequest == null && pendingClearRequest == null) {
                        isDrainScheduled = false
                        return
                    }
                }
                continue
            }

            when (work) {
                is PersistRequest -> publishPersistResult(work)
                is ClearRequest -> publishClearResult(work)
            }
        }
    }

    private fun publishPersistResult(request: PersistRequest) {
        val result = try {
            persistOperation(request.request)
        } catch (_: RuntimeException) {
            MonitorSnapshotPersistResult(
                snapshot = request.request.snapshot,
                pauseState = request.request.pauseState,
                guardArmed = false,
                progressSaved = false,
                guardCleared = false
            )
        }
        try {
            resultExecutor.execute {
                request.onResults.forEach { onResult -> onResult(result) }
            }
        } catch (_: RuntimeException) {
            // Service 主线程已销毁时，磁盘结果无需再更新 UI 状态。
        }
    }

    private fun publishCancelledPersistResult(request: PersistRequest) {
        val result = MonitorSnapshotPersistResult(
            snapshot = request.request.snapshot,
            pauseState = request.request.pauseState,
            guardArmed = false,
            progressSaved = false,
            guardCleared = false
        )
        try {
            resultExecutor.execute {
                request.onResults.forEach { onResult -> onResult(result) }
            }
        } catch (_: RuntimeException) {
            // Service 已销毁时，取消结果无需再触碰 UI 状态。
        }
    }

    private fun publishClearResult(request: ClearRequest) {
        val result = try {
            request.operation()
        } catch (_: RuntimeException) {
            MonitorStateClearResult(progressCleared = false, guardCleared = false)
        }
        try {
            resultExecutor.execute { request.onResult(result) }
        } catch (_: RuntimeException) {
            // Service 主线程已销毁时，不再尝试触碰锁屏资源。
        }
    }

    private fun scheduleDrain(): Boolean = try {
        ioExecutor.execute(::drain)
        true
    } catch (_: RuntimeException) {
        synchronized(stateLock) {
            isDrainScheduled = false
            pendingRequest = null
            pendingClearRequest = null
            if (!isClosed) acceptsSnapshots = true
        }
        false
    }

    override fun close() {
        synchronized(stateLock) {
            isClosed = true
            acceptsSnapshots = false
        }
        // 已排队的 drain 仍应完成最后一份快照，避免服务销毁时丢失进度。
        ioExecutor.shutdown()
    }

    private companion object {
        private const val MAX_MERGED_APPLIED_ORDER_IDS = 64

        fun createIoExecutor(): ExecutorService = Executors.newSingleThreadExecutor(
            ThreadFactory { task ->
                Thread(task, "controlfree-monitor-persistence").apply { isDaemon = true }
            }
        )
    }

    private data class PersistRequest(
        val request: MonitorSnapshotPersistRequest,
        val onResults: List<(MonitorSnapshotPersistResult) -> Unit>
    )

    private data class ClearRequest(
        val operation: () -> MonitorStateClearResult,
        val onResult: (MonitorStateClearResult) -> Unit
    )
}
