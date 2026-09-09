package com.example.controlfree.growth

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import java.util.UUID

/**
 * 监督计时器与 Room 成长账本之间的轻量恢复桥梁。
 *
 * Room 负责最终、幂等的奖励结算；这里仅保存当前运行与锁定周期的稳定 ID，以及
 * “继续自律”承诺的单调时钟锚点。进程在快照落盘后、账本结算前退出时，恢复流程仍
 * 能用同一 cycleId 补做结算，而不会重复发放成长值。
 */
class GrowthRuntimeStore internal constructor(
    private val preferences: SharedPreferences,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    @Synchronized
    fun startNewRun(): String {
        val runId = requireValidId(runIdFactory(), "监督运行 ID")
        val pendingSettlements = readPendingSettlementsById()
        readActiveCycle()?.takeIf { it.pendingOutcome != null }?.let { pendingCycle ->
            pendingSettlements[pendingCycle.cycleId] = pendingCycle.forPendingSettlement()
        }
        check(
            preferences.edit()
                .removeCycle()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_RUN_ID, runId)
                .putLong(KEY_NEXT_CYCLE_ORDINAL, 0L)
                .writePendingSettlements(pendingSettlements.values)
                .commit()
        ) { "无法保存成长运行状态" }
        return runId
    }

    @Synchronized
    fun ensureRun(): String {
        readRunId()?.let { return it }
        return startNewRun()
    }

    /** 明确进入一个新的锁定阶段；同一 lockSessionId 的重复通知保持幂等。 */
    @Synchronized
    fun beginLockCycle(
        lockSessionId: Long,
        mode: String,
        configuredDurationSeconds: Long
    ): GrowthRuntimeCycle {
        require(lockSessionId > 0L) { "锁定会话 ID 必须为正数" }
        require(mode.isNotBlank()) { "监督模式不能为空" }
        require(configuredDurationSeconds > 0L) { "锁定时长必须为正数" }
        val previousCycle = readActiveCycle()
        previousCycle?.takeIf { it.lockSessionId == lockSessionId }?.let { return it }

        val runId = ensureRun()
        val ordinal = preferences.getLong(KEY_NEXT_CYCLE_ORDINAL, 0L).coerceAtLeast(0L)
        val cycleId = "$runId:$ordinal"
        val nextOrdinal = if (ordinal == Long.MAX_VALUE) Long.MAX_VALUE else ordinal + 1L
        val cycle = GrowthRuntimeCycle(
            runId = runId,
            cycleOrdinal = ordinal,
            cycleId = cycleId,
            lockSessionId = lockSessionId,
            mode = mode,
            configuredDurationSeconds = configuredDurationSeconds,
            pendingOutcome = null,
            continuation = null
        )
        val pendingSettlements = readPendingSettlementsById()
        previousCycle?.takeIf { it.pendingOutcome != null }?.let { pendingCycle ->
            pendingSettlements[pendingCycle.cycleId] = pendingCycle.forPendingSettlement()
        }
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putLong(KEY_NEXT_CYCLE_ORDINAL, nextOrdinal)
                .writePendingSettlements(pendingSettlements.values)
                .writeCycle(cycle)
                .commit()
        ) { "无法保存成长周期" }
        return cycle
    }

    /** 进程恢复仍处于锁定阶段时沿用旧 cycleId，只更新新的运行时锁定会话 ID。 */
    @Synchronized
    fun restoreOrBeginLockCycle(
        lockSessionId: Long,
        mode: String,
        configuredDurationSeconds: Long
    ): GrowthRuntimeCycle {
        require(lockSessionId > 0L) { "锁定会话 ID 必须为正数" }
        readActiveCycle()?.let { existing ->
            val restored = existing.copy(lockSessionId = lockSessionId, pendingOutcome = null)
            val pendingSettlements = readPendingSettlementsById().apply {
                remove(existing.cycleId)
            }
            check(
                preferences.edit()
                    .writePendingSettlements(pendingSettlements.values)
                    .writeCycle(restored)
                    .commit()
            ) {
                "无法恢复成长周期"
            }
            return restored
        }
        return beginLockCycle(lockSessionId, mode, configuredDurationSeconds)
    }

    @Synchronized
    fun activeCycle(): GrowthRuntimeCycle? = readActiveCycle()

    /** 返回所有已确定结果但尚未完成 Room 结算的周期，支持跨运行、跨进程逐个补偿。 */
    @Synchronized
    fun pendingSettlementCycles(): List<GrowthRuntimeCycle> {
        val pendingSettlements = readPendingSettlementsById()
        readActiveCycle()?.takeIf { it.pendingOutcome != null }?.let { pendingCycle ->
            pendingSettlements[pendingCycle.cycleId] = pendingCycle.forPendingSettlement()
        }
        return pendingSettlements.values.sortedWith(PENDING_SETTLEMENT_COMPARATOR)
    }

    /** 在监督快照开始切换前记录候选结果；只有快照落盘成功后才允许最终结算。 */
    @Synchronized
    fun markPendingOutcome(cycleId: String, outcome: GrowthCycleOutcome): Boolean {
        val current = readActiveCycle()?.takeIf { it.cycleId == cycleId } ?: return false
        val pendingCycle = current.copy(pendingOutcome = outcome)
        val pendingSettlements = readPendingSettlementsById().apply {
            put(cycleId, pendingCycle.forPendingSettlement())
        }
        return preferences.edit()
            .writePendingSettlements(pendingSettlements.values)
            .writeCycle(pendingCycle)
            .commit()
    }

    @Synchronized
    fun clearPendingOutcome(cycleId: String): Boolean {
        val current = readActiveCycle()
        val matchesActiveCycle = current?.cycleId == cycleId
        val pendingSettlements = readPendingSettlementsById()
        val removedFromOutbox = pendingSettlements.remove(cycleId) != null
        if (!matchesActiveCycle && !removedFromOutbox) return false

        val editor = preferences.edit()
            .writePendingSettlements(pendingSettlements.values)
        if (matchesActiveCycle) {
            editor.writeCycle(checkNotNull(current).copy(pendingOutcome = null))
        }
        return editor.commit()
    }

    /** 清理时必须再次匹配 cycleId，避免旧异步回调删掉新一轮锁定周期。 */
    @Synchronized
    fun completeCycle(cycleId: String): Boolean = completeCycleInternal(cycleId)

    /** 完成一个待结算周期；若它仍是当前周期，也会同步清理当前周期记录。 */
    @Synchronized
    fun completePendingSettlement(cycleId: String): Boolean = completeCycleInternal(cycleId)

    @Synchronized
    fun armContinuation(
        cycleId: String,
        nowElapsedMillis: Long,
        bootCount: Int,
        remainingSeconds: Int
    ): GrowthContinuationCommitment? {
        require(nowElapsedMillis >= 0L) { "单调时钟不能为负数" }
        require(remainingSeconds >= 0) { "剩余秒数不能为负数" }
        val current = readActiveCycle()?.takeIf { it.cycleId == cycleId } ?: return null
        current.continuation?.let { return it }
        val requiredSeconds = minOf(
            GrowthPolicy.CONTINUATION_REWARD_SECONDS,
            remainingSeconds.toLong()
        )
        if (requiredSeconds <= 0L) return null
        val commitment = GrowthContinuationCommitment(
            armedAtElapsedMillis = nowElapsedMillis,
            requiredSeconds = requiredSeconds,
            bootCount = bootCount
        )
        return if (
            preferences.edit().writeCycle(current.copy(continuation = commitment)).commit()
        ) {
            commitment
        } else {
            null
        }
    }

    @Synchronized
    fun isContinuationDue(
        cycleId: String,
        nowElapsedMillis: Long,
        currentBootCount: Int
    ): Boolean {
        val commitment = readActiveCycle()
            ?.takeIf { it.cycleId == cycleId }
            ?.continuation
            ?: return false
        return continuationReached(commitment, nowElapsedMillis, currentBootCount)
    }

    @Synchronized
    fun clearContinuation(cycleId: String): Boolean {
        val current = readActiveCycle()?.takeIf { it.cycleId == cycleId } ?: return false
        if (current.continuation == null) return true
        return preferences.edit().writeCycle(current.copy(continuation = null)).commit()
    }

    private fun readRunId(): String? = preferences.getString(KEY_RUN_ID, null)
        ?.takeIf { it.isNotBlank() && it.length <= MAX_RUNTIME_ID_LENGTH }

    private fun completeCycleInternal(cycleId: String): Boolean {
        val current = readActiveCycle()
        val matchesActiveCycle = current?.cycleId == cycleId
        val pendingSettlements = readPendingSettlementsById()
        val removedFromOutbox = pendingSettlements.remove(cycleId) != null
        if (!matchesActiveCycle && !removedFromOutbox) return false

        val editor = preferences.edit()
            .writePendingSettlements(pendingSettlements.values)
        if (matchesActiveCycle) editor.removeCycle()
        return editor.commit()
    }

    private fun readPendingSettlementsById(): LinkedHashMap<String, GrowthRuntimeCycle> {
        val pendingSettlements = linkedMapOf<String, GrowthRuntimeCycle>()
        preferences.getStringSet(KEY_PENDING_SETTLEMENT_OUTBOX, emptySet())
            .orEmpty()
            .mapNotNull(::decodePendingSettlement)
            .sortedWith(PENDING_SETTLEMENT_COMPARATOR)
            .forEach { cycle -> pendingSettlements[cycle.cycleId] = cycle }
        return pendingSettlements
    }

    private fun readActiveCycle(): GrowthRuntimeCycle? {
        if (preferences.getInt(KEY_SCHEMA_VERSION, 0) != SCHEMA_VERSION) return null
        val runId = readRunId() ?: return null
        val cycleId = preferences.getString(KEY_CYCLE_ID, null)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_RUNTIME_ID_LENGTH }
            ?: return null
        val ordinal = preferences.getLong(KEY_CYCLE_ORDINAL, -1L).takeIf { it >= 0L }
            ?: return null
        val lockSessionId = preferences.getLong(KEY_LOCK_SESSION_ID, -1L).takeIf { it > 0L }
            ?: return null
        val mode = preferences.getString(KEY_MODE, null)?.takeIf(String::isNotBlank) ?: return null
        val configuredSeconds = preferences.getLong(KEY_CONFIGURED_SECONDS, -1L)
            .takeIf { it > 0L }
            ?: return null
        val outcome = preferences.getString(KEY_PENDING_OUTCOME, null)
            ?.let(GrowthCycleOutcome::fromStoredValue)
        val armedAt = preferences.getLong(KEY_CONTINUATION_ARMED_ELAPSED, -1L)
        val requiredSeconds = preferences.getLong(KEY_CONTINUATION_REQUIRED_SECONDS, -1L)
        val continuation = if (armedAt >= 0L && requiredSeconds > 0L) {
            GrowthContinuationCommitment(
                armedAtElapsedMillis = armedAt,
                requiredSeconds = requiredSeconds,
                bootCount = preferences.getInt(KEY_CONTINUATION_BOOT_COUNT, Int.MIN_VALUE)
            )
        } else {
            null
        }
        return GrowthRuntimeCycle(
            runId = runId,
            cycleOrdinal = ordinal,
            cycleId = cycleId,
            lockSessionId = lockSessionId,
            mode = mode,
            configuredDurationSeconds = configuredSeconds,
            pendingOutcome = outcome,
            continuation = continuation
        )
    }

    private fun SharedPreferences.Editor.writeCycle(
        cycle: GrowthRuntimeCycle
    ): SharedPreferences.Editor = this
        .putString(KEY_RUN_ID, cycle.runId)
        .putString(KEY_CYCLE_ID, cycle.cycleId)
        .putLong(KEY_CYCLE_ORDINAL, cycle.cycleOrdinal)
        .putLong(KEY_LOCK_SESSION_ID, cycle.lockSessionId)
        .putString(KEY_MODE, cycle.mode)
        .putLong(KEY_CONFIGURED_SECONDS, cycle.configuredDurationSeconds)
        .apply {
            cycle.pendingOutcome?.let { putString(KEY_PENDING_OUTCOME, it.storedValue) }
                ?: remove(KEY_PENDING_OUTCOME)
            cycle.continuation?.let { commitment ->
                putLong(KEY_CONTINUATION_ARMED_ELAPSED, commitment.armedAtElapsedMillis)
                putLong(KEY_CONTINUATION_REQUIRED_SECONDS, commitment.requiredSeconds)
                putInt(KEY_CONTINUATION_BOOT_COUNT, commitment.bootCount)
            } ?: run {
                remove(KEY_CONTINUATION_ARMED_ELAPSED)
                remove(KEY_CONTINUATION_REQUIRED_SECONDS)
                remove(KEY_CONTINUATION_BOOT_COUNT)
            }
        }

    private fun SharedPreferences.Editor.removeCycle(): SharedPreferences.Editor = this
        .remove(KEY_CYCLE_ID)
        .remove(KEY_CYCLE_ORDINAL)
        .remove(KEY_LOCK_SESSION_ID)
        .remove(KEY_MODE)
        .remove(KEY_CONFIGURED_SECONDS)
        .remove(KEY_PENDING_OUTCOME)
        .remove(KEY_CONTINUATION_ARMED_ELAPSED)
        .remove(KEY_CONTINUATION_REQUIRED_SECONDS)
        .remove(KEY_CONTINUATION_BOOT_COUNT)

    private fun SharedPreferences.Editor.writePendingSettlements(
        cycles: Collection<GrowthRuntimeCycle>
    ): SharedPreferences.Editor {
        val encodedCycles = cycles
            .asSequence()
            .filter { it.pendingOutcome != null }
            .associateBy(GrowthRuntimeCycle::cycleId)
            .values
            .map(::encodePendingSettlement)
            .toSet()
        return if (encodedCycles.isEmpty()) {
            remove(KEY_PENDING_SETTLEMENT_OUTBOX)
        } else {
            putStringSet(KEY_PENDING_SETTLEMENT_OUTBOX, encodedCycles)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "control_free_growth_runtime"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_NEXT_CYCLE_ORDINAL = "next_cycle_ordinal"
        private const val KEY_CYCLE_ID = "cycle_id"
        private const val KEY_CYCLE_ORDINAL = "cycle_ordinal"
        private const val KEY_LOCK_SESSION_ID = "lock_session_id"
        private const val KEY_MODE = "mode"
        private const val KEY_CONFIGURED_SECONDS = "configured_seconds"
        private const val KEY_PENDING_OUTCOME = "pending_outcome"
        private const val KEY_CONTINUATION_ARMED_ELAPSED = "continuation_armed_elapsed"
        private const val KEY_CONTINUATION_REQUIRED_SECONDS = "continuation_required_seconds"
        private const val KEY_CONTINUATION_BOOT_COUNT = "continuation_boot_count"
        private const val KEY_PENDING_SETTLEMENT_OUTBOX = "pending_settlement_outbox_v1"

        private val PENDING_SETTLEMENT_COMPARATOR = compareBy<GrowthRuntimeCycle>(
            GrowthRuntimeCycle::runId,
            GrowthRuntimeCycle::cycleOrdinal,
            GrowthRuntimeCycle::cycleId
        )

        @Volatile
        private var instance: GrowthRuntimeStore? = null

        fun getInstance(context: Context): GrowthRuntimeStore =
            instance ?: synchronized(this) {
                instance ?: GrowthRuntimeStore(
                    context.applicationContext.getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                    )
                ).also { instance = it }
            }
    }
}

