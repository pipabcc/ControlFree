package com.example.controlfree

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.example.controlfree.data.MonitorRecoveryGuard
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.diagnostics.MonitorRuntimeHealthRegistry
import java.security.SecureRandom

const val NO_LOCK_SESSION = 0L

/**
 * 监督服务失联后的锁定衰减状态。
 *
 * 服务在线时锁定真值由它逐秒发布；服务被系统杀死或崩溃后没有任何东西再更新剩余
 * 时间，若此时把"该锁着"当作永久事实，锁屏就会冻结成无出口状态。这里用单调时钟
 * 让兜底自行耗尽：既不需要服务复活，也无法通过杀服务提前解锁。
 */
data class RetainedLockDecay(
    /** 运行时失联的单调时刻，绝对上限的锚点。 */
    val unavailableSinceElapsedMillis: Long,
    /** 上次结算的单调时刻。 */
    val settledAtElapsedMillis: Long,
    /** 上次结算时观察到的屏幕状态。 */
    val settledInteractive: Boolean,
    /** 结算后仍需锁定的毫秒数。 */
    val remainingMillis: Long,
    /** 最终逃生舱：失联超过该时长后无条件交还给持久化真值。 */
    val ceilingMillis: Long
)

/**
 * 纯策略层：按与 [MonitorCycle] 相同的规则衰减失联期间的锁定剩余时间。
 */
object RetainedLockDecayCalculator {
    /**
     * 观察心跳断档超过该间隔即按熄屏结算。锁屏界面的心跳只在 RESUMED 时运行，
     * 断档几乎必然是熄屏；不这样归因就会重现"熄屏也不倒计时"。
     */
    const val OBSERVATION_GAP_TOLERANCE_MILLIS = 5_000L

    /** 兜底上限取两倍锁定时长，并夹在可感知区间内。 */
    fun ceilingMillisFor(lockDurationMillis: Long): Long =
        (lockDurationMillis.coerceAtLeast(0L) * 2L)
            .coerceIn(MIN_CEILING_MILLIS, MAX_CEILING_MILLIS)

    fun settle(
        decay: RetainedLockDecay,
        nowElapsedMillis: Long,
        isInteractive: Boolean,
        lockCountsDownWhileInteractive: Boolean
    ): RetainedLockDecay {
        val elapsedMillis =
            (nowElapsedMillis - decay.settledAtElapsedMillis).coerceAtLeast(0L)
        val observerWasRunning = elapsedMillis <= OBSERVATION_GAP_TOLERANCE_MILLIS
        // 心跳连续时按上次观察到的屏幕状态结算；心跳断档的区间按熄屏结算。
        val intervalInteractive = observerWasRunning && decay.settledInteractive
        val countsDown = lockCountsDownWhileInteractive || !intervalInteractive
        return decay.copy(
            settledAtElapsedMillis = nowElapsedMillis,
            settledInteractive = isInteractive,
            remainingMillis = if (countsDown) {
                (decay.remainingMillis - elapsedMillis).coerceAtLeast(0L)
            } else {
                decay.remainingMillis
            }
        )
    }

    /** 绝对逃生舱：无论屏幕状态如何，无人值守的锁层都不得超过上限。 */
    fun isCeilingReached(decay: RetainedLockDecay, nowElapsedMillis: Long): Boolean =
        nowElapsedMillis - decay.unavailableSinceElapsedMillis >= decay.ceilingMillis

    private const val MIN_CEILING_MILLIS = 5L * 60_000L
    private const val MAX_CEILING_MILLIS = 60L * 60_000L
}

data class RuntimeLockTruth(
    val sessionId: Long,
    val isRuntimeAvailable: Boolean,
    val isMonitorActive: Boolean,
    val phase: MonitorPhase,
    val shouldShowLockUi: Boolean,
    val remainingSeconds: Int,
    /** 专注模式锁定阶段亮屏也计时；普通监督仅熄屏计时。 */
    val lockCountsDownWhileInteractive: Boolean = false,
    /** 运行时失联后的衰减状态；服务在线时为 null。 */
    val retained: RetainedLockDecay? = null
)

enum class LockTruthSource {
    LIVE_RUNTIME,
    RETAINED_RUNTIME,
    RECOVERY_GUARD,
    PAUSED_SNAPSHOT,
    PERSISTED_SNAPSHOT,
    NONE,
    STALE_SESSION
}

