package com.example.controlfree

enum class MonitorPhase(val storedValue: String) {
    USAGE("usage"),
    LOCK("lock");

    fun next(): MonitorPhase = if (this == USAGE) LOCK else USAGE

    companion object {
        fun fromStoredValue(value: String?): MonitorPhase =
            entries.firstOrNull { it.storedValue == value } ?: USAGE
    }
}

enum class MonitorSessionMode(
    val storedValue: String,
    val initialPhase: MonitorPhase
) {
    SUPERVISION("supervision", MonitorPhase.USAGE),
    FOCUS("focus", MonitorPhase.LOCK);

    companion object {
        fun fromStoredValue(value: String?): MonitorSessionMode =
            entries.firstOrNull { it.storedValue == value } ?: SUPERVISION
    }
}

data class MonitorCycleSnapshot(
    val phase: MonitorPhase,
    val remainingMillis: Long,
    val checkpointElapsedMillis: Long,
    val bootCount: Int,
    val isInteractive: Boolean,
    val screenOffRecoveryRemainderMillis: Long = 0L
)

/**
 * 基于单调时钟的监督周期状态机。
 *
 * 玩机阶段亮屏时消耗时间；普通监督熄屏每满 5 分钟恢复 1 分钟玩机额度。
 * 普通监督锁定阶段仅熄屏计时，专注模式锁定阶段仍连续计时。
 * 调用方负责把 [android.os.SystemClock.elapsedRealtime] 与系统启动计数传入本类；
 * 本类不依赖 Android 生命周期，因此边界规则可以通过普通单元测试验证。
 */