data class GrowthRuntimeCycle(
    val runId: String,
    val cycleOrdinal: Long,
    val cycleId: String,
    val lockSessionId: Long,
    val mode: String,
    val configuredDurationSeconds: Long,
    val pendingOutcome: GrowthCycleOutcome?,
    val continuation: GrowthContinuationCommitment?
)

private fun GrowthRuntimeCycle.forPendingSettlement(): GrowthRuntimeCycle = copy(
    pendingOutcome = requireNotNull(pendingOutcome) { "待结算周期缺少结果" },
    continuation = null
)

private fun encodePendingSettlement(cycle: GrowthRuntimeCycle): String {
    val outcome = requireNotNull(cycle.pendingOutcome) { "待结算周期缺少结果" }
    return listOf(
        PENDING_SETTLEMENT_RECORD_VERSION,
        encodePendingText(cycle.runId),
        cycle.cycleOrdinal.toString(),
        encodePendingText(cycle.cycleId),
        cycle.lockSessionId.toString(),
        encodePendingText(cycle.mode),
        cycle.configuredDurationSeconds.toString(),
        encodePendingText(outcome.storedValue)
    ).joinToString(PENDING_SETTLEMENT_FIELD_SEPARATOR)
}

private fun decodePendingSettlement(storedValue: String): GrowthRuntimeCycle? = runCatching {
    val fields = storedValue.split(PENDING_SETTLEMENT_FIELD_SEPARATOR)
    require(fields.size == PENDING_SETTLEMENT_FIELD_COUNT)
    require(fields[0] == PENDING_SETTLEMENT_RECORD_VERSION)
    val runId = decodePendingText(fields[1])
    val ordinal = fields[2].toLong()
    val cycleId = decodePendingText(fields[3])
    val lockSessionId = fields[4].toLong()
    val mode = decodePendingText(fields[5])
    val configuredDurationSeconds = fields[6].toLong()
    val outcome = requireNotNull(
        GrowthCycleOutcome.fromStoredValue(decodePendingText(fields[7]))
    )
    require(runId.isNotBlank() && runId.length <= MAX_RUNTIME_ID_LENGTH)
    require(cycleId.isNotBlank() && cycleId.length <= MAX_RUNTIME_ID_LENGTH)
    require(ordinal >= 0L)
    require(lockSessionId > 0L)
    require(mode.isNotBlank())
    require(configuredDurationSeconds > 0L)
    GrowthRuntimeCycle(
        runId = runId,
        cycleOrdinal = ordinal,
        cycleId = cycleId,
        lockSessionId = lockSessionId,
        mode = mode,
        configuredDurationSeconds = configuredDurationSeconds,
        pendingOutcome = outcome,
        continuation = null
    )
}.getOrNull()