data class LockTruthDecision(
    val shouldStayLocked: Boolean,
    val remainingSeconds: Int,
    val sessionId: Long,
    val source: LockTruthSource,
    /** 真值由本地单调衰减推导，而非服务实时发布。 */
    val isUnattendedFallback: Boolean = false
)

data class LockTruthInputs(
    val runtime: RuntimeLockTruth?,
    val expectedSessionId: Long = NO_LOCK_SESSION,
    val recoveryLockRequired: Boolean,
    val persistedMonitorActive: Boolean,
    val persistedPhase: MonitorPhase?,
    val persistedPauseActive: Boolean = false,
    val persistedRemainingSeconds: Int,
    val fallbackLockSeconds: Int,
    val nowElapsedMillis: Long = 0L,
    /** 监督服务失联的单调时刻；服务在线时为 0。 */
    val serviceUnavailableSinceElapsedMillis: Long = 0L
)

/** 纯策略层，供 JVM 测试覆盖磁盘滞后、恢复保护和旧 session。 */
object LockTruthResolver {
    fun resolve(inputs: LockTruthInputs): LockTruthDecision {
        val runtime = inputs.runtime
        val hasStaleExpectedSession =
            runtime != null &&
            inputs.expectedSessionId != NO_LOCK_SESSION &&
            inputs.expectedSessionId != runtime.sessionId
        if (runtime?.isRuntimeAvailable == true) {
            return LockTruthDecision(
                shouldStayLocked = runtime.isMonitorActive && runtime.shouldShowLockUi,
                remainingSeconds = runtime.remainingSeconds.coerceAtLeast(0),
                sessionId = runtime.sessionId,
                source = if (hasStaleExpectedSession) {
                    LockTruthSource.STALE_SESSION
                } else {
                    LockTruthSource.LIVE_RUNTIME
                }
            )
        }
        val retained = runtime?.retained
        if (
            runtime?.isMonitorActive == true &&
            runtime.shouldShowLockUi &&
            retained != null &&
            retained.remainingMillis > 0L &&
            !RetainedLockDecayCalculator.isCeilingReached(retained, inputs.nowElapsedMillis)
        ) {
            return LockTruthDecision(
                shouldStayLocked = true,
                remainingSeconds = ceilSeconds(retained.remainingMillis),
                sessionId = runtime.sessionId,
                source = if (hasStaleExpectedSession) {
                    LockTruthSource.STALE_SESSION
                } else {
                    LockTruthSource.RETAINED_RUNTIME
                },
                isUnattendedFallback = true
            )
        }
        // 衰减耗尽或触及上限后向下贯穿继续仲裁，绝不在此直接放行：阶段推进始终是
        // 持久化真值的职责，兜底只决定这个无人值守的锁层还挂不挂。
        if (
            inputs.persistedMonitorActive &&
            inputs.persistedPhase == MonitorPhase.LOCK &&
            inputs.persistedPauseActive
        ) {
            return LockTruthDecision(
                shouldStayLocked = false,
                remainingSeconds = inputs.persistedRemainingSeconds.coerceAtLeast(0),
                sessionId = runtime?.sessionId ?: inputs.expectedSessionId,
                source = LockTruthSource.PAUSED_SNAPSHOT
            )
        }
        if (inputs.recoveryLockRequired) {
            // Guard 只能沿用真实 LOCK 的剩余值。快照已投影到 USAGE 时，其中的
            // remainingSeconds 是玩机额度，拿它维持锁层会在每次投影时重置为完整时长。
            // 对这种事务不确定状态使用独立、可耗尽的锁定兜底。
            val mayUsePersistedRemaining = inputs.persistedPhase == MonitorPhase.LOCK
            val remainingSeconds = unattendedFallbackSeconds(
                inputs = inputs,
                mayUsePersistedRemaining = mayUsePersistedRemaining
            )
            if (remainingSeconds > 0) {
                return LockTruthDecision(
                    shouldStayLocked = true,
                    remainingSeconds = remainingSeconds,
                    sessionId = runtime?.sessionId ?: inputs.expectedSessionId,
                    source = LockTruthSource.RECOVERY_GUARD,
                    isUnattendedFallback =
                        !mayUsePersistedRemaining || inputs.persistedRemainingSeconds <= 0
                )
            }
        }
        if (inputs.persistedMonitorActive && inputs.persistedPhase == MonitorPhase.LOCK) {
            val remainingSeconds = unattendedFallbackSeconds(
                inputs = inputs,
                mayUsePersistedRemaining = true
            )
            if (remainingSeconds > 0) {
                return LockTruthDecision(
                    shouldStayLocked = true,
                    remainingSeconds = remainingSeconds,
                    sessionId = runtime?.sessionId ?: inputs.expectedSessionId,
                    source = LockTruthSource.PERSISTED_SNAPSHOT,
                    isUnattendedFallback = inputs.persistedRemainingSeconds <= 0
                )
            }
        }
        return LockTruthDecision(
            shouldStayLocked = false,
            remainingSeconds = 0,
            sessionId = runtime?.sessionId ?: inputs.expectedSessionId,
            source = LockTruthSource.NONE
        )
    }

