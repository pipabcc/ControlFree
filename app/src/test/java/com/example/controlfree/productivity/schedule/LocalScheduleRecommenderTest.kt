package com.example.controlfree.productivity.schedule

import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScheduleRecommenderTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `推荐不会与忙碌区间重叠且按半小时对齐`() {
        val now = ZonedDateTime.of(2026, 7, 22, 14, 10, 0, 0, zone)
        val busyStart = ZonedDateTime.of(2026, 7, 22, 14, 30, 0, 0, zone)
        val candidates = LocalScheduleRecommender.recommend(
            nowEpochMillis = now.toInstant().toEpochMilli(),
            deadlineEpochMillis = now.plusDays(1).toInstant().toEpochMilli(),
            durationMinutes = 45,
            focusHistory = emptyList(),
            busyIntervals = listOf(
                BusyInterval(busyStart.toInstant().toEpochMilli(), busyStart.plusHours(1).toInstant().toEpochMilli())
            ),
            zoneId = zone
        )

        assertTrue(candidates.isNotEmpty())
        val first = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(candidates.first().startEpochMillis), zone)
        assertEquals(0, first.minute % 30)
        assertTrue(candidates.none { it.startEpochMillis < busyStart.plusHours(1).toInstant().toEpochMilli() && it.endEpochMillis > busyStart.toInstant().toEpochMilli() })
    }

    @Test
    fun `历史高效时段获得更高评分`() {
        val now = ZonedDateTime.of(2026, 7, 20, 8, 0, 0, 0, zone)
        val historic = (1L..4L).map { weeksAgo ->
            val start = now.minusWeeks(weeksAgo).withHour(15)
            FocusHistorySample(
                startedAtEpochMillis = start.toInstant().toEpochMilli(),
                endedAtEpochMillis = start.plusMinutes(60).toInstant().toEpochMilli(),
                completed = true
            )
        }
        val candidates = LocalScheduleRecommender.recommend(
            nowEpochMillis = now.toInstant().toEpochMilli(),
            deadlineEpochMillis = now.plusDays(1).toInstant().toEpochMilli(),
            durationMinutes = 30,
            focusHistory = historic,
            busyIntervals = emptyList(),
            zoneId = zone
        )

        assertEquals(15, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(candidates.first().startEpochMillis), zone).hour)
    }

    @Test
    fun `计划日习惯达标时间可以独立形成推荐信号并说明原因`() {
        val now = ZonedDateTime.of(2026, 7, 20, 8, 0, 0, 0, zone)
        val habitHistory = (1L..4L).map { weeksAgo ->
            HabitHistorySample(
                checkedInAtEpochMillis = now.minusWeeks(weeksAgo).withHour(16).toInstant().toEpochMilli(),
                targetReached = true,
                wasScheduled = true
            )
        }

        val candidates = LocalScheduleRecommender.recommend(
            nowEpochMillis = now.toInstant().toEpochMilli(),
            deadlineEpochMillis = now.plusDays(1).toInstant().toEpochMilli(),
            durationMinutes = 30,
            focusHistory = emptyList(),
            habitHistory = habitHistory,
            busyIntervals = emptyList(),
            zoneId = zone
        )

        val first = candidates.first()
        assertEquals(16, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(first.startEpochMillis), zone).hour)
        assertTrue(first.reason.contains("计划日"))
        assertTrue(first.reason.contains("打卡"))
    }

    @Test
    fun `专注与习惯共同命中时原因明确包含两类证据`() {
        val now = ZonedDateTime.of(2026, 7, 20, 8, 0, 0, 0, zone)
        val historyStart = now.minusWeeks(1).withHour(15)
        val candidates = LocalScheduleRecommender.recommend(
            nowEpochMillis = now.toInstant().toEpochMilli(),
            deadlineEpochMillis = now.plusDays(1).toInstant().toEpochMilli(),
            durationMinutes = 30,
            focusHistory = listOf(
                FocusHistorySample(
                    startedAtEpochMillis = historyStart.toInstant().toEpochMilli(),
                    endedAtEpochMillis = historyStart.plusMinutes(60).toInstant().toEpochMilli(),
                    completed = true
                )
            ),
            habitHistory = listOf(
                HabitHistorySample(
                    checkedInAtEpochMillis = historyStart.toInstant().toEpochMilli(),
                    targetReached = true,
                    wasScheduled = true
                )
            ),
            busyIntervals = emptyList(),
            zoneId = zone
        )

        assertTrue(candidates.first().reason.contains("专注"))
        assertTrue(candidates.first().reason.contains("习惯"))
    }

    @Test
    fun `远期截止只在未来七天内寻找可执行时段`() {
        val now = ZonedDateTime.of(2026, 7, 20, 8, 0, 0, 0, zone)
        val candidates = LocalScheduleRecommender.recommend(
            nowEpochMillis = now.toInstant().toEpochMilli(),
            deadlineEpochMillis = now.plusDays(30).toInstant().toEpochMilli(),
            durationMinutes = 30,
            focusHistory = emptyList(),
            busyIntervals = listOf(
                BusyInterval(
                    now.toInstant().toEpochMilli(),
                    now.plusDays(8).toInstant().toEpochMilli()
                )
            ),
            zoneId = zone
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `已启用周期监督的跨午夜时段会被展开`() {
        val queryStart = ZonedDateTime.of(2026, 7, 21, 21, 0, 0, 0, zone)
        val expectedStart = queryStart.withHour(22)
        val expectedEnd = queryStart.plusDays(1).withHour(2)
        val plan = globalPlan(
            enabled = true,
            activeDays = setOf(DayOfWeek.TUESDAY),
            range = DailyTimeRange(22 * 60, 2 * 60)
        )

        val intervals = SupervisionBusyIntervalFactory.create(
            plans = listOf(plan),
            startEpochMillis = queryStart.toInstant().toEpochMilli(),
            endExclusiveEpochMillis = queryStart.plusDays(1).withHour(3).toInstant().toEpochMilli(),
            deviceZoneId = zone
        )

        assertEquals(
            listOf(
                BusyInterval(
                    expectedStart.toInstant().toEpochMilli(),
                    expectedEnd.toInstant().toEpochMilli()
                )
            ),
            intervals
        )
    }

    @Test
    fun `预约周期监督从生效时刻开始展开而不是从查询起点开始`() {
        val queryStart = ZonedDateTime.of(2026, 7, 20, 13, 0, 0, 0, zone)
        val activation = queryStart.withHour(15)
        val plan = globalPlan(
            enabled = false,
            activeDays = setOf(DayOfWeek.MONDAY),
            range = DailyTimeRange(14 * 60, 16 * 60)
        ).copy(scheduledEnableAtEpochMillis = activation.toInstant().toEpochMilli())

        assertTrue(
            SupervisionBusyIntervalFactory.create(
                plans = listOf(plan),
                startEpochMillis = queryStart.toInstant().toEpochMilli(),
                endExclusiveEpochMillis = queryStart.withHour(14).withMinute(30)
                    .toInstant().toEpochMilli(),
                deviceZoneId = zone
            ).isEmpty()
        )
        assertEquals(
            listOf(
                BusyInterval(
                    activation.toInstant().toEpochMilli(),
                    queryStart.withHour(16).toInstant().toEpochMilli()
                )
            ),
            SupervisionBusyIntervalFactory.create(
                plans = listOf(plan),
                startEpochMillis = queryStart.toInstant().toEpochMilli(),
                endExclusiveEpochMillis = queryStart.withHour(17).toInstant().toEpochMilli(),
                deviceZoneId = zone
            )
        )
    }

    @Test
    fun `已预约的一次性专注会过滤候选时段`() {
        val now = ZonedDateTime.of(2026, 7, 22, 14, 0, 0, 0, zone)
        val windowStart = now.plusHours(1)
        val windowEnd = windowStart.plusHours(1)
        val window = OneTimeFocusWindow(
            windowStart.toInstant().toEpochMilli(),
            windowEnd.toInstant().toEpochMilli()
        )
        val plan = focusPlan(
            enabled = false,
            scheduledEnableAtEpochMillis = window.startEpochMillis,
            window = window
        )

        val candidates = LocalScheduleRecommender.recommend(
            nowEpochMillis = now.toInstant().toEpochMilli(),
            deadlineEpochMillis = now.plusDays(1).toInstant().toEpochMilli(),
            durationMinutes = 30,
            focusHistory = emptyList(),
            busyIntervals = SupervisionBusyIntervalFactory.create(
                plans = listOf(plan),
                startEpochMillis = now.toInstant().toEpochMilli(),
                endExclusiveEpochMillis = now.plusDays(1).toInstant().toEpochMilli(),
                deviceZoneId = zone
            ),
            zoneId = zone
        )

        assertTrue(candidates.none { candidate ->
            candidate.startEpochMillis < window.endEpochMillis &&
                candidate.endEpochMillis > window.startEpochMillis
        })
    }

    @Test
    fun `停用未预约计划和 App 独立监督不占用全局锁定通道`() {
        val start = ZonedDateTime.of(2026, 7, 22, 14, 0, 0, 0, zone)
        val disabledGlobal = globalPlan(
            enabled = false,
            activeDays = setOf(start.dayOfWeek),
            range = DailyTimeRange(14 * 60, 16 * 60)
        )
        val appPlan = SupervisionPlan(
            id = "app-plan",
            name = "App 监督",
            type = SupervisionPlanType.APP,
            enabled = true,
            schedule = weeklySchedule(setOf(start.dayOfWeek), DailyTimeRange(14 * 60, 16 * 60)),
            policy = AppRulePolicy(
                packageName = "com.example.video",
                usageAllowance = Duration.ofMinutes(10),
                restDuration = Duration.ofMinutes(20)
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )

        assertTrue(
            SupervisionBusyIntervalFactory.create(
                plans = listOf(disabledGlobal, appPlan),
                startEpochMillis = start.toInstant().toEpochMilli(),
                endExclusiveEpochMillis = start.plusHours(3).toInstant().toEpochMilli(),
                deviceZoneId = zone
            ).isEmpty()
        )
    }

    private fun globalPlan(
        enabled: Boolean,
        activeDays: Set<DayOfWeek>,
        range: DailyTimeRange
    ) = SupervisionPlan(
        id = "global-plan",
        name = "全局监督",
        type = SupervisionPlanType.GLOBAL,
        enabled = enabled,
        schedule = weeklySchedule(activeDays, range),
        policy = GlobalCyclePolicy(Duration.ofMinutes(20), Duration.ofMinutes(10)),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )

    private fun focusPlan(
        enabled: Boolean,
        scheduledEnableAtEpochMillis: Long?,
        window: OneTimeFocusWindow
    ) = SupervisionPlan(
        id = "focus-plan",
        name = "一次性专注",
        type = SupervisionPlanType.FOCUS,
        enabled = enabled,
        schedule = weeklySchedule(
            setOf(DayOfWeek.WEDNESDAY),
            DailyTimeRange(15 * 60, 16 * 60)
        ),
        policy = FocusCyclePolicy(Duration.ofMinutes(60), Duration.ofMinutes(1)),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis,
        oneTimeFocusWindow = window
    )

    private fun weeklySchedule(
        activeDays: Set<DayOfWeek>,
        range: DailyTimeRange
    ) = WeeklySchedule(
        zoneId = zone,
        activeDays = activeDays,
        ranges = listOf(range),
        zoneMode = ScheduleZoneMode.FIXED
    )
}
