package com.example.controlfree.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsRepositoryTest {
    @Test
    fun `同秒链式合并经协调层后只计一次进入前台`() {
        val date = LocalDate.of(2026, 1, 1)
        val aggregation = UsageTimelineAggregator.aggregate(
            events = listOf(
                timelineEvent(BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
                timelineEvent(BASE + 1_100L, UsageTimelineEventType.ACTIVITY_PAUSED),
                timelineEvent(BASE + 1_700L, UsageTimelineEventType.ACTIVITY_RESUMED),
                timelineEvent(BASE + 2_100L, UsageTimelineEventType.ACTIVITY_PAUSED),
                timelineEvent(BASE + 2_600L, UsageTimelineEventType.ACTIVITY_RESUMED),
                timelineEvent(BASE + 3_000L, UsageTimelineEventType.ACTIVITY_PAUSED)
            ),
            dayRanges = listOf(UsageDayRange(date, BASE, BASE + HOUR))
        )
        val eventSlice = requireNotNull(aggregation.packages[APP_A]?.days?.get(date))
        val reconciled = AppUsageDayReconciler.reconcile(
            eventSlice = eventSlice,
            summary = PackageDayUsageSummary(
                foregroundMillis = eventSlice.foregroundMillis,
                lastTimeUsedMillis = eventSlice.lastTimeUsedMillis
            )
        )
        val details = AppUsageDayDetails(
            date = date,
            foregroundMillis = reconciled.foregroundMillis,
            sessions = reconciled.sessions,
            sessionDetailCompleteness = reconciled.sessionDetailCompleteness
        )

        assertEquals(1, details.launchCount)
        assertEquals(1, details.sessions.size)
        assertEquals(3_000L, details.foregroundMillis)
        assertEquals(
            details.foregroundMillis,
            details.sessions.sumOf(AppUsageSession::durationMillis)
        )
        assertEquals(
            AppUsageSessionDetailCompleteness.COMPLETE,
            details.sessionDetailCompleteness
        )
    }

    @Test
    fun `系统汇总显著大于逐次事件时保留会话并标记明细不完整`() {
        val eventSlice = eventSlice(
            UsageSessionSlice(BASE, BASE + 10 * MINUTE),
            UsageSessionSlice(BASE + 20 * MINUTE, BASE + 30 * MINUTE)
        )

        val result = AppUsageDayReconciler.reconcile(
            eventSlice = eventSlice,
            summary = PackageDayUsageSummary(
                foregroundMillis = 45 * MINUTE,
                lastTimeUsedMillis = BASE + 50 * MINUTE
            )
        )

        assertEquals(45 * MINUTE, result.foregroundMillis)
        assertEquals(BASE + 50 * MINUTE, result.lastTimeUsedMillis)
        assertEquals(AppUsageSessionDetailCompleteness.PARTIAL, result.sessionDetailCompleteness)
        assertEquals(20 * MINUTE, result.sessions.sumOf(AppUsageSession::durationMillis))
        assertEquals(2, result.sessions.size)
    }

    @Test
    fun `只有系统汇总时不伪造会话并标记明细不完整`() {
        val result = AppUsageDayReconciler.reconcile(
            eventSlice = null,
            summary = PackageDayUsageSummary(
                foregroundMillis = 12 * MINUTE,
                lastTimeUsedMillis = BASE + 12 * MINUTE
            )
        )

        assertEquals(12 * MINUTE, result.foregroundMillis)
        assertTrue(result.sessions.isEmpty())
        assertEquals(AppUsageSessionDetailCompleteness.PARTIAL, result.sessionDetailCompleteness)
    }

    @Test
    fun `事件比系统汇总更完整时采用事件时长`() {
        val eventSlice = eventSlice(UsageSessionSlice(BASE, BASE + 20 * MINUTE))

        val result = AppUsageDayReconciler.reconcile(
            eventSlice = eventSlice,
            summary = PackageDayUsageSummary(
                foregroundMillis = 15 * MINUTE,
                lastTimeUsedMillis = BASE + 15 * MINUTE
            )
        )

        assertEquals(20 * MINUTE, result.foregroundMillis)
        assertEquals(AppUsageSessionDetailCompleteness.COMPLETE, result.sessionDetailCompleteness)
        assertEquals(result.foregroundMillis, result.sessions.sumOf(AppUsageSession::durationMillis))
    }

    @Test
    fun `小幅汇总误差不误报明细缺失`() {
        val eventSlice = eventSlice(UsageSessionSlice(BASE, BASE + HOUR))

        val result = AppUsageDayReconciler.reconcile(
            eventSlice = eventSlice,
            summary = PackageDayUsageSummary(
                foregroundMillis = HOUR + 20_000L,
                lastTimeUsedMillis = BASE + HOUR + 20_000L
            )
        )

        assertEquals(HOUR + 20_000L, result.foregroundMillis)
        assertEquals(AppUsageSessionDetailCompleteness.COMPLETE, result.sessionDetailCompleteness)
    }

    @Test
    fun `无汇总且无事件时返回真正的空记录`() {
        val result = AppUsageDayReconciler.reconcile(eventSlice = null, summary = null)

        assertEquals(0L, result.foregroundMillis)
        assertEquals(0L, result.lastTimeUsedMillis)
        assertTrue(result.sessions.isEmpty())
        assertEquals(AppUsageSessionDetailCompleteness.COMPLETE, result.sessionDetailCompleteness)
    }

    private fun eventSlice(vararg sessions: UsageSessionSlice): UsageDaySlice = UsageDaySlice(
        foregroundMillis = sessions.sumOf(UsageSessionSlice::durationMillis),
        lastTimeUsedMillis = sessions.maxOfOrNull(UsageSessionSlice::endMillis) ?: 0L,
        sessions = sessions.toList()
    )

    private fun timelineEvent(
        timestampMillis: Long,
        type: UsageTimelineEventType
    ) = UsageTimelineEvent(APP_A, "Main", timestampMillis, type)

    private companion object {
        const val APP_A = "example.a"
        const val BASE = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
    }
}
