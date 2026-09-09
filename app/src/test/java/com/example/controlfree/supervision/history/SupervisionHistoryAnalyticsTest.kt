package com.example.controlfree.supervision.history

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SupervisionHistoryAnalyticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `重叠的全局和App会话按覆盖时间合并而不重复累计`() {
        val now = Instant.parse("2026-07-16T04:00:00Z")
        val first = record(
            id = "global",
            kind = SupervisionSessionKind.MANUAL_GLOBAL,
            start = Instant.parse("2026-07-16T00:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-07-16T02:00:00Z").toEpochMilli()
        )
        val overlapping = record(
            id = "app",
            kind = SupervisionSessionKind.APP,
            start = Instant.parse("2026-07-16T01:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-07-16T03:00:00Z").toEpochMilli()
        )

        val overview = SupervisionHistoryAnalytics.aggregate(
            listOf(first, overlapping),
            periodDays = 7,
            now = now,
            zoneId = zone
        )

        assertEquals(3L * 60L * 60_000L, overview.totalCoveredMillis)
        assertEquals(2, overview.sessionCount)
        assertEquals(2, overview.completedCount)
        assertEquals(0, overview.replacedCount)
        assertEquals(2, overview.days.last().sessionsStarted)
    }

    @Test
    fun `跨自然日会话按本地日边界拆分且活动会话截断到当前时间`() {
        // 上海本地时间为次日 02:00，验证午夜前 30 分钟与午夜后 2 小时分别归档。
        val now = Instant.parse("2026-07-15T18:00:00Z")
        val active = record(
            id = "active",
            kind = SupervisionSessionKind.MANUAL_GLOBAL,
            start = Instant.parse("2026-07-15T15:30:00Z").toEpochMilli(),
            end = null
        )

        val overview = SupervisionHistoryAnalytics.aggregate(
            listOf(active),
            periodDays = 2,
            now = now,
            zoneId = zone
        )

        assertEquals(30L * 60_000L, overview.days.first().coveredMillis)
        assertEquals(2L * 60L * 60_000L, overview.days.last().coveredMillis)
        assertEquals(1, overview.activeCount)
    }

    @Test
    fun `旧版即时记录单独计数避免误归类为监督`() {
        val now = Instant.parse("2026-07-16T04:00:00Z")
        val legacy = record(
            id = "legacy",
            kind = SupervisionSessionKind.MANUAL_GLOBAL,
            start = Instant.parse("2026-07-16T00:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-07-16T01:00:00Z").toEpochMilli(),
            displayName = LEGACY_AMBIGUOUS_MANUAL_DISPLAY_NAME
        )

        val overview = SupervisionHistoryAnalytics.aggregate(
            listOf(legacy),
            periodDays = 7,
            now = now,
            zoneId = zone
        )

        assertEquals(1, overview.legacyAmbiguousManualCount)
        assertEquals(1, overview.countsByKind[SupervisionSessionKind.MANUAL_GLOBAL])
    }

    private fun record(
        id: String,
        kind: SupervisionSessionKind,
        start: Long,
        end: Long?,
        displayName: String = id
    ): SupervisionSessionRecord {
        val isApp = kind == SupervisionSessionKind.APP
        val planId = if (isApp) "plan-$id" else null
        return SupervisionSessionRecord(
            sessionId = id,
            identityKey = "identity-$id",
            runtimeSlot = if (end == null) {
                if (isApp) appRuntimeSlot(requireNotNull(planId)) else GLOBAL_RUNTIME_SLOT
            } else {
                null
            },
            kind = kind,
            displayName = displayName,
            planId = planId,
            packageName = if (isApp) "example.$id" else null,
            usageMinutes = 30,
            lockMinutes = 5,
            startedAtEpochMillis = start,
            endedAtEpochMillis = end,
            endReason = end?.let { SupervisionSessionEndReason.COMPLETED },
            updatedAtEpochMillis = end ?: start
        )
    }
}
