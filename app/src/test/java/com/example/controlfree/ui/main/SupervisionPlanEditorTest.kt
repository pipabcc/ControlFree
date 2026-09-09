package com.example.controlfree.ui.main

import com.example.controlfree.data.AllowedApp
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.ScheduleZoneMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionPlanEditorTest {

    @Test
    fun `App编辑器内容高度在横屏和大屏均保持可见且稳定`() {
        assertEquals(180, resolveAppPlanEditorContentHeightDp(360))
        assertEquals(460, resolveAppPlanEditorContentHeightDp(640))
        assertEquals(560, resolveAppPlanEditorContentHeightDp(900))
    }

    @Test
    fun `App选择器可按名称和包名搜索并忽略首尾空格`() {
        val apps = listOf(
            AllowedApp(packageName = "com.example.reader", label = "阅读器"),
            AllowedApp(packageName = "com.example.music", label = "音乐")
        )

        assertEquals(listOf(apps[0]), filterSupervisableApps(apps, " 阅读 "))
        assertEquals(listOf(apps[1]), filterSupervisableApps(apps, "MUSIC"))
        assertEquals(apps, filterSupervisableApps(apps, "   "))
    }

    @Test
    fun `八点到十二点会构建成指定时段执行的监督任务`() {
        val result = GlobalPlanEditorMapper.build(
            draft = draft(),
            newPlanId = "plan-a",
            nowEpochMillis = 100L,
            deviceZoneId = ZoneId.of("Asia/Shanghai")
        ) as GlobalPlanBuildResult.Success

        assertEquals("plan-a", result.plan.id)
        assertEquals(listOf(DailyTimeRange(8 * 60, 12 * 60)), result.plan.schedule.ranges)
        assertTrue(
            result.plan.schedule.isActiveAt(
                Instant.parse("2026-07-13T01:00:00Z"),
                ZoneId.of("Asia/Shanghai")
            )
        )
        assertFalse(
            result.plan.schedule.isActiveAt(
                Instant.parse("2026-07-13T05:00:00Z"),
                ZoneId.of("Asia/Shanghai")
            )
        )
    }

    @Test
    fun `全局编辑器保留多个App触发配置并保持任务停用`() {
        val triggerApps = setOf("example.video", "example.music")
        val result = GlobalPlanEditorMapper.build(
            draft = draft().copy(
                enabled = false,
                triggerAppPackageNames = triggerApps
            ),
            newPlanId = "plan-trigger",
            nowEpochMillis = 100L,
            deviceZoneId = ZoneId.of("Asia/Shanghai")
        ) as GlobalPlanBuildResult.Success

        assertFalse(result.plan.enabled)
        assertEquals(triggerApps, result.plan.triggerAppPackageNames)
        assertEquals(
            triggerApps,
            GlobalPlanEditorMapper.fromPlan(result.plan).triggerAppPackageNames
        )
    }

    @Test
    fun `编辑计划时版本严格递增并保留固定时区`() {
        val draft = draft().copy(
            planId = "existing",
            expectedUpdatedAtEpochMillis = 200L,
            createdAtEpochMillis = 50L,
            zoneId = ZoneId.of("Europe/London"),
            zoneMode = ScheduleZoneMode.FIXED
        )

        val result = GlobalPlanEditorMapper.build(
            draft,
            newPlanId = "unused",
            nowEpochMillis = 150L,
            deviceZoneId = ZoneId.of("Asia/Shanghai")
        ) as GlobalPlanBuildResult.Success

        assertEquals("existing", result.plan.id)
        assertEquals(201L, result.plan.updatedAtEpochMillis)
        assertEquals(ZoneId.of("Europe/London"), result.plan.schedule.zoneId)
        assertEquals(ScheduleZoneMode.FIXED, result.plan.schedule.zoneMode)
        assertEquals(200L, result.expectedUpdatedAtEpochMillis)
    }

    @Test
    fun `空执行日和重叠时段都会阻止保存`() {
        val noDays = GlobalPlanEditorMapper.build(
            draft().copy(activeDays = emptySet()),
            "plan-a",
            100L,
            ZoneId.of("Asia/Shanghai")
        )
        val overlap = GlobalPlanEditorMapper.build(
            draft().copy(
                ranges = listOf(
                    TimeRangeDraft(1L, 8 * 60, 12 * 60),
                    TimeRangeDraft(2L, 11 * 60, 13 * 60)
                )
            ),
            "plan-b",
            100L,
            ZoneId.of("Asia/Shanghai")
        )

        assertTrue(noDays is GlobalPlanBuildResult.Invalid)
        assertTrue(overlap is GlobalPlanBuildResult.Invalid)
    }

    @Test
    fun `时间段建议不会占用已有或跨午夜时段`() {
        val suggestion = TimeRangeSuggestion.next(
            ranges = listOf(
                TimeRangeDraft(1L, 22 * 60, 2 * 60),
                TimeRangeDraft(2L, 8 * 60, 12 * 60)
            ),
            id = 3L
        )

        assertEquals(TimeRangeDraft(3L, 12 * 60, 13 * 60), suggestion)
    }

    @Test
    fun `时间范围和执行日摘要清晰表达跨日语义`() {
        assertEquals("每天", formatActiveDays(DayOfWeek.entries.toSet()))
        assertEquals(
            "08:00–12:00；22:00–次日 07:00",
            formatTimeRanges(
                listOf(DailyTimeRange(8 * 60, 12 * 60), DailyTimeRange(22 * 60, 7 * 60))
            )
        )
    }

    @Test
    fun `App计划会保存独立目标额度每日上限和禁用时段`() {
        val result = AppPlanEditorMapper.build(
            draft = appDraft().copy(
                disabledRanges = listOf(TimeRangeDraft(2L, 22 * 60, 7 * 60))
            ),
            newPlanId = "app-video",
            nowEpochMillis = 100L,
            deviceZoneId = ZoneId.of("Asia/Shanghai")
        ) as AppPlanBuildResult.Success
        val policy = result.plan.policy as AppRulePolicy

        assertEquals("example.video", policy.packageName)
        assertEquals(30L, policy.usageAllowance.toMinutes())
        assertEquals(10L, policy.restDuration.toMinutes())
        assertEquals(120L, policy.dailyUsageLimit.toMinutes())
        assertEquals(listOf(DailyTimeRange(22 * 60, 7 * 60)), policy.disabledRanges)
        assertEquals(listOf(DailyTimeRange(8 * 60, 12 * 60)), result.plan.schedule.ranges)
    }

    @Test
    fun `App计划未选择目标或同一App时段重叠时拒绝保存`() {
        val noTarget = AppPlanEditorMapper.build(
            appDraft().copy(packageName = ""),
            "app-video",
            100L,
            ZoneId.of("Asia/Shanghai")
        )
        val overlapping = AppPlanEditorMapper.build(
            appDraft().copy(
                ranges = listOf(
                    TimeRangeDraft(1L, 8 * 60, 12 * 60),
                    TimeRangeDraft(2L, 11 * 60, 13 * 60)
                )
            ),
            "app-video",
            100L,
            ZoneId.of("Asia/Shanghai")
        )

        assertTrue(noTarget is AppPlanBuildResult.Invalid)
        assertTrue(overlapping is AppPlanBuildResult.Invalid)
    }

    @Test
    fun `专注任务保存锁定玩机参数和多个执行时间范围`() {
        val result = FocusPlanEditorMapper.build(
            draft = focusDraft().copy(
                ranges = listOf(
                    TimeRangeDraft(1L, 8 * 60, 12 * 60),
                    TimeRangeDraft(2L, 14 * 60, 16 * 60)
                )
            ),
            newPlanId = "focus-a",
            nowEpochMillis = 100L,
            deviceZoneId = ZoneId.of("Asia/Shanghai")
        ) as FocusPlanBuildResult.Success
        val policy = result.plan.policy as FocusCyclePolicy

        assertEquals(15L, policy.lockDuration.toMinutes())
        assertEquals(3L, policy.playDuration.toMinutes())
        assertEquals(
            listOf(DailyTimeRange(8 * 60, 12 * 60), DailyTimeRange(14 * 60, 16 * 60)),
            result.plan.schedule.ranges
        )
        val restoredDraft = FocusPlanEditorMapper.fromPlan(result.plan)
        assertEquals(15, restoredDraft.lockMinutes)
        assertEquals(3, restoredDraft.playMinutes)
    }

    @Test
    fun `专注任务拒绝非法时长和重叠时间范围`() {
        val invalidDuration = FocusPlanEditorMapper.build(
            focusDraft().copy(lockMinutes = 0),
            "focus-a",
            100L,
            ZoneId.of("Asia/Shanghai")
        )
        val overlap = FocusPlanEditorMapper.build(
            focusDraft().copy(
                ranges = listOf(
                    TimeRangeDraft(1L, 8 * 60, 12 * 60),
                    TimeRangeDraft(2L, 11 * 60, 13 * 60)
                )
            ),
            "focus-b",
            100L,
            ZoneId.of("Asia/Shanghai")
        )

        assertTrue(invalidDuration is FocusPlanBuildResult.Invalid)
        assertTrue(overlap is FocusPlanBuildResult.Invalid)
    }

    @Test
    fun `专注任务允许保存一分钟锁定时长`() {
        val result = FocusPlanEditorMapper.build(
            focusDraft().copy(lockMinutes = 1),
            "focus-one-minute",
            100L,
            ZoneId.of("Asia/Shanghai")
        )

        val plan = (result as FocusPlanBuildResult.Success).plan
        assertEquals(1L, (plan.policy as FocusCyclePolicy).lockDuration.toMinutes())
    }

    @Test
    fun `三类编辑草稿均拒绝立即启用和预约开启同时存在`() {
        val enableAt = 180_000L

        assertTrue(
            GlobalPlanEditorMapper.build(
                draft().copy(scheduledEnableAtEpochMillis = enableAt),
                "global-invalid",
                100L,
                ZoneId.of("Asia/Shanghai")
            ) is GlobalPlanBuildResult.Invalid
        )
        assertTrue(
            AppPlanEditorMapper.build(
                appDraft().copy(scheduledEnableAtEpochMillis = enableAt),
                "app-invalid",
                100L,
                ZoneId.of("Asia/Shanghai")
            ) is AppPlanBuildResult.Invalid
        )
        assertTrue(
            FocusPlanEditorMapper.build(
                focusDraft().copy(scheduledEnableAtEpochMillis = enableAt),
                "focus-invalid",
                100L,
                ZoneId.of("Asia/Shanghai")
            ) is FocusPlanBuildResult.Invalid
        )
    }

    @Test
    fun `三类编辑草稿均保留有效预约时间`() {
        val enableAt = 180_000L
        val zoneId = ZoneId.of("Asia/Shanghai")

        val global = GlobalPlanEditorMapper.build(
            draft().copy(enabled = false, scheduledEnableAtEpochMillis = enableAt),
            "global-reserved",
            100L,
            zoneId
        ) as GlobalPlanBuildResult.Success
        val app = AppPlanEditorMapper.build(
            appDraft().copy(enabled = false, scheduledEnableAtEpochMillis = enableAt),
            "app-reserved",
            100L,
            zoneId
        ) as AppPlanBuildResult.Success
        val focus = FocusPlanEditorMapper.build(
            focusDraft().copy(enabled = false, scheduledEnableAtEpochMillis = enableAt),
            "focus-reserved",
            100L,
            zoneId
        ) as FocusPlanBuildResult.Success

        assertEquals(enableAt, global.plan.scheduledEnableAtEpochMillis)
        assertEquals(enableAt, app.plan.scheduledEnableAtEpochMillis)
        assertEquals(enableAt, focus.plan.scheduledEnableAtEpochMillis)
    }

    @Test
    fun `一次性专注保留绝对窗口并以完整起止时间展示`() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val start = Instant.parse("2026-07-13T01:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-07-13T01:30:00Z").toEpochMilli()
        val window = OneTimeFocusWindow(start, end)
        val result = FocusPlanEditorMapper.build(
            draft = focusDraft().copy(
                enabled = false,
                zoneId = zoneId,
                zoneMode = ScheduleZoneMode.FIXED,
                oneTimeFocusWindow = window
            ),
            newPlanId = "focus-once",
            nowEpochMillis = start - 60_000L,
            deviceZoneId = zoneId
        ) as FocusPlanBuildResult.Success

        assertEquals(window, result.plan.oneTimeFocusWindow)
        assertEquals(start, result.plan.scheduledEnableAtEpochMillis)
        assertEquals(
            "2026年7月13日 09:00:00 - 2026年7月13日 09:30:00",
            formatOneTimeFocusWindow(window, zoneId, Locale.SIMPLIFIED_CHINESE)
        )
    }

    private fun draft() = GlobalPlanEditorDraft(
        planId = null,
        expectedUpdatedAtEpochMillis = null,
        createdAtEpochMillis = null,
        name = "早晨任务 A",
        enabled = true,
        activeDays = setOf(DayOfWeek.MONDAY),
        ranges = listOf(TimeRangeDraft(1L, 8 * 60, 12 * 60)),
        usageMinutes = 30,
        lockMinutes = 5,
        zoneId = ZoneId.of("Asia/Shanghai"),
        zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
    )

    private fun appDraft() = AppPlanEditorDraft(
        planId = null,
        expectedUpdatedAtEpochMillis = null,
        createdAtEpochMillis = null,
        name = "视频 App 监督",
        enabled = true,
        activeDays = setOf(DayOfWeek.MONDAY),
        ranges = listOf(TimeRangeDraft(1L, 8 * 60, 12 * 60)),
        disabledRanges = emptyList(),
        packageName = "example.video",
        packageLabel = "视频",
        usageAllowanceMinutes = 30,
        restMinutes = 10,
        dailyUsageLimitMinutes = 120,
        zoneId = ZoneId.of("Asia/Shanghai"),
        zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
    )

    private fun focusDraft() = FocusPlanEditorDraft(
        planId = null,
        expectedUpdatedAtEpochMillis = null,
        createdAtEpochMillis = null,
        name = "上午专注",
        enabled = true,
        activeDays = setOf(DayOfWeek.MONDAY),
        ranges = listOf(TimeRangeDraft(1L, 8 * 60, 12 * 60)),
        lockMinutes = 15,
        playMinutes = 3,
        zoneId = ZoneId.of("Asia/Shanghai"),
        zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
    )
}
