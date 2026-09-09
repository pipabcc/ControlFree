package com.example.controlfree.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

data class AppUsageRecord(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
    val lastTimeUsedMillis: Long
)

data class DailyUsage(
    val date: LocalDate,
    val foregroundMillis: Long
)

data class AppUsageSession(
    val startMillis: Long,
    val endMillis: Long
) {
    val durationMillis: Long
        get() = (endMillis - startMillis).coerceAtLeast(0L)
}

enum class AppUsageSessionDetailCompleteness {
    COMPLETE,
    PARTIAL
}

data class AppUsageDayDetails(
    val date: LocalDate,
    val foregroundMillis: Long,
    val sessions: List<AppUsageSession>,
    val sessionDetailCompleteness: AppUsageSessionDetailCompleteness
) {
    val launchCount: Int
        get() = sessions.size
}

data class AppUsageDetails(
    val packageName: String,
    val label: String,
    val days: List<AppUsageDayDetails>
)

data class UsageOverview(
    val todayApps: List<AppUsageRecord>,
    val lastSevenDays: List<DailyUsage>,
    val appDetailsByPackage: Map<String, AppUsageDetails> = emptyMap()
) {
    val todayTotalMillis: Long = todayApps.sumOf(AppUsageRecord::foregroundMillis)
}

internal data class PackageDayUsageSummary(
    val foregroundMillis: Long,
    val lastTimeUsedMillis: Long
)

internal data class UsageSummaryBucket(
    val packageName: String,
    val firstTimeStampMillis: Long,
    val lastTimeStampMillis: Long,
    val foregroundMillis: Long,
    val lastTimeUsedMillis: Long
)

/**
 * 系统日桶与本机自然日可能错位（厂商统计日、重启拆分、跨天桶），按区间相交统计会让同一个桶
 * 在相邻两天各计一次全额。这里把每个桶按时间中点归属到唯一的一天，并把单包单日时长钳制在
 * 当日实际长度内，保证不会出现“一天 29 小时”。
 */
internal object DailyUsageSummaryAttributor {
    fun attribute(
        buckets: List<UsageSummaryBucket>,
        days: List<UsageDayRange>
    ): Map<LocalDate, Map<String, PackageDayUsageSummary>> {
        val result = days.associateTo(linkedMapOf()) {
            it.date to linkedMapOf<String, PackageDayUsageSummary>()
        }
        if (days.isEmpty()) return result
        val windowStartMillis = days.first().startMillis
        val windowEndMillis = days.last().endMillis
        buckets.forEach { bucket ->
            val foregroundMillis = bucket.foregroundMillis
            if (foregroundMillis <= 0L) return@forEach
            val firstMillis = bucket.firstTimeStampMillis
            val lastMillis = maxOf(bucket.lastTimeStampMillis, firstMillis)
            val midpointMillis = (firstMillis + (lastMillis - firstMillis) / 2L)
                .coerceAtMost((windowEndMillis - 1L).coerceAtLeast(windowStartMillis))
            val day = days.firstOrNull {
                midpointMillis >= it.startMillis && midpointMillis < it.endMillis
            } ?: return@forEach
            val summaries = result.getValue(day.date)
            val previous = summaries[bucket.packageName]
            summaries[bucket.packageName] = PackageDayUsageSummary(
                foregroundMillis = ((previous?.foregroundMillis ?: 0L) + foregroundMillis)
                    .coerceAtMost(day.durationMillis),
                lastTimeUsedMillis = maxOf(
                    previous?.lastTimeUsedMillis ?: 0L,
                    bucket.lastTimeUsedMillis.coerceAtLeast(0L)
                )
            )
        }
        return result
    }
}

internal data class ReconciledAppUsageDay(
    val foregroundMillis: Long,
    val lastTimeUsedMillis: Long,
    val sessions: List<AppUsageSession>,
    val sessionDetailCompleteness: AppUsageSessionDetailCompleteness
)

/**
 * 将逐次事件与系统日汇总合并。逐次会话始终保持原样，汇总只负责补齐总时长和最后使用时间。
 */
internal object AppUsageDayReconciler {
    private const val ABSOLUTE_DETAIL_GAP_TOLERANCE_MILLIS = 5_000L
    private const val RELATIVE_DETAIL_GAP_DIVISOR = 100L

    fun reconcile(
        eventSlice: UsageDaySlice?,
        summary: PackageDayUsageSummary?
    ): ReconciledAppUsageDay {
        val sessions = eventSlice?.sessions.orEmpty().map { session ->
            AppUsageSession(
                startMillis = session.startMillis,
                endMillis = session.endMillis
            )
        }
        val sessionMillis = sessions.sumOf(AppUsageSession::durationMillis)
        val summaryMillis = summary?.foregroundMillis?.coerceAtLeast(0L) ?: 0L
        val effectiveMillis = maxOf(sessionMillis, summaryMillis)
        val detailGapMillis = (summaryMillis - sessionMillis).coerceAtLeast(0L)
        val detailGapToleranceMillis = maxOf(
            ABSOLUTE_DETAIL_GAP_TOLERANCE_MILLIS,
            summaryMillis / RELATIVE_DETAIL_GAP_DIVISOR
        )
        val isSummaryOnly = summaryMillis > 0L && sessions.isEmpty()
        val isSignificantDetailGap = detailGapMillis > detailGapToleranceMillis

        return ReconciledAppUsageDay(
            foregroundMillis = effectiveMillis,
            lastTimeUsedMillis = maxOf(
                eventSlice?.lastTimeUsedMillis ?: 0L,
                summary?.lastTimeUsedMillis ?: 0L
            ),
            sessions = sessions,
            sessionDetailCompleteness = if (isSummaryOnly || isSignificantDetailGap) {
                AppUsageSessionDetailCompleteness.PARTIAL
            } else {
                AppUsageSessionDetailCompleteness.COMPLETE
            }
        )
    }
}

