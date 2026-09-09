package com.example.controlfree.supervision.commitment

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentRuntimeCoordinatorPolicyTest {
    @Test
    fun `活动窗口启动服务且空闲窗口只通知已有服务`() {
        assertEquals(
            CommitmentRuntimeAction.START_OR_RECONCILE,
            decideCommitmentRuntimeAction(hasActiveWindow = true, serviceRunning = false)
        )
        assertEquals(
            CommitmentRuntimeAction.RECONCILE_RUNNING,
            decideCommitmentRuntimeAction(hasActiveWindow = false, serviceRunning = true)
        )
        assertEquals(
            CommitmentRuntimeAction.NONE,
            decideCommitmentRuntimeAction(hasActiveWindow = false, serviceRunning = false)
        )
    }
}
