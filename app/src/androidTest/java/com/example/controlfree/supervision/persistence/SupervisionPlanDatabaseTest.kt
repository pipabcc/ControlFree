package com.example.controlfree.supervision.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.todo.TodoItemEntity
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupervisionPlanDatabaseTest {
    private lateinit var database: ControlFreeDatabase
    private lateinit var dao: SupervisionPlanDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.supervisionPlanDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replace会原子替换时间段且删除级联清理子表() = runBlocking {
        val initial = plan("morning", 8 * 60, 12 * 60, updatedAt = 1L).copy(
            enabled = false,
            triggerAppPackageNames = setOf("example.video", "example.music")
        )
        dao.replace(SupervisionPlanMapper.toRecord(initial))
        dao.upsertActivationReservation(PlanActivationReservationEntity(initial.id, 1_000L))
        val changed = plan("morning", 13 * 60, 17 * 60, updatedAt = 2L)

        dao.replace(SupervisionPlanMapper.toRecord(changed))

        val loaded = requireNotNull(dao.getById("morning"))
        assertEquals(listOf(13 * 60), loaded.ranges.map { it.startMinute })
        assertEquals(2L, loaded.plan.updatedAtEpochMillis)
        assertEquals(1_000L, loaded.activationReservation?.enableAtEpochMillis)
        assertTrue(loaded.triggerApps.isEmpty())

        dao.delete("morning")
        assertNull(dao.getById("morning"))
        assertNull(dao.getActivationReservation("morning"))
        assertEquals(0L, countRows("supervision_time_ranges"))
        assertEquals(0L, countRows("global_supervision_policies"))
        assertEquals(0L, countRows("supervision_plan_trigger_apps"))
    }

    @Test
    fun App触发会原子启用且重复触发幂等并保留多个App配置() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val now = Instant.parse("2026-07-13T01:00:00Z").toEpochMilli()
        val triggerPlan = plan(
            id = "trigger-global",
            startMinute = 8 * 60,
            endMinute = 12 * 60,
            updatedAt = 7L,
            zoneMode = ScheduleZoneMode.FIXED
        ).copy(
            enabled = false,
            triggerAppPackageNames = setOf("example.video", "example.music")
        )
        assertTrue(repository.save(triggerPlan, null) is PlanWriteResult.Success)

        val activated = repository.activateFromAppTrigger(
            planId = triggerPlan.id,
            expectedUpdatedAtEpochMillis = 7L,
            triggerPackageName = "example.music",
            nowEpochMillis = now,
            deviceZoneId = ZoneId.of("Asia/Shanghai")
        )

        assertEquals(
            AppTriggerActivationResult.Activated(triggerPlan.id, emptySet()),
            activated
        )
        val stored = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertTrue(stored.enabled)
        assertEquals(setOf("example.video", "example.music"), stored.triggerAppPackageNames)
        assertTrue(
            repository.activateFromAppTrigger(
                planId = triggerPlan.id,
                expectedUpdatedAtEpochMillis = 7L,
                triggerPackageName = "example.music",
                nowEpochMillis = now,
                deviceZoneId = ZoneId.of("Asia/Shanghai")
            ) is AppTriggerActivationResult.AlreadyEnabled
        )
    }

    @Test
    fun App触发会拒绝过期版本错误App和时段外请求() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val activeTime = Instant.parse("2026-07-13T01:00:00Z").toEpochMilli()
        val outsideTime = Instant.parse("2026-07-13T05:00:00Z").toEpochMilli()
        val triggerPlan = plan(
            id = "trigger-global",
            startMinute = 8 * 60,
            endMinute = 12 * 60,
            updatedAt = 7L,
            zoneMode = ScheduleZoneMode.FIXED
        ).copy(
            enabled = false,
            triggerAppPackageNames = setOf("example.video")
        )
        assertTrue(repository.save(triggerPlan, null) is PlanWriteResult.Success)

        val results = listOf(
            repository.activateFromAppTrigger(
                triggerPlan.id,
                6L,
                "example.video",
                activeTime,
                ZoneId.of("Asia/Shanghai")
            ),
            repository.activateFromAppTrigger(
                triggerPlan.id,
                7L,
                "example.other",
                activeTime,
                ZoneId.of("Asia/Shanghai")
            ),
            repository.activateFromAppTrigger(
                triggerPlan.id,
                7L,
                "example.video",
                outsideTime,
                ZoneId.of("Asia/Shanghai")
            )
        )

        assertTrue(results.all { it is AppTriggerActivationResult.NoLongerEligible })
        assertEquals(triggerPlan, (repository.loadPlans() as PlanLoadResult.Success).plans.single())
    }

    @Test
    fun 整合保存可原子新建修改和取消预约() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val initialEnableAt = System.currentTimeMillis() + 3_600_000L
        val initial = plan("editor-reservation", 8 * 60, 12 * 60, updatedAt = 1L)
            .copy(enabled = false)

        assertEquals(
            PlanWriteResult.Success(setOf(initial.id)),
            repository.saveWithReservation(initial, null, initialEnableAt)
        )
        val created = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(false, created.enabled)
        assertEquals(initialEnableAt, created.scheduledEnableAtEpochMillis)
        assertEquals(initialEnableAt, dao.getActivationReservation(initial.id)?.enableAtEpochMillis)

        val changedEnableAt = initialEnableAt + 3_600_000L
        val changed = created.copy(name = "修改后的预约", updatedAtEpochMillis = 2L)
        assertEquals(
            PlanWriteResult.Success(setOf(initial.id)),
            repository.saveWithReservation(changed, 1L, changedEnableAt)
        )
        val updated = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals("修改后的预约", updated.name)
        assertEquals(changedEnableAt, updated.scheduledEnableAtEpochMillis)

        val canceled = updated.copy(updatedAtEpochMillis = 3L)
        assertEquals(
            PlanWriteResult.Success(setOf(initial.id)),
            repository.saveWithReservation(canceled, 2L, null)
        )
        val afterCancel = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertNull(afterCancel.scheduledEnableAtEpochMillis)
        assertNull(dao.getActivationReservation(initial.id))
    }

    @Test
    fun 整合保存立即启用会清除预约且非法请求不污染旧数据() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val enableAt = System.currentTimeMillis() + 3_600_000L
        val disabled = plan("editor-enable", 8 * 60, 12 * 60, updatedAt = 1L)
            .copy(enabled = false)
        assertTrue(repository.saveWithReservation(disabled, null, enableAt) is PlanWriteResult.Success)

        val scheduled = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        val invalid = scheduled.copy(name = "不应写入", enabled = true, updatedAtEpochMillis = 2L)
        assertTrue(
            repository.saveWithReservation(invalid, 1L, enableAt) is PlanWriteResult.InvalidInput
        )
        assertEquals(scheduled, (repository.loadPlans() as PlanLoadResult.Success).plans.single())
        assertEquals(enableAt, dao.getActivationReservation(disabled.id)?.enableAtEpochMillis)

        val enabled = scheduled.copy(enabled = true, updatedAtEpochMillis = 2L)
        assertEquals(
            PlanWriteResult.Success(setOf(disabled.id)),
            repository.saveWithReservation(enabled, 1L, null)
        )
        val afterEnable = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertTrue(afterEnable.enabled)
        assertNull(afterEnable.scheduledEnableAtEpochMillis)
        assertNull(dao.getActivationReservation(disabled.id))
    }

    @Test
    fun 预约仅允许停用任务的未来时间并执行乐观锁() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val disabled = plan("reserved", 8 * 60, 12 * 60, updatedAt = 5L)
            .copy(enabled = false)
        assertTrue(repository.save(disabled, null) is PlanWriteResult.Success)

        assertTrue(
            repository.scheduleEnable(disabled.id, 100L, 5L, 100L) is
                PlanWriteResult.InvalidInput
        )
        assertTrue(
            repository.scheduleEnable(disabled.id, 200L, 4L, 100L) is
                PlanWriteResult.StaleData
        )
        val scheduled = repository.scheduleEnable(disabled.id, 200L, 5L, 100L)

        assertEquals(PlanWriteResult.Success(setOf(disabled.id)), scheduled)
        val loaded = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(false, loaded.enabled)
        assertEquals(100L, loaded.updatedAtEpochMillis)
        assertEquals(200L, loaded.scheduledEnableAtEpochMillis)
        assertTrue(
            repository.scheduleEnable(disabled.id, 300L, 5L, 100L) is
                PlanWriteResult.StaleData
        )

        val enabled = plan("enabled", 13 * 60, 14 * 60, updatedAt = 1L)
        assertTrue(repository.save(enabled, null) is PlanWriteResult.Success)
        assertTrue(
            repository.scheduleEnable(enabled.id, 300L, 1L, 100L) is
                PlanWriteResult.InvalidInput
        )
    }

    @Test
    fun 编辑停用任务保留预约而手动启用会原子取消预约() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val disabled = plan("editable", 8 * 60, 12 * 60, updatedAt = 1L)
            .copy(enabled = false)
        assertTrue(repository.save(disabled, null) is PlanWriteResult.Success)
        assertTrue(repository.scheduleEnable(disabled.id, 500L, 1L, 100L) is PlanWriteResult.Success)

        val scheduled = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        val edited = scheduled.copy(name = "编辑后", updatedAtEpochMillis = 101L)
        assertTrue(repository.save(edited, 100L) is PlanWriteResult.Success)
        val afterEdit = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(500L, afterEdit.scheduledEnableAtEpochMillis)
        assertEquals("编辑后", afterEdit.name)

        val enabled = afterEdit.copy(enabled = true, updatedAtEpochMillis = 102L)
        assertTrue(repository.save(enabled, 101L) is PlanWriteResult.Success)
        val afterEnable = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(true, afterEnable.enabled)
        assertNull(afterEnable.scheduledEnableAtEpochMillis)
        assertNull(dao.getActivationReservation(disabled.id))
    }

    @Test
    fun 取消预约递增版本且重复取消幂等() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val disabled = plan("cancel", 8 * 60, 12 * 60, updatedAt = 1L)
            .copy(enabled = false)
        assertTrue(repository.save(disabled, null) is PlanWriteResult.Success)
        assertTrue(repository.scheduleEnable(disabled.id, 500L, 1L, 100L) is PlanWriteResult.Success)

        val canceled = repository.cancelScheduledEnable(disabled.id, 100L, 150L)

        assertEquals(PlanWriteResult.Success(setOf(disabled.id)), canceled)
        val loaded = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(150L, loaded.updatedAtEpochMillis)
        assertNull(loaded.scheduledEnableAtEpochMillis)
        assertEquals(
            PlanWriteResult.Success(emptySet()),
            repository.cancelScheduledEnable(disabled.id, 150L, 160L)
        )
        assertEquals(
            150L,
            (repository.loadPlans() as PlanLoadResult.Success).plans.single()
                .updatedAtEpochMillis
        )
    }

    @Test
    fun 到期预约原子启用并清理预约且保留原计划时段() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val disabled = plan("due", 8 * 60, 12 * 60, updatedAt = 1L)
            .copy(enabled = false)
        assertTrue(repository.save(disabled, null) is PlanWriteResult.Success)
        assertTrue(repository.scheduleEnable(disabled.id, 200L, 1L, 100L) is PlanWriteResult.Success)

        assertEquals(
            ScheduledEnableBatchResult.Completed(emptySet(), emptySet(), emptyList()),
            repository.activateDueScheduledEnables(199L)
        )
        val result = repository.activateDueScheduledEnables(200L)

        assertEquals(
            ScheduledEnableBatchResult.Completed(setOf(disabled.id), emptySet(), emptyList()),
            result
        )
        val enabled = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(true, enabled.enabled)
        assertEquals(disabled.schedule, enabled.schedule)
        assertEquals(200L, enabled.updatedAtEpochMillis)
        assertNull(enabled.scheduledEnableAtEpochMillis)
    }

    @Test
    fun 到期启用专注会沿用规则自动停用重叠全局监督() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val global = plan("global-live", 8 * 60, 12 * 60, updatedAt = 1L)
        val focus = focusPlan("focus-reserved", 11 * 60, 13 * 60).copy(enabled = false)
        assertTrue(repository.save(global, null) is PlanWriteResult.Success)
        assertTrue(repository.save(focus, null) is PlanWriteResult.Success)
        assertTrue(repository.scheduleEnable(focus.id, 200L, 1L, 100L) is PlanWriteResult.Success)

        val result = repository.activateDueScheduledEnables(200L)

        assertEquals(
            ScheduledEnableBatchResult.Completed(
                activatedPlanIds = setOf(focus.id),
                autoDisabledPlanIds = setOf(global.id),
                failures = emptyList()
            ),
            result
        )
        val loaded = (repository.loadPlans() as PlanLoadResult.Success).plans
        assertEquals(false, loaded.first { it.id == global.id }.enabled)
        assertEquals(true, loaded.first { it.id == focus.id }.enabled)
    }

    @Test
    fun 无法解决的同类型冲突只报告一次并清除预约() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val active = plan("active-global", 8 * 60, 12 * 60, updatedAt = 1L)
        val reserved = plan("reserved-global", 11 * 60, 13 * 60, updatedAt = 1L)
            .copy(enabled = false)
        assertTrue(repository.save(active, null) is PlanWriteResult.Success)
        assertTrue(repository.save(reserved, null) is PlanWriteResult.Success)
        assertTrue(repository.scheduleEnable(reserved.id, 200L, 1L, 100L) is PlanWriteResult.Success)

        val first = repository.activateDueScheduledEnables(200L)

        assertTrue(first is ScheduledEnableBatchResult.Completed)
        first as ScheduledEnableBatchResult.Completed
        assertTrue(first.activatedPlanIds.isEmpty())
        assertEquals(1, first.failures.size)
        assertEquals(ScheduledEnableFailureReason.CONFLICT, first.failures.single().reason)
        val stillDisabled = (repository.loadPlans() as PlanLoadResult.Success).plans
            .first { it.id == reserved.id }
        assertEquals(false, stillDisabled.enabled)
        assertNull(stillDisabled.scheduledEnableAtEpochMillis)
        assertEquals(
            ScheduledEnableBatchResult.Completed(emptySet(), emptySet(), emptyList()),
            repository.activateDueScheduledEnables(201L)
        )
    }

    @Test
    fun 冲突或版本未递增时不会破坏旧数据() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val first = plan("first", 8 * 60, 12 * 60, updatedAt = 1L)
        assertTrue(repository.save(first, null) is PlanWriteResult.Success)

        val sameVersion = first.copy(name = "旧版本覆盖")
        assertTrue(repository.save(sameVersion, 1L) is PlanWriteResult.NonIncreasingVersion)

        val overlapping = plan("second", 11 * 60, 13 * 60, updatedAt = 1L)
        assertTrue(repository.save(overlapping, null) is PlanWriteResult.Conflicts)

        val loaded = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(listOf(first), loaded.plans)
    }

    @Test
    fun 批量保存拒绝重复ID且不写入任何记录() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val first = plan("same", 8 * 60, 9 * 60, updatedAt = 1L)
        val second = plan("same", 10 * 60, 11 * 60, updatedAt = 2L)

        val result = repository.saveAll(
            listOf(PlanSaveRequest(first, null), PlanSaveRequest(second, null))
        )

        assertEquals(PlanWriteResult.DuplicateIds(setOf("same")), result)
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun 禁用计划允许保存但重新启用时必须重新检查冲突() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val first = plan("first", 8 * 60, 12 * 60, updatedAt = 1L)
        val disabled = plan("disabled", 10 * 60, 11 * 60, updatedAt = 1L)
            .copy(enabled = false)
        assertTrue(repository.save(first, null) is PlanWriteResult.Success)
        assertTrue(repository.save(disabled, null) is PlanWriteResult.Success)

        val result = repository.setEnabled(
            planId = disabled.id,
            enabled = true,
            expectedUpdatedAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L
        )

        assertTrue(result is PlanWriteResult.Conflicts)
        val loaded = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(false, loaded.plans.first { it.id == disabled.id }.enabled)
    }

    @Test
    fun 同一App重叠计划被拒绝而不同App可以并行() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val first = appPlan("video-a", "example.video", 8 * 60, 12 * 60)
        val overlapping = appPlan("video-b", "example.video", 11 * 60, 13 * 60)
        val otherApp = appPlan("game", "example.game", 11 * 60, 13 * 60)

        assertTrue(repository.save(first, null) is PlanWriteResult.Success)
        assertTrue(repository.save(overlapping, null) is PlanWriteResult.Conflicts)
        assertTrue(repository.save(otherApp, null) is PlanWriteResult.Success)

        val loaded = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(setOf("video-a", "game"), loaded.plans.mapTo(linkedSetOf()) { it.id })
    }

    @Test
    fun 保存启用专注计划会原子停用重叠全局监督() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val global = plan("global", 8 * 60, 12 * 60, updatedAt = 1L)
        val focus = focusPlan("focus", 11 * 60, 13 * 60)

        assertTrue(repository.save(global, null) is PlanWriteResult.Success)
        val result = repository.save(focus, null)
        assertEquals(
            PlanWriteResult.Success(
                affectedPlanIds = setOf("focus", "global"),
                autoDisabledPlanIds = setOf("global")
            ),
            result
        )
        val switched = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(false, switched.plans.first { it.id == global.id }.enabled)
        assertEquals(true, switched.plans.first { it.id == focus.id }.enabled)

        assertEquals(2L, countRows("global_supervision_policies"))
    }

    @Test
    fun 保存启用全局监督会原子停用重叠专注计划() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val focus = focusPlan("focus", 8 * 60, 12 * 60)
        val global = plan("global", 11 * 60, 13 * 60, updatedAt = 1L)

        assertTrue(repository.save(focus, null) is PlanWriteResult.Success)
        val result = repository.save(global, null)

        assertEquals(
            PlanWriteResult.Success(
                affectedPlanIds = setOf("global", "focus"),
                autoDisabledPlanIds = setOf("focus")
            ),
            result
        )
        val loaded = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(true, loaded.plans.first { it.id == global.id }.enabled)
        assertEquals(false, loaded.plans.first { it.id == focus.id }.enabled)
    }

    @Test
    fun 通过启用开关开启专注也会自动停用重叠全局监督() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val global = plan("global", 8 * 60, 12 * 60, updatedAt = 1L)
        val disabledFocus = focusPlan("focus", 11 * 60, 13 * 60).copy(enabled = false)

        assertTrue(repository.save(global, null) is PlanWriteResult.Success)
        assertTrue(repository.save(disabledFocus, null) is PlanWriteResult.Success)
        val result = repository.setEnabled(
            planId = disabledFocus.id,
            enabled = true,
            expectedUpdatedAtEpochMillis = disabledFocus.updatedAtEpochMillis,
            updatedAtEpochMillis = 2L
        )

        assertTrue(result is PlanWriteResult.Success)
        assertEquals(setOf("focus", "global"), (result as PlanWriteResult.Success).affectedPlanIds)
        assertEquals(setOf("global"), result.autoDisabledPlanIds)
        val loaded = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(false, loaded.plans.first { it.id == global.id }.enabled)
        assertEquals(true, loaded.plans.first { it.id == disabledFocus.id }.enabled)
    }

    @Test
    fun 应急解锁会原子停用启用中的定时专注计划() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val focus = focusPlan("focus", 8 * 60, 12 * 60, updatedAt = 5L)
        assertTrue(repository.save(focus, null) is PlanWriteResult.Success)

        val result = repository.disableScheduledFocusPlan(
            planId = focus.id,
            nowEpochMillis = 100L
        )

        assertEquals(PlanWriteResult.Success(setOf(focus.id)), result)
        val disabled = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(false, disabled.enabled)
        assertEquals(100L, disabled.updatedAtEpochMillis)
        assertEquals(focus.copy(enabled = false, updatedAtEpochMillis = 100L), disabled)
    }

    @Test
    fun 重复停用定时专注计划幂等且不递增版本() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val disabledFocus = focusPlan("focus", 8 * 60, 12 * 60, updatedAt = 7L)
            .copy(enabled = false)
        assertTrue(repository.save(disabledFocus, null) is PlanWriteResult.Success)

        val result = repository.disableScheduledFocusPlan(
            planId = disabledFocus.id,
            nowEpochMillis = 100L
        )

        assertEquals(PlanWriteResult.Success(emptySet()), result)
        val loaded = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(disabledFocus, loaded)
    }

    @Test
    fun 运行期间计划被编辑后应急解锁仍停用数据库最新版本() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val original = focusPlan("focus", 8 * 60, 12 * 60, updatedAt = 2L)
        assertTrue(repository.save(original, null) is PlanWriteResult.Success)
        val edited = original.copy(name = "已编辑专注", updatedAtEpochMillis = 9L)
        assertTrue(repository.save(edited, 2L) is PlanWriteResult.Success)

        val result = repository.disableScheduledFocusPlan(
            planId = original.id,
            nowEpochMillis = 3L
        )

        assertEquals(PlanWriteResult.Success(setOf(original.id)), result)
        val disabled = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals("已编辑专注", disabled.name)
        assertEquals(false, disabled.enabled)
        assertEquals(10L, disabled.updatedAtEpochMillis)
    }

    @Test
    fun 应急解锁拒绝停用非专注计划() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val global = plan("global", 8 * 60, 12 * 60, updatedAt = 4L)
        assertTrue(repository.save(global, null) is PlanWriteResult.Success)

        val result = repository.disableScheduledFocusPlan(
            planId = global.id,
            nowEpochMillis = 100L
        )

        assertTrue(result is PlanWriteResult.InvalidInput)
        assertEquals(global, (repository.loadPlans() as PlanLoadResult.Success).plans.single())
    }

    @Test
    fun 停用不存在的定时专注计划返回清晰结果() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)

        val result = repository.disableScheduledFocusPlan(
            planId = "missing-focus",
            nowEpochMillis = 100L
        )

        assertEquals(PlanWriteResult.NotFound("missing-focus"), result)
    }

    @Test
    fun 保存启用专注计划会停用无法直接比较时区的全局监督() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val global = plan(
            id = "global",
            startMinute = 8 * 60,
            endMinute = 12 * 60,
            updatedAt = 1L,
            zoneId = ZoneId.of("Asia/Shanghai"),
            zoneMode = ScheduleZoneMode.FIXED
        )
        val focus = focusPlan(
            id = "focus",
            startMinute = 8 * 60,
            endMinute = 12 * 60,
            zoneId = ZoneId.of("UTC"),
            zoneMode = ScheduleZoneMode.FIXED
        )

        assertTrue(repository.save(global, null) is PlanWriteResult.Success)
        val result = repository.save(focus, null)

        assertEquals(
            PlanWriteResult.Success(
                affectedPlanIds = setOf("focus", "global"),
                autoDisabledPlanIds = setOf("global")
            ),
            result
        )
        val loaded = repository.loadPlans() as PlanLoadResult.Success
        assertEquals(false, loaded.plans.first { it.id == global.id }.enabled)
        assertEquals(true, loaded.plans.first { it.id == focus.id }.enabled)
    }

    @Test
    fun 计划类型切换会清理旧策略表且陈旧删除被拒绝() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val global = plan("switch", 8 * 60, 12 * 60, updatedAt = 1L)
        assertTrue(repository.save(global, null) is PlanWriteResult.Success)
        val app = appPlan("switch", "example.video", 8 * 60, 12 * 60, updatedAt = 2L)

        assertTrue(repository.save(app, 1L) is PlanWriteResult.Success)
        assertEquals(0L, countRows("global_supervision_policies"))
        assertEquals(1L, countRows("app_supervision_policies"))
        assertTrue(repository.delete("switch", 1L) is PlanWriteResult.StaleData)
        assertTrue(repository.delete("switch", 2L) is PlanWriteResult.Success)
    }

    @Test
    fun 一次性专注和待办原子关联且重复点击幂等到期后停用() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val todo = TodoItemEntity(
            id = "todo-once",
            title = "完成报告",
            description = null,
            dueDateEpochMillis = null,
            priority = 2,
            isCompleted = false,
            completedAtEpochMillis = null,
            category = "工作",
            repeatRule = null,
            associatedFocusPlanId = null,
            supervisionLockEnabled = false,
            createdAtEpochMillis = 1L
        )
        database.todoDao().upsertTodo(todo)
        val start = Instant.parse("2026-07-13T01:00:00Z").toEpochMilli()
        val end = start + 30 * 60_000L
        val now = start - 60_000L

        val first = repository.scheduleOneTimeFocusForTodo(
            todoId = todo.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now
        ) as OneTimeFocusScheduleResult.Success
        val second = repository.scheduleOneTimeFocusForTodo(
            todoId = todo.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now
        ) as OneTimeFocusScheduleResult.Success

        assertTrue(first.wasCreated)
        assertTrue(!second.wasCreated)
        assertEquals(1L, countRows("supervision_plans"))
        assertEquals(1L, countRows("plan_activation_reservations"))
        val linkedTodo = requireNotNull(database.todoDao().getById(todo.id))
        assertEquals(first.planId, linkedTodo.associatedFocusPlanId)
        assertEquals(false, linkedTodo.supervisionLockEnabled)
        assertEquals(start, linkedTodo.scheduledStartEpochMillis)
        assertEquals(end, linkedTodo.scheduledEndEpochMillis)

        assertEquals(
            PlanWriteResult.Success(setOf(first.planId)),
            repository.disableExpiredOneTimeFocusPlans(end)
        )
        val expired = (repository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertTrue(!expired.enabled)
        assertNull(expired.scheduledEnableAtEpochMillis)
        assertNull(database.supervisionPlanDao().getActivationReservation(first.planId))
        val expiredTodo = requireNotNull(database.todoDao().getById(todo.id))
        assertNull(expiredTodo.associatedFocusPlanId)
        assertEquals(start, expiredTodo.scheduledStartEpochMillis)
        assertEquals(end, expiredTodo.scheduledEndEpochMillis)
        assertEquals(30, expiredTodo.estimatedFocusMinutes)
    }

    @Test
    fun 一次性专注与全局或专注计划冲突时均不写入() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val todo = TodoItemEntity(
            id = "todo-conflict",
            title = "整理方案",
            description = null,
            dueDateEpochMillis = null,
            priority = 2,
            isCompleted = false,
            completedAtEpochMillis = null,
            category = "工作",
            repeatRule = null,
            associatedFocusPlanId = null,
            supervisionLockEnabled = true,
            createdAtEpochMillis = 1L
        )
        database.todoDao().upsertTodo(todo)
        val start = Instant.parse("2026-07-13T01:15:00Z").toEpochMilli()
        val end = start + 30 * 60_000L
        val now = start - 60_000L
        val conflictingPlans = listOf(
            plan(
                id = "global-conflict",
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                updatedAt = 1L,
                zoneMode = ScheduleZoneMode.FIXED
            ),
            focusPlan(
                id = "focus-conflict",
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                zoneMode = ScheduleZoneMode.FIXED
            )
        )

        conflictingPlans.forEach { conflictingPlan ->
            dao.replace(SupervisionPlanMapper.toRecord(conflictingPlan))

            val result = repository.scheduleOneTimeFocusForTodo(
                todoId = todo.id,
                startEpochMillis = start,
                endEpochMillis = end,
                nowEpochMillis = now
            )

            assertTrue(result is OneTimeFocusScheduleResult.Conflicts)
            val conflicts = (result as OneTimeFocusScheduleResult.Conflicts).conflicts
            assertTrue(
                conflicts.any { conflict ->
                    conflict.firstPlanId == conflictingPlan.id ||
                        conflict.secondPlanId == conflictingPlan.id
                }
            )
            assertEquals(1L, countRows("supervision_plans"))
            assertEquals(0L, countRows("plan_activation_reservations"))
            assertEquals(todo, database.todoDao().getById(todo.id))

            dao.delete(conflictingPlan.id)
        }
    }

    @Test
    fun 已预约一次性专注会阻止后来保存重叠全局或专注计划() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val todo = todo("todo-reserved-conflict")
        database.todoDao().upsertTodo(todo)
        val start = Instant.parse("2026-07-13T01:15:00Z").toEpochMilli()
        val end = start + 30 * 60_000L
        val scheduled = repository.scheduleOneTimeFocusForTodo(
            todoId = todo.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = start - 60_000L
        ) as OneTimeFocusScheduleResult.Success
        val laterPlans = listOf(
            plan(
                id = "later-global",
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                updatedAt = 1L,
                zoneMode = ScheduleZoneMode.FIXED
            ),
            focusPlan(
                id = "later-focus",
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                zoneMode = ScheduleZoneMode.FIXED
            )
        )

        laterPlans.forEach { laterPlan ->
            val result = repository.save(laterPlan, expectedUpdatedAtEpochMillis = null)

            assertTrue(result is PlanWriteResult.Conflicts)
            assertEquals(1L, countRows("supervision_plans"))
            assertEquals(1L, countRows("plan_activation_reservations"))
            assertEquals(
                scheduled.planId,
                database.todoDao().getById(todo.id)?.associatedFocusPlanId
            )
        }
    }

    @Test
    fun 一次性专注到点遇到后来出现的全局计划会明确失败且不自动停用() = runBlocking {
        val repository = SupervisionPlanRepository.createForTest(database)
        val todo = todo("todo-activation-conflict")
        database.todoDao().upsertTodo(todo)
        val start = Instant.parse("2026-07-13T01:15:00Z").toEpochMilli()
        val end = start + 30 * 60_000L
        val scheduled = repository.scheduleOneTimeFocusForTodo(
            todoId = todo.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = start - 60_000L
        ) as OneTimeFocusScheduleResult.Success
        val laterGlobal = plan(
            id = "global-before-activation",
            startMinute = 9 * 60,
            endMinute = 10 * 60,
            updatedAt = 1L,
            zoneMode = ScheduleZoneMode.FIXED
        )
        // 模拟外部恢复或旧版本在预约落库后写入的冲突计划。
        dao.replace(SupervisionPlanMapper.toRecord(laterGlobal))

        val result = repository.activateDueScheduledEnables(start)

        assertTrue(result is ScheduledEnableBatchResult.Completed)
        result as ScheduledEnableBatchResult.Completed
        assertTrue(result.activatedPlanIds.isEmpty())
        assertTrue(result.autoDisabledPlanIds.isEmpty())
        assertEquals(ScheduledEnableFailureReason.CONFLICT, result.failures.single().reason)
        val plans = (repository.loadPlans() as PlanLoadResult.Success).plans
        assertEquals(true, plans.first { it.id == laterGlobal.id }.enabled)
        val oneTime = plans.first { it.id == scheduled.planId }
        assertEquals(false, oneTime.enabled)
        assertNull(oneTime.scheduledEnableAtEpochMillis)
        assertNull(dao.getActivationReservation(scheduled.planId))
        val unlinkedTodo = requireNotNull(database.todoDao().getById(todo.id))
        assertNull(unlinkedTodo.associatedFocusPlanId)
        assertEquals(start, unlinkedTodo.scheduledStartEpochMillis)
        assertEquals(end, unlinkedTodo.scheduledEndEpochMillis)
        assertEquals(30, unlinkedTodo.estimatedFocusMinutes)
    }

    private fun countRows(table: String): Long {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun plan(
        id: String,
        startMinute: Int,
        endMinute: Int,
        updatedAt: Long,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
        zoneMode: ScheduleZoneMode = ScheduleZoneMode.FOLLOW_DEVICE
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.GLOBAL,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = zoneId,
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(startMinute, endMinute)),
            zoneMode = zoneMode
        ),
        policy = GlobalCyclePolicy(Duration.ofMinutes(30), Duration.ofMinutes(5)),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt
    )

    private fun appPlan(
        id: String,
        packageName: String,
        startMinute: Int,
        endMinute: Int,
        updatedAt: Long = 1L
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.APP,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = ZoneId.of("Asia/Shanghai"),
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(startMinute, endMinute))
        ),
        policy = AppRulePolicy(
            packageName = packageName,
            usageAllowance = Duration.ofMinutes(30),
            restDuration = Duration.ofMinutes(5)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt
    )

    private fun focusPlan(
        id: String,
        startMinute: Int,
        endMinute: Int,
        updatedAt: Long = 1L,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
        zoneMode: ScheduleZoneMode = ScheduleZoneMode.FOLLOW_DEVICE
    ) = SupervisionPlan(
        id = id,
        name = id,
        type = SupervisionPlanType.FOCUS,
        enabled = true,
        schedule = WeeklySchedule(
            zoneId = zoneId,
            activeDays = setOf(DayOfWeek.MONDAY),
            ranges = listOf(DailyTimeRange(startMinute, endMinute)),
            zoneMode = zoneMode
        ),
        policy = FocusCyclePolicy(
            lockDuration = Duration.ofMinutes(15),
            playDuration = Duration.ofMinutes(3)
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt
    )

    private fun todo(id: String) = TodoItemEntity(
        id = id,
        title = "整理方案",
        description = null,
        dueDateEpochMillis = null,
        priority = 2,
        isCompleted = false,
        completedAtEpochMillis = null,
        category = "工作",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = 1L
    )
}
