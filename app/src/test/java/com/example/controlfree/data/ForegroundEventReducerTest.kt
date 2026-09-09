package com.example.controlfree.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEventReducerTest {
    @Test
    fun `overlap 重复事件不会再次发布或回退状态`() {
        val allowedForeground = event("example.allowed", 1_000L, ForegroundEventKind.FOREGROUND)
        val homeForeground = event("example.home", 1_500L, ForegroundEventKind.FOREGROUND)
        val first = ForegroundEventReducer.reduce(
            ForegroundReducerState(),
            listOf(allowedForeground, homeForeground)
        )

        val replay = ForegroundEventReducer.reduce(
            first.state,
            listOf(allowedForeground, homeForeground)
        )

        assertEquals("example.home", replay.state.currentPackageName)
        assertEquals(1_500L, replay.state.transitionTimestampMillis)
        assertTrue(replay.newEvents.isEmpty())
    }

    @Test
    fun `目标进入后台且没有后继前台事件时清除前台包`() {
        val foreground = ForegroundEventReducer.reduce(
            ForegroundReducerState(),
            listOf(event("example.allowed", 1_000L, ForegroundEventKind.FOREGROUND))
        )

        val background = ForegroundEventReducer.reduce(
            foreground.state,
            listOf(event("example.allowed", 2_000L, ForegroundEventKind.BACKGROUND))
        )

        assertNull(background.state.currentPackageName)
        assertEquals(2_000L, background.state.transitionTimestampMillis)
    }

    @Test
    fun `延迟到达的旧事件不会覆盖较新的前台包`() {
        val current = ForegroundEventReducer.reduce(
            ForegroundReducerState(),
            listOf(event("example.current", 5_000L, ForegroundEventKind.FOREGROUND))
        )

        val delayed = ForegroundEventReducer.reduce(
            current.state,
            listOf(event("example.old", 4_000L, ForegroundEventKind.FOREGROUND))
        )

        assertEquals("example.current", delayed.state.currentPackageName)
        assertEquals(5_000L, delayed.state.transitionTimestampMillis)
        assertEquals(1, delayed.newEvents.size)
    }

    @Test
    fun `同包 Activity 切换以后续恢复事件为准`() {
        val initial = ForegroundEventReducer.reduce(
            ForegroundReducerState(),
            listOf(event("example.allowed", 1_000L, ForegroundEventKind.FOREGROUND))
        )

        val transition = ForegroundEventReducer.reduce(
            initial.state,
            listOf(
                event("example.allowed", 2_000L, ForegroundEventKind.BACKGROUND),
                event("example.allowed", 2_100L, ForegroundEventKind.FOREGROUND)
            )
        )

        assertEquals("example.allowed", transition.state.currentPackageName)
        assertEquals(2_100L, transition.state.transitionTimestampMillis)
    }

    @Test
    fun `切换非白名单以前台事件替换目标包`() {
        val initial = ForegroundEventReducer.reduce(
            ForegroundReducerState(),
            listOf(event("example.allowed", 1_000L, ForegroundEventKind.FOREGROUND))
        )

        val switched = ForegroundEventReducer.reduce(
            initial.state,
            listOf(event("example.blocked", 2_000L, ForegroundEventKind.FOREGROUND))
        )

        assertEquals("example.blocked", switched.state.currentPackageName)
    }

    private fun event(
        packageName: String,
        timestampMillis: Long,
        kind: ForegroundEventKind
    ) = ForegroundAppEvent(packageName, timestampMillis, kind)
}
