package com.example.controlfree.supervision.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SupervisionHistoryDay(
    val date: LocalDate,
    val coveredMillis: Long,
    val sessionsStarted: Int
)

data class SupervisionHistoryOverview(
    val periodDays: Int,
    val days: List<SupervisionHistoryDay>,
    val totalCoveredMillis: Long,
    val sessionCount: Int,
    val activeCount: Int,
    val completedCount: Int,
    val cancelledCount: Int,
    val replacedCount: Int,
    val legacyAmbiguousManualCount: Int,
    val countsByKind: Map<SupervisionSessionKind, Int>
)

object SupervisionHistoryAnalytics {
    fun aggregate(
        records: Collection<SupervisionSessionRecord>,
        periodDays: Int,
        now: Instant,
        zoneId: ZoneId
    ): SupervisionHistoryOverview {
        require(periodDays in 1..365) { "历史趋势天数无效" }
        val today = now.atZone(zoneId).toLocalDate()
        val firstDate = today.minusDays(periodDays.toLong() - 1L)
        val dates = List(periodDays) { index -> firstDate.plusDays(index.toLong()) }
        val rangeStart = firstDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val nowEpochMillis = now.toEpochMilli().coerceAtLeast(0L)
        val intervalsByDate = dates.associateWith { mutableListOf<TimeInterval>() }
        val relevant = records.filter { record ->
            val effectiveEnd = (record.endedAtEpochMillis ?: nowEpochMillis)
                .coerceAtMost(nowEpochMillis)
            record.startedAtEpochMillis < nowEpochMillis && effectiveEnd > rangeStart
        }

        relevant.forEach { record ->
            val sessionStart = record.startedAtEpochMillis.coerceAtLeast(rangeStart)
            val sessionEnd = (record.endedAtEpochMillis ?: nowEpochMillis)
                .coerceAtMost(nowEpochMillis)
            if (sessionEnd <= sessionStart) return@forEach
            dates.forEach { date ->
                val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1L).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val overlapStart = maxOf(sessionStart, dayStart)
                val overlapEnd = minOf(sessionEnd, dayEnd)
                if (overlapEnd > overlapStart) {
                    intervalsByDate.getValue(date) += TimeInterval(overlapStart, overlapEnd)
                }
            }
        }

        val days = dates.map { date ->
            SupervisionHistoryDay(
                date = date,
                coveredMillis = mergedDuration(intervalsByDate.getValue(date)),
                sessionsStarted = relevant.count { record ->
                    Instant.ofEpochMilli(record.startedAtEpochMillis)
                        .atZone(zoneId)
                        .toLocalDate() == date
                }
            )
        }
        return SupervisionHistoryOverview(
            periodDays = periodDays,
            days = days,
            totalCoveredMillis = days.sumOf(SupervisionHistoryDay::coveredMillis),
            sessionCount = relevant.size,
            activeCount = relevant.count(SupervisionSessionRecord::isActive),
            completedCount = relevant.count {
                it.endReason == SupervisionSessionEndReason.COMPLETED
            },
            cancelledCount = relevant.count {
                it.endReason == SupervisionSessionEndReason.CANCELLED
            },
            replacedCount = relevant.count {
                it.endReason == SupervisionSessionEndReason.REPLACED
            },
            legacyAmbiguousManualCount = relevant.count {
                it.isLegacyAmbiguousManual
            },
            countsByKind = SupervisionSessionKind.entries.associateWith { kind ->
                relevant.count { record -> record.kind == kind }
            }
        )
    }

    private fun mergedDuration(intervals: List<TimeInterval>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals.sortedWith(compareBy(TimeInterval::start, TimeInterval::end))
        var currentStart = sorted.first().start
        var currentEnd = sorted.first().end
        var total = 0L
        sorted.drop(1).forEach { interval ->
            if (interval.start <= currentEnd) {
                currentEnd = maxOf(currentEnd, interval.end)
            } else {
                total = saturatedAdd(total, currentEnd - currentStart)
                currentStart = interval.start
                currentEnd = interval.end
            }
        }
        return saturatedAdd(total, currentEnd - currentStart)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class TimeInterval(val start: Long, val end: Long) {
        init {
            require(start >= 0L && end > start)
        }
    }
}
