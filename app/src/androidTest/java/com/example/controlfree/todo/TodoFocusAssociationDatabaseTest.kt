package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.supervision.persistence.OneTimeFocusScheduleResult
import com.example.controlfree.supervision.persistence.PlanActivationReservationEntity
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.PlanWriteResult
import com.example.controlfree.supervision.persistence.SupervisionPlanMapper
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoFocusAssociationDatabaseTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private lateinit var database: ControlFreeDatabase
    private lateinit var todoRepository: TodoRepository
    private lateinit var planRepository: SupervisionPlanRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        todoRepository = TodoRepository.createForTest(
            database,
            Clock.fixed(now, ZoneOffset.UTC)
        )
        planRepository = SupervisionPlanRepository.createForTest(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 编辑待办时间窗会原子删除失配的一次性专注计划() = runBlocking {
        val original = todo("edit-window")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = now.plusSeconds(5_400L).toEpochMilli()
        val scheduled = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success
        val linked = requireNotNull(todoRepository.getTodoById(original.id))

        val editedStart = start + 15 * 60_000L
        val editedEnd = end + 15 * 60_000L
        val edited = todoRepository.saveTodo(
            linked.copy(
                scheduledStartEpochMillis = editedStart,
                scheduledEndEpochMillis = editedEnd,
                updatedAtEpochMillis = now.plusSeconds(1L).toEpochMilli()
            )
        )

        assertNull(edited.associatedFocusPlanId)
        // 时间字段仍属于待办日历本身，仅移除已经失配的监督关联。
        assertEquals(editedStart, edited.scheduledStartEpochMillis)
        assertEquals(editedEnd, edited.scheduledEndEpochMillis)
        assertNull(database.supervisionPlanDao().getById(scheduled.planId))
        assertNull(database.supervisionPlanDao().getActivationReservation(scheduled.planId))
    }

    @Test
    fun 安排专注不会因设备时钟回拨让待办版本倒退() = runBlocking {
        val futureVersion = now.plusSeconds(2L * 3_600L).toEpochMilli()
        val original = todo("clock-rollback").copy(updatedAtEpochMillis = futureVersion)
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3L * 3_600L).toEpochMilli()
        val end = start + 30 * 60_000L

        assertTrue(
            planRepository.scheduleOneTimeFocusForTodo(
                todoId = original.id,
                startEpochMillis = start,
                endEpochMillis = end,
                nowEpochMillis = now.toEpochMilli()
            ) is OneTimeFocusScheduleResult.Success
        )

        assertEquals(
            futureVersion,
            requireNotNull(todoRepository.getTodoById(original.id)).updatedAtEpochMillis
        )
    }

    @Test
    fun 使用旧推荐版本不会覆盖待办最新排程() = runBlocking {
        val original = todo("stale-suggestion")
        todoRepository.saveTodo(original)
        val latestStart = now.plusSeconds(5L * 3_600L).toEpochMilli()
        val latestEnd = latestStart + 45 * 60_000L
        val latest = todoRepository.saveTodo(
            original.copy(
                title = "最新标题",
                scheduledStartEpochMillis = latestStart,
                scheduledEndEpochMillis = latestEnd,
                estimatedFocusMinutes = 45,
                updatedAtEpochMillis = original.updatedAtEpochMillis
            )
        )
        assertEquals(original.updatedAtEpochMillis + 1L, latest.updatedAtEpochMillis)

        val proposedStart = now.plusSeconds(3_600L).toEpochMilli()
        val proposedEnd = proposedStart + 30 * 60_000L
        val result = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            expectedTodoUpdatedAtEpochMillis = original.updatedAtEpochMillis,
            startEpochMillis = proposedStart,
            endEpochMillis = proposedEnd,
            nowEpochMillis = now.toEpochMilli()
        )

        assertEquals(
            OneTimeFocusScheduleResult.StaleTodo(
                todoId = original.id,
                expectedUpdatedAtEpochMillis = original.updatedAtEpochMillis,
                actualUpdatedAtEpochMillis = latest.updatedAtEpochMillis
            ),
            result
        )
        assertTrue(
            (planRepository.loadPlans() as PlanLoadResult.Success).plans.isEmpty()
        )
        assertNull(database.supervisionPlanDao().getActivationReservation(original.id))
        assertEquals(latest, todoRepository.getTodoById(original.id))
    }

    @Test
    fun 重复应用同一带版本建议保持幂等() = runBlocking {
        val original = todo("same-suggestion-twice")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = start + 30 * 60_000L

        val first = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            expectedTodoUpdatedAtEpochMillis = original.updatedAtEpochMillis,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.plusMillis(1L).toEpochMilli()
        )
        val second = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            expectedTodoUpdatedAtEpochMillis = original.updatedAtEpochMillis,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.plusMillis(2L).toEpochMilli()
        )

        assertTrue((first as OneTimeFocusScheduleResult.Success).wasCreated)
        assertEquals(
            OneTimeFocusScheduleResult.Success(first.planId, wasCreated = false),
            second
        )
        assertEquals(
            1,
            (planRepository.loadPlans() as PlanLoadResult.Success).plans.size
        )
    }

    @Test
    fun 待办版本到达最大值后编辑不会溢出() = runBlocking {
        val original = todo("max-version").copy(updatedAtEpochMillis = Long.MAX_VALUE)
        todoRepository.saveTodo(original)

        val edited = todoRepository.saveTodo(original.copy(title = "最大版本后的编辑"))

        assertEquals(Long.MAX_VALUE, edited.updatedAtEpochMillis)
        assertEquals("最大版本后的编辑", edited.title)
    }

    @Test
    fun 完成待办不会因业务时间较旧而让版本倒退() = runBlocking {
        val futureVersion = now.plusSeconds(3_600L).toEpochMilli()
        val original = todo("complete-clock-rollback").copy(
            updatedAtEpochMillis = futureVersion
        )
        todoRepository.saveTodo(original)

        val completed = requireNotNull(
            todoRepository.toggleTodoCompletion(
                id = original.id,
                isCompleted = true,
                completedAtEpochMillis = now.toEpochMilli()
            )
        ).todo

        assertEquals(futureVersion + 1L, completed.updatedAtEpochMillis)
        assertEquals(now.toEpochMilli(), completed.completedAtEpochMillis)
    }

    @Test
    fun 预约尚未生效时不会阻止待办专注安排() = runBlocking {
        val original = todo("reservation-after-window")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = start + 30 * 60_000L
        insertScheduledAllDayGlobal(
            id = "future-global",
            activationEpochMillis = end + 1_000L
        )

        val result = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        )

        assertTrue(result is OneTimeFocusScheduleResult.Success)
        assertEquals(
            2,
            (planRepository.loadPlans() as PlanLoadResult.Success).plans.size
        )
    }

    @Test
    fun 已生效预约周期计划与待办窗口重叠时拒绝安排() = runBlocking {
        val original = todo("reservation-overlap")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = start + 30 * 60_000L
        insertScheduledAllDayGlobal(
            id = "active-global",
            activationEpochMillis = start - 1_000L
        )

        val result = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        )

        assertTrue(result is OneTimeFocusScheduleResult.Conflicts)
        assertEquals(
            1,
            (planRepository.loadPlans() as PlanLoadResult.Success).plans.size
        )
        assertEquals(original, todoRepository.getTodoById(original.id))
    }

    @Test
    fun 同一分钟内的秒级窗口也能持久化为一次性专注() = runBlocking {
        val original = todo("sub-minute-window")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_610L).toEpochMilli()
        val end = start + 20_000L

        val result = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        )

        assertTrue(result is OneTimeFocusScheduleResult.Success)
        val stored = (planRepository.loadPlans() as PlanLoadResult.Success).plans.single()
        assertEquals(start, stored.oneTimeFocusWindow?.startEpochMillis)
        assertEquals(end, stored.oneTimeFocusWindow?.endEpochMillis)
        assertEquals(1L, (stored.policy as FocusCyclePolicy).lockDuration.toMinutes())
    }

    @Test
    fun 重新安排待办会删除旧的一次性计划而不留下孤儿预约() = runBlocking {
        val original = todo("replace-once")
        todoRepository.saveTodo(original)
        val firstStart = now.plusSeconds(3_600L).toEpochMilli()
        val firstEnd = firstStart + 30 * 60_000L
        val first = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = firstStart,
            endEpochMillis = firstEnd,
            nowEpochMillis = now.toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success

        val secondStart = now.plusSeconds(3L * 3_600L).toEpochMilli()
        val secondEnd = secondStart + 30 * 60_000L
        val second = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = secondStart,
            endEpochMillis = secondEnd,
            nowEpochMillis = now.toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success

        assertTrue(first.planId != second.planId)
        assertNull(database.supervisionPlanDao().getById(first.planId))
        assertNull(database.supervisionPlanDao().getActivationReservation(first.planId))
        assertNotNull(database.supervisionPlanDao().getById(second.planId))
        assertEquals(second.planId, todoRepository.getTodoById(original.id)?.associatedFocusPlanId)
    }

    @Test
    fun 完成或删除待办不会误删普通周期监督计划() = runBlocking {
        val periodicFocus = periodicPlan("periodic-focus", SupervisionPlanType.FOCUS)
        val global = periodicPlan("global", SupervisionPlanType.GLOBAL)
        database.supervisionPlanDao().replace(SupervisionPlanMapper.toRecord(periodicFocus))
        database.supervisionPlanDao().replace(SupervisionPlanMapper.toRecord(global))
        val focusTodo = todo("focus-todo").copy(associatedFocusPlanId = periodicFocus.id)
        val globalTodo = todo("global-todo").copy(associatedFocusPlanId = global.id)
        todoRepository.saveTodo(focusTodo)
        todoRepository.saveTodo(globalTodo)

        assertNotNull(
            todoRepository.toggleTodoCompletion(
                id = focusTodo.id,
                isCompleted = true,
                completedAtEpochMillis = now.plusSeconds(1L).toEpochMilli()
            )
        )
        todoRepository.deleteTodo(globalTodo.id)

        assertNotNull(database.supervisionPlanDao().getById(periodicFocus.id))
        assertNotNull(database.supervisionPlanDao().getById(global.id))
        assertNull(requireNotNull(todoRepository.getTodoById(focusTodo.id)).associatedFocusPlanId)
        assertNull(todoRepository.getTodoById(globalTodo.id))
    }

    @Test
    fun 从监督页删除计划会清空待办的全部专注关联字段() = runBlocking {
        val original = todo("delete-plan")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = now.plusSeconds(5_400L).toEpochMilli()
        val scheduled = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success
        val stored = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans
            .single { it.id == scheduled.planId }

        assertTrue(
            planRepository.delete(stored.id, stored.updatedAtEpochMillis) is PlanWriteResult.Success
        )

        val cleared = requireNotNull(todoRepository.getTodoById(original.id))
        assertNull(cleared.associatedFocusPlanId)
        assertNull(cleared.scheduledStartEpochMillis)
        assertNull(cleared.scheduledEndEpochMillis)
        assertNull(cleared.estimatedFocusMinutes)
        assertNull(database.supervisionPlanDao().getById(scheduled.planId))
    }

    @Test
    fun 取消一次性专注预约会同步清空待办排程关联() = runBlocking {
        val original = todo("cancel-once")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = now.plusSeconds(5_400L).toEpochMilli()
        val scheduled = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success
        val stored = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans
            .single { it.id == scheduled.planId }

        assertTrue(
            planRepository.cancelScheduledEnable(
                planId = stored.id,
                expectedUpdatedAtEpochMillis = stored.updatedAtEpochMillis,
                nowEpochMillis = now.plusSeconds(1L).toEpochMilli()
            ) is PlanWriteResult.Success
        )

        val cleared = requireNotNull(todoRepository.getTodoById(original.id))
        assertNull(cleared.associatedFocusPlanId)
        assertNull(cleared.scheduledStartEpochMillis)
        assertNull(cleared.scheduledEndEpochMillis)
        assertNull(cleared.estimatedFocusMinutes)
        assertNull(database.supervisionPlanDao().getActivationReservation(stored.id))

        val terminal = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans
            .single { it.id == stored.id }
        val enableResult = planRepository.setEnabled(
            planId = terminal.id,
            enabled = true,
            expectedUpdatedAtEpochMillis = terminal.updatedAtEpochMillis,
            updatedAtEpochMillis = terminal.updatedAtEpochMillis + 1L
        )
        val scheduleResult = planRepository.scheduleEnable(
            planId = terminal.id,
            enableAtEpochMillis = start,
            expectedUpdatedAtEpochMillis = terminal.updatedAtEpochMillis,
            nowEpochMillis = now.plusSeconds(2L).toEpochMilli()
        )
        assertEquals(
            "一次性专注已结束，请从待办重新安排",
            (enableResult as PlanWriteResult.InvalidInput).reason
        )
        assertEquals(
            "一次性专注已结束，请从待办重新安排",
            (scheduleResult as PlanWriteResult.InvalidInput).reason
        )
    }

    @Test
    fun 过期但尚未清理的一次性专注禁止重新启用或预约() = runBlocking {
        val original = todo("expired-before-reconcile")
        todoRepository.saveTodo(original)
        val scheduled = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = 1_000L,
            endEpochMillis = 2_000L,
            nowEpochMillis = 0L
        ) as OneTimeFocusScheduleResult.Success
        val terminal = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans.single { it.id == scheduled.planId }

        val enableResult = planRepository.setEnabled(
            planId = terminal.id,
            enabled = true,
            expectedUpdatedAtEpochMillis = terminal.updatedAtEpochMillis,
            updatedAtEpochMillis = terminal.updatedAtEpochMillis + 1L
        )
        val scheduleResult = planRepository.scheduleEnable(
            planId = terminal.id,
            enableAtEpochMillis = 3_000L,
            expectedUpdatedAtEpochMillis = terminal.updatedAtEpochMillis,
            nowEpochMillis = 2_001L
        )

        assertEquals(
            "一次性专注已结束，请从待办重新安排",
            (enableResult as PlanWriteResult.InvalidInput).reason
        )
        assertEquals(
            "一次性专注已结束，请从待办重新安排",
            (scheduleResult as PlanWriteResult.InvalidInput).reason
        )
    }

    @Test
    fun 一次性专注改为周期计划会清理旧预约和待办排程() = runBlocking {
        val original = todo("convert-once-to-periodic")
        todoRepository.saveTodo(original)
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = now.plusSeconds(5_400L).toEpochMilli()
        val scheduled = planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success
        val oneTime = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans
            .single { it.id == scheduled.planId }

        val periodic = oneTime.copy(
            enabled = false,
            scheduledEnableAtEpochMillis = null,
            oneTimeFocusWindow = null,
            updatedAtEpochMillis = oneTime.updatedAtEpochMillis + 1L
        )
        assertTrue(
            planRepository.save(
                periodic,
                expectedUpdatedAtEpochMillis = oneTime.updatedAtEpochMillis
            ) is PlanWriteResult.Success
        )

        val stored = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans
            .single { it.id == scheduled.planId }
        assertEquals(SupervisionPlanType.FOCUS, stored.type)
        assertNull(stored.oneTimeFocusWindow)
        assertNull(stored.scheduledEnableAtEpochMillis)
        assertNull(database.supervisionPlanDao().getActivationReservation(scheduled.planId))

        val cleared = requireNotNull(todoRepository.getTodoById(original.id))
        assertNull(cleared.associatedFocusPlanId)
        assertNull(cleared.scheduledStartEpochMillis)
        assertNull(cleared.scheduledEndEpochMillis)
        assertNull(cleared.estimatedFocusMinutes)
    }

    private fun todo(id: String): TodoItemEntity = TodoItemEntity(
        id = id,
        title = "整理报告",
        description = null,
        dueDateEpochMillis = null,
        priority = 2,
        isCompleted = false,
        completedAtEpochMillis = null,
        category = "工作",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = now.toEpochMilli(),
        updatedAtEpochMillis = now.toEpochMilli()
    )

    private fun periodicPlan(id: String, type: SupervisionPlanType): SupervisionPlan =
        SupervisionPlan(
            id = id,
            name = id,
            type = type,
            enabled = false,
            schedule = WeeklySchedule(
                zoneId = ZoneOffset.UTC,
                activeDays = setOf(DayOfWeek.MONDAY),
                ranges = listOf(DailyTimeRange(9 * 60, 10 * 60))
            ),
            policy = when (type) {
                SupervisionPlanType.FOCUS -> FocusCyclePolicy(
                    lockDuration = Duration.ofMinutes(30L),
                    playDuration = Duration.ofMinutes(5L)
                )
                SupervisionPlanType.GLOBAL -> GlobalCyclePolicy(
                    usageDuration = Duration.ofMinutes(30L),
                    lockDuration = Duration.ofMinutes(10L)
                )
                SupervisionPlanType.APP -> error("测试不创建 App 计划")
            },
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )

    private suspend fun insertScheduledAllDayGlobal(
        id: String,
        activationEpochMillis: Long
    ) {
        val plan = SupervisionPlan(
            id = id,
            name = id,
            type = SupervisionPlanType.GLOBAL,
            enabled = false,
            schedule = WeeklySchedule(
                zoneId = ZoneOffset.UTC,
                activeDays = setOf(now.atZone(ZoneOffset.UTC).dayOfWeek),
                ranges = listOf(DailyTimeRange(0, 1_440)),
                zoneMode = ScheduleZoneMode.FIXED
            ),
            policy = GlobalCyclePolicy(
                usageDuration = Duration.ofMinutes(30L),
                lockDuration = Duration.ofMinutes(10L)
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )
        database.supervisionPlanDao().replace(SupervisionPlanMapper.toRecord(plan))
        database.supervisionPlanDao().upsertActivationReservation(
            PlanActivationReservationEntity(plan.id, activationEpochMillis)
        )
    }
}
