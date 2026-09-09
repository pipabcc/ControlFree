package com.example.controlfree.supervision.runtime

import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionScheduleEvaluatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `任务A只在八点到十二点成为活动任务`() {
        val taskA = plan("task-a", 8 * 60, 12 * 60)
        val waitingEvaluation = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(taskA),
            now = Instant.parse("2026-07-12T23:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready
        val activeEvaluation = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(taskA),
            now = Instant.parse("2026-07-13T01:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready
        val endedEvaluation = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(taskA),
            now = Instant.parse("2026-07-13T04:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        assertNull(waitingEvaluation.active)
        assertEquals(
            Instant.parse("2026-07-13T00:00:00Z"),
            waitingEvaluation.nextReconciliationAt
        )
        assertEquals("task-a", activeEvaluation.active?.plan?.id)
        assertEquals(
            Instant.parse("2026-07-13T04:00:00Z"),
            activeEvaluation.active?.activeUntil
        )
        assertNull(endedEvaluation.active)
    }

    @Test
    fun `全天全周连续计划无需虚构结束闹钟`() {
        val continuousPlan = plan("always", 0, 24 * 60).copy(
            schedule = WeeklySchedule(
                zoneId = zone,
                activeDays = DayOfWeek.entries.toSet(),
                ranges = listOf(DailyTimeRange(0, 24 * 60))
            )
        )

        val evaluation = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(continuousPlan),
            now = Instant.parse("2026-07-13T01:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        assertEquals("always", evaluation.active?.plan?.id)
        assertEquals(Long.MAX_VALUE, evaluation.active?.activeUntil?.toEpochMilli())
        assertNull(evaluation.nextReconciliationAt)

        val suppressed = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(continuousPlan),
            now = Instant.parse("2026-07-13T01:00:00Z"),
            deviceZoneId = zone,
            suppression = ScheduledOccurrenceSuppression(
                planId = continuousPlan.id,
                planUpdatedAtEpochMillis = continuousPlan.updatedAtEpochMillis,
                suppressUntilEpochMillis = Long.MAX_VALUE
            )
        ) as SupervisionScheduleEvaluation.Ready

        assertNull(suppressed.active)
        assertNull(suppressed.nextReconciliationAt)
    }

    @Test
    fun `一次性专注只在绝对时间窗内活动并在结束时给出边界`() {
        val start = Instant.parse("2026-07-13T01:00:00Z")
        val end = Instant.parse("2026-07-13T02:00:00Z")
        val once = focusPlan("once", 1, 2).copy(
            oneTimeFocusWindow = OneTimeFocusWindow(
                startEpochMillis = start.toEpochMilli(),
                endEpochMillis = end.toEpochMilli()
            )
        )

        val before = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(once),
            now = start.minusSeconds(1),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready
        val active = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(once),
            now = start,
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready
        val after = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(once),
            now = end,
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        assertEquals(start, before.nextReconciliationAt)
        assertEquals("once", active.active?.plan?.id)
        assertEquals(end, active.active?.activeUntil)
        assertEquals(end, active.nextReconciliationAt)
        assertTrue(after.active == null)
    }

    @Test
    fun `手动结束后只跳过当前计划版本的本次时段`() {
        val taskA = plan("task-a", 8 * 60, 12 * 60, version = 10L)
        val now = Instant.parse("2026-07-13T01:00:00Z")
        val suppression = ScheduledOccurrenceSuppression(
            planId = "task-a",
            planUpdatedAtEpochMillis = 10L,
            suppressUntilEpochMillis = Instant.parse("2026-07-13T04:00:00Z").toEpochMilli()
        )

        val skipped = SupervisionScheduleEvaluator.evaluate(
            listOf(taskA),
            now,
            zone,
            suppression
        ) as SupervisionScheduleEvaluation.Ready
        val edited = SupervisionScheduleEvaluator.evaluate(
            listOf(taskA.copy(updatedAtEpochMillis = 11L)),
            now,
            zone,
            suppression
        ) as SupervisionScheduleEvaluation.Ready

        assertNull(skipped.active)
        assertEquals("task-a", edited.active?.plan?.id)
    }

    @Test
    fun `防御性拒绝同时活动的多个全局任务`() {
        val result = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(
                plan("task-a", 8 * 60, 12 * 60),
                plan("task-b", 10 * 60, 13 * 60)
            ),
            now = Instant.parse("2026-07-13T03:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        )

        assertTrue(result is SupervisionScheduleEvaluation.ConflictingActivePlans)
        assertEquals(
            setOf("task-a", "task-b"),
            (result as SupervisionScheduleEvaluation.ConflictingActivePlans).planIds
        )
    }

    @Test
    fun `不同App任务可以同时活动并由最早边界统一唤醒`() {
        val video = appPlan("video", "example.video", 8 * 60, 12 * 60)
        val game = appPlan("game", "example.game", 9 * 60, 11 * 60)

        val result = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(video, game),
            now = Instant.parse("2026-07-13T02:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        assertEquals(setOf("video", "game"), result.activeApps.map { it.plan.id }.toSet())
        assertEquals(Instant.parse("2026-07-13T03:00:00Z"), result.nextReconciliationAt)
    }

    @Test
    fun `同一App的活动规则冲突会被运行时再次拒绝`() {
        val result = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(
                appPlan("video-a", "example.video", 8 * 60, 12 * 60),
                appPlan("video-b", "example.video", 9 * 60, 11 * 60)
            ),
            now = Instant.parse("2026-07-13T02:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        )

        assertTrue(result is SupervisionScheduleEvaluation.ConflictingActivePlans)
        assertEquals(
            setOf("video-a", "video-b"),
            (result as SupervisionScheduleEvaluation.ConflictingActivePlans).planIds
        )
    }

    @Test
    fun `禁用时段在执行时段外仍生成规则并拥有最高优先边界`() {
        val base = appPlan("video", "example.video", 8 * 60, 12 * 60)
        val plan = base.copy(
            policy = (base.policy as AppRulePolicy).copy(
                disabledRanges = listOf(DailyTimeRange(20 * 60, 22 * 60)),
                dailyUsageLimit = Duration.ofMinutes(120)
            )
        )
        val result = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(plan),
            now = Instant.parse("2026-07-13T13:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        val active = result.activeApps.single()
        assertEquals(false, active.isExecutionWindowActive)
        assertEquals(Instant.parse("2026-07-13T14:00:00Z"), active.disabledUntil)
        assertEquals(Instant.parse("2026-07-13T14:00:00Z"), active.activeUntil)
    }

    @Test
    fun `跨午夜执行时段在零点只重置每日累计而不截断单次周期`() {
        val plan = appPlan("video", "example.video", 22 * 60, 7 * 60)
        val result = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(plan),
            now = Instant.parse("2026-07-13T15:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        val active = result.activeApps.single()
        assertEquals(Instant.parse("2026-07-13T23:00:00Z"), active.cycleActiveUntil)
        assertEquals(Instant.parse("2026-07-13T16:00:00Z"), active.dailyUsageResetAt)
        assertEquals(Instant.parse("2026-07-13T23:00:00Z"), active.activeUntil)
        assertEquals(Instant.parse("2026-07-13T16:00:00Z"), result.nextReconciliationAt)
    }

    @Test
    fun `App触发只下发时段内停用的全局任务并保留多个目标App`() {
        val eligible = plan("trigger-a", 8 * 60, 12 * 60, version = 7L).copy(
            enabled = false,
            triggerAppPackageNames = setOf("example.video", "example.music")
        )
        val enabled = eligible.copy(id = "enabled", enabled = true)
        val outside = eligible.copy(id = "outside", schedule = WeeklySchedule(
            zoneId = zone,
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(13 * 60, 14 * 60))
        ))
        val appPlan = appPlan("app", "example.game", 8 * 60, 12 * 60)

        val result = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(eligible, enabled, outside, appPlan),
            now = Instant.parse("2026-07-13T01:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        val trigger = result.activeAppTriggers.single()
        assertEquals("trigger-a", trigger.plan.id)
        assertEquals(setOf("example.video", "example.music"), trigger.plan.triggerAppPackageNames)
        assertEquals(Instant.parse("2026-07-13T04:00:00Z"), trigger.activeUntil)
        assertEquals(Instant.parse("2026-07-13T04:00:00Z"), result.nextReconciliationAt)
    }

    @Test
    fun `运行决策不会用定时任务覆盖手动监督`() {
        val active = ActiveDeviceSupervision(
            plan = plan("task-a", 8 * 60, 12 * 60),
            activeUntil = Instant.parse("2026-07-13T04:00:00Z")
        )

        assertEquals(
            ScheduledRuntimeAction.KEEP,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = true,
                serviceRunning = true,
                ownerResult = ScheduledOwnerReadResult.None,
                active = active
            )
        )
        assertEquals(
            ScheduledRuntimeAction.KEEP,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = false,
                serviceRunning = true,
                ownerResult = ScheduledOwnerReadResult.None,
                active = active
            )
        )
    }

    @Test
    fun `计划在定时启动落盘前关闭会立即停止排队服务`() {
        val active = ActiveDeviceSupervision(
            plan = plan("task-a", 8 * 60, 12 * 60),
            activeUntil = Instant.parse("2026-07-13T04:00:00Z")
        )

        assertEquals(
            ScheduledRuntimeAction.STOP_SCHEDULED,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = false,
                serviceRunning = true,
                ownerResult = ScheduledOwnerReadResult.None,
                active = null,
                scheduledRuntimeOwned = true
            )
        )
        assertEquals(
            ScheduledRuntimeAction.KEEP,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = false,
                serviceRunning = true,
                ownerResult = ScheduledOwnerReadResult.None,
                active = active,
                scheduledRuntimeOwned = true
            )
        )
        assertEquals(
            ScheduledRuntimeAction.KEEP,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = false,
                serviceRunning = true,
                ownerResult = ScheduledOwnerReadResult.None,
                active = null,
                scheduledRuntimeOwned = false
            )
        )
    }

    @Test
    fun `定时运行在边界变化时替换并在时段结束时停止`() {
        val active = ActiveDeviceSupervision(
            plan = plan("task-a", 8 * 60, 12 * 60, version = 10L),
            activeUntil = Instant.parse("2026-07-13T04:00:00Z")
        )
        val matchingOwner = ScheduledMonitorOwner(
            "task-a",
            10L,
            active.activeUntil.toEpochMilli()
        )

        assertEquals(
            ScheduledRuntimeAction.KEEP,
            ScheduledRuntimeDecisionPolicy.decide(
                true,
                true,
                ScheduledOwnerReadResult.Available(matchingOwner),
                active
            )
        )
        assertEquals(
            ScheduledRuntimeAction.START_OR_REPLACE,
            ScheduledRuntimeDecisionPolicy.decide(
                true,
                true,
                ScheduledOwnerReadResult.Available(matchingOwner.copy(activeUntilEpochMillis = 1L)),
                active
            )
        )
        assertEquals(
            ScheduledRuntimeAction.STOP_SCHEDULED,
            ScheduledRuntimeDecisionPolicy.decide(
                true,
                true,
                ScheduledOwnerReadResult.Available(matchingOwner),
                active = null
            )
        )
        assertEquals(
            ScheduledRuntimeAction.RESTORE_SCHEDULED,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = true,
                serviceRunning = false,
                ownerResult = ScheduledOwnerReadResult.Available(matchingOwner),
                active = active
            )
        )
    }

    @Test
    fun `残留来源会被清理而活动监督的损坏来源保持默认保护`() {
        val owner = ScheduledMonitorOwner("task-a", 10L, 20L)

        assertEquals(
            ScheduledRuntimeAction.CLEAR_STALE_OWNER,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = false,
                serviceRunning = false,
                ownerResult = ScheduledOwnerReadResult.Available(owner),
                active = null
            )
        )
        assertEquals(
            ScheduledRuntimeAction.HOLD_CORRUPT_STATE,
            ScheduledRuntimeDecisionPolicy.decide(
                monitorActive = true,
                serviceRunning = false,
                ownerResult = ScheduledOwnerReadResult.Corrupted,
                active = null
            )
        )
    }

    @Test
    fun `专注计划在时间范围内占用唯一设备锁定通道`() {
        val focus = focusPlan("focus-a", 8 * 60, 12 * 60)
        val active = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(focus),
            now = Instant.parse("2026-07-13T01:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        ) as SupervisionScheduleEvaluation.Ready

        assertEquals("focus-a", active.active?.plan?.id)

        val conflict = SupervisionScheduleEvaluator.evaluate(
            plans = listOf(focus, plan("global", 10 * 60, 13 * 60)),
            now = Instant.parse("2026-07-13T03:00:00Z"),
            deviceZoneId = zone,
            suppression = null
        )
        assertEquals(
            setOf("focus-a", "global"),
            (conflict as SupervisionScheduleEvaluation.ConflictingActivePlans).planIds
        )
    }

    private fun plan(
        id: String,
        startMinute: Int,
        endMinute: Int,
        version: Long = 1L
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.GLOBAL,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = zone,
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(startMinute, endMinute))
        ),
        policy = GlobalCyclePolicy(Duration.ofMinutes(30), Duration.ofMinutes(5)),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = version
    )

    private fun appPlan(
        id: String,
        packageName: String,
        startMinute: Int,
        endMinute: Int
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.APP,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = zone,
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(startMinute, endMinute))
        ),
        policy = AppRulePolicy(
            packageName = packageName,
            usageAllowance = Duration.ofMinutes(30),
            restDuration = Duration.ofMinutes(5)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )

    private fun focusPlan(
        id: String,
        startMinute: Int,
        endMinute: Int
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.FOCUS,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = zone,
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(startMinute, endMinute))
        ),
        policy = FocusCyclePolicy(
            lockDuration = Duration.ofMinutes(15),
            playDuration = Duration.ofMinutes(3)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L
    )
}
