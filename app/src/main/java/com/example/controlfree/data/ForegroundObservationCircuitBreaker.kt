package com.example.controlfree.data

enum class ForegroundObservationCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

internal enum class ForegroundObservationCircuitDecision {
    ALLOW,
    REJECT
}

data class ForegroundObservationCircuitSnapshot(
    val state: ForegroundObservationCircuitState,
    val consecutiveFailures: Int,
    val openUntilElapsedMillis: Long
)

/**
 * UsageStats 查询熔断器。所有时间都使用单调时钟，避免系统时间调整绕过冷却窗口。
 */
internal class ForegroundObservationCircuitBreaker(
    private val failureThreshold: Int,
    private val openDurationMillis: Long
) {
    private var state = ForegroundObservationCircuitState.CLOSED
    private var consecutiveFailures = 0
    private var openUntilElapsedMillis = 0L

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(openDurationMillis > 0L) { "openDurationMillis must be positive" }
    }

    fun beforeRequest(nowElapsedMillis: Long): ForegroundObservationCircuitDecision {
        require(nowElapsedMillis >= 0L) { "nowElapsedMillis must not be negative" }
        return when (state) {
            ForegroundObservationCircuitState.CLOSED ->
                ForegroundObservationCircuitDecision.ALLOW

            ForegroundObservationCircuitState.OPEN -> {
                if (nowElapsedMillis < openUntilElapsedMillis) {
                    ForegroundObservationCircuitDecision.REJECT
                } else {
                    state = ForegroundObservationCircuitState.HALF_OPEN
                    ForegroundObservationCircuitDecision.ALLOW
                }
            }

            // HALF_OPEN 的唯一探针由 Worker 的 currentAttempt 持有；在它完成前拒绝其他请求。
            ForegroundObservationCircuitState.HALF_OPEN ->
                ForegroundObservationCircuitDecision.REJECT
        }
    }

    fun recordHealthyResponse() {
        state = ForegroundObservationCircuitState.CLOSED
        consecutiveFailures = 0
        openUntilElapsedMillis = 0L
    }

    fun recordFailure(nowElapsedMillis: Long) {
        require(nowElapsedMillis >= 0L) { "nowElapsedMillis must not be negative" }
        if (state == ForegroundObservationCircuitState.HALF_OPEN) {
            open(nowElapsedMillis)
            return
        }
        if (consecutiveFailures < Int.MAX_VALUE) consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) open(nowElapsedMillis)
    }

    fun forceOpen(nowElapsedMillis: Long) {
        require(nowElapsedMillis >= 0L) { "nowElapsedMillis must not be negative" }
        consecutiveFailures = maxOf(consecutiveFailures, failureThreshold)
        open(nowElapsedMillis)
    }

    fun reset() {
        state = ForegroundObservationCircuitState.CLOSED
        consecutiveFailures = 0
        openUntilElapsedMillis = 0L
    }

    fun snapshot() = ForegroundObservationCircuitSnapshot(
        state = state,
        consecutiveFailures = consecutiveFailures,
        openUntilElapsedMillis = openUntilElapsedMillis
    )

    private fun open(nowElapsedMillis: Long) {
        state = ForegroundObservationCircuitState.OPEN
        openUntilElapsedMillis = saturatedAdd(nowElapsedMillis, openDurationMillis)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
