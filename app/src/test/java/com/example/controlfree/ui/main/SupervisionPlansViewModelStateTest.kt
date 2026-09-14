package com.example.controlfree.ui.main

import com.example.controlfree.data.AllowedApp
import com.example.controlfree.supervision.PlanConflictReason
import com.example.controlfree.supervision.SupervisionPlanConflict
import java.time.DayOfWeek
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionPlansViewModelStateTest {
    @Test
    fun `新建App计划选择目标后使用具体App名称`() {
        val viewModel = SupervisionPlansViewModel()
        viewModel.openNewAppPlan(ZoneId.of("Asia/Shanghai"))

        viewModel.selectSupervisableApp(
            AllowedApp(packageName = "com.example.video", label = "视频")
        )

        val draft = requireNotNull(viewModel.uiState.value.appEditorDraft)
        assertEquals("视频", draft.name)
        assertEquals("视频", appPlanListItemName(LEGACY_DEFAULT_APP_PLAN_NAME, "视频"))
    }

    @Test
    fun `自定义App计划名称在更换目标时保留`() {
        val viewModel = SupervisionPlansViewModel()
        viewModel.openNewAppPlan(ZoneId.of("Asia/Shanghai"))
        viewModel.selectSupervisableApp(
            AllowedApp(packageName = "com.example.video", label = "视频")
        )
        viewModel.updateAppName("晚间娱乐")

        viewModel.selectSupervisableApp(
            AllowedApp(packageName = "com.example.music", label = "音乐")
        )

        assertEquals("晚间娱乐", viewModel.uiState.value.appEditorDraft?.name)
    }

    @Test
    fun `新建专注任务使用锁定优先默认值并与其他编辑器互斥`() {
        val viewModel = SupervisionPlansViewModel()
        val zoneId = ZoneId.of("Asia/Shanghai")

        viewModel.openNewFocusPlan(zoneId)

        val focusDraft = requireNotNull(viewModel.uiState.value.focusEditorDraft)
        assertEquals(5, focusDraft.lockMinutes)
        assertEquals(1, focusDraft.playMinutes)
        assertEquals(listOf(TimeRangeDraft(focusDraft.ranges.single().id, 0, 24 * 60)), focusDraft.ranges)
        assertEquals(DayOfWeek.entries.toSet(), focusDraft.activeDays)
        assertNull(viewModel.uiState.value.editorDraft)
        assertNull(viewModel.uiState.value.appEditorDraft)

        viewModel.openNewGlobalPlan(zoneId)
        assertNotNull(viewModel.uiState.value.editorDraft)
        assertNull(viewModel.uiState.value.focusEditorDraft)
    }

    @Test
    fun `专注编辑器可以更新参数执行日和多个时段`() {
        val viewModel = SupervisionPlansViewModel()
        viewModel.openNewFocusPlan(ZoneId.of("Asia/Shanghai"))
        val initialRangeId = requireNotNull(viewModel.uiState.value.focusEditorDraft)
            .ranges.single().id

        viewModel.updateFocusName("上午深度专注")
        viewModel.updateFocusLockMinutes(30)
        viewModel.updateFocusPlayMinutes(5)
        viewModel.toggleFocusDay(DayOfWeek.MONDAY)
        viewModel.updateFocusRangeStart(initialRangeId, 9 * 60)
        viewModel.addFocusRange()

        val draft = requireNotNull(viewModel.uiState.value.focusEditorDraft)
        assertEquals("上午深度专注", draft.name)
        assertEquals(30, draft.lockMinutes)
        assertEquals(5, draft.playMinutes)
        assertTrue(DayOfWeek.MONDAY !in draft.activeDays)
        assertEquals(2, draft.ranges.size)
        assertEquals(9 * 60, draft.ranges.first().startMinute)
    }

    @Test
    fun `计划冲突消息区分时段App与时区问题`() {
        fun conflict(reason: PlanConflictReason) = SupervisionPlanConflict("one", "two", reason)

        assertEquals(
            "同一个 App 的已启用执行时段不能重叠",
            planConflictMessage(listOf(conflict(PlanConflictReason.OVERLAPPING_APP_RULE)))
        )
        assertEquals(
            "计划使用了不同固定时区，无法安全判断时段冲突，请统一时区设置",
            planConflictMessage(listOf(conflict(PlanConflictReason.DIFFERENT_TIME_ZONES)))
        )
        assertEquals(
            "该时段与已启用的全局监督或专注任务冲突",
            planConflictMessage(listOf(conflict(PlanConflictReason.OVERLAPPING_TIME_RANGE)))
        )
    }
}
