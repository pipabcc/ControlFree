package com.example.controlfree

import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.supervision.runtime.ActiveDeviceSupervision
import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledStartAuthorizationTest {
    private val activeUntil = Instant.parse("2026-07-17T12:00:00Z")

    @Test
    fun `只有与数据库活动计划完全一致的启动命令才获准`() {
        val plan = plan()
        val active = ActiveDeviceSupervision(plan, activeUntil)
        val request = request(plan)

        assertTrue(scheduledStartMatchesActive(request, active))
        assertFalse(scheduledStartMatchesActive(request, null))
        assertFalse(scheduledStartMatchesActive(request, active.copy(plan = plan.copy(enabled = false))))
    }

    @Test
    fun `旧版本或旧参数的排队命令会被拒绝`() {
        val plan = plan()
        val active = ActiveDeviceSupervision(plan, activeUntil)
        val request = request(plan)

        assertFalse(
            scheduledStartMatchesActive(
                request.copy(
                    owner = request.owner.copy(planUpdatedAtEpochMillis = 6L)
                ),
                active
            )
        )
        assertFalse(
            scheduledStartMatchesActive(request.copy(lockMinutes = 6), active)
        )
    }

    @Test
    fun `恢复任务名称时必须同时匹配计划编号和版本`() {
        val owner = ScheduledMonitorOwner(
            planId = "global-a",
            planUpdatedAtEpochMillis = 7L,
            activeUntilEpochMillis = activeUntil.toEpochMilli()
        )

        assertTrue(scheduledPlanMatchesOwner("global-a", 7L, owner))
        assertFalse(scheduledPlanMatchesOwner("global-b", 7L, owner))
        assertFalse(scheduledPlanMatchesOwner("global-a", 8L, owner))
    }

    private fun request(plan: SupervisionPlan) = ScheduledStartAuthorizationRequest(
        owner = ScheduledMonitorOwner(
            planId = plan.id,
            planUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
            activeUntilEpochMillis = activeUntil.toEpochMilli()
        ),
        usageMinutes = 30,
        lockMinutes = 5,
        sessionMode = MonitorSessionMode.SUPERVISION
    )

    private fun plan() = SupervisionPlan(
        id = "global-a",
        name = "日常监督",
        type = SupervisionPlanType.GLOBAL,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(DayOfWeek.FRIDAY),
            ranges = listOf(DailyTimeRange(8 * 60, 20 * 60))
        ),
        policy = GlobalCyclePolicy(
            usageDuration = Duration.ofMinutes(30),
            lockDuration = Duration.ofMinutes(5)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 7L
    )
}
