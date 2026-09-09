package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryScheduleValidationDatabaseTest {
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
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 零时长和不完整排程不会写入数据库() = runBlocking {
        val invalidSchedules = listOf(
            Triple("zero-duration", 1_000L, 1_000L),
            Triple("missing-end", 1_000L, null),
            Triple("missing-start", null, 2_000L)
        )

        invalidSchedules.forEach { (id, start, end) ->
            try {
                repository.saveTodo(todo(id, start, end))
                fail("无效排程 $id 应被拒绝")
            } catch (_: IllegalArgumentException) {
                // 预期失败：仓储边界不得持久化日程模型无法表示的数据。
            }
            assertNull(repository.getTodoById(id))
        }
    }

    private fun todo(id: String, start: Long?, end: Long?) = TodoItemEntity(
        id = id,
        title = "排程校验",
        description = null,
        dueDateEpochMillis = null,
        priority = 0,
        isCompleted = false,
        completedAtEpochMillis = null,
        category = "默认",
        repeatRule = null,
        associatedFocusPlanId = null,
        supervisionLockEnabled = false,
        createdAtEpochMillis = 1L,
        scheduledStartEpochMillis = start,
        scheduledEndEpochMillis = end,
        estimatedFocusMinutes = 30
    )
}
