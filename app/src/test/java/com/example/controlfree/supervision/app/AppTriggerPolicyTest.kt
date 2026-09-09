package com.example.controlfree.supervision.app

import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTriggerPolicyTest {
    @Test
    fun `首次观察目标App只建立基线且退出再进入才触发`() {
        val rule = rule("plan-a", "example.video")

        val baseline = AppTriggerEdgePolicy.onObservation(
            previous = AppTriggerEdgeState(),
            rules = listOf(rule),
            observation = observation("example.video", 1L)
        )

        assertNull(baseline.triggeredRule)
        assertEquals("example.video", baseline.state.foregroundPackage)
        assertTrue("plan-a" !in baseline.state.armedPlanIds)

        val stillForeground = AppTriggerEdgePolicy.onObservation(
            previous = baseline.state,
            rules = listOf(rule),
            observation = observation("example.video", 2L)
        )
        assertNull(stillForeground.triggeredRule)

        val left = AppTriggerEdgePolicy.onObservation(
            previous = stillForeground.state,
            rules = listOf(rule),
            observation = observation("example.home", 3L)
        )
        assertNull(left.triggeredRule)
        assertTrue("plan-a" in left.state.armedPlanIds)

        val reentered = AppTriggerEdgePolicy.onObservation(
            previous = left.state,
            rules = listOf(rule),
            observation = observation("example.video", 4L)
        )
        assertEquals(rule, reentered.triggeredRule)
        assertEquals("example.video", reentered.triggerPackageName)
        assertTrue("plan-a" !in reentered.state.armedPlanIds)
    }

    @Test
    fun `同一任务的多个App均可触发但连续重复观察不会重复触发`() {
        val rule = rule("plan-a", "example.video", "example.music")
        val baseline = AppTriggerEdgePolicy.onObservation(
            previous = AppTriggerEdgeState(),
            rules = listOf(rule),
            observation = observation("example.home", 1L)
        )

        val video = AppTriggerEdgePolicy.onObservation(
            previous = baseline.state,
            rules = listOf(rule),
            observation = observation("example.video", 2L)
        )
        assertEquals(rule, video.triggeredRule)

        val duplicate = AppTriggerEdgePolicy.onObservation(
            previous = video.state,
            rules = listOf(rule),
            observation = observation(
                packageName = "example.video",
                timestampMillis = 2L,
                events = listOf(event("example.video", 2L, ForegroundEventKind.FOREGROUND))
            )
        )
        assertNull(duplicate.triggeredRule)

        val switchedTarget = AppTriggerEdgePolicy.onObservation(
            previous = duplicate.state,
            rules = listOf(rule),
            observation = observation("example.music", 3L)
        )
        assertEquals(rule, switchedTarget.triggeredRule)
        assertEquals("example.music", switchedTarget.triggerPackageName)
    }

    @Test
    fun `单次进入同一App只触发一个任务且排序稳定`() {
        val later = rule("plan-b", "example.video")
        val earlier = rule("plan-a", "example.video")
        val baseline = AppTriggerEdgePolicy.onObservation(
            previous = AppTriggerEdgeState(),
            rules = listOf(later, earlier),
            observation = observation("example.home", 1L)
        )

        val entered = AppTriggerEdgePolicy.onObservation(
            previous = baseline.state,
            rules = listOf(later, earlier),
            observation = observation("example.video", 2L)
        )

        assertEquals("plan-a", entered.triggeredRule?.planId)
        assertTrue(entered.state.armedPlanIds.isEmpty())
    }

    @Test
    fun `不可用观察不会建立基线或触发`() {
        val rule = rule("plan-a", "example.video")

        val decision = AppTriggerEdgePolicy.onObservation(
            previous = AppTriggerEdgeState(),
            rules = listOf(rule),
            observation = ForegroundObservation.unavailable(
                ForegroundObservationStatus.ACCESS_DENIED
            )
        )

        assertEquals(AppTriggerEdgeState(), decision.state)
        assertNull(decision.triggeredRule)
    }

    private fun rule(id: String, vararg packageNames: String) = AppTriggerRule(
        planId = id,
        planUpdatedAtEpochMillis = 7L,
        planName = "计划-$id",
        packageNames = packageNames.toSet(),
        occurrenceEndEpochMillis = 100_000L
    )

    private fun observation(
        packageName: String?,
        timestampMillis: Long,
        events: List<ForegroundAppEvent> = emptyList()
    ) = ForegroundObservation(
        packageName = packageName,
        transitionTimestampMillis = timestampMillis,
        newEvents = events,
        status = ForegroundObservationStatus.AVAILABLE,
        observedAtEpochMillis = timestampMillis
    )

    private fun event(
        packageName: String,
        timestampMillis: Long,
        kind: ForegroundEventKind
    ) = ForegroundAppEvent(packageName, timestampMillis, kind)
}
