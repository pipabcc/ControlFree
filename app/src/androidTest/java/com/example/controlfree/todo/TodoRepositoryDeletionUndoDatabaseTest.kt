package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.supervision.persistence.OneTimeFocusScheduleResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryDeletionUndoDatabaseTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository
    private lateinit var planRepository: SupervisionPlanRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(database, Clock.fixed(now, ZoneOffset.UTC))
        planRepository = SupervisionPlanRepository.createForTest(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 删除并撤销待办会完整恢复子任务承诺实例和一次性专注() = runBlocking {
        val original = todo("todo-undo")
        repository.saveTodoWithCommitment(original, commitment())
        repository.saveSubtask(
            todoId = original.id,
            parentSubtaskId = null,
            title = "整理数据",
            sortOrder = 0,
            id = "subtask-undo",
            nowEpochMillis = now.toEpochMilli()
        )
        val start = now.plusSeconds(3_600L).toEpochMilli()
        val end = start + 30 * 60_000L
        assertTrue(
            planRepository.scheduleOneTimeFocusForTodo(
                todoId = original.id,
                startEpochMillis = start,
                endEpochMillis = end,
                nowEpochMillis = now.toEpochMilli()
            ) is OneTimeFocusScheduleResult.Success
        )

        val snapshot = requireNotNull(repository.deleteTodoForUndo(original.id))
        val policy = requireNotNull(snapshot.commitmentPolicy).policy
        val occurrence = snapshot.openCommitmentOccurrences.single()
        val plan = requireNotNull(snapshot.oneTimeFocusPlan)
        assertEquals(1, snapshot.subtasks.size)
        assertNull(repository.getTodoById(original.id))
        assertTrue(database.todoDao().getSubtasks(original.id).isEmpty())
        assertNull(repository.getCommitmentPolicy(CommitmentSourceType.TODO, original.id))
        val deletionHistory = requireNotNull(
            database.commitmentDao().getOccurrence(policy.id, occurrence.occurrenceKey)
        )
        assertEquals(snapshot.deletedAtEpochMillis, deletionHistory.satisfiedAtEpochMillis)
        assertNull(database.supervisionPlanDao().getById(plan.plan.planId))

        assertTrue(repository.restoreDeletedTodo(snapshot))
        assertEquals(snapshot.todo, repository.getTodoById(original.id))
        assertEquals(snapshot.subtasks, database.todoDao().getSubtasks(original.id))
        assertEquals(
            snapshot.commitmentPolicy,
            repository.getCommitmentPolicy(CommitmentSourceType.TODO, original.id)
        )
        assertEquals(
            occurrence,
            database.commitmentDao().getOccurrence(policy.id, occurrence.occurrenceKey)
        )
        assertEquals(plan, database.supervisionPlanDao().getById(plan.plan.planId))
    }

    @Test
    fun 恢复待办遇到专注计划晚期冲突会回滚全部写入() = runBlocking {
        val original = todo("todo-conflict")
        repository.saveTodoWithCommitment(original, commitment())
        repository.saveSubtask(
            todoId = original.id,
            parentSubtaskId = null,
            title = "冲突子任务",
            sortOrder = 0,
            id = "subtask-conflict",
            nowEpochMillis = now.toEpochMilli()
        )
        val start = now.plusSeconds(7_200L).toEpochMilli()
        val end = start + 30 * 60_000L
        planRepository.scheduleOneTimeFocusForTodo(
            todoId = original.id,
            startEpochMillis = start,
            endEpochMillis = end,
            nowEpochMillis = now.toEpochMilli()
        )
        val snapshot = requireNotNull(repository.deleteTodoForUndo(original.id))
        val plan = requireNotNull(snapshot.oneTimeFocusPlan)
        val deletionHistory = snapshot.openCommitmentOccurrences.associate { occurrence ->
            occurrence.id to requireNotNull(
                database.commitmentDao().getOccurrence(
                    occurrence.policyId,
                    occurrence.occurrenceKey
                )
            )
        }
        database.supervisionPlanDao().upsertPlan(plan.plan.copy(name = "新写入的同编号计划"))

        assertFalse(repository.restoreDeletedTodo(snapshot))
        assertNull(repository.getTodoById(original.id))
        assertTrue(database.todoDao().getSubtasks(original.id).isEmpty())
        assertNull(repository.getCommitmentPolicy(CommitmentSourceType.TODO, original.id))
        snapshot.openCommitmentOccurrences.forEach {
            assertEquals(
                deletionHistory.getValue(it.id),
                database.commitmentDao().getOccurrence(it.policyId, it.occurrenceKey)
            )
        }
        assertEquals(
            "新写入的同编号计划",
            database.supervisionPlanDao().getById(plan.plan.planId)?.plan?.name
        )
    }

    private fun todo(id: String) = TodoItemEntity(
        id = id,
        title = "整理报告",
        description = null,
        dueDateEpochMillis = now.plusSeconds(10_800L).toEpochMilli(),
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

    private fun commitment() = CommitmentPolicyInput(
        enabled = true,
        localDeadlineMinute = null,
        graceMinutes = 5,
        maxLockMinutes = 30,
        zoneId = ZoneOffset.UTC.id,
        blockedPackages = setOf("com.example.video", "com.example.social")
    )
}
