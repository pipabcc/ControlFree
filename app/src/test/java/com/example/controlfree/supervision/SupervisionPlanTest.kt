package com.example.controlfree.supervision

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionPlanTest {
    @Test
    fun `普通时间段按半开区间生效`() {
        val schedule = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0))
        )

        assertTrue(schedule.isActiveAt(instant("2026-07-13T09:00:00+08:00")))
        assertTrue(schedule.isActiveAt(instant("2026-07-13T11:59:59+08:00")))
        assertFalse(schedule.isActiveAt(instant("2026-07-13T12:00:00+08:00")))
    }

    @Test
    fun `跨午夜时间段归属于开始日`() {
        val schedule = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(22, 0, 7, 0))
        )

        assertTrue(schedule.isActiveAt(instant("2026-07-13T23:00:00+08:00")))
        assertTrue(schedule.isActiveAt(instant("2026-07-14T06:59:59+08:00")))
        assertFalse(schedule.isActiveAt(instant("2026-07-14T07:00:00+08:00")))
        assertFalse(schedule.isActiveAt(instant("2026-07-12T23:00:00+08:00")))
    }

    @Test
    fun `完整一天可以用零点到二十四点表达`() {
        val schedule = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(0, 24 * 60))
        )

        assertTrue(schedule.isActiveAt(instant("2026-07-13T00:00:00+08:00")))
        assertTrue(schedule.isActiveAt(instant("2026-07-13T23:59:59+08:00")))
        assertFalse(schedule.isActiveAt(instant("2026-07-14T00:00:00+08:00")))
    }

    @Test
    fun `午夜结束统一使用二十四点编码`() {
        val schedule = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(22 * 60, 24 * 60))
        )

        assertTrue(schedule.isActiveAt(instant("2026-07-13T23:59:59+08:00")))
        assertFalse(schedule.isActiveAt(instant("2026-07-14T00:00:00+08:00")))
        assertThrows(IllegalArgumentException::class.java) {
            DailyTimeRange(22 * 60, 0)
        }
    }

    @Test
    fun `下一个边界同时覆盖开始与结束`() {
        val schedule = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0))
        )

        assertEquals(
            instant("2026-07-13T09:00:00+08:00"),
            schedule.nextBoundaryAfter(instant("2026-07-13T08:00:00+08:00"))
        )
        assertEquals(
            instant("2026-07-13T12:00:00+08:00"),
            schedule.nextBoundaryAfter(instant("2026-07-13T09:00:00+08:00"))
        )
    }

    @Test
    fun `同一计划重叠区间会被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            schedule(
                days = setOf(DayOfWeek.MONDAY),
                ranges = listOf(range(9, 0, 12, 0), range(11, 30, 13, 0))
            )
        }
    }

    @Test
    fun `周日跨午夜与周一时间段重叠会被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            WeeklySchedule(
                zoneId = ZONE,
                activeDays = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
                ranges = listOf(range(23, 0, 1, 0), range(0, 30, 2, 0))
            )
        }
    }

    @Test
    fun `首尾相邻区间允许保存`() {
        val schedule = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0), range(12, 0, 13, 0))
        )

        assertTrue(schedule.isActiveAt(instant("2026-07-13T12:00:00+08:00")))
        assertEquals(
            instant("2026-07-13T13:00:00+08:00"),
            schedule.nextStateChangeAfter(instant("2026-07-13T09:00:00+08:00"))
        )
    }

    @Test
    fun `相同时区计划可以检测重叠与分离`() {
        val morning = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0))
        )
        val overlap = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(11, 0, 13, 0))
        )
        val afternoon = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(13, 0, 15, 0))
        )

        assertEquals(ScheduleOverlapComparison.OVERLAPS, morning.compareOverlap(overlap))
        assertEquals(ScheduleOverlapComparison.DISJOINT, morning.compareOverlap(afternoon))
    }

    @Test
    fun `不同时区返回明确不可比较结果`() {
        val shanghai = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0))
        )
        val london = WeeklySchedule(
            zoneId = ZoneId.of("Europe/London"),
            activeDays = shanghai.activeDays,
            ranges = shanghai.ranges,
            zoneMode = ScheduleZoneMode.FIXED
        )
        val fixedShanghai = WeeklySchedule(
            zoneId = ZONE,
            activeDays = shanghai.activeDays,
            ranges = shanghai.ranges,
            zoneMode = ScheduleZoneMode.FIXED
        )

        assertEquals(
            ScheduleOverlapComparison.DIFFERENT_TIME_ZONES,
            fixedShanghai.compareOverlap(london)
        )
    }

    @Test
    fun `跟随设备时区会使用调用方提供的当前时区`() {
        val schedule = WeeklySchedule(
            zoneId = ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0)),
            zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
        )

        assertTrue(
            schedule.isActiveAt(
                Instant.parse("2026-07-13T08:00:00Z"),
                ZoneId.of("Europe/London")
            )
        )
        assertFalse(schedule.isActiveAt(Instant.parse("2026-07-13T08:00:00Z")))
    }

    @Test
    fun `跟随设备计划可在同一设备时区比较`() {
        val shanghaiCreated = schedule(
            days = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(9, 0, 12, 0))
        )
        val londonCreated = WeeklySchedule(
            zoneId = ZoneId.of("Europe/London"),
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(range(11, 0, 13, 0))
        )

        assertEquals(
            ScheduleOverlapComparison.OVERLAPS,
            shanghaiCreated.compareOverlap(londonCreated, ZoneId.of("America/New_York"))
        )
    }

    @Test
    fun `夏令时缺口中的开始边界顺延到有效时间`() {
        val newYork = WeeklySchedule(
            zoneId = ZoneId.of("America/New_York"),
            activeDays = setOf(DayOfWeek.SUNDAY),
            ranges = listOf(range(2, 30, 4, 0))
        )

        assertFalse(newYork.isActiveAt(Instant.parse("2026-03-08T06:59:59Z")))
        assertTrue(newYork.isActiveAt(Instant.parse("2026-03-08T07:00:00Z")))
        assertEquals(
            Instant.parse("2026-03-08T08:00:00Z"),
            newYork.nextBoundaryAfter(Instant.parse("2026-03-08T07:00:00Z"))
        )
    }

    @Test
    fun `夏令时重复时间覆盖两次本地时段`() {
        val newYork = WeeklySchedule(
            zoneId = ZoneId.of("America/New_York"),
            activeDays = setOf(DayOfWeek.SUNDAY),
            ranges = listOf(range(1, 0, 1, 30))
        )

        assertTrue(newYork.isActiveAt(Instant.parse("2026-11-01T05:15:00Z")))
        assertTrue(newYork.isActiveAt(Instant.parse("2026-11-01T06:15:00Z")))
        assertFalse(newYork.isActiveAt(Instant.parse("2026-11-01T06:30:00Z")))
    }

    @Test
    fun `计划类型与策略不匹配时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            plan(
                id = "global",
                type = SupervisionPlanType.GLOBAL,
                policy = appPolicy("example.video")
            )
        }
    }

    @Test
    fun `只报告已启用全局计划的冲突`() {
        val first = plan(id = "first", range = range(9, 0, 12, 0))
        val second = plan(id = "second", range = range(11, 0, 13, 0))
        val disabled = plan(
            id = "disabled",
            enabled = false,
            range = range(10, 0, 11, 0)
        )
        val app = plan(
            id = "app",
            type = SupervisionPlanType.APP,
            range = range(10, 0, 11, 0),
            policy = appPolicy("example.video")
        )

        assertEquals(
            listOf(
                SupervisionPlanConflict(
                    firstPlanId = "first",
                    secondPlanId = "second",
                    reason = PlanConflictReason.OVERLAPPING_TIME_RANGE
                )
            ),
            SupervisionPlanConflictDetector.findEnabledGlobalConflicts(
                listOf(first, second, disabled, app)
            )
        )
    }

    @Test
    fun `全局监督与专注计划共用锁定通道并拒绝时间重叠`() {
        val global = plan(id = "global", range = range(8, 0, 12, 0))
        val focus = plan(
            id = "focus",
            type = SupervisionPlanType.FOCUS,
            range = range(11, 0, 13, 0),
            policy = FocusCyclePolicy(
                lockDuration = Duration.ofMinutes(15),
                playDuration = Duration.ofMinutes(3)
            )
        )

        assertEquals(
            listOf(
                SupervisionPlanConflict(
                    firstPlanId = "global",
                    secondPlanId = "focus",
                    reason = PlanConflictReason.OVERLAPPING_TIME_RANGE
                )
            ),
            SupervisionPlanConflictDetector.findEnabledDeviceLockConflicts(
                listOf(global, focus)
            )
        )
    }

    @Test
    fun `一次性专注时间窗按半开区间生效且不受周计划重复`() {
        val window = OneTimeFocusWindow(
            startEpochMillis = instant("2026-07-13T01:00:00+08:00").toEpochMilli(),
            endEpochMillis = instant("2026-07-13T02:00:00+08:00").toEpochMilli()
        )

        assertTrue(window.contains(window.startEpochMillis))
        assertTrue(window.contains(window.endEpochMillis - 1L))
        assertFalse(window.contains(window.endEpochMillis))
        assertFalse(
            window.overlaps(
                OneTimeFocusWindow(window.endEpochMillis, window.endEpochMillis + 60_000L)
            )
        )
    }

    @Test
    fun `一次性专注与同类周计划重叠时报告冲突`() {
        val window = OneTimeFocusWindow(
            startEpochMillis = instant("2026-07-13T10:00:00+08:00").toEpochMilli(),
            endEpochMillis = instant("2026-07-13T11:00:00+08:00").toEpochMilli()
        )
        val oneTime = plan(
            id = "once",
            type = SupervisionPlanType.FOCUS,
            policy = FocusCyclePolicy(Duration.ofMinutes(60), Duration.ofMinutes(1)),
            range = range(10, 0, 11, 0)
        ).copy(oneTimeFocusWindow = window)
        val weekly = plan(
            id = "weekly-focus",
            type = SupervisionPlanType.FOCUS,
            policy = FocusCyclePolicy(Duration.ofMinutes(30), Duration.ofMinutes(1)),
            range = range(9, 0, 12, 0)
        )

        val conflicts = SupervisionPlanConflictDetector.findEnabledDeviceLockConflicts(
            listOf(oneTime, weekly)
        )
        assertEquals(1, conflicts.size)
        assertEquals(PlanConflictReason.OVERLAPPING_TIME_RANGE, conflicts.single().reason)
    }

    @Test
    fun `一次性窗口从周计划结束边界开始仍检查窗口内后续时段`() {
        val window = OneTimeFocusWindow(
            startEpochMillis = instant("2026-07-13T10:00:00+08:00").toEpochMilli(),
            endEpochMillis = instant("2026-07-13T11:30:00+08:00").toEpochMilli()
        )
        val oneTime = plan(
            id = "once-after-boundary",
            type = SupervisionPlanType.FOCUS,
            policy = FocusCyclePolicy(Duration.ofMinutes(60), Duration.ofMinutes(1)),
            range = range(10, 0, 11, 30)
        ).copy(oneTimeFocusWindow = window)
        val weekly = plan(
            id = "weekly-with-gap",
            type = SupervisionPlanType.FOCUS,
            policy = FocusCyclePolicy(Duration.ofMinutes(30), Duration.ofMinutes(1)),
            range = range(9, 0, 10, 0)
        ).copy(
            schedule = schedule(
                days = setOf(DayOfWeek.MONDAY),
                ranges = listOf(range(9, 0, 10, 0), range(11, 0, 12, 0))
            )
        )

        val conflicts = SupervisionPlanConflictDetector.findEnabledDeviceLockConflicts(
            listOf(oneTime, weekly)
        )

        assertEquals(1, conflicts.size)
        assertEquals(PlanConflictReason.OVERLAPPING_TIME_RANGE, conflicts.single().reason)
    }

    @Test
    fun `非专注计划不能携带一次性时间窗`() {
        val window = OneTimeFocusWindow(1_000L, 2_000L)
        assertThrows(IllegalArgumentException::class.java) {
            plan(id = "global-once").copy(oneTimeFocusWindow = window)
        }
    }

    @Test
    fun `同一App的重叠计划会报告冲突`() {
        val first = plan(
            id = "video-morning",
            type = SupervisionPlanType.APP,
            range = range(9, 0, 12, 0),
            policy = appPolicy("example.video")
        )
        val second = plan(
            id = "video-noon",
            type = SupervisionPlanType.APP,
            range = range(11, 0, 13, 0),
            policy = appPolicy("example.video")
        )
        val otherApp = plan(
            id = "game",
            type = SupervisionPlanType.APP,
            range = range(11, 0, 13, 0),
            policy = appPolicy("example.game")
        )

        assertEquals(
            listOf(
                SupervisionPlanConflict(
                    "video-morning",
                    "video-noon",
                    PlanConflictReason.OVERLAPPING_APP_RULE
                )
            ),
            SupervisionPlanConflictDetector.findEnabledAppConflicts(
                listOf(first, second, otherApp)
            )
        )
    }

    @Test
    fun `计划集合防御性复制外部可变集合`() {
        val days = mutableSetOf(DayOfWeek.MONDAY)
        val ranges = mutableListOf(range(9, 0, 12, 0))
        val schedule = WeeklySchedule(ZONE, days, ranges)

        days.clear()
        ranges += range(11, 0, 13, 0)

        assertEquals(setOf(DayOfWeek.MONDAY), schedule.activeDays)
        assertEquals(listOf(range(9, 0, 12, 0)), schedule.ranges)
    }

    @Test
    fun `策略时长只接受分钟粒度且不超过一天`() {
        assertThrows(IllegalArgumentException::class.java) {
            GlobalCyclePolicy(Duration.ofSeconds(90), Duration.ofMinutes(5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            appPolicy("example.video").copy(usageAllowance = Duration.ofDays(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FocusCyclePolicy(Duration.ofMinutes(5), Duration.ofSeconds(90))
        }
    }

    private fun plan(
        id: String,
        type: SupervisionPlanType = SupervisionPlanType.GLOBAL,
        enabled: Boolean = true,
        range: DailyTimeRange = range(9, 0, 12, 0),
        policy: SupervisionPolicy = GlobalCyclePolicy(
            usageDuration = Duration.ofMinutes(30),
            lockDuration = Duration.ofMinutes(5)
        )
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = type,
        enabled = enabled,
        schedule = schedule(setOf(DayOfWeek.MONDAY), listOf(range)),
        policy = policy,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )

    private fun appPolicy(packageName: String) = AppRulePolicy(
        packageName = packageName,
        usageAllowance = Duration.ofMinutes(20),
        restDuration = Duration.ofMinutes(10)
    )

    private fun schedule(
        days: Set<DayOfWeek>,
        ranges: List<DailyTimeRange>
    ) = WeeklySchedule(ZONE, days, ranges)

    private fun range(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ) = DailyTimeRange(
        startMinute = startHour * 60 + startMinute,
        endMinuteExclusive = if (endHour == 24) 24 * 60 else endHour * 60 + endMinute
    )

    private fun instant(value: String): Instant = ZonedDateTime.parse(value).toInstant()

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
