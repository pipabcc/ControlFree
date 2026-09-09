package com.example.controlfree.supervision.runtime

import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.controlfree.supervision.app.AppSupervisionRuntimeSnapshot
import com.example.controlfree.supervision.app.AppSupervisionSnapshotReadResult
import com.example.controlfree.supervision.app.AppTriggerRule

class SupervisionScheduleCoordinatorPolicyTest {
    @Test
    fun `专注计划调度配置保持锁定优先语义`() {
        val configuration = SupervisionPlan(
            id = "focus-a",
            name = "上午专注",
            type = SupervisionPlanType.FOCUS,
            enabled = true,
            schedule = WeeklySchedule(
                zoneId = ZoneId.of("Asia/Shanghai"),
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(DailyTimeRange(8 * 60, 12 * 60))
            ),
            policy = FocusCyclePolicy(
                lockDuration = Duration.ofMinutes(15),
                playDuration = Duration.ofMinutes(3)
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L
        ).toScheduledCycleConfiguration()

        assertEquals(MonitorSessionMode.FOCUS, configuration.sessionMode)
        assertEquals(3, configuration.usageMinutes)
        assertEquals(15, configuration.lockMinutes)
    }

    @Test
    fun `全新自动启动必须检查运行条件`() {
        assertTrue(
            requiresRuntimePrerequisites(
                runtimeAction = ScheduledRuntimeAction.START_OR_REPLACE,
                monitorActive = false
            )
        )
    }

    @Test
    fun `运行中替换与崩溃恢复不重复阻断`() {
        assertFalse(
            requiresRuntimePrerequisites(
                runtimeAction = ScheduledRuntimeAction.START_OR_REPLACE,
                monitorActive = true
            )
        )
        assertFalse(
            requiresRuntimePrerequisites(
                runtimeAction = ScheduledRuntimeAction.RESTORE_SCHEDULED,
                monitorActive = true
            )
        )
    }

    @Test
    fun `保留停止和清理动作不检查启动条件`() {
        listOf(
            ScheduledRuntimeAction.KEEP,
            ScheduledRuntimeAction.STOP_SCHEDULED,
            ScheduledRuntimeAction.CLEAR_STALE_OWNER,
            ScheduledRuntimeAction.HOLD_CORRUPT_STATE
        ).forEach { action ->
            assertFalse(requiresRuntimePrerequisites(action, monitorActive = false))
        }
    }

    @Test
    fun `下一次协调选择未来最早预约并忽略已到期预约`() {
        val now = Instant.ofEpochMilli(10_000L)
        val plans = listOf(
            focusPlan("past", scheduledAt = 9_000L),
            focusPlan("later", scheduledAt = 30_000L),
            focusPlan("earlier", scheduledAt = 20_000L)
        )

        assertEquals(
            Instant.ofEpochMilli(20_000L),
            earliestScheduledEnableAt(plans, now)
        )
    }

    @Test
    fun `周期边界和预约时间取较早者`() {
        val boundary = Instant.ofEpochMilli(30_000L)
        val reservation = Instant.ofEpochMilli(20_000L)

        assertEquals(reservation, earliestInstant(boundary, reservation))
        assertEquals(boundary, earliestInstant(boundary, null))
        assertEquals(reservation, earliestInstant(null, reservation))
    }

    @Test
    fun `App规则运行决策区分应用更新停止与残留清理`() {
        val rule = ActiveAppSupervision(
            plan = appPlan(),
            activeUntil = java.time.Instant.ofEpochMilli(100_000L)
        ).toRuntimeRule()
        val stored = AppSupervisionSnapshotReadResult.Available(
            AppSupervisionRuntimeSnapshot(listOf(rule), emptyList(), 1L)
        )

        assertEquals(
            AppSupervisionRuntimeAction.APPLY,
            decideAppSupervisionRuntimeAction(listOf(rule), false, stored)
        )
        assertEquals(
            AppSupervisionRuntimeAction.KEEP,
            decideAppSupervisionRuntimeAction(listOf(rule), true, stored)
        )
        assertEquals(
            AppSupervisionRuntimeAction.STOP,
            decideAppSupervisionRuntimeAction(emptyList(), true, stored)
        )
        assertEquals(
            AppSupervisionRuntimeAction.CLEAR_STALE,
            decideAppSupervisionRuntimeAction(emptyList(), false, stored)
        )
    }

    @Test
    fun `App触发规则也会保持前台观察服务且规则变化时重新下发`() {
        val triggerRule = AppTriggerRule(
            planId = "global-trigger",
            planUpdatedAtEpochMillis = 7L,
            planName = "应用触发监督",
            packageNames = setOf("example.video", "example.music"),
            occurrenceEndEpochMillis = 100_000L
        )
        val stored = AppSupervisionSnapshotReadResult.Available(
            AppSupervisionRuntimeSnapshot(
                rules = emptyList(),
                states = emptyList(),
                savedAtEpochMillis = 1L,
                triggerRules = listOf(triggerRule)
            )
        )

        assertEquals(
            AppSupervisionRuntimeAction.APPLY,
            decideAppSupervisionRuntimeAction(
                activeRules = emptyList(),
                serviceRunning = false,
                storedRuntime = stored,
                triggerRules = listOf(triggerRule)
            )
        )
        assertEquals(
            AppSupervisionRuntimeAction.KEEP,
            decideAppSupervisionRuntimeAction(
                activeRules = emptyList(),
                serviceRunning = true,
                storedRuntime = stored,
                triggerRules = listOf(triggerRule)
            )
        )
        assertEquals(
            AppSupervisionRuntimeAction.APPLY,
            decideAppSupervisionRuntimeAction(
                activeRules = emptyList(),
                serviceRunning = true,
                storedRuntime = stored,
                triggerRules = listOf(triggerRule.copy(planUpdatedAtEpochMillis = 8L))
            )
        )
    }

    private fun appPlan() = com.example.controlfree.supervision.SupervisionPlan(
        id = "video",
        name = "视频监督",
        type = com.example.controlfree.supervision.SupervisionPlanType.APP,
        enabled = true,
        schedule = com.example.controlfree.supervision.WeeklySchedule(
            zoneId = java.time.ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(java.time.DayOfWeek.MONDAY),
            ranges = listOf(com.example.controlfree.supervision.DailyTimeRange(8 * 60, 12 * 60))
        ),
        policy = com.example.controlfree.supervision.AppRulePolicy(
            packageName = "example.video",
            usageAllowance = java.time.Duration.ofMinutes(30),
            restDuration = java.time.Duration.ofMinutes(5)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L
    )

    private fun focusPlan(id: String, scheduledAt: Long) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.FOCUS,
        enabled = false,
        schedule = WeeklySchedule(
            zoneId = ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(8 * 60, 12 * 60))
        ),
        policy = FocusCyclePolicy(
            lockDuration = Duration.ofMinutes(15),
            playDuration = Duration.ofMinutes(3)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        scheduledEnableAtEpochMillis = scheduledAt
    )
}