class MonitorCycle(
    private val usageDurationMillis: Long,
    private val lockDurationMillis: Long,
    private val initialPhase: MonitorPhase = MonitorPhase.USAGE,
    private val lockCountsDownWhileInteractive: Boolean = false,
    private val usageRecoveryIntervalMillis: Long = 5L * 60_000L,
    private val usageRecoveryRewardMillis: Long = 60_000L
) {
    init {
        require(usageDurationMillis > 0) { "玩机时长必须大于 0" }
        require(lockDurationMillis > 0) { "锁定时长必须大于 0" }
        require(usageRecoveryIntervalMillis > 0L) { "玩机恢复周期必须大于 0" }
        require(usageRecoveryRewardMillis >= 0L) { "玩机恢复额度不能为负数" }
    }

    fun start(
        nowElapsedMillis: Long,
        bootCount: Int,
        isInteractive: Boolean
    ): MonitorCycleSnapshot =
        MonitorCycleSnapshot(
            phase = initialPhase,
            remainingMillis = durationMillis(initialPhase),
            checkpointElapsedMillis = nowElapsedMillis,
            bootCount = bootCount,
            isInteractive = isInteractive,
            screenOffRecoveryRemainderMillis = 0L
        )

    /**
     * 按快照记录的旧屏幕状态结算到 [nowElapsedMillis]。
     *
     * 若本阶段已到期，只进入一次下一阶段并恢复该阶段完整时长。超出的时间不会
     * 继续消耗下一阶段，避免一次延迟调度跨越多个监督周期。
     */
    fun advance(
        snapshot: MonitorCycleSnapshot,
        nowElapsedMillis: Long
    ): MonitorCycleSnapshot {
        val boundedRemaining = snapshot.remainingMillis.coerceIn(
            minimumValue = 0L,
            maximumValue = durationMillis(snapshot.phase)
        )
        val elapsedMillis = elapsedSince(
            checkpointElapsedMillis = snapshot.checkpointElapsedMillis,
            nowElapsedMillis = nowElapsedMillis
        )
        val settlement = settle(
            phase = snapshot.phase,
            boundedRemaining = boundedRemaining,
            elapsedMillis = elapsedMillis,
            isInteractive = snapshot.isInteractive,
            recoveryRemainderMillis = snapshot.screenOffRecoveryRemainderMillis
        )
        val remainingAfterSettlement = settlement.remainingMillis

        if (remainingAfterSettlement == 0L) {
            val nextPhase = snapshot.phase.next()
            return snapshot.copy(
                phase = nextPhase,
                remainingMillis = durationMillis(nextPhase),
                checkpointElapsedMillis = nowElapsedMillis,
                screenOffRecoveryRemainderMillis = 0L
            )
        }

        return snapshot.copy(
            remainingMillis = remainingAfterSettlement,
            checkpointElapsedMillis = nowElapsedMillis,
            screenOffRecoveryRemainderMillis = settlement.recoveryRemainderMillis
        )
    }

    /**
     * 屏幕状态变化时，必须先按旧状态结算，再启用新状态。
     */
    fun updateInteractiveState(
        snapshot: MonitorCycleSnapshot,
        nowElapsedMillis: Long,
        isInteractive: Boolean
    ): MonitorCycleSnapshot =
        advance(snapshot, nowElapsedMillis).copy(isInteractive = isInteractive)

    /**
     * 在恢复环境不可信时重新建立单调时钟锚点；不可安全结算的间隔不计时。
     */
    fun reanchor(
        snapshot: MonitorCycleSnapshot,
        nowElapsedMillis: Long,
        bootCount: Int,
        isInteractive: Boolean
    ): MonitorCycleSnapshot {
        val boundedRemaining = snapshot.remainingMillis.coerceIn(
            minimumValue = 0L,
            maximumValue = durationMillis(snapshot.phase)
        )
        val phase = if (boundedRemaining == 0L) snapshot.phase.next() else snapshot.phase
        val remainingMillis = if (boundedRemaining == 0L) {
            durationMillis(phase)
        } else {
            boundedRemaining
        }
        return snapshot.copy(
            phase = phase,
            remainingMillis = remainingMillis,
            checkpointElapsedMillis = nowElapsedMillis,
            bootCount = bootCount,
            isInteractive = isInteractive,
            screenOffRecoveryRemainderMillis = if (phase == MonitorPhase.USAGE) {
                snapshot.screenOffRecoveryRemainderMillis
                    .coerceIn(0L, usageRecoveryIntervalMillis - 1L)
            } else {
                0L
            }
        )
    }

    /** 经过认证后跳过当前阶段，并从下一阶段的完整额度重新开始。 */
    fun skipCurrentPhase(
        snapshot: MonitorCycleSnapshot,
        nowElapsedMillis: Long,
        bootCount: Int,
        isInteractive: Boolean
    ): MonitorCycleSnapshot {
        val nextPhase = snapshot.phase.next()
        return snapshot.copy(
            phase = nextPhase,
            remainingMillis = durationMillis(nextPhase),
            checkpointElapsedMillis = nowElapsedMillis.coerceAtLeast(0L),
            bootCount = bootCount,
            isInteractive = isInteractive,
            screenOffRecoveryRemainderMillis = 0L
        )
    }

    fun remainingSeconds(snapshot: MonitorCycleSnapshot): Int {
        val remainingMillis = snapshot.remainingMillis.coerceIn(
            minimumValue = 0L,
            maximumValue = durationMillis(snapshot.phase)
        )
        val roundedSeconds = remainingMillis / MILLIS_PER_SECOND +
            if (remainingMillis % MILLIS_PER_SECOND == 0L) 0L else 1L
        return roundedSeconds
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun durationMillis(phase: MonitorPhase): Long =
        if (phase == MonitorPhase.USAGE) usageDurationMillis else lockDurationMillis

    /**
     * 返回当前计时条件持续不变时的单调时钟阶段边界。调用方可据此安排
     * ELAPSED_REALTIME_WAKEUP，即使主线程在熄屏休眠期间不执行也能按时结算。
     */
    fun nextPhaseDeadlineElapsedMillis(snapshot: MonitorCycleSnapshot): Long? {
        if (!shouldCountDown(snapshot.phase, snapshot.isInteractive)) return null
        val boundedRemaining = snapshot.remainingMillis.coerceIn(
            minimumValue = 0L,
            maximumValue = durationMillis(snapshot.phase)
        )
        return saturatedAdd(
            snapshot.checkpointElapsedMillis.coerceAtLeast(0L),
            boundedRemaining
        )
    }

    /**
     * 返回某阶段的计时规则是否不受亮灭屏状态影响。
     */
    fun canSettleAcrossInteractiveStateChange(phase: MonitorPhase): Boolean =
        shouldCountDown(phase, isInteractive = true) ==
            shouldCountDown(phase, isInteractive = false)

    private fun shouldCountDown(
        phase: MonitorPhase,
        isInteractive: Boolean
    ): Boolean = when (phase) {
        MonitorPhase.USAGE -> isInteractive
        MonitorPhase.LOCK -> !isInteractive || lockCountsDownWhileInteractive
    }

    private fun settle(
        phase: MonitorPhase,
        boundedRemaining: Long,
        elapsedMillis: Long,
        isInteractive: Boolean,
        recoveryRemainderMillis: Long
    ): Settlement = when {
        phase == MonitorPhase.USAGE && !isInteractive -> {
            val previousRemainder = recoveryRemainderMillis
                .coerceIn(0L, usageRecoveryIntervalMillis - 1L)
            val totalOffMillis = saturatedAdd(previousRemainder, elapsedMillis)
            val completedIntervals = totalOffMillis / usageRecoveryIntervalMillis
            val recoveredMillis = saturatedMultiply(
                completedIntervals,
                usageRecoveryRewardMillis
            )
            Settlement(
                remainingMillis = saturatedAdd(boundedRemaining, recoveredMillis)
                    .coerceAtMost(usageDurationMillis),
                recoveryRemainderMillis = totalOffMillis % usageRecoveryIntervalMillis
            )
        }
        shouldCountDown(phase, isInteractive) -> Settlement(
            remainingMillis = (boundedRemaining - elapsedMillis).coerceAtLeast(0L),
            recoveryRemainderMillis = if (phase == MonitorPhase.USAGE) {
                recoveryRemainderMillis.coerceIn(0L, usageRecoveryIntervalMillis - 1L)
            } else {
                0L
            }
        )
        else -> Settlement(
            remainingMillis = boundedRemaining,
            recoveryRemainderMillis = 0L
        )
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatedMultiply(left: Long, right: Long): Long = when {
        left == 0L || right == 0L -> 0L
        left > Long.MAX_VALUE / right -> Long.MAX_VALUE
        else -> left * right
    }

    private fun elapsedSince(
        checkpointElapsedMillis: Long,
        nowElapsedMillis: Long
    ): Long {
        if (nowElapsedMillis <= checkpointElapsedMillis) return 0L

        val difference = nowElapsedMillis - checkpointElapsedMillis
        return if (difference >= 0L) difference else Long.MAX_VALUE
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }

    private data class Settlement(
        val remainingMillis: Long,
        val recoveryRemainderMillis: Long
    )
}
