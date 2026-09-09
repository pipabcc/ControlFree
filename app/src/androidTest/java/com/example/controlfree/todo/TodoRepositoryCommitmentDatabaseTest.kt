package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryCommitmentDatabaseTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val fixedInstant = Instant.parse("2026-07-22T04:00:00Z")
    private val nowEpochMillis = fixedInstant.toEpochMilli()
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(
            database = database,
            clock = Clock.fixed(fixedInstant, zoneId)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 取消待办完成后重新开放原防拖延实例() = runBlocking {
        val todo = todo(id = "todo-reopen", dueAt = nowEpochMillis + 60_000L)
        repository.saveTodoWithCommitment(todo, todoCommitment())
        val relation = requireNotNull(
            repository.getCommitmentPolicy(CommitmentSourceType.TODO, todo.id)
        )
        val initial = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, todo.id)
        )
        repository.markCommitmentOccurrenceActivated(initial.id, nowEpochMillis)

        repository.toggleTodoCompletion(
            id = todo.id,
            isCompleted = true,
            completedAtEpochMillis = nowEpochMillis + 120_000L
        )
        val satisfied = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, todo.id)
        )
        assertNotNull(satisfied.satisfiedAtEpochMillis)
        assertFalse(database.commitmentDao().getOpenOccurrences().any { it.id == initial.id })

        repository.toggleTodoCompletion(
            id = todo.id,
            isCompleted = false,
            completedAtEpochMillis = nowEpochMillis + 180_000L
        )
        val reopened = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, todo.id)
        )
        assertNull(reopened.satisfiedAtEpochMillis)
        assertNull(reopened.activatedAtEpochMillis)
        assertTrue(database.commitmentDao().getOpenOccurrences().any { it.id == initial.id })
    }

    @Test
    fun 撤销习惯达标后重新开放当日防拖延实例() = runBlocking {
        val today = LocalDate.of(2026, 7, 22)
        val habit = habit(id = "habit-reopen", startDate = today)
        repository.saveHabitWithCommitment(habit, habitCommitment())
        val relation = requireNotNull(
            repository.getCommitmentPolicy(CommitmentSourceType.HABIT, habit.id)
        )
        val initial = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, today.toString())
        )
        repository.markCommitmentOccurrenceActivated(initial.id, nowEpochMillis)

        val checkIn = repository.checkInHabit(
            habitId = habit.id,
            dateStr = today.toString(),
            nowEpochMillis = nowEpochMillis
        )
        assertTrue(requireNotNull(checkIn).targetReached)
        val satisfied = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, today.toString())
        )
        assertNotNull(satisfied.satisfiedAtEpochMillis)

        repository.decrementHabitCheckIn(
            habitId = habit.id,
            dateStr = today.toString(),
            nowEpochMillis = nowEpochMillis + 60_000L
        )
        val reopened = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, today.toString())
        )
        assertNull(reopened.satisfiedAtEpochMillis)
        assertNull(reopened.activatedAtEpochMillis)
        assertTrue(database.commitmentDao().getOpenOccurrences().any { it.id == initial.id })
    }

    @Test
    fun 重复待办新实例继承防拖延策略和应用范围() = runBlocking {
        val todo = todo(
            id = "todo-recurring",
            dueAt = nowEpochMillis + 60_000L,
            recurrenceType = TodoRecurrenceType.DAILY
        )
        val commitment = todoCommitment()
        repository.saveTodoWithCommitment(todo, commitment)

        val completion = requireNotNull(
            repository.toggleTodoCompletion(
                id = todo.id,
                isCompleted = true,
                completedAtEpochMillis = nowEpochMillis + 120_000L
            )
        )
        val nextTodo = requireNotNull(completion.nextRecurringTodo)
        val inherited = requireNotNull(
            repository.getCommitmentPolicy(CommitmentSourceType.TODO, nextTodo.id)
        )

        assertEquals(commitment.graceMinutes, inherited.policy.graceMinutes)
        assertEquals(commitment.maxLockMinutes, inherited.policy.maxLockMinutes)
        assertEquals(commitment.zoneId, inherited.policy.zoneId)
        assertEquals(commitment.blockedPackages, inherited.blockedApps.mapTo(linkedSetOf()) { it.packageName })
        assertTrue(inherited.policy.enabled)
        assertTrue(nextTodo.supervisionLockEnabled)
        assertTrue(
            database.commitmentDao().getOpenOccurrences().any { occurrence ->
                occurrence.policyId == inherited.policy.id &&
                    occurrence.occurrenceKey == "${nextTodo.recurrenceSeriesId}:${nextTodo.recurrenceSequence}"
            }
        )
    }

    @Test
    fun 习惯校准预建下一计划并在跨日截止后持久激活() = runBlocking {
        val today = LocalDate.of(2026, 7, 22)
        val tomorrow = today.plusDays(1)
        val habit = habit(id = "habit-cross-day", startDate = today)
        repository.saveHabitWithCommitment(habit, habitCommitment())
        repository.checkInHabit(
            habitId = habit.id,
            dateStr = today.toString(),
            nowEpochMillis = nowEpochMillis
        )

        repository.reconcileCommitmentOccurrences(nowEpochMillis)
        val relation = requireNotNull(
            repository.getCommitmentPolicy(CommitmentSourceType.HABIT, habit.id)
        )
        val future = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, tomorrow.toString())
        )
        assertNull(future.activatedAtEpochMillis)
        assertNull(future.satisfiedAtEpochMillis)

        val afterTomorrowDeadline = tomorrow.atTime(10, 6)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        repository.reconcileCommitmentOccurrences(afterTomorrowDeadline)

        val activated = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, tomorrow.toString())
        )
        assertNotNull(activated.activatedAtEpochMillis)
        assertTrue(
            database.commitmentDao().getOpenOccurrences().any { occurrence ->
                occurrence.policyId == relation.policy.id &&
                    occurrence.occurrenceKey == tomorrow.plusDays(1).toString()
            }
        )
    }

    @Test
    fun 修改习惯计划会清理不再匹配的未来防拖延实例() = runBlocking {
        val today = LocalDate.of(2026, 7, 22)
        val habit = habit(id = "habit-reschedule", startDate = today)
        repository.saveHabitWithCommitment(habit, habitCommitment())
        repository.checkInHabit(habit.id, today.toString(), nowEpochMillis = nowEpochMillis)
        repository.reconcileCommitmentOccurrences(nowEpochMillis)

        repository.saveHabitWithCommitment(
            habit.copy(
                frequencyType = HabitFrequencyType.SPECIFIC_WEEKDAYS.storedValue,
                weekdaysMask = 1 shl 6,
                updatedAtEpochMillis = nowEpochMillis + 1L
            ),
            habitCommitment()
        )
        repository.reconcileCommitmentOccurrences(nowEpochMillis + 1L)

        val relation = requireNotNull(
            repository.getCommitmentPolicy(CommitmentSourceType.HABIT, habit.id)
        )
        assertNull(
            database.commitmentDao().getOccurrence(
                relation.policy.id,
                today.plusDays(1).toString()
            )
        )
        assertNotNull(
            database.commitmentDao().getOccurrence(
                relation.policy.id,
                LocalDate.of(2026, 7, 26).toString()
            )
        )
    }

    @Test
    fun 重复校准在业务状态未变化时不更新防拖延实例() = runBlocking {
        val todo = todo(id = "todo-stable-reconcile", dueAt = nowEpochMillis + 60_000L)
        repository.saveTodoWithCommitment(todo, todoCommitment())
        val relation = requireNotNull(
            repository.getCommitmentPolicy(CommitmentSourceType.TODO, todo.id)
        )
        val initial = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, todo.id)
        )

        val changed = repository.reconcileCommitmentOccurrences(nowEpochMillis + 30_000L)
        val afterReconcile = requireNotNull(
            database.commitmentDao().getOccurrence(relation.policy.id, todo.id)
        )

        assertEquals(0, changed)
        assertEquals(initial.updatedAtEpochMillis, afterReconcile.updatedAtEpochMillis)
        assertEquals(initial, afterReconcile)
    }

    @Test
    fun 保存待办时事务化替换子任务并保留层级() = runBlocking {
        val todo = todo(id = "todo-subtasks", dueAt = nowEpochMillis + 60_000L)
        val first = TodoSubtaskEntity(
            id = "subtask-first",
            todoId = todo.id,
            parentSubtaskId = null,
            title = "第一项",
            isCompleted = false,
            completedAtEpochMillis = null,
            sortOrder = 0,
            createdAtEpochMillis = nowEpochMillis
        )
        repository.saveTodoWithCommitment(todo, todoCommitment(), listOf(first))

        val child = TodoSubtaskEntity(
            id = "subtask-child",
            todoId = todo.id,
            parentSubtaskId = first.id,
            title = "下级事项",
            isCompleted = true,
            completedAtEpochMillis = nowEpochMillis,
            sortOrder = 1,
            createdAtEpochMillis = nowEpochMillis
        )
        repository.saveTodoWithCommitment(todo, todoCommitment(), listOf(first, child))

        val saved = database.todoDao().getSubtasks(todo.id)
        assertEquals(listOf(first.id, child.id), saved.map(TodoSubtaskEntity::id))
        assertEquals(first.id, saved.last().parentSubtaskId)
        assertTrue(saved.last().isCompleted)
    }

    private fun todo(
        id: String,
        dueAt: Long,
        recurrenceType: TodoRecurrenceType = TodoRecurrenceType.NONE
    ) = TodoItemEntity(
        id = id,
        title = "测试待办",
        description = null,
        dueDateEpochMillis = dueAt,
        priority = 2,
        isCompleted = false,
        completedAtEpochMillis = null,
        category = "测试",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = nowEpochMillis,
        recurrenceType = recurrenceType.storedValue,
        recurrenceInterval = 1
    )

    private fun habit(id: String, startDate: LocalDate) = HabitItemEntity(
        id = id,
        name = "测试习惯",
        iconRes = "check",
        colorHex = "#2196F3",
        frequencyType = HabitFrequencyType.DAILY.storedValue,
        targetCountPerDay = 1,
        currentStreak = 0,
        bestStreak = 0,
        isArchived = false,
        createdAtEpochMillis = nowEpochMillis,
        startDate = startDate.toString()
    )

    private fun todoCommitment() = CommitmentPolicyInput(
        enabled = true,
        localDeadlineMinute = null,
        graceMinutes = 5,
        maxLockMinutes = 30,
        zoneId = zoneId.id,
        blockedPackages = setOf("com.example.video", "com.example.social")
    )

    private fun habitCommitment() = CommitmentPolicyInput(
        enabled = true,
        localDeadlineMinute = 10 * 60,
        graceMinutes = 5,
        maxLockMinutes = 30,
        zoneId = zoneId.id,
        blockedPackages = setOf("com.example.video")
    )
}
