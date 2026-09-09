package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryRewardDatabaseTest {
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(
            database,
            Clock.fixed(
                Instant.parse("2026-07-22T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 闪念每日只生成五条成长奖励事件() = runBlocking {
        repeat(6) { index -> repository.addQuickNote("第 ${index + 1} 条闪念") }

        val rewards = repository.getPendingRewardEvents()
            .filter { it.rewardType == ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue }

        assertEquals(ProductivityRewardPolicy.MAX_REWARDED_QUICK_NOTES_PER_DAY, rewards.size)
        assertEquals(
            List(ProductivityRewardPolicy.MAX_REWARDED_QUICK_NOTES_PER_DAY) {
                ProductivityRewardPolicy.QUICK_NOTE_POINTS
            },
            rewards.map(ProductivityRewardEventEntity::balancePoints)
        )
        assertEquals(setOf("2026-07-22"), rewards.map(ProductivityRewardEventEntity::localDate).toSet())
    }

    @Test
    fun 闪念奖励额度按本地午夜重置() = runBlocking {
        val beforeMidnight = Instant.parse("2026-07-22T15:59:00Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-07-22T16:00:00Z").toEpochMilli()
        repeat(6) { index ->
            repository.addQuickNote("午夜前 $index", nowEpochMillis = beforeMidnight + index)
            repository.addQuickNote("午夜后 $index", nowEpochMillis = afterMidnight + index)
        }

        val groupedRewards = repository.getPendingRewardEvents()
            .filter { it.rewardType == ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue }
            .groupingBy(ProductivityRewardEventEntity::localDate)
            .eachCount()

        assertEquals(mapOf("2026-07-22" to 5, "2026-07-23" to 5), groupedRewards)
    }

    @Test
    fun 并发记录闪念仍严格限制为本地自然日五次奖励() = runBlocking {
        coroutineScope {
            List(20) { index ->
                async(Dispatchers.Default) { repository.addQuickNote("并发闪念 $index") }
            }.awaitAll()
        }

        val rewards = repository.getPendingRewardEvents()
            .filter { it.rewardType == ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue }

        assertEquals(5, rewards.size)
        assertEquals(5, rewards.sumOf(ProductivityRewardEventEntity::balancePoints))
    }

    @Test
    fun 已处理的闪念奖励仍占用当日额度() = runBlocking {
        repeat(5) { index -> repository.addQuickNote("已处理闪念 $index") }
        repository.getPendingRewardEvents()
            .filter { it.rewardType == ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue }
            .forEach { repository.markRewardEventProcessed(it.id) }

        repository.addQuickNote("当日第六条闪念")

        assertEquals(
            5,
            database.productivityEventDao().countRewardEvents(
                ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue,
                "2026-07-22"
            )
        )
        assertEquals(
            0,
            repository.getPendingRewardEvents().count {
                it.rewardType == ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue
            }
        )
    }

    @Test
    fun 普通待办完成只奖励一次且固定为一点() = runBlocking {
        repository.saveTodo(
            TodoItemEntity(
                id = "todo-reward",
                title = "完成测试任务",
                description = null,
                dueDateEpochMillis = null,
                priority = 0,
                isCompleted = false,
                completedAtEpochMillis = null,
                category = "测试",
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = Instant.parse("2026-07-22T04:00:00Z").toEpochMilli()
            )
        )

        repository.toggleTodoCompletion("todo-reward", true)
        repository.toggleTodoCompletion("todo-reward", true)
        repository.toggleTodoCompletion("todo-reward", false)
        repository.toggleTodoCompletion("todo-reward", true)

        val rewards = repository.getPendingRewardEvents()
            .filter { it.rewardType == ProductivityRewardType.TODO_COMPLETED.storedValue }
        assertEquals(1, rewards.size)
        assertEquals(1, rewards.single().balancePoints)
        assertEquals(1, rewards.single().experiencePoints)
    }

    @Test
    fun 并发重复完成同一待办只奖励一点() = runBlocking {
        repository.saveTodo(
            TodoItemEntity(
                id = "todo-concurrent-reward",
                title = "并发完成测试",
                description = null,
                dueDateEpochMillis = null,
                priority = 0,
                isCompleted = false,
                completedAtEpochMillis = null,
                category = "测试",
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = Instant.parse("2026-07-22T04:00:00Z").toEpochMilli()
            )
        )

        coroutineScope {
            List(12) {
                async(Dispatchers.Default) {
                    repository.toggleTodoCompletion("todo-concurrent-reward", true)
                }
            }.awaitAll()
        }

        val rewards = repository.getPendingRewardEvents().filter {
            it.rewardType == ProductivityRewardType.TODO_COMPLETED.storedValue
        }
        assertEquals(1, rewards.size)
        assertEquals(1, rewards.single().balancePoints)
    }

    @Test
    fun 习惯当日首次达标奖励两点且撤销后重达标不重复() = runBlocking {
        repository.saveHabit(
            HabitItemEntity(
                id = "habit-reward",
                name = "阅读两次",
                iconRes = "book",
                colorHex = "#21C76A",
                frequencyType = HabitFrequencyType.DAILY.storedValue,
                targetCountPerDay = 2,
                currentStreak = 0,
                bestStreak = 0,
                isArchived = false,
                createdAtEpochMillis = Instant.parse("2026-07-22T04:00:00Z").toEpochMilli(),
                startDate = "2026-07-22"
            )
        )

        repository.checkInHabit("habit-reward", "2026-07-22")
        repository.checkInHabit("habit-reward", "2026-07-22")
        repository.decrementHabitCheckIn("habit-reward", "2026-07-22")
        repository.checkInHabit("habit-reward", "2026-07-22")

        val rewards = repository.getPendingRewardEvents()
            .filter { it.rewardType == ProductivityRewardType.HABIT_TARGET_REACHED.storedValue }
        assertEquals(1, rewards.size)
        assertEquals(2, rewards.single().balancePoints)
        assertEquals(2, rewards.single().experiencePoints)
    }

    @Test
    fun 并发重复打卡同一习惯只在首次达标时奖励两点() = runBlocking {
        repository.saveHabit(
            HabitItemEntity(
                id = "habit-concurrent-reward",
                name = "并发打卡",
                iconRes = "check",
                colorHex = "#21C76A",
                frequencyType = HabitFrequencyType.DAILY.storedValue,
                targetCountPerDay = 1,
                currentStreak = 0,
                bestStreak = 0,
                isArchived = false,
                createdAtEpochMillis = Instant.parse("2026-07-22T04:00:00Z").toEpochMilli(),
                startDate = "2026-07-22"
            )
        )

        coroutineScope {
            List(12) {
                async(Dispatchers.Default) {
                    repository.checkInHabit("habit-concurrent-reward", "2026-07-22")
                }
            }.awaitAll()
        }

        val rewards = repository.getPendingRewardEvents().filter {
            it.rewardType == ProductivityRewardType.HABIT_TARGET_REACHED.storedValue
        }
        assertEquals(1, rewards.size)
        assertEquals(2, rewards.single().balancePoints)
    }
}
