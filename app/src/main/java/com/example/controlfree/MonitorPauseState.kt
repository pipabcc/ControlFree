package com.example.controlfree

import kotlin.math.min

internal const val MONITOR_UNKNOWN_BOOT_COUNT = -1
internal const val MAX_LOCK_PAUSE_MILLIS = MAX_LOCK_PAUSE_MINUTES * 60_000L

/** 持久化同一锁定阶段的累计暂停额度及当前暂停截止点。 */
data class MonitorPauseState(
    val accumulatedPauseMillis: Long,
    val startedAtEpochMillis: Long = 0L,
    val untilEpochMillis: Long = 0L,
    val deadlineElapsedMillis: Long = 0L,
    val bootCount: Int = MONITOR_UNKNOWN_BOOT_COUNT
) {
    val isActive: Boolean
        get() = untilEpochMillis > startedAtEpochMillis

    val remainingBudgetMillis: Long
        get() = (MAX_LOCK_PAUSE_MILLIS - accumulatedPauseMillis).coerceAtLeast(0L)

    val isStructurallyValid: Boolean
        get() {
            if (accumulatedPauseMillis !in 0L..MAX_LOCK_PAUSE_MILLIS) return false
            if (!isActive) {
                return startedAtEpochMillis == 0L &&
                    untilEpochMillis == 0L &&
                    deadlineElapsedMillis == 0L &&
                    bootCount == MONITOR_UNKNOWN_BOOT_COUNT
            }
            val activeDuration = untilEpochMillis - startedAtEpochMillis
            return startedAtEpochMillis >= 0L &&
                activeDuration in 1L..MAX_LOCK_PAUSE_MILLIS &&
                activeDuration <= accumulatedPauseMillis &&
                deadlineElapsedMillis > 0L &&
                bootCount >= 0
        }

    fun isActiveAt(
        nowEpochMillis: Long,
        nowElapsedMillis: Long,
        currentBootCount: Int
    ): Boolean {
        if (!isStructurallyValid || !isActive) return false
        if (currentBootCount < 0 || bootCount != currentBootCount) return false
        val activeDuration = untilEpochMillis - startedAtEpochMillis
        val startedAtElapsedMillis = deadlineElapsedMillis - activeDuration
        if (nowElapsedMillis < startedAtElapsedMillis) return false
        // 两套时钟任一到期或异常回拨都立即恢复锁定，避免调整墙钟延长暂停。
        return nowElapsedMillis < deadlineElapsedMillis &&
            nowEpochMillis >= startedAtEpochMillis &&
            nowEpochMillis < untilEpochMillis
    }

    fun clearActive(): MonitorPauseState = copy(
        startedAtEpochMillis = 0L,
        untilEpochMillis = 0L,
        deadlineElapsedMillis = 0L,
        bootCount = MONITOR_UNKNOWN_BOOT_COUNT
    )

    companion object {
        fun begin(
            previous: MonitorPauseState?,
            requestedMillis: Long,
            nowEpochMillis: Long,
            nowElapsedMillis: Long,
            bootCount: Int,
            hardStopEpochMillis: Long? = null
        ): MonitorPauseState? {
            if (
                requestedMillis <= 0L ||
                nowEpochMillis < 0L ||
                nowElapsedMillis < 0L ||
                bootCount < 0
            ) {
                return null
            }
            val accumulated = previous?.accumulatedPauseMillis ?: 0L
            if (previous?.isActive == true || accumulated !in 0L..MAX_LOCK_PAUSE_MILLIS) {
                return null
            }
            val allowedMillis = min(requestedMillis, MAX_LOCK_PAUSE_MILLIS - accumulated)
            if (allowedMillis != requestedMillis) return null
            val requestedUntil = saturatedAdd(nowEpochMillis, allowedMillis)
            val effectiveUntil = hardStopEpochMillis?.let { min(requestedUntil, it) }
                ?: requestedUntil
            val effectiveDuration = effectiveUntil - nowEpochMillis
            if (effectiveDuration <= 0L) return null
            val deadlineElapsed = saturatedAdd(nowElapsedMillis, effectiveDuration)
            return MonitorPauseState(
                accumulatedPauseMillis = accumulated + effectiveDuration,
                startedAtEpochMillis = nowEpochMillis,
                untilEpochMillis = effectiveUntil,
                deadlineElapsedMillis = deadlineElapsed,
                bootCount = bootCount
            ).takeIf(MonitorPauseState::isStructurallyValid)
        }

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