    /**
     * 没有可信剩余时间时才合成兜底，且必须随单调时钟耗尽。
     * 恒定返回完整锁机时长会把"暂停/跳过后剩 1 分钟"重置成"又要锁 10 分钟"，
     * 并且永远不会到期——这正是无出口吸收态的来源。
     */
    private fun unattendedFallbackSeconds(
        inputs: LockTruthInputs,
        mayUsePersistedRemaining: Boolean
    ): Int {
        if (mayUsePersistedRemaining) {
            inputs.persistedRemainingSeconds.takeIf { it > 0 }?.let { return it }
        }
        val ceilingSeconds = inputs.fallbackLockSeconds.coerceAtLeast(1)
        if (inputs.serviceUnavailableSinceElapsedMillis <= 0L) return ceilingSeconds
        val elapsedSeconds = (
            (inputs.nowElapsedMillis - inputs.serviceUnavailableSinceElapsedMillis)
                .coerceAtLeast(0L) / 1_000L
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (ceilingSeconds - elapsedSeconds).coerceAtLeast(0)
    }
}

internal fun ceilSeconds(remainingMillis: Long): Int {
    val safeMillis = remainingMillis.coerceAtLeast(0L)
    val seconds = safeMillis / 1_000L + if (safeMillis % 1_000L == 0L) 0L else 1L
    return seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal data class RuntimeUnavailableAnchorRecord(
    val elapsedMillis: Long,
    val bootCount: Int
)

/**
 * 失联锚点一旦建立只能前移，不能被后续健康心跳或 Repository 重建推后；启动批次变化
 * 时单调时钟失去可比性，才从当前观察重新开始。
 */
internal fun resolveRuntimeUnavailableAnchorRecord(
    runtimeAvailable: Boolean,
    persisted: RuntimeUnavailableAnchorRecord?,
    observedElapsedMillis: Long,
    nowElapsedMillis: Long,
    currentBootCount: Int
): RuntimeUnavailableAnchorRecord? {
    if (runtimeAvailable) return null
    val now = nowElapsedMillis.coerceAtLeast(1L)
    val persistedElapsed = persisted
        ?.takeIf { record ->
            record.elapsedMillis in 1L..now &&
                (
                    record.bootCount == currentBootCount ||
                        record.bootCount == MONITOR_UNKNOWN_BOOT_COUNT ||
                        currentBootCount == MONITOR_UNKNOWN_BOOT_COUNT
                    )
        }
        ?.elapsedMillis
    val observed = observedElapsedMillis.takeIf { it in 1L..now } ?: now
    return RuntimeUnavailableAnchorRecord(
        elapsedMillis = persistedElapsed?.let { minOf(it, observed) } ?: observed,
        bootCount = currentBootCount
    )
}

/**
 * 进程内实时锁定真值。磁盘保存可能落后于阶段切换，因此只要 Service 仍可用，
 * LockActivity 必须优先采用这里的 session 和锁层可见性。
 */
object RuntimeLockTruthRegistry {
    private val lock = Any()
    private val random = SecureRandom()
    private var current: RuntimeLockTruth? = null

    fun beginSession(
        phase: MonitorPhase,
        shouldShowLockUi: Boolean,
        remainingSeconds: Int,
        lockCountsDownWhileInteractive: Boolean
    ): Long = synchronized(lock) {
        val sessionId = generateSessionId()
        current = RuntimeLockTruth(
            sessionId = sessionId,
            isRuntimeAvailable = true,
            isMonitorActive = true,
            phase = phase,
            shouldShowLockUi = shouldShowLockUi,
            remainingSeconds = remainingSeconds.coerceAtLeast(0),
            lockCountsDownWhileInteractive = lockCountsDownWhileInteractive
        )
        sessionId
    }

    fun publish(
        sessionId: Long,
        phase: MonitorPhase,
        shouldShowLockUi: Boolean,
        remainingSeconds: Int
    ): Boolean = synchronized(lock) {
        val existing = current
        if (sessionId == NO_LOCK_SESSION || existing?.sessionId != sessionId) return false
        current = existing.copy(
            isRuntimeAvailable = true,
            isMonitorActive = true,
            phase = phase,
            shouldShowLockUi = shouldShowLockUi,
            remainingSeconds = remainingSeconds.coerceAtLeast(0),
            // 服务回来了，此前的失联衰减作废。
            retained = null
        )
        true
    }

    /**
     * 标记运行时失联并开始单调衰减。保留锁层是为了扛住磁盘滞后，但必须有期限：
     * 不带衰减的"保留"会变成谁也清不掉的僵尸真值。
     */
    fun markRuntimeUnavailable(
        sessionId: Long,
        nowElapsedMillis: Long,
        isInteractive: Boolean,
        lockDurationMillis: Long
    ) {
        synchronized(lock) {
            val existing = current
            if (existing?.sessionId != sessionId) return
            current = existing.copy(
                isRuntimeAvailable = false,
                retained = existing.retained ?: RetainedLockDecay(
                    unavailableSinceElapsedMillis = nowElapsedMillis,
                    settledAtElapsedMillis = nowElapsedMillis,
                    settledInteractive = isInteractive,
                    remainingMillis = existing.remainingSeconds.coerceAtLeast(0) * 1_000L,
                    ceilingMillis =
                        RetainedLockDecayCalculator.ceilingMillisFor(lockDurationMillis)
                )
            )
        }
    }

    /** 结算失联期间的衰减，并返回最新真值。服务在线或无衰减状态时原样返回。 */
    fun settleRetained(
        nowElapsedMillis: Long,
        isInteractive: Boolean
    ): RuntimeLockTruth? = synchronized(lock) {
        val existing = current ?: return@synchronized null
        val decay = existing.retained
        if (existing.isRuntimeAvailable || decay == null) return@synchronized existing
        val settled = RetainedLockDecayCalculator.settle(
            decay = decay,
            nowElapsedMillis = nowElapsedMillis,
            isInteractive = isInteractive,
            lockCountsDownWhileInteractive = existing.lockCountsDownWhileInteractive
        )
        current = existing.copy(
            retained = settled,
            remainingSeconds = ceilSeconds(settled.remainingMillis)
        )
        current
    }

    fun endSession(sessionId: Long) {
        synchronized(lock) {
            if (current?.sessionId == sessionId) current = null
        }
    }

    fun snapshot(): RuntimeLockTruth? = synchronized(lock) { current }

    fun currentSessionId(): Long = synchronized(lock) { current?.sessionId ?: NO_LOCK_SESSION }

    fun isCurrentSession(sessionId: Long): Boolean = synchronized(lock) {
        sessionId != NO_LOCK_SESSION && current?.sessionId == sessionId
    }

    internal fun resetForTest() {
        synchronized(lock) { current = null }
    }

    private fun generateSessionId(): Long {
        var candidate: Long
        do {
            // 锁定会话会持久化进百科挑战凭证；统一使用正数，避免各层对负数语义不一致。
            candidate = random.nextLong() and Long.MAX_VALUE
        } while (candidate == NO_LOCK_SESSION || candidate == current?.sessionId)
        return candidate
    }
}

/**
 * 把首次失联的单调时刻保存到独立轻量状态中。进程内副本覆盖磁盘瞬时写入失败，磁盘
 * 副本覆盖 Activity/进程重建；只有重新观察到实时运行时才清除本轮锚点。
 */
private class RuntimeUnavailableAnchorStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun resolve(
        runtimeAvailable: Boolean,
        observedElapsedMillis: Long,
        nowElapsedMillis: Long,
        currentBootCount: Int
    ): Long = synchronized(PROCESS_LOCK) {
        if (runtimeAvailable) {
            processRecord = null
            clearPersistedRecord()
            return@synchronized 0L
        }
        val persistedRecord = readPersistedRecord()
        val resolved = listOf(processRecord, persistedRecord)
            .map { candidate ->
                requireNotNull(resolveRuntimeUnavailableAnchorRecord(
                    runtimeAvailable = false,
                    persisted = candidate,
                    observedElapsedMillis = observedElapsedMillis,
                    nowElapsedMillis = nowElapsedMillis,
                    currentBootCount = currentBootCount
                ))
            }
            .minBy(RuntimeUnavailableAnchorRecord::elapsedMillis)
        processRecord = resolved
        if (persistedRecord != resolved) persistRecord(resolved)
        resolved.elapsedMillis
    }

    private fun readPersistedRecord(): RuntimeUnavailableAnchorRecord? = try {
        if (
            !preferences.contains(KEY_ELAPSED_MILLIS) ||
            !preferences.contains(KEY_BOOT_COUNT)
        ) {
            null
        } else {
            RuntimeUnavailableAnchorRecord(
                elapsedMillis = preferences.getLong(KEY_ELAPSED_MILLIS, 0L),
                bootCount = preferences.getInt(KEY_BOOT_COUNT, MONITOR_UNKNOWN_BOOT_COUNT)
            )
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun persistRecord(record: RuntimeUnavailableAnchorRecord) {
        try {
            preferences.edit()
                .putLong(KEY_ELAPSED_MILLIS, record.elapsedMillis)
                .putInt(KEY_BOOT_COUNT, record.bootCount)
                .commit()
        } catch (_: RuntimeException) {
            // 进程内副本仍能保证当前生命周期内锚点不滑动。
        }
    }

    private fun clearPersistedRecord() {
        try {
            if (
                preferences.contains(KEY_ELAPSED_MILLIS) ||
                preferences.contains(KEY_BOOT_COUNT)
            ) {
                preferences.edit()
                    .remove(KEY_ELAPSED_MILLIS)
                    .remove(KEY_BOOT_COUNT)
                    .commit()
            }
        } catch (_: RuntimeException) {
            // 旧锚点只会缩短下一次失联兜底，不会制造永久锁层。
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "lock_truth_runtime_anchor"
        const val KEY_ELAPSED_MILLIS = "unavailable_since_elapsed_millis"
        const val KEY_BOOT_COUNT = "boot_count"
        val PROCESS_LOCK = Any()
        var processRecord: RuntimeUnavailableAnchorRecord? = null
    }
}

class LockTruthRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = PreferenceManager(appContext)
    private val recoveryGuard = MonitorRecoveryGuard(appContext)
    private val unavailableAnchorStore = RuntimeUnavailableAnchorStore(appContext)
    private val cachedBootCount by lazy { readBootCount() }

    fun resolve(expectedSessionId: Long = NO_LOCK_SESSION): LockTruthDecision {
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        val isInteractive = isInteractive()
        val progress = preferences.inspectMonitorProgress()
        val savedSnapshot = progress.snapshot
        val sessionMode = preferences.getMonitorSessionMode()
        val projected = projectPersisted(
            saved = savedSnapshot,
            nowElapsedMillis = nowElapsedMillis,
            isInteractive = isInteractive,
            sessionMode = sessionMode
        )
        val runtime = RuntimeLockTruthRegistry.settleRetained(
            nowElapsedMillis = nowElapsedMillis,
            isInteractive = isInteractive
        )
        return LockTruthResolver.resolve(
            LockTruthInputs(
                // 先结算失联衰减，再交给纯策略层仲裁。
                runtime = runtime,
                expectedSessionId = expectedSessionId,
                recoveryLockRequired = recoveryGuard.requiresLock(),
                persistedMonitorActive = preferences.isMonitorActive(),
                persistedPhase = projected.phase ?: inferFallbackPersistedPhase(),
                // 暂停有效性由 MonitorPauseState 用单调时钟与墙钟双重校验，撤销精确
                // 闹钟权限无法延长暂停；再叠一层权限门控只会误判"暂停不存在"，把用户
                // 送进完整锁定分支。
                persistedPauseActive = progress.pauseState?.isActiveAt(
                    nowEpochMillis = System.currentTimeMillis(),
                    nowElapsedMillis = nowElapsedMillis,
                    currentBootCount = cachedBootCount
                ) == true,
                persistedRemainingSeconds = projected.remainingSeconds,
                fallbackLockSeconds = (
                    when (sessionMode) {
                        MonitorSessionMode.SUPERVISION -> preferences.getLockTime()
                        MonitorSessionMode.FOCUS -> preferences.getFocusLockTime()
                    }.coerceAtLeast(1).toLong() * 60L
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                nowElapsedMillis = nowElapsedMillis,
                serviceUnavailableSinceElapsedMillis =
                    serviceUnavailableSinceElapsedMillis(
                        runtime = runtime,
                        nowElapsedMillis = nowElapsedMillis
                    )
            )
        )
    }

    /**
     * 只读投影：用与服务相同的 [MonitorCycle] 规则把磁盘快照结算到当下，绝不写盘。
     *
     * 磁盘上的 remainingMillis 是写入那一刻的静止数字。直接拿它显示，服务一旦停摆
     * 倒计时就冻结；按单调时钟投影后，锁定真正到期时阶段会翻成玩机，锁屏随之自行退出。
     */
    private fun projectPersisted(
        saved: MonitorCycleSnapshot?,
        nowElapsedMillis: Long,
        isInteractive: Boolean,
        sessionMode: MonitorSessionMode
    ): ProjectedProgress {
        if (saved == null) return ProjectedProgress(null, 0)
        return try {
            val usageMinutes = when (sessionMode) {
                MonitorSessionMode.SUPERVISION -> preferences.getUsageTime()
                MonitorSessionMode.FOCUS -> preferences.getFocusPlayTime()
            }
            val lockMinutes = when (sessionMode) {
                MonitorSessionMode.SUPERVISION -> preferences.getLockTime()
                MonitorSessionMode.FOCUS -> preferences.getFocusLockTime()
            }
            val cycle = MonitorCycle(
                usageDurationMillis =
                    usageMinutes.coerceAtLeast(1).toLong() * 60_000L,
                lockDurationMillis =
                    lockMinutes.coerceAtLeast(1).toLong() * 60_000L,
                initialPhase = sessionMode.initialPhase,
                lockCountsDownWhileInteractive = sessionMode == MonitorSessionMode.FOCUS
            )
            val restored = MonitorProgressRestorer(cycle).restore(
                saved = saved,
                nowElapsedMillis = nowElapsedMillis,
                currentBootCount = cachedBootCount,
                currentInteractive = isInteractive
            )
            ProjectedProgress(restored.phase, cycle.remainingSeconds(restored))
        } catch (_: RuntimeException) {
            // 配置非法时退回原始快照：宁可用静止数字，也不能让锁屏失去真值来源。
            ProjectedProgress(saved.phase, ceilSeconds(saved.remainingMillis))
        }
    }

    private fun serviceUnavailableSinceElapsedMillis(
        runtime: RuntimeLockTruth?,
        nowElapsedMillis: Long
    ): Long {
        val health = MonitorRuntimeHealthRegistry.snapshot()
        val observedElapsedMillis = runtime?.retained?.unavailableSinceElapsedMillis
            ?.takeIf { it > 0L }
            ?: health.lastHeartbeatElapsedMillis
        return unavailableAnchorStore.resolve(
            runtimeAvailable = runtime?.isRuntimeAvailable == true,
            observedElapsedMillis = observedElapsedMillis,
            nowElapsedMillis = nowElapsedMillis,
            currentBootCount = cachedBootCount
        )
    }

    private fun isInteractive(): Boolean = try {
        appContext.getSystemService(PowerManager::class.java)?.isInteractive != false
    } catch (_: RuntimeException) {
        true
    }

    private data class ProjectedProgress(
        val phase: MonitorPhase?,
        val remainingSeconds: Int
    )

    private fun readBootCount(): Int = try {
        Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.BOOT_COUNT,
            MONITOR_UNKNOWN_BOOT_COUNT
        )
    } catch (_: RuntimeException) {
        MONITOR_UNKNOWN_BOOT_COUNT
    }

    private fun inferFallbackPersistedPhase(): MonitorPhase = if (
        preferences.getMonitorSchemaVersion() >= 2 ||
        preferences.getLegacyPhaseEndsAtMillis() <= 0L
    ) {
        MonitorPhase.LOCK
    } else {
        preferences.getLegacyMonitorPhase() ?: MonitorPhase.LOCK
    }
}
