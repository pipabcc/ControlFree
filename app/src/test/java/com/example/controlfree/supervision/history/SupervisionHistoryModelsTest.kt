package com.example.controlfree.supervision.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionHistoryModelsTest {
    @Test
    fun `活动记录使用运行槽位而结束记录必须释放槽位`() {
        val active = record(runtimeSlot = GLOBAL_RUNTIME_SLOT, endedAt = null, reason = null)
        val ended = record(
            runtimeSlot = null,
            endedAt = 2_000L,
            reason = SupervisionSessionEndReason.CANCELLED
        )

        assertTrue(active.isActive)
        assertEquals(1_000L, active.effectiveDurationMillis(2_000L))
        assertEquals(1_000L, ended.effectiveDurationMillis(Long.MAX_VALUE))
    }

    @Test
    fun `会话类型与运行槽位或目标不一致时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(runtimeSlot = appRuntimeSlot("plan"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(kind = SupervisionSessionKind.APP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(runtimeSlot = null, endedAt = null, reason = null)
        }
    }

    private fun descriptor() = SupervisionSessionDescriptor(
        identityKey = "manual:one",
        runtimeSlot = GLOBAL_RUNTIME_SLOT,
        kind = SupervisionSessionKind.MANUAL_GLOBAL,
        displayName = "即时监督",
        planId = null,
        packageName = null,
        usageMinutes = 30,
        lockMinutes = 5
    )

    private fun record(
        runtimeSlot: String?,
        endedAt: Long?,
        reason: SupervisionSessionEndReason?
    ) = SupervisionSessionRecord(
        sessionId = "session-one",
        identityKey = "manual:one",
        runtimeSlot = runtimeSlot,
        kind = SupervisionSessionKind.MANUAL_GLOBAL,
        displayName = "即时监督",
        planId = null,
        packageName = null,
        usageMinutes = 30,
        lockMinutes = 5,
        startedAtEpochMillis = 1_000L,
        endedAtEpochMillis = endedAt,
        endReason = reason,
        updatedAtEpochMillis = endedAt ?: 1_000L
    )
}
