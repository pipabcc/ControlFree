package com.example.controlfree.ai

import com.example.controlfree.data.AppUsageDayDetails
import com.example.controlfree.data.AppUsageDetails
import com.example.controlfree.data.AppUsageRecord
import com.example.controlfree.data.AppUsageSession
import com.example.controlfree.data.AppUsageSessionDetailCompleteness
import com.example.controlfree.data.DailyUsage
import com.example.controlfree.data.UsageOverview
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.supervision.history.SupervisionSessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorSnapshotProviderTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-07-19T04:00:00Z").toEpochMilli()
    private val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    private val expectedDates = List(DAYS_PER_WINDOW) { index ->
        today.minusDays((DAYS_PER_WINDOW - 1L) - index)
    }

    @Test
    fun `聚合用机和监督数据且快照不携带App身份`() = runTest {
        val provider = provider(
            usageOverview = completeUsageOverview(),
            historyRecords = historyRecords()
        )

        val snapshot = provider.load()

        assertEquals(220, snapshot.appUsage.todayMinutes)
        assertEquals(listOf(60, 70, 80, 90, 100, 110, 220), snapshot.appUsage.sevenDayMinutesOldestFirst)
        assertEquals(104, snapshot.appUsage.sevenDayDailyAverageMinutes)
        assertEquals(
            listOf(
                TopAppUsageShare(durationMinutes = 120, sharePercent = 54),
                TopAppUsageShare(durationMinutes = 60, sharePercent = 27),
                TopAppUsageShare(durationMinutes = 30, sharePercent = 13)
            ),
            snapshot.appUsage.topAppShares
        )
        assertEquals(7, snapshot.appUsage.todayLaunchCount)
        assertEquals(180, snapshot.supervision.sevenDayCoverageMinutes)
        assertEquals(3, snapshot.supervision.sessionCount)
        assertEquals(1, snapshot.supervision.completedCount)
        assertEquals(1, snapshot.supervision.endedEarlyCount)
        assertEquals(1, snapshot.supervision.replacedCount)
        assertEquals(
            BehaviorSnapshotQuality(
                appUsageAvailable = true,
                sevenDayUsageComplete = true,
                todayLaunchCountComplete = true,
                supervisionAvailable = true
            ),
            snapshot.quality
        )
        val serializedShape = snapshot.toString()
        assertFalse(serializedShape.contains("example.video"))
        assertFalse(serializedShape.contains("视频软件"))
    }

    @Test
    fun `用机数据失败时仍返回监督聚合并标记部分质量`() = runTest {
        val provider = provider(
            usageSource = BehaviorUsageSource { _, _ -> error("usage unavailable") },
            historyRecords = historyRecords()
        )

        val snapshot = provider.load()

        assertEquals(0, snapshot.appUsage.todayMinutes)
        assertEquals(List(DAYS_PER_WINDOW) { 0 }, snapshot.appUsage.sevenDayMinutesOldestFirst)
        assertEquals(3, snapshot.supervision.sessionCount)
        assertFalse(snapshot.quality.appUsageAvailable)
        assertFalse(snapshot.quality.sevenDayUsageComplete)
        assertFalse(snapshot.quality.todayLaunchCountComplete)
        assertTrue(snapshot.quality.supervisionAvailable)
    }

    @Test
    fun `监督历史失败时仍返回用机聚合并标记部分质量`() = runTest {
        val provider = provider(
            usageOverview = completeUsageOverview(),
            historySource = BehaviorHistorySource { _, _, _ -> error("history unavailable") }
        )

        val snapshot = provider.load()

        assertEquals(220, snapshot.appUsage.todayMinutes)
        assertEquals(0, snapshot.supervision.sessionCount)
        assertTrue(snapshot.quality.appUsageAvailable)
        assertFalse(snapshot.quality.supervisionAvailable)
    }

    @Test
    fun `异常用机边界被归一且缺失日期和启动明细被标记`() = runTest {
        val overview = UsageOverview(
            todayApps = listOf(
                AppUsageRecord(
                    packageName = "example.huge",
                    label = "不会出现在快照",
                    foregroundMillis = Long.MAX_VALUE,
                    lastTimeUsedMillis = Long.MAX_VALUE
                )
            ),
            lastSevenDays = listOf(
                DailyUsage(expectedDates.first(), -10L),
                DailyUsage(expectedDates.first(), Long.MAX_VALUE),
                DailyUsage(expectedDates.last(), Long.MAX_VALUE)
            ),
            appDetailsByPackage = emptyMap()
        )
        val provider = provider(usageOverview = overview, historyRecords = emptyList())

        val snapshot = provider.load()

        assertEquals(MINUTES_PER_DAY, snapshot.appUsage.todayMinutes)
        assertEquals(
            listOf(MINUTES_PER_DAY, 0, 0, 0, 0, 0, MINUTES_PER_DAY),
            snapshot.appUsage.sevenDayMinutesOldestFirst
        )
        assertEquals(
            listOf(TopAppUsageShare(MINUTES_PER_DAY, 100)),
            snapshot.appUsage.topAppShares
        )
        assertFalse(snapshot.quality.sevenDayUsageComplete)
        assertFalse(snapshot.quality.todayLaunchCountComplete)
    }

    @Test
    fun `调用方取消不会被降级为数据不可用`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = BehaviorSnapshotProvider(
            usageSource = BehaviorUsageSource { _, _ -> throw CancellationException("cancel") },
            historySource = BehaviorHistorySource { _, _, _ -> emptyList() },
            nowEpochMillis = { now },
            zoneId = { zoneId },
            ioDispatcher = dispatcher
        )

        var cancellationPropagated = false
        try {
            provider.load()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }

    @Test
    fun `两个数据源都在注入的IO调度器执行`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BehaviorSnapshot-IO-Test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val observedThreads = mutableListOf<String>()
        try {
            val provider = BehaviorSnapshotProvider(
                usageSource = BehaviorUsageSource { _, _ ->
                    observedThreads += Thread.currentThread().name
                    completeUsageOverview()
                },
                historySource = BehaviorHistorySource { _, _, _ ->
                    observedThreads += Thread.currentThread().name
                    emptyList()
                },
                nowEpochMillis = { now },
                zoneId = { zoneId },
                ioDispatcher = dispatcher
            )

            runBlocking { provider.load() }

            assertEquals(2, observedThreads.size)
            // kotlinx.coroutines 调试模式会在线程名后附加协程编号。
            assertTrue(observedThreads.all { it.startsWith("BehaviorSnapshot-IO-Test") })
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `使用情况权限被撤销时用机数据标记不可用且不读取统计源`() = runTest {
        var usageReads = 0
        val snapshot = provider(
            usageSource = BehaviorUsageSource { _, _ ->
                usageReads++
                completeUsageOverview()
            },
            historyRecords = emptyList(),
            usageAccessChecker = { false }
        ).load()

        assertEquals(0, usageReads)
        assertFalse(snapshot.quality.appUsageAvailable)
        assertFalse(snapshot.quality.sevenDayUsageComplete)
        assertFalse(snapshot.quality.todayLaunchCountComplete)
        assertTrue(snapshot.quality.supervisionAvailable)
        assertEquals(0, snapshot.appUsage.todayMinutes)
    }

    private fun provider(
        usageOverview: UsageOverview? = null,
        usageSource: BehaviorUsageSource = BehaviorUsageSource { _, _ ->
            requireNotNull(usageOverview)
        },
        historyRecords: List<SupervisionSessionRecord>? = null,
        historySource: BehaviorHistorySource = BehaviorHistorySource { _, _, _ ->
            requireNotNull(historyRecords)
        },
        usageAccessChecker: () -> Boolean = { true }
    ) = BehaviorSnapshotProvider(
        usageSource = usageSource,
        historySource = historySource,
        usageAccessChecker = usageAccessChecker,
        nowEpochMillis = { now },
        zoneId = { zoneId },
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun completeUsageOverview(): UsageOverview {
        val durations = listOf(120, 60, 30, 10)
        val launches = listOf(2, 1, 3, 1)
        val apps = durations.mapIndexed { index, minutes ->
            AppUsageRecord(
                packageName = "example.${if (index == 0) "video" else "app$index"}",
                label = if (index == 0) "视频软件" else "App $index",
                foregroundMillis = minutes * MINUTE_MILLIS,
                lastTimeUsedMillis = now
            )
        }
        val details = apps.mapIndexed { index, app ->
            app.packageName to AppUsageDetails(
                packageName = app.packageName,
                label = app.label,
                days = listOf(
                    AppUsageDayDetails(
                        date = today,
                        foregroundMillis = app.foregroundMillis,
                        sessions = List(launches[index]) { sessionIndex ->
                            val start = now - (sessionIndex + 1L) * 10L * MINUTE_MILLIS
                            AppUsageSession(start, start + MINUTE_MILLIS)
                        },
                        sessionDetailCompleteness = AppUsageSessionDetailCompleteness.COMPLETE
                    )
                )
            )
        }.toMap()
        return UsageOverview(
            todayApps = apps,
            lastSevenDays = expectedDates.zip(listOf(60, 70, 80, 90, 100, 110, 220))
                .map { (date, minutes) -> DailyUsage(date, minutes * MINUTE_MILLIS) },
            appDetailsByPackage = details
        )
    }

    private fun historyRecords(): List<SupervisionSessionRecord> {
        val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return listOf(
            historyRecord(
                id = "completed",
                start = dayStart,
                end = dayStart + 60L * MINUTE_MILLIS,
                reason = SupervisionSessionEndReason.COMPLETED
            ),
            historyRecord(
                id = "cancelled",
                start = dayStart + 60L * MINUTE_MILLIS,
                end = dayStart + 120L * MINUTE_MILLIS,
                reason = SupervisionSessionEndReason.CANCELLED
            ),
            historyRecord(
                id = "replaced",
                start = dayStart + 180L * MINUTE_MILLIS,
                end = dayStart + 240L * MINUTE_MILLIS,
                reason = SupervisionSessionEndReason.REPLACED
            )
        )
    }

    private fun historyRecord(
        id: String,
        start: Long,
        end: Long,
        reason: SupervisionSessionEndReason
    ) = SupervisionSessionRecord(
        sessionId = id,
        identityKey = "identity-$id",
        runtimeSlot = null,
        kind = SupervisionSessionKind.MANUAL_GLOBAL,
        displayName = "任务-$id",
        planId = null,
        packageName = null,
        usageMinutes = 30,
        lockMinutes = 5,
        startedAtEpochMillis = start,
        endedAtEpochMillis = end,
        endReason = reason,
        updatedAtEpochMillis = end
    )

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
