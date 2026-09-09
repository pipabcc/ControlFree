package com.example.controlfree

/**
 * 前台应用观察的运行状态。这里不依赖 Android 类型，避免调度规则散落到 Service 中。
 */
enum class ForegroundObservationCadenceMode {
    LOCKED,
    ALLOWLIST_LAUNCHING,
    ALLOWLIST_ACTIVE,
    CALL_ACTIVE
}

enum class ForegroundObservationCadenceTrigger {
    SCREEN_ON,
    SYSTEM_NAVIGATION,
    ALLOWLIST_LAUNCH
}

data class ForegroundObservationCadenceInput(
    val nowElapsedMillis: Long,
    val isLockPhase: Boolean,
    val isInteractive: Boolean,
    val mode: ForegroundObservationCadenceMode
)

/**
 * 根据锁定状态、临时放行状态和查询健康度计算下一次前台观察间隔。
 *
 * 所有时间均来自 elapsedRealtime，因而不受系统时间或时区变化影响。策略仅保存本次
 * Service 生命周期内的短期节奏状态，熄屏或离开锁定阶段后应调用 [reset]。
 */
class ForegroundObservationCadencePolicy {
    private var rapidCadenceUntilElapsedMillis = 0L
    private var currentMode: ForegroundObservationCadenceMode? = null
    private var currentModeSinceElapsedMillis = 0L
    private var consecutiveQueryFailures = 0

    fun onTrigger(
        trigger: ForegroundObservationCadenceTrigger,
        nowElapsedMillis: Long
    ) {
        require(nowElapsedMillis >= 0L) { "nowElapsedMillis must not be negative" }
        val rapidWindowMillis = when (trigger) {
            ForegroundObservationCadenceTrigger.SCREEN_ON,
            ForegroundObservationCadenceTrigger.SYSTEM_NAVIGATION,
            ForegroundObservationCadenceTrigger.ALLOWLIST_LAUNCH ->
                RAPID_CADENCE_WINDOW_MILLIS
        }
        rapidCadenceUntilElapsedMillis = maxOf(
            rapidCadenceUntilElapsedMillis,
            saturatedAdd(nowElapsedMillis, rapidWindowMillis)
        )

        // Home/最近任务等事件发生后，快速观察结束仍先经过普通锁层的活跃频率。
        if (currentMode == ForegroundObservationCadenceMode.LOCKED) {
            currentModeSinceElapsedMillis = nowElapsedMillis
        }
    }

    fun recordObservationResult(isAvailable: Boolean) {
        consecutiveQueryFailures = if (isAvailable) {
            0
        } else {
            (consecutiveQueryFailures + 1).coerceAtMost(MAX_TRACKED_FAILURES)
        }
    }

    /** 返回 null 表示当前不应挂载前台观察任务。 */
    fun nextDelayMillis(input: ForegroundObservationCadenceInput): Long? {
        require(input.nowElapsedMillis >= 0L) { "nowElapsedMillis must not be negative" }
        if (!input.isLockPhase || !input.isInteractive) {
            reset()
            return null
        }

        updateMode(input.mode, input.nowElapsedMillis)
        val baseDelayMillis = when {
            input.nowElapsedMillis < rapidCadenceUntilElapsedMillis -> RAPID_INTERVAL_MILLIS
            input.mode == ForegroundObservationCadenceMode.ALLOWLIST_LAUNCHING ->
                RAPID_INTERVAL_MILLIS
            input.mode == ForegroundObservationCadenceMode.CALL_ACTIVE ->
                callActiveInterval(input.nowElapsedMillis)
            input.mode == ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE ->
                allowlistActiveInterval(input.nowElapsedMillis)
            else -> lockedInterval(input.nowElapsedMillis)
        }
        return applyFailureBackoff(baseDelayMillis)
    }

    fun reset() {
        rapidCadenceUntilElapsedMillis = 0L
        consecutiveQueryFailures = 0
        clearCadenceState()
    }

    private fun updateMode(mode: ForegroundObservationCadenceMode, nowElapsedMillis: Long) {
        if (currentMode == mode) return
        currentMode = mode
        currentModeSinceElapsedMillis = nowElapsedMillis
    }

    private fun allowlistActiveInterval(nowElapsedMillis: Long): Long =
        if (nowElapsedMillis - currentModeSinceElapsedMillis <
            ALLOWLIST_STABLE_AFTER_MILLIS
        ) {
            ALLOWLIST_ACTIVE_INTERVAL_MILLIS
        } else {
            ALLOWLIST_STABLE_INTERVAL_MILLIS
        }

    private fun callActiveInterval(nowElapsedMillis: Long): Long =
        if (nowElapsedMillis - currentModeSinceElapsedMillis < CALL_STABLE_AFTER_MILLIS) {
            RAPID_INTERVAL_MILLIS
        } else {
            CALL_STABLE_INTERVAL_MILLIS
        }

    private fun lockedInterval(nowElapsedMillis: Long): Long =
        if (nowElapsedMillis - currentModeSinceElapsedMillis < LOCK_STABLE_AFTER_MILLIS) {
            LOCK_ACTIVE_INTERVAL_MILLIS
        } else {
            LOCK_STABLE_INTERVAL_MILLIS
        }

    private fun applyFailureBackoff(baseDelayMillis: Long): Long {
        var delayMillis = baseDelayMillis
        repeat(consecutiveQueryFailures) {
            delayMillis = (delayMillis * 2L).coerceAtMost(MAX_FAILURE_BACKOFF_MILLIS)
        }
        return delayMillis
    }

    private fun clearCadenceState() {
        currentMode = null
        currentModeSinceElapsedMillis = 0L
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        const val RAPID_INTERVAL_MILLIS = 250L
        const val RAPID_CADENCE_WINDOW_MILLIS = 2_000L
        const val ALLOWLIST_ACTIVE_INTERVAL_MILLIS = 500L
        const val ALLOWLIST_STABLE_INTERVAL_MILLIS = 1_000L
        const val ALLOWLIST_STABLE_AFTER_MILLIS = 5_000L
        const val CALL_STABLE_INTERVAL_MILLIS = 1_000L
        const val CALL_STABLE_AFTER_MILLIS = 5_000L
        const val LOCK_ACTIVE_INTERVAL_MILLIS = 1_500L
        const val LOCK_STABLE_INTERVAL_MILLIS = 2_000L
        const val LOCK_STABLE_AFTER_MILLIS = 10_000L
        const val MAX_FAILURE_BACKOFF_MILLIS = 8_000L
        private const val MAX_TRACKED_FAILURES = 16
    }
}
