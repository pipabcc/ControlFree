package com.example.controlfree.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundObservationCircuitBreakerTest {
    @Test
    fun `达到失败阈值后打开且冷却前拒绝`() {
        val breaker = breaker()

        repeat(2) { breaker.recordFailure(1_000L) }
        assertEquals(
            ForegroundObservationCircuitDecision.ALLOW,
            breaker.beforeRequest(1_000L)
        )
        breaker.recordFailure(1_000L)

        assertEquals(ForegroundObservationCircuitState.OPEN, breaker.snapshot().state)
        assertEquals(6_000L, breaker.snapshot().openUntilElapsedMillis)
        assertEquals(
            ForegroundObservationCircuitDecision.REJECT,
            breaker.beforeRequest(5_999L)
        )
    }

    @Test
    fun `冷却结束只允许一个半开探针且成功后关闭`() {
        val breaker = breaker()
        repeat(3) { breaker.recordFailure(0L) }

        assertEquals(
            ForegroundObservationCircuitDecision.ALLOW,
            breaker.beforeRequest(5_000L)
        )
        assertEquals(ForegroundObservationCircuitState.HALF_OPEN, breaker.snapshot().state)
        assertEquals(
            ForegroundObservationCircuitDecision.REJECT,
            breaker.beforeRequest(5_000L)
        )

        breaker.recordHealthyResponse()

        assertEquals(ForegroundObservationCircuitState.CLOSED, breaker.snapshot().state)
        assertEquals(0, breaker.snapshot().consecutiveFailures)
    }

    @Test
    fun `半开探针失败会重新进入完整冷却窗口`() {
        val breaker = breaker()
        repeat(3) { breaker.recordFailure(0L) }
        assertEquals(
            ForegroundObservationCircuitDecision.ALLOW,
            breaker.beforeRequest(5_000L)
        )

        breaker.recordFailure(5_100L)

        assertEquals(ForegroundObservationCircuitState.OPEN, breaker.snapshot().state)
        assertEquals(10_100L, breaker.snapshot().openUntilElapsedMillis)
    }

    @Test
    fun `强制打开在单调时间上饱和而不溢出`() {
        val breaker = breaker()

        breaker.forceOpen(Long.MAX_VALUE - 1_000L)

        assertEquals(Long.MAX_VALUE, breaker.snapshot().openUntilElapsedMillis)
    }

    private fun breaker() = ForegroundObservationCircuitBreaker(
        failureThreshold = 3,
        openDurationMillis = 5_000L
    )
}
