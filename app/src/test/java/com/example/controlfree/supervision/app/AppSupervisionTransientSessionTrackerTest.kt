package com.example.controlfree.supervision.app

import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSupervisionTransientSessionTrackerTest {
    @Test
    fun `乱序和重叠重复事件只结算一次未出现在端点的完整会话`() {
        val tracker = AppSupervisionTransientSessionTracker()
        assertTrue(
            tracker.recordObservation(
                sampledAtEpochMillis = 1_000L,
                foregroundPackage = "example.home",
                isInteractive = true
            ).isEmpty()
        )

        val result = tracker.recordObservation(
            sampledAtEpochMillis = 2_000L,
            foregroundPackage = "example.home",
            isInteractive = true,
            events = listOf(
                event("example.video", 1_700L, ForegroundEventKind.BACKGROUND),
                event("example.video", 1_200L, ForegroundEventKind.FOREGROUND),
                event("example.video", 1_700L, ForegroundEventKind.BACKGROUND),
                event("example.video", 1_200L, ForegroundEventKind.FOREGROUND)
            )
        )

        assertEquals(mapOf("example.video" to 500L), result)
        assertTrue(
            tracker.recordObservation(
                sampledAtEpochMillis = 3_000L,
                foregroundPackage = "example.home",
                isInteractive = true,
                events = listOf(
                    event("example.video", 1_200L, ForegroundEventKind.FOREGROUND),
                    event("example.video", 1_700L, ForegroundEventKind.BACKGROUND)
                )
            ).isEmpty()
        )
    }

    @Test
    fun `端点出现目标包时不补扣其区间内会话`() {
        val tracker = AppSupervisionTransientSessionTracker()
        tracker.recordObservation(1_000L, "example.video", true)

        val result = tracker.recordObservation(
            sampledAtEpochMillis = 2_000L,
            foregroundPackage = "example.video",
            isInteractive = true,
            events = listOf(
                event("example.video", 1_200L, ForegroundEventKind.FOREGROUND),
                event("example.video", 1_700L, ForegroundEventKind.BACKGROUND)
            )
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `跨两次查询迟到的结束事件仍能闭合原轮询区间会话`() {
        val tracker = AppSupervisionTransientSessionTracker()
        tracker.recordObservation(0L, "example.home", true)

        assertTrue(
            tracker.recordObservation(
                sampledAtEpochMillis = 1_000L,
                foregroundPackage = "example.home",
                isInteractive = true,
                events = listOf(event("example.video", 300L, ForegroundEventKind.FOREGROUND))
            ).isEmpty()
        )

        val result = tracker.recordObservation(
            sampledAtEpochMillis = 2_000L,
            foregroundPackage = "example.home",
            isInteractive = true,
            events = listOf(event("example.video", 700L, ForegroundEventKind.BACKGROUND))
        )

        assertEquals(mapOf("example.video" to 400L), result)
    }

    @Test
    fun `不可用观察和时钟回退都会切断连续性`() {
        val tracker = AppSupervisionTransientSessionTracker()
        tracker.recordObservation(1_000L, "example.home", true)
        tracker.recordObservation(
            sampledAtEpochMillis = 1_500L,
            foregroundPackage = null,
            isInteractive = true,
            isAvailable = false,
            events = listOf(
                event("example.video", 1_100L, ForegroundEventKind.FOREGROUND),
                event("example.video", 1_400L, ForegroundEventKind.BACKGROUND)
            )
        )

        assertTrue(
            tracker.recordObservation(
                sampledAtEpochMillis = 2_000L,
                foregroundPackage = "example.home",
                isInteractive = true,
                events = listOf(event("example.video", 1_600L, ForegroundEventKind.BACKGROUND))
            ).isEmpty()
        )

        tracker.recordObservation(3_000L, "example.home", true)
        assertTrue(
            tracker.recordObservation(
                sampledAtEpochMillis = 2_500L,
                foregroundPackage = "example.home",
                isInteractive = true,
                events = listOf(
                    event("example.video", 2_100L, ForegroundEventKind.FOREGROUND),
                    event("example.video", 2_300L, ForegroundEventKind.BACKGROUND)
                )
            ).isEmpty()
        )
    }

    @Test
    fun `熄屏端点不结算事件时长`() {
        val tracker = AppSupervisionTransientSessionTracker()
        tracker.recordObservation(0L, "example.home", false)
        val result = tracker.recordObservation(
            sampledAtEpochMillis = 1_000L,
            foregroundPackage = "example.home",
            isInteractive = true,
            events = listOf(
                event("example.video", 100L, ForegroundEventKind.FOREGROUND),
                event("example.video", 900L, ForegroundEventKind.BACKGROUND)
            )
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `同包前台事件合并而后台后再次进入拆分会话`() {
        val tracker = AppSupervisionTransientSessionTracker()
        tracker.recordObservation(0L, "example.home", true)
        val result = tracker.recordObservation(
            sampledAtEpochMillis = 2_000L,
            foregroundPackage = "example.home",
            isInteractive = true,
            events = listOf(
                event("example.video", 100L, ForegroundEventKind.FOREGROUND),
                event("example.video", 200L, ForegroundEventKind.FOREGROUND),
                event("example.video", 400L, ForegroundEventKind.BACKGROUND),
                event("example.video", 500L, ForegroundEventKind.FOREGROUND),
                event("example.video", 700L, ForegroundEventKind.BACKGROUND)
            )
        )
        assertEquals(mapOf("example.video" to 500L), result)
    }

    private fun event(
        packageName: String,
        timestampMillis: Long,
        kind: ForegroundEventKind
    ) = ForegroundAppEvent(packageName, timestampMillis, kind)
}
