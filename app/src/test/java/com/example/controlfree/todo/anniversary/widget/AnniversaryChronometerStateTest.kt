package com.example.controlfree.todo.anniversary.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryChronometerStateTest {
    @Test
    fun `倒数使用未来的elapsedRealtime基准`() {
        val state = anniversaryChronometerState(
            nowEpochMillis = 1_000L,
            occurrenceEpochMillis = 61_000L,
            elapsedRealtimeMillis = 10_000L,
            countDown = true
        )

        assertEquals(70_000L, state.baseElapsedRealtimeMillis)
        assertTrue(state.countDown)
        assertEquals("还有 %s", state.format)
    }

    @Test
    fun `正数使用过去的elapsedRealtime基准`() {
        val state = anniversaryChronometerState(
            nowEpochMillis = 61_000L,
            occurrenceEpochMillis = 1_000L,
            elapsedRealtimeMillis = 70_000L,
            countDown = false
        )

        assertEquals(10_000L, state.baseElapsedRealtimeMillis)
        assertFalse(state.countDown)
        assertEquals("已过 %s", state.format)
    }

    @Test
    fun `正数跨度长于本次开机时间时允许负基准`() {
        val state = anniversaryChronometerState(
            nowEpochMillis = 100_000L,
            occurrenceEpochMillis = 0L,
            elapsedRealtimeMillis = 10_000L,
            countDown = false
        )

        assertEquals(-90_000L, state.baseElapsedRealtimeMillis)
    }
}
