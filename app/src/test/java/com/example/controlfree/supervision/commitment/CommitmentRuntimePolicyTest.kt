package com.example.controlfree.supervision.commitment

import com.example.controlfree.todo.CommitmentBlockedAppEntity
import com.example.controlfree.todo.CommitmentOccurrenceEntity
import com.example.controlfree.todo.CommitmentPolicyEntity
import com.example.controlfree.todo.CommitmentPolicyWithApps
import com.example.controlfree.todo.CommitmentRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentRuntimePolicyTest {
    @Test
    fun `截止加宽限后只阻止显式绑定包`() {
        val snapshot = snapshot()

        assertNull(evaluate(snapshot, "video.app", now = 159_999L))
        assertNull(evaluate(snapshot, "reader.app", now = 160_000L))
        assertEquals(
            "occurrence-1",
            evaluate(snapshot, "video.app", now = 160_000L)?.occurrenceId
        )
    }

    @Test
    fun `习惯达标 occurrence 立即解除限制`() {
        val activeHabit = snapshot(sourceType = "HABIT")
        val satisfiedHabit = activeHabit.copy(
            openOccurrences = activeHabit.openOccurrences.map {
                it.copy(satisfiedAtEpochMillis = 160_001L)
            }
        )

        assertEquals(
            "occurrence-1",
            evaluate(activeHabit, "video.app", now = 160_000L)?.occurrenceId
        )
        assertNull(evaluate(satisfiedHabit, "video.app", now = 160_001L))
        assertNull(
            CommitmentRuntimePolicy.nextBoundaryEpochMillis(
                satisfiedHabit,
                nowEpochMillis = 160_001L
            )
        )
    }

    @Test
    fun `完成关闭或删除解除限制但固定时长不会自动放行`() {
        val completed = snapshot().copy(openOccurrences = emptyList())
        val staleSatisfied = snapshot().copy(
            openOccurrences = snapshot().openOccurrences.map {
                it.copy(satisfiedAtEpochMillis = 170_000L)
            }
        )
        val disabled = snapshot(enabled = false)
        val deleted = snapshot().copy(policies = emptyList())

        assertNull(evaluate(completed, "video.app", now = 160_000L))
        assertNull(evaluate(staleSatisfied, "video.app", now = 160_000L))
        assertNull(evaluate(disabled, "video.app", now = 160_000L))
        assertNull(evaluate(deleted, "video.app", now = 160_000L))
        assertEquals(
            "occurrence-1",
            evaluate(snapshot(), "video.app", now = 220_000L)?.occurrenceId
        )
    }

    @Test
    fun `系统保护状态未知与电话期间始终失败开放`() {
        val snapshot = snapshot()
        assertNull(
            CommitmentRuntimePolicy.evaluate(
                snapshot,
                "video.app",
                isObservationAvailable = true,
                isInteractive = true,
                isCallActive = false,
                isProtectedPackage = null,
                nowEpochMillis = 160_000L
            )
        )
        assertNull(
            CommitmentRuntimePolicy.evaluate(
                snapshot,
                "video.app",
                isObservationAvailable = true,
                isInteractive = true,
                isCallActive = false,
                isProtectedPackage = true,
                nowEpochMillis = 160_000L
            )
        )
        assertNull(
            CommitmentRuntimePolicy.evaluate(
                snapshot,
                "video.app",
                isObservationAvailable = true,
                isInteractive = true,
                isCallActive = true,
                isProtectedPackage = false,
                nowEpochMillis = 160_000L
            )
        )
        assertNull(
            CommitmentRuntimePolicy.evaluate(
                snapshot,
                "video.app",
                isObservationAvailable = false,
                isInteractive = true,
                isCallActive = false,
                isProtectedPackage = false,
                nowEpochMillis = 160_000L
            )
        )
    }

    @Test
    fun `边界计算覆盖启动和失效并忽略无效策略`() {
        val snapshot = snapshot()
        assertEquals(160_000L, CommitmentRuntimePolicy.nextBoundaryEpochMillis(snapshot, 0L))
        assertFalse(CommitmentRuntimePolicy.hasActiveWindow(snapshot, 159_999L))
        assertTrue(CommitmentRuntimePolicy.hasActiveWindow(snapshot, 160_000L))
        assertEquals(
            "occurrence-1",
            evaluate(snapshot, "video.app", now = 219_999L)?.occurrenceId
        )
        assertNull(CommitmentRuntimePolicy.nextBoundaryEpochMillis(snapshot, 160_000L))
        assertTrue(CommitmentRuntimePolicy.hasActiveWindow(snapshot, 220_000L))
        assertEquals(
            "occurrence-1",
            evaluate(snapshot, "video.app", now = 220_000L)?.occurrenceId
        )
        assertNull(CommitmentRuntimePolicy.nextBoundaryEpochMillis(snapshot, 220_000L))
        assertNull(
            CommitmentRuntimePolicy.nextBoundaryEpochMillis(snapshot(enabled = false), 0L)
        )
    }

    @Test
    fun `已持久激活的承诺在墙钟回拨后仍保持限制`() {
        val activated = snapshot().copy(
            openOccurrences = snapshot().openOccurrences.map { occurrence ->
                occurrence.copy(activatedAtEpochMillis = 160_000L)
            }
        )

        assertTrue(CommitmentRuntimePolicy.hasActiveWindow(activated, 10_000L))
        assertTrue(
            CommitmentRuntimePolicy.isActiveBlockedPackage(
                activated,
                "video.app",
                10_000L
            )
        )
        assertEquals(
            "occurrence-1",
            evaluate(activated, "video.app", now = 10_000L)?.occurrenceId
        )
        assertNull(CommitmentRuntimePolicy.nextBoundaryEpochMillis(activated, 10_000L))
    }

    @Test
    fun `饱和到最大墙钟的无效启动边界不会注册闹钟`() {
        val saturated = snapshot().copy(
            openOccurrences = snapshot().openOccurrences.map { occurrence ->
                occurrence.copy(deadlineEpochMillis = Long.MAX_VALUE - 30_000L)
            }
        )

        assertFalse(CommitmentRuntimePolicy.hasActiveWindow(saturated, Long.MAX_VALUE - 1L))
        assertNull(
            CommitmentRuntimePolicy.nextBoundaryEpochMillis(
                saturated,
                Long.MAX_VALUE - 120_000L
            )
        )
    }

    private fun evaluate(
        snapshot: CommitmentRuntimeSnapshot,
        packageName: String,
        now: Long
    ) = CommitmentRuntimePolicy.evaluate(
        snapshot = snapshot,
        foregroundPackage = packageName,
        isObservationAvailable = true,
        isInteractive = true,
        isCallActive = false,
        isProtectedPackage = false,
        nowEpochMillis = now
    )

    private fun snapshot(
        enabled: Boolean = true,
        sourceType: String = "TODO"
    ): CommitmentRuntimeSnapshot {
        val policy = CommitmentPolicyEntity(
            id = "policy-1",
            sourceType = sourceType,
            sourceId = "todo-1",
            enabled = enabled,
            localDeadlineMinute = null,
            graceMinutes = 1,
            maxLockMinutes = 1,
            zoneId = "Asia/Shanghai",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )
        return CommitmentRuntimeSnapshot(
            policies = listOf(
                CommitmentPolicyWithApps(
                    policy = policy,
                    blockedApps = listOf(
                        CommitmentBlockedAppEntity(policy.id, "video.app")
                    )
                )
            ),
            openOccurrences = listOf(
                CommitmentOccurrenceEntity(
                    id = "occurrence-1",
                    policyId = policy.id,
                    occurrenceKey = "todo-1",
                    deadlineEpochMillis = 100_000L,
                    expiresAtEpochMillis = 220_000L,
                    activatedAtEpochMillis = null,
                    satisfiedAtEpochMillis = null,
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L
                )
            )
        )
    }
}
