package com.example.controlfree.supervision.commitment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentLockEvaluatorTest {
    private val policy = ActiveCommitment(
        policyId = "policy-1",
        sourceType = CommitmentSourceType.TODO,
        sourceId = "todo-1",
        displayName = "完成报告",
        enabled = true,
        deadlineEpochMillis = 1_000L,
        graceMinutes = 1,
        satisfiedAtEpochMillis = null,
        blockedPackages = setOf("video.app")
    )

    @Test
    fun `截止加宽限前放行`() {
        val decision = evaluate(policy, now = 60_999L)
        assertFalse(decision.shouldBlock)
    }

    @Test
    fun `逾期后只阻止用户选择的应用`() {
        val decision = evaluate(policy, now = 61_000L)
        assertTrue(decision.shouldBlock)
        assertEquals("policy-1", decision.policyId)
    }

    @Test
    fun `完成后立即放行`() {
        val decision = evaluate(policy.copy(satisfiedAtEpochMillis = 60_000L), now = 61_000L)
        assertFalse(decision.shouldBlock)
    }

    @Test
    fun `受保护应用和不可用观察永远不阻止`() {
        val protected = CommitmentLockEvaluator.evaluate(
            commitments = listOf(policy),
            foregroundPackage = "video.app",
            isObservationAvailable = true,
            isInteractive = true,
            protectedPackages = setOf("video.app"),
            nowEpochMillis = 61_000L
        )
        val unavailable = CommitmentLockEvaluator.evaluate(
            commitments = listOf(policy),
            foregroundPackage = "video.app",
            isObservationAvailable = false,
            isInteractive = true,
            protectedPackages = emptySet(),
            nowEpochMillis = 61_000L
        )
        assertFalse(protected.shouldBlock)
        assertFalse(unavailable.shouldBlock)
    }

    private fun evaluate(item: ActiveCommitment, now: Long): CommitmentLockDecision =
        CommitmentLockEvaluator.evaluate(
            commitments = listOf(item),
            foregroundPackage = "video.app",
            isObservationAvailable = true,
            isInteractive = true,
            protectedPackages = emptySet(),
            nowEpochMillis = now
        )
}
