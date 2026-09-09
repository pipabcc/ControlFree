package com.example.controlfree.growth

import com.example.controlfree.todo.ProductivityRewardEventEntity
import com.example.controlfree.todo.ProductivityRewardType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductivityRewardOutboxConsumerTest {
    @Test
    fun `进程级启动器复用活动任务并在任务结束后允许重启`() {
        var launchCount = 0
        val firstJob = Job()
        val secondJob = Job()
        val jobs = ArrayDeque(listOf(firstJob, secondJob))
        val launcher = RewardOutboxConsumerLauncher {
            launchCount++
            jobs.removeFirst()
        }

        assertSame(firstJob, launcher.start())
        assertSame(firstJob, launcher.start())
        assertEquals(1, launchCount)

        firstJob.cancel()
        assertSame(secondJob, launcher.start())
        assertEquals(2, launchCount)

        secondJob.cancel()
    }

    @Test
    fun `三类 outbox 事件映射为确认后的奖励规则`() {
        val todo = event("todo", ProductivityRewardType.TODO_COMPLETED, 1)
            .toProductivityRewardRequest()
        val habit = event("habit", ProductivityRewardType.HABIT_TARGET_REACHED, 2)
            .toProductivityRewardRequest()
        val note = event("note", ProductivityRewardType.QUICK_NOTE_CAPTURED, 1)
            .toProductivityRewardRequest()

        assertEquals(GrowthLedgerReason.TODO_COMPLETED, todo?.reason)
        assertEquals(1, todo?.rewardPoints)
        assertEquals(GrowthLedgerReason.HABIT_CHECKED_IN, habit?.reason)
        assertEquals(2, habit?.rewardPoints)
        assertEquals(GrowthLedgerReason.QUICK_NOTE_CAPTURED, note?.reason)
        assertEquals(1, note?.rewardPoints)
    }

    @Test
    fun `拒绝奖励值或类型被篡改的事件`() {
        assertNull(
            event("wrong-points", ProductivityRewardType.HABIT_TARGET_REACHED, 1)
                .toProductivityRewardRequest()
        )
        assertNull(
            event("wrong-type", ProductivityRewardType.TODO_COMPLETED, 1)
                .copy(rewardType = "UNKNOWN")
                .toProductivityRewardRequest()
        )
    }

    @Test
    fun `入账失败时不会提前确认 outbox`() = runTest {
        var markCalls = 0
        val processor = ProductivityRewardOutboxProcessor(
            award = { error("成长账本暂不可用") },
            markProcessed = { _, _ ->
                markCalls++
                true
            },
            nowEpochMillis = { 200L }
        )

        val result = processor.process(
            listOf(event("todo", ProductivityRewardType.TODO_COMPLETED, 1))
        )

        assertEquals(0, result.processedCount)
        assertEquals(1, result.failedCount)
        assertEquals(0, markCalls)
    }

    @Test
    fun `确认失败后用同一 sourceKey 重试且最终可完成`() = runTest {
        val awardedSourceKeys = mutableListOf<String>()
        var markAttempts = 0
        val processor = ProductivityRewardOutboxProcessor(
            award = { awardedSourceKeys += it.sourceKey },
            markProcessed = { _, processedAt ->
                assertEquals(300L, processedAt)
                markAttempts++
                if (markAttempts == 1) error("数据库暂不可写")
                true
            },
            nowEpochMillis = { 300L }
        )
        val pending = listOf(event("note", ProductivityRewardType.QUICK_NOTE_CAPTURED, 1))

        val first = processor.process(pending)
        val second = processor.process(pending)

        assertEquals(1, first.failedCount)
        assertEquals(1, second.processedCount)
        assertEquals(listOf("source:note", "source:note"), awardedSourceKeys)
        assertTrue(awardedSourceKeys.distinct().size == 1)
    }

    private fun event(
        id: String,
        type: ProductivityRewardType,
        points: Int
    ) = ProductivityRewardEventEntity(
        id = id,
        sourceKey = "source:$id",
        rewardType = type.storedValue,
        subjectId = id,
        balancePoints = points,
        experiencePoints = points,
        localDate = "2026-07-22",
        createdAtEpochMillis = 100L,
        processedAtEpochMillis = null
    )
}
