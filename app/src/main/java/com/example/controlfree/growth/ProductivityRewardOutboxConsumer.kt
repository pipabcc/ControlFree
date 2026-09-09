package com.example.controlfree.growth

import android.content.Context
import android.util.Log
import com.example.controlfree.todo.ProductivityRewardEventEntity
import com.example.controlfree.todo.ProductivityRewardPolicy
import com.example.controlfree.todo.ProductivityRewardType
import com.example.controlfree.todo.TodoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class RewardOutboxBatchResult(
    val processedCount: Int,
    val invalidCount: Int,
    val failedCount: Int
)

/**
 * 保持 outbox 与成长账本之间的关键顺序：先幂等入账，成功后再确认事件。
 * 若进程在两步之间终止，下次会用同一 sourceKey 重试，不会重复奖励。
 */
internal class ProductivityRewardOutboxProcessor(
    private val award: suspend (ProductivityRewardRequest) -> Unit,
    private val markProcessed: suspend (eventId: String, processedAtEpochMillis: Long) -> Boolean,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun process(events: List<ProductivityRewardEventEntity>): RewardOutboxBatchResult {
        var processed = 0
        var invalid = 0
        var failed = 0

        events.forEach { event ->
            val request = event.toProductivityRewardRequest()
            if (request == null) {
                invalid++
                return@forEach
            }
            try {
                award(request)
                if (markProcessed(event.id, nowEpochMillis().coerceAtLeast(0L))) {
                    processed++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
        }
        return RewardOutboxBatchResult(processed, invalid, failed)
    }
}

internal fun ProductivityRewardEventEntity.toProductivityRewardRequest(): ProductivityRewardRequest? {
    val (reason, expectedPoints) = when (rewardType) {
        ProductivityRewardType.TODO_COMPLETED.storedValue ->
            GrowthLedgerReason.TODO_COMPLETED to ProductivityRewardPolicy.TODO_COMPLETION_POINTS
        ProductivityRewardType.HABIT_TARGET_REACHED.storedValue ->
            GrowthLedgerReason.HABIT_CHECKED_IN to ProductivityRewardPolicy.HABIT_TARGET_POINTS
        ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue ->
            GrowthLedgerReason.QUICK_NOTE_CAPTURED to ProductivityRewardPolicy.QUICK_NOTE_POINTS
        else -> return null
    }
    if (balancePoints != expectedPoints || experiencePoints != expectedPoints) return null
    if (sourceKey.isBlank() || createdAtEpochMillis < 0L) return null
    return ProductivityRewardRequest(
        sourceKey = sourceKey,
        reason = reason,
        rewardPoints = balancePoints,
        experiencePoints = experiencePoints,
        occurredAtEpochMillis = createdAtEpochMillis
    )
}

/**
 * 为进程级消费者复用同一个活动任务，避免每次进入清单页面都创建重复订阅。
 * 任务结束或被取消后允许下一次入口重新启动，保证异常恢复不会被旧任务状态卡住。
 */
internal class RewardOutboxConsumerLauncher(
    private val launch: () -> Job
) {
    private var activeJob: Job? = null

    @Synchronized
    fun start(): Job {
        activeJob?.takeIf { it.isActive }?.let { return it }
        return launch().also { activeJob = it }
    }
}

class ProductivityRewardOutboxConsumer private constructor(context: Context) {
    private val todoRepository = TodoRepository.getInstance(context.applicationContext)
    private val growthRepository = GrowthRepository.getInstance(context.applicationContext)
    private val processor = ProductivityRewardOutboxProcessor(
        award = { request -> growthRepository.awardProductivity(request) },
        markProcessed = todoRepository::markRewardEventProcessed
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processLauncher = RewardOutboxConsumerLauncher { startIn(processScope) }

    /**
     * 在应用进程首次进入前台时启动。消费者使用进程级 scope，因页面切换和 ViewModel 销毁仍会持续运行。
     */
    fun startForProcess(): Job = processLauncher.start()

    fun startIn(scope: CoroutineScope): Job = scope.launch {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        while (currentCoroutineContext().isActive) {
            try {
                todoRepository.observePendingRewardEvents().collect { pending ->
                    retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
                    if (pending.isNotEmpty()) drainWithRetry()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "订阅生产力奖励 outbox 失败，将重试", error)
            }
            if (!currentCoroutineContext().isActive) return@launch
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        }
    }

    private suspend fun drainWithRetry() {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        while (currentCoroutineContext().isActive) {
            val pending = try {
                todoRepository.getPendingRewardEvents(BATCH_SIZE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "读取生产力奖励 outbox 失败，将重试", error)
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                continue
            }
            if (pending.isEmpty()) return

            val result = processor.process(pending)
            if (result.invalidCount > 0) {
                Log.e(TAG, "检测到 ${result.invalidCount} 条无效生产力奖励事件，已保留待排查")
            }
            if (result.failedCount > 0) {
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                continue
            }
            if (result.processedCount == 0) return
            retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        }
    }

    companion object {
        private const val TAG = "ProductivityReward"
        private const val BATCH_SIZE = 100
        private const val INITIAL_RETRY_DELAY_MILLIS = 500L
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L

        @Volatile
        private var instance: ProductivityRewardOutboxConsumer? = null

        fun getInstance(context: Context): ProductivityRewardOutboxConsumer =
            instance ?: synchronized(this) {
                instance ?: ProductivityRewardOutboxConsumer(context).also { instance = it }
            }
    }
}
