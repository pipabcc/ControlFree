package com.example.controlfree.ai

import android.content.Context
import com.example.controlfree.data.AppUsageSessionDetailCompleteness
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.data.UsageOverview
import com.example.controlfree.data.UsageStatsRepository
import com.example.controlfree.supervision.history.SupervisionHistoryAnalytics
import com.example.controlfree.supervision.history.SupervisionSessionRecord
import com.example.controlfree.supervision.persistence.SupervisionHistoryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface BehaviorUsageSource {
    suspend fun load(nowEpochMillis: Long, zoneId: ZoneId): UsageOverview
}

internal fun interface BehaviorHistorySource {
    suspend fun load(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        activeEndEpochMillis: Long
    ): List<SupervisionSessionRecord>
}

/**
 * 从 Android 用机统计与本机监督历史生成最小行为快照。
 *
 * 两个数据源彼此隔离：其中一个读取失败时仍返回另一部分；所有阻塞读取统一在
 * [ioDispatcher] 执行。测试可通过内部构造函数注入纯数据源、时钟与时区。
 */
internal class BehaviorSnapshotProvider internal constructor(
    private val usageSource: BehaviorUsageSource,
    private val historySource: BehaviorHistorySource,
    private val usageAccessChecker: () -> Boolean = { true },
    private val nowEpochMillis: () -> Long,
    private val zoneId: () -> ZoneId,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun load(): BehaviorSnapshot = withContext(ioDispatcher) {
        val safeNowEpochMillis = nowEpochMillis().coerceAtLeast(0L)
        val safeZoneId = zoneId()
        val today = Instant.ofEpochMilli(safeNowEpochMillis).atZone(safeZoneId).toLocalDate()
        val expectedDates = List(DAYS_PER_WINDOW) { index ->
            today.minusDays((DAYS_PER_WINDOW - 1L) - index)
        }

        val usageResult = if (hasUsageAccessSafely()) {
            captureSource { usageSource.load(safeNowEpochMillis, safeZoneId) }
        } else {
            SourceResult(value = null, isAvailable = false)
        }
        val startEpochMillis = expectedDates.first()
            .atStartOfDay(safeZoneId)
            .toInstant()
            .toEpochMilli()
            .coerceAtLeast(0L)
        val endExclusiveEpochMillis = saturatedIncrement(safeNowEpochMillis)
        val historyResult = captureSource {
            historySource.load(
                startEpochMillis = startEpochMillis,
                endExclusiveEpochMillis = endExclusiveEpochMillis,
                activeEndEpochMillis = safeNowEpochMillis
            )
        }

        val appUsage = usageResult.value?.let { overview ->
            normalizeAppUsage(overview, expectedDates, today)
        } ?: emptyAppUsage()
        val supervision = historyResult.value?.let { records ->
            normalizeSupervision(records, safeNowEpochMillis, safeZoneId)
        } ?: emptySupervision()

        BehaviorSnapshot(
            appUsage = appUsage.snapshot,
            supervision = supervision,
            quality = BehaviorSnapshotQuality(
                appUsageAvailable = usageResult.isAvailable,
                sevenDayUsageComplete = usageResult.isAvailable && appUsage.sevenDayComplete,
                todayLaunchCountComplete =
                    usageResult.isAvailable && appUsage.todayLaunchCountComplete,
                supervisionAvailable = historyResult.isAvailable
            )
        )
    }

    private fun hasUsageAccessSafely(): Boolean = try {
        usageAccessChecker()
    } catch (_: RuntimeException) {
        false
    }

    private fun normalizeAppUsage(
        overview: UsageOverview,
        expectedDates: List<LocalDate>,
        today: LocalDate
    ): NormalizedAppUsage {
        val dailyEntryCounts = overview.lastSevenDays.groupingBy { it.date }.eachCount()
        val dailyMillisByDate = overview.lastSevenDays.groupBy { it.date }.mapValues { (_, days) ->
            days.fold(0L) { total, day -> saturatedAdd(total, day.foregroundMillis) }
        }
        val sevenDayMinutes = expectedDates.map { date ->
            millisToMinutes(dailyMillisByDate[date] ?: 0L, MINUTES_PER_DAY)
        }
        val todayMillis = overview.todayTotalMillis.coerceIn(0L, MILLIS_PER_DAY)
        val todayMinutes = millisToMinutes(todayMillis, MINUTES_PER_DAY)
        var remainingTopAppMinutes = todayMinutes
        val topAppShares = overview.todayApps
            .asSequence()
            .map { app -> app.foregroundMillis.coerceAtLeast(0L) }
            .sortedDescending()
            .map { millis -> millisToMinutes(millis, MINUTES_PER_DAY) }
            .filter { minutes -> minutes > 0 }
            .take(TOP_APP_LIMIT)
            .mapNotNull { rawMinutes ->
                val durationMinutes = minOf(rawMinutes, remainingTopAppMinutes)
                if (durationMinutes <= 0) return@mapNotNull null
                remainingTopAppMinutes -= durationMinutes
                TopAppUsageShare(
                    durationMinutes = durationMinutes,
                    sharePercent = if (todayMinutes == 0) {
                        0
                    } else {
                        (durationMinutes.toLong() * 100L / todayMinutes).toInt().coerceIn(0, 100)
                    }
                )
            }
            .toList()

        var launchCount = 0L
        var launchCountComplete = true
        overview.todayApps.forEach { app ->
            val details = overview.appDetailsByPackage[app.packageName]
                ?.days
                ?.singleOrNull { day -> day.date == today }
            if (details == null) {
                launchCountComplete = false
            } else {
                launchCount = saturatedAdd(launchCount, details.launchCount.toLong())
                if (
                    details.sessionDetailCompleteness !=
                    AppUsageSessionDetailCompleteness.COMPLETE
                ) {
                    launchCountComplete = false
                }
            }
        }

        return NormalizedAppUsage(
            snapshot = AppUsageBehaviorSnapshot(
                todayMinutes = todayMinutes,
                sevenDayMinutesOldestFirst = sevenDayMinutes,
                sevenDayDailyAverageMinutes = sevenDayMinutes.sum() / DAYS_PER_WINDOW,
                topAppShares = topAppShares,
                todayLaunchCount = launchCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            ),
            sevenDayComplete = expectedDates.all { date -> dailyEntryCounts[date] == 1 } &&
                dailyEntryCounts.size == DAYS_PER_WINDOW,
            todayLaunchCountComplete = launchCountComplete
        )
    }

    private fun normalizeSupervision(
        records: List<SupervisionSessionRecord>,
        nowEpochMillis: Long,
        zoneId: ZoneId
    ): SupervisionBehaviorSnapshot {
        val overview = SupervisionHistoryAnalytics.aggregate(
            records = records,
            periodDays = DAYS_PER_WINDOW,
            now = Instant.ofEpochMilli(nowEpochMillis),
            zoneId = zoneId
        )
        val sessionCount = overview.sessionCount.coerceAtLeast(0)
        val completedCount = overview.completedCount.coerceIn(0, sessionCount)
        val endedEarlyCount = overview.cancelledCount.coerceIn(
            0,
            sessionCount - completedCount
        )
        val replacedCount = overview.replacedCount.coerceIn(
            0,
            sessionCount - completedCount - endedEarlyCount
        )
        return SupervisionBehaviorSnapshot(
            sevenDayCoverageMinutes = millisToMinutes(
                overview.totalCoveredMillis,
                MINUTES_PER_SEVEN_DAYS
            ),
            sessionCount = sessionCount,
            completedCount = completedCount,
            endedEarlyCount = endedEarlyCount,
            replacedCount = replacedCount
        )
    }

    private fun emptyAppUsage() = NormalizedAppUsage(
        snapshot = AppUsageBehaviorSnapshot(
            todayMinutes = 0,
            sevenDayMinutesOldestFirst = List(DAYS_PER_WINDOW) { 0 },
            sevenDayDailyAverageMinutes = 0,
            topAppShares = emptyList(),
            todayLaunchCount = 0
        ),
        sevenDayComplete = false,
        todayLaunchCountComplete = false
    )

    private fun emptySupervision() = SupervisionBehaviorSnapshot(
        sevenDayCoverageMinutes = 0,
        sessionCount = 0,
        completedCount = 0,
        endedEarlyCount = 0,
        replacedCount = 0
    )

    companion object {
        @Volatile
        private var instance: BehaviorSnapshotProvider? = null

        fun getInstance(context: Context): BehaviorSnapshotProvider =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): BehaviorSnapshotProvider {
            val usageRepository = UsageStatsRepository(context)
            val usageAccessManager = UsageAccessManager(context)
            val historyRepository = SupervisionHistoryRepository.getInstance(context)
            return BehaviorSnapshotProvider(
                usageSource = BehaviorUsageSource { now, zone ->
                    usageRepository.loadOverview(nowMillis = now, zoneId = zone)
                },
                historySource = BehaviorHistorySource { start, end, activeEnd ->
                    historyRepository.getOverlapping(start, end, activeEnd)
                },
                usageAccessChecker = usageAccessManager::hasUsageAccess,
                nowEpochMillis = System::currentTimeMillis,
                zoneId = ZoneId::systemDefault,
                ioDispatcher = Dispatchers.IO
            )
        }
    }
}

private data class SourceResult<T>(val value: T?, val isAvailable: Boolean)

private data class NormalizedAppUsage(
    val snapshot: AppUsageBehaviorSnapshot,
    val sevenDayComplete: Boolean,
    val todayLaunchCountComplete: Boolean
)

private suspend fun <T> captureSource(block: suspend () -> T): SourceResult<T> = try {
    SourceResult(block(), true)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    SourceResult(null, false)
}

private fun millisToMinutes(millis: Long, maximumMinutes: Int): Int =
    (millis.coerceAtLeast(0L) / MILLIS_PER_MINUTE)
        .coerceAtMost(maximumMinutes.toLong())
        .toInt()

private fun saturatedIncrement(value: Long): Long =
    if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

private fun saturatedAdd(left: Long, right: Long): Long {
    val safeLeft = left.coerceAtLeast(0L)
    val safeRight = right.coerceAtLeast(0L)
    return if (safeLeft > Long.MAX_VALUE - safeRight) Long.MAX_VALUE else safeLeft + safeRight
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_DAY = MINUTES_PER_DAY * MILLIS_PER_MINUTE