class UsageStatsRepository(private val context: Context) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager

    fun loadOverview(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): UsageOverview {
        val dateWindow = UsageDateWindowFactory.create(nowMillis, zoneId)
        val aggregation = UsageTimelineAggregator.aggregate(
            events = queryTimelineEvents(
                startMillis = dateWindow.queryStartMillis,
                endMillis = dateWindow.queryEndMillis
            ),
            dayRanges = dateWindow.days
        )
        val summariesByDate = queryDailyUsageSummaries(dateWindow)
        val allPackageNames = linkedSetOf<String>().apply {
            addAll(aggregation.packages.keys)
            summariesByDate.values.forEach { summaries -> addAll(summaries.keys) }
        }
        val reconciledUsage = allPackageNames.associateWith { packageName ->
            dateWindow.days.associate { day ->
                day.date to AppUsageDayReconciler.reconcile(
                    eventSlice = aggregation.packages[packageName]?.days?.get(day.date),
                    summary = summariesByDate[day.date]?.get(packageName)
                )
            }
        }
        // 所有包都先参与独占前台状态机；只在生成展示结果时过滤不可启动包。
        val launchablePackages = allPackageNames.filterTo(linkedSetOf()) { packageName ->
            packageManager.getLaunchIntentForPackage(packageName) != null
        }
        val today = dateWindow.days.last().date
        val todayApps = launchablePackages.mapNotNull { packageName ->
            val todayUsage = reconciledUsage[packageName]?.get(today)
                ?.takeIf { it.foregroundMillis > 0L }
                ?: return@mapNotNull null
            AppUsageRecord(
                packageName = packageName,
                label = getApplicationLabel(packageName),
                foregroundMillis = todayUsage.foregroundMillis,
                lastTimeUsedMillis = todayUsage.lastTimeUsedMillis
            )
        }.sortedWith(
            compareByDescending<AppUsageRecord> { it.foregroundMillis }
                .thenBy { it.label }
                .thenBy { it.packageName }
        )
        val daily = dateWindow.days.map { day ->
            DailyUsage(
                date = day.date,
                // 独占前台意味着各 App 用时之和不可能超过当日实际长度；钳制兜底厂商统计误差。
                foregroundMillis = launchablePackages.sumOf { packageName ->
                    reconciledUsage[packageName]
                        ?.get(day.date)
                        ?.foregroundMillis
                        ?: 0L
                }.coerceAtMost(day.durationMillis)
            )
        }
        val appDetailsByPackage = launchablePackages.associate { packageName ->
            val label = getApplicationLabel(packageName)
            packageName to AppUsageDetails(
                packageName = packageName,
                label = label,
                days = dateWindow.days.map { day ->
                    val reconciledDay = requireNotNull(
                        reconciledUsage[packageName]?.get(day.date)
                    )
                    AppUsageDayDetails(
                        date = day.date,
                        foregroundMillis = reconciledDay.foregroundMillis,
                        sessions = reconciledDay.sessions,
                        sessionDetailCompleteness = reconciledDay.sessionDetailCompleteness
                    )
                }
            )
        }
        return UsageOverview(todayApps, daily, appDetailsByPackage)
    }

    private fun queryDailyUsageSummaries(
        dateWindow: UsageDateWindow
    ): Map<LocalDate, Map<String, PackageDayUsageSummary>> {
        // 只对整个窗口查询一次：按天分段查询会让跨天日桶在相邻两天各计一次全额。
        val dailyStats = try {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                dateWindow.queryStartMillis,
                dateWindow.queryEndMillis
            ).orEmpty()
        } catch (_: RuntimeException) {
            // 部分 OEM 会在统计服务短暂不可用时抛异常；保留事件时间轴作为安全降级。
            emptyList()
        }
        val buckets = dailyStats.mapNotNull { usageStats ->
            val packageName = usageStats.packageName?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            UsageSummaryBucket(
                packageName = packageName,
                firstTimeStampMillis = usageStats.firstTimeStamp,
                lastTimeStampMillis = usageStats.lastTimeStamp,
                foregroundMillis = usageStats.totalTimeInForeground.coerceAtLeast(0L),
                lastTimeUsedMillis = usageStats.lastTimeUsed.coerceAtLeast(0L)
            )
        }
        return DailyUsageSummaryAttributor.attribute(buckets, dateWindow.days)
    }

    private fun queryTimelineEvents(
        startMillis: Long,
        endMillis: Long
    ): List<UsageTimelineEvent> {
        if (endMillis <= startMillis) return emptyList()
        val usageEvents = usageStatsManager.queryEvents(startMillis, endMillis)
            ?: return emptyList()
        val androidEvent = UsageEvents.Event()
        val timelineEvents = ArrayList<UsageTimelineEvent>()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(androidEvent)
            val type = UsageTimelineEventType.fromAndroidEventType(androidEvent.eventType)
                ?: continue
            timelineEvents += UsageTimelineEvent(
                packageName = androidEvent.packageName,
                className = androidEvent.className,
                timestampMillis = androidEvent.timeStamp,
                type = type
            )
        }
        return timelineEvents
    }

    private fun getApplicationLabel(packageName: String): String =
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
}