private fun encodePendingText(value: String): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodePendingText(value: String): String = String(
    Base64.getUrlDecoder().decode(value),
    Charsets.UTF_8
)

data class GrowthContinuationCommitment(
    val armedAtElapsedMillis: Long,
    val requiredSeconds: Long,
    val bootCount: Int
)

internal fun continuationReached(
    commitment: GrowthContinuationCommitment,
    nowElapsedMillis: Long,
    currentBootCount: Int
): Boolean {
    if (nowElapsedMillis < commitment.armedAtElapsedMillis) return false
    if (commitment.bootCount < 0 || commitment.bootCount != currentBootCount) return false
    val requiredMillis = commitment.requiredSeconds
        .coerceAtLeast(0L)
        .coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
    return nowElapsedMillis - commitment.armedAtElapsedMillis >= requiredMillis
}

private fun requireValidId(value: String, label: String): String = value.trim().also { id ->
    require(id.isNotBlank() && id.length <= MAX_RUNTIME_ID_LENGTH) { "$label 无效" }
}

private const val MAX_RUNTIME_ID_LENGTH = 160
private const val PENDING_SETTLEMENT_RECORD_VERSION = "1"
private const val PENDING_SETTLEMENT_FIELD_SEPARATOR = "|"
private const val PENDING_SETTLEMENT_FIELD_COUNT = 8
