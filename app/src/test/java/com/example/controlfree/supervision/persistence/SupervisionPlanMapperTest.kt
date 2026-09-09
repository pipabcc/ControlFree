package com.example.controlfree.supervision.persistence

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionPlanMapperTest {
    @Test
    fun `全局计划的多个触发App可完整往返`() {
        val original = SupervisionPlan(
            id = "global-trigger",
            name = "应用触发监督",
            type = SupervisionPlanType.GLOBAL,
            enabled = false,
            schedule = WeeklySchedule(
                zoneId = ZoneId.of("Asia/Shanghai"),
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(DailyTimeRange(8 * 60, 12 * 60))
            ),
            policy = GlobalCyclePolicy(Duration.ofMinutes(30), Duration.ofMinutes(5)),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            triggerAppPackageNames = linkedSetOf("example.video", "example.music")
        )
        val stored = SupervisionPlanMapper.toRecord(original)

        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = stored.globalPolicy,
                appPolicy = stored.appPolicy,
                ranges = stored.ranges,
                triggerApps = stored.triggerApps
            )
        )

        assertEquals(
            listOf("example.music", "example.video"),
            stored.triggerApps.map { it.packageName }
        )
        assertEquals(original, (result as PlanMappingResult.Success).plan)
    }

    @Test
    fun `触发App关联到其他计划时映射失败`() {
        val original = SupervisionPlan(
            id = "global-trigger",
            name = "应用触发监督",
            type = SupervisionPlanType.GLOBAL,
            enabled = false,
            schedule = WeeklySchedule(
                zoneId = ZoneId.of("Asia/Shanghai"),
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(DailyTimeRange(8 * 60, 12 * 60))
            ),
            policy = GlobalCyclePolicy(Duration.ofMinutes(30), Duration.ofMinutes(5)),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L
        )
        val stored = SupervisionPlanMapper.toRecord(original)

        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = stored.globalPolicy,
                appPolicy = null,
                ranges = stored.ranges,
                triggerApps = listOf(SupervisionPlanTriggerAppEntity("other", "example.video"))
            )
        )

        assertTrue(result is PlanMappingResult.Failure)
    }

    @Test
    fun `专注计划复用周期策略表并可完整往返`() {
        val original = SupervisionPlan(
            id = "focus-a",
            name = "上午专注",
            type = SupervisionPlanType.FOCUS,
            enabled = true,
            schedule = WeeklySchedule(
                zoneId = ZoneId.of("Asia/Shanghai"),
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(
                    DailyTimeRange(8 * 60, 10 * 60),
                    DailyTimeRange(14 * 60, 16 * 60)
                )
            ),
            policy = FocusCyclePolicy(
                lockDuration = Duration.ofMinutes(15),
                playDuration = Duration.ofMinutes(3)
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L
        )

        val stored = SupervisionPlanMapper.toRecord(original)
        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = stored.globalPolicy,
                appPolicy = stored.appPolicy,
                ranges = stored.ranges,
                disabledRanges = stored.disabledRanges
            )
        )

        assertEquals(original, (result as PlanMappingResult.Success).plan)
        assertEquals(3L, stored.globalPolicy?.usageDurationMinutes)
        assertEquals(15L, stored.globalPolicy?.lockDurationMinutes)
    }

    @Test
    fun `App计划可完整往返且保留时区模式`() {
        val original = appPlan()
        val stored = SupervisionPlanMapper.toRecord(original)

        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = stored.globalPolicy,
                appPolicy = stored.appPolicy,
                ranges = stored.ranges,
                disabledRanges = stored.disabledRanges
            )
        )

        assertEquals(original, (result as PlanMappingResult.Success).plan)
    }

    @Test
    fun `预约记录映射到领域计划但计划主体记录不承载预约`() {
        val original = appPlan().copy(
            enabled = false,
            scheduledEnableAtEpochMillis = 50_000L
        )
        val stored = SupervisionPlanMapper.toRecord(original)

        val withoutReservation = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = stored.globalPolicy,
                appPolicy = stored.appPolicy,
                ranges = stored.ranges,
                disabledRanges = stored.disabledRanges
            )
        ) as PlanMappingResult.Success
        val withReservation = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = stored.globalPolicy,
                appPolicy = stored.appPolicy,
                ranges = stored.ranges,
                activationReservation = PlanActivationReservationEntity(original.id, 50_000L),
                disabledRanges = stored.disabledRanges
            )
        ) as PlanMappingResult.Success

        assertEquals(null, withoutReservation.plan.scheduledEnableAtEpochMillis)
        assertEquals(original, withReservation.plan)
    }

    @Test
    fun `一次性专注时间窗可完整往返并校验预约开始时间`() {
        val original = SupervisionPlan(
            id = "focus-once",
            name = "一次性专注",
            type = SupervisionPlanType.FOCUS,
            enabled = false,
            schedule = WeeklySchedule(
                zoneId = ZoneId.of("Asia/Shanghai"),
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(DailyTimeRange(9 * 60, 10 * 60)),
                zoneMode = ScheduleZoneMode.FIXED
            ),
            policy = FocusCyclePolicy(
                lockDuration = Duration.ofMinutes(60),
                playDuration = Duration.ofMinutes(1)
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            scheduledEnableAtEpochMillis = 3_600_000L,
            oneTimeFocusWindow = OneTimeFocusWindow(3_600_000L, 7_200_000L)
        )
        val stored = SupervisionPlanMapper.toRecord(original)
        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                stored.plan,
                stored.globalPolicy,
                stored.appPolicy,
                stored.ranges,
                PlanActivationReservationEntity(original.id, 3_600_000L)
            )
        ) as PlanMappingResult.Success

        assertEquals(original, result.plan)
        assertEquals(3_600_000L, stored.plan.oneTimeStartEpochMillis)
        assertEquals(7_200_000L, stored.plan.oneTimeEndEpochMillis)
    }

    @Test
    fun `无效预约关联和负数时间均返回映射失败`() {
        val stored = SupervisionPlanMapper.toRecord(appPlan())

        val wrongPlan = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                stored.plan,
                stored.globalPolicy,
                stored.appPolicy,
                stored.ranges,
                PlanActivationReservationEntity("app-b", 1L)
            )
        )
        val negativeTime = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                stored.plan,
                stored.globalPolicy,
                stored.appPolicy,
                stored.ranges,
                PlanActivationReservationEntity(stored.plan.planId, -1L)
            )
        )

        assertTrue(wrongPlan is PlanMappingResult.Failure)
        assertTrue(negativeTime is PlanMappingResult.Failure)
    }

    @Test
    fun `无效星期掩码返回整条计划失败`() {
        val stored = SupervisionPlanMapper.toRecord(appPlan())

        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan.copy(activeDaysMask = 0b1000_0000),
                globalPolicy = null,
                appPolicy = stored.appPolicy,
                ranges = stored.ranges
            )
        )

        assertTrue(result is PlanMappingResult.Failure)
        assertEquals("app-a", (result as PlanMappingResult.Failure).planId)
    }

    @Test
    fun `无效时区和策略错配均返回明确失败`() {
        val stored = SupervisionPlanMapper.toRecord(appPlan())
        val invalidZone = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                stored.plan.copy(zoneId = "Invalid/Zone"),
                null,
                stored.appPolicy,
                stored.ranges
            )
        )
        val mismatchedPolicy = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(stored.plan, null, null, stored.ranges)
        )

        assertTrue(invalidZone is PlanMappingResult.Failure)
        assertTrue(mismatchedPolicy is PlanMappingResult.Failure)
    }

    @Test
    fun `策略关联到其他计划时拒绝整条记录`() {
        val stored = SupervisionPlanMapper.toRecord(appPlan())

        val result = SupervisionPlanMapper.fromRecord(
            SupervisionPlanWithRanges(
                plan = stored.plan,
                globalPolicy = null,
                appPolicy = stored.appPolicy?.copy(planId = "app-b"),
                ranges = stored.ranges
            )
        )

        assertTrue(result is PlanMappingResult.Failure)
    }

    private fun appPlan() = SupervisionPlan(
        id = "app-a",
        name = "视频监督",
        type = SupervisionPlanType.APP,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            ranges = listOf(DailyTimeRange(8 * 60, 12 * 60)),
            zoneMode = ScheduleZoneMode.FIXED
        ),
        policy = AppRulePolicy(
            packageName = "example.video",
            usageAllowance = Duration.ofMinutes(30),
            restDuration = Duration.ofMinutes(10),
            dailyUsageLimit = Duration.ofMinutes(120),
            disabledRanges = listOf(DailyTimeRange(22 * 60, 7 * 60))
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L
    )
}
