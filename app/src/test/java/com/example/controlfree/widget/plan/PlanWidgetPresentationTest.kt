package com.example.controlfree.widget.plan

import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.widget.ControlFreeWidgetType
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanWidgetPresentationTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `监督组件同时接受全局与App独立监督`() {
        assertEquals(
            setOf(SupervisionPlanType.GLOBAL, SupervisionPlanType.APP),
            compatiblePlanTypes(ControlFreeWidgetType.SUPERVISION)
        )
        assertEquals(
            setOf(SupervisionPlanType.FOCUS),
            compatiblePlanTypes(ControlFreeWidgetType.FOCUS)
        )
    }

    @Test
    fun `运行中的全局监督展示结束边界和周期信息`() {
        val now = Instant.parse("2026-07-27T01:00:00Z")
        val presentation = planWidgetPresentation(
            plan = plan(SupervisionPlanType.GLOBAL, enabled = true),
            isActive = true,
            now = now,
            deviceZoneId = zoneId
        )

        assertEquals("全局监督", presentation.category)
        assertEquals("运行中 · 至 12:00", presentation.status)
        assertEquals("可用30分钟 · 锁定5分钟", presentation.details)
        assertEquals("关闭", presentation.action)
    }

    @Test
    fun `App监督使用应用名称且停用时提供开启动作`() {
        val presentation = planWidgetPresentation(
            plan = plan(SupervisionPlanType.APP, enabled = false),
            isActive = false,
            now = Instant.parse("2026-07-27T01:00:00Z"),
            deviceZoneId = zoneId,
            appLabel = "示例应用"
        )

        assertEquals("App 独立监督", presentation.category)
        assertEquals("已停用", presentation.status)
        assertTrue(presentation.details.startsWith("示例应用 · 可用30分钟"))
        assertEquals("开启", presentation.action)
    }

    @Test
    fun `监督组件小高度使用紧凑布局而专注组件保持完整布局`() {
        assertTrue(usesCompactSupervisionLayout(ControlFreeWidgetType.SUPERVISION, 64))
        assertTrue(!usesCompactSupervisionLayout(ControlFreeWidgetType.SUPERVISION, 120))
        assertTrue(!usesCompactSupervisionLayout(ControlFreeWidgetType.FOCUS, 64))
    }

    @Test
    fun `只有关闭监督任务必须进入身份验证`() {
        assertTrue(
            requiresPlanWidgetAuthentication(
                ControlFreeWidgetType.SUPERVISION,
                targetEnabled = false,
                hasCredentials = true
            )
        )
        assertTrue(
            !requiresPlanWidgetAuthentication(
                ControlFreeWidgetType.SUPERVISION,
                targetEnabled = false,
                hasCredentials = false
            )
        )
        assertTrue(
            !requiresPlanWidgetAuthentication(
                ControlFreeWidgetType.SUPERVISION,
                targetEnabled = true,
                hasCredentials = true
            )
        )
        assertTrue(
            !requiresPlanWidgetAuthentication(
                ControlFreeWidgetType.FOCUS,
                targetEnabled = false,
                hasCredentials = true
            )
        )
    }

    private fun plan(type: SupervisionPlanType, enabled: Boolean): SupervisionPlan =
        SupervisionPlan(
            id = type.name,
            name = "测试任务",
            type = type,
            enabled = enabled,
            schedule = WeeklySchedule(
                zoneId = zoneId,
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(DailyTimeRange(8 * 60, 12 * 60))
            ),
            policy = when (type) {
                SupervisionPlanType.GLOBAL -> GlobalCyclePolicy(
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(5)
                )
                SupervisionPlanType.APP -> AppRulePolicy(
                    "example.app",
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(5)
                )
                SupervisionPlanType.FOCUS -> FocusCyclePolicy(
                    Duration.ofMinutes(20),
                    Duration.ofMinutes(1)
                )
            },
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )
}
