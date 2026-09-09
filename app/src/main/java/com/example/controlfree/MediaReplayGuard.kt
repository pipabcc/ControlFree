package com.example.controlfree

/** 检测到锁定期间媒体恢复播放后应采取的动作。 */
enum class MediaReplayAction {
    NONE,
    REAPPLY_MEDIA_PAUSE
}

internal fun shouldEnableMediaReplayGuard(
    isLockPhase: Boolean,
    isLockSurfaceVisible: Boolean,
    isAllowedAppMediaTransition: Boolean,
    isCallActive: Boolean,
    isInteractive: Boolean
): Boolean =
    isLockPhase &&
        (isLockSurfaceVisible || !isInteractive) &&
        !isAllowedAppMediaTransition &&
        !isCallActive

/**
 * 锁定期间媒体重新播放检测的纯 Kotlin 调度策略。
 *
 * 守卫不创建线程或循环，由监督服务现有的主计时节奏驱动。持续检测到播放时按
 * 1/2/4/8 秒退避；一旦静默就恢复调用方给定的常规探测间隔。
 */
class MediaReplayGuard(
    private val minimumRetryMillis: Long = DEFAULT_MINIMUM_RETRY_MILLIS,
    private val maximumRetryMillis: Long = DEFAULT_MAXIMUM_RETRY_MILLIS
) {
    private var enabled = false
    private var nextProbeElapsedMillis = Long.MAX_VALUE
    private var pauseCooldownUntilElapsedMillis = 0L
    private var consecutivePlaybackDetections = 0

    init {
        require(minimumRetryMillis > 0L) { "minimumRetryMillis must be positive" }
        require(maximumRetryMillis >= minimumRetryMillis) {
            "maximumRetryMillis must be at least minimumRetryMillis"
        }
    }

    fun setEnabled(
        shouldEnable: Boolean,
        nowElapsedMillis: Long,
        probeImmediately: Boolean = false
    ) {
        if (!shouldEnable) {
            disable()
            return
        }

        if (!enabled) {
            enabled = true
            consecutivePlaybackDetections = 0
            nextProbeElapsedMillis = if (probeImmediately) {
                maxOf(nowElapsedMillis, pauseCooldownUntilElapsedMillis)
            } else {
                maxOf(
                    safeAdd(nowElapsedMillis, minimumRetryMillis),
                    pauseCooldownUntilElapsedMillis
                )
            }
            return
        }

        if (probeImmediately) requestImmediateProbe(nowElapsedMillis)
    }

    fun requestImmediateProbe(nowElapsedMillis: Long) {
        if (!enabled) return
        nextProbeElapsedMillis = minOf(
            nextProbeElapsedMillis,
            maxOf(nowElapsedMillis, pauseCooldownUntilElapsedMillis)
        )
    }

    /** 已主动发送暂停命令后至少等待一个最小重试间隔，避免 isMusicActive 状态滞后造成连发。 */
    fun deferProbeAfterPause(nowElapsedMillis: Long) {
        pauseCooldownUntilElapsedMillis = safeAdd(nowElapsedMillis, minimumRetryMillis)
        if (enabled) nextProbeElapsedMillis = pauseCooldownUntilElapsedMillis
    }

    fun shouldProbe(nowElapsedMillis: Long): Boolean =
        enabled && nowElapsedMillis >= nextProbeElapsedMillis

    fun recordProbeResult(
        nowElapsedMillis: Long,
        isMediaPlaying: Boolean,
        quietProbeIntervalMillis: Long
    ): MediaReplayAction {
        if (!enabled) return MediaReplayAction.NONE
        require(quietProbeIntervalMillis > 0L) {
            "quietProbeIntervalMillis must be positive"
        }

        if (!isMediaPlaying) {
            consecutivePlaybackDetections = 0
            pauseCooldownUntilElapsedMillis = 0L
            nextProbeElapsedMillis = safeAdd(nowElapsedMillis, quietProbeIntervalMillis)
            return MediaReplayAction.NONE
        }

        val retryDelay = retryDelayMillis(consecutivePlaybackDetections)
        consecutivePlaybackDetections = (consecutivePlaybackDetections + 1).coerceAtMost(63)
        pauseCooldownUntilElapsedMillis = safeAdd(nowElapsedMillis, minimumRetryMillis)
        nextProbeElapsedMillis = safeAdd(nowElapsedMillis, retryDelay)
        return MediaReplayAction.REAPPLY_MEDIA_PAUSE
    }

    fun disable() {
        enabled = false
        nextProbeElapsedMillis = Long.MAX_VALUE
        pauseCooldownUntilElapsedMillis = 0L
        consecutivePlaybackDetections = 0
    }

    internal val isEnabled: Boolean
        get() = enabled

    internal fun nextProbeElapsedMillisForTest(): Long = nextProbeElapsedMillis

    internal fun nextProbeDelayMillis(nowElapsedMillis: Long): Long? =
        if (!enabled) null else (nextProbeElapsedMillis - nowElapsedMillis).coerceAtLeast(0L)

    private fun retryDelayMillis(detectionIndex: Int): Long {
        var delay = minimumRetryMillis
        repeat(detectionIndex.coerceAtMost(62)) {
            if (delay >= maximumRetryMillis) return maximumRetryMillis
            delay = (delay * 2L).coerceAtMost(maximumRetryMillis)
        }
        return delay
    }

    private fun safeAdd(base: Long, delay: Long): Long =
        if (base > Long.MAX_VALUE - delay) Long.MAX_VALUE else base + delay

    companion object {
        const val DEFAULT_MINIMUM_RETRY_MILLIS = 1_000L
        const val DEFAULT_MAXIMUM_RETRY_MILLIS = 8_000L
    }
}
