package com.example.controlfree.supervision.history

import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.supervision.app.AppSupervisionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SupervisionHistoryRecorderTest {
    @Test
    fun `同一计划时段生成稳定身份而下一时段会变化`() {
        val first = scheduledDescriptor("plan", 7L, 10_000L, "早间", 30, 5)
        val recovered = scheduledDescriptor("plan", 7L, 10_000L, "早间", 30, 5)
        val next = scheduledDescriptor("plan", 7L, 20_000L, "早间", 30, 5)

        assertEquals(first.identityKey, recovered.identityKey)
        assertNotEquals(first.identityKey, next.identityKey)
        assertEquals(GLOBAL_RUNTIME_SLOT, first.runtimeSlot)
    }

    @Test
    fun `App规则会映射为独立槽位和分钟配置`() {
        val descriptor = appDescriptor(
            AppSupervisionRule(
                planId = "video",
                planUpdatedAtEpochMillis = 2L,
                planName = "视频监督",
                packageName = "example.video",
                occurrenceEndEpochMillis = 100_000L,
                usageAllowanceMillis = 30L * 60_000L,
                restDurationMillis = 10L * 60_000L
            )
        )

        assertEquals(appRuntimeSlot("video"), descriptor.runtimeSlot)
        assertEquals(30, descriptor.usageMinutes)
        assertEquals(10, descriptor.lockMinutes)
        assertEquals(SupervisionSessionKind.APP, descriptor.kind)
    }

    @Test
    fun `即时和定时专注使用独立历史类型并保持锁定玩机参数`() {
        val manual = manualDescriptor(
            identityKey = "manual-focus",
            usageMinutes = 3,
            lockMinutes = 15,
            sessionMode = MonitorSessionMode.FOCUS
        )
        val scheduled = scheduledDescriptor(
            planId = "focus-a",
            planUpdatedAtEpochMillis = 2L,
            activeUntilEpochMillis = 100_000L,
            planName = "上午专注",
            usageMinutes = 3,
            lockMinutes = 15,
            sessionMode = MonitorSessionMode.FOCUS
        )
        val global = scheduledDescriptor(
            "focus-a",
            2L,
            100_000L,
            "上午监督",
            3,
            15,
            MonitorSessionMode.SUPERVISION
        )

        assertEquals(SupervisionSessionKind.MANUAL_FOCUS, manual.kind)
        assertEquals(SupervisionSessionKind.SCHEDULED_FOCUS, scheduled.kind)
        assertEquals(3, scheduled.usageMinutes)
        assertEquals(15, scheduled.lockMinutes)
        assertNotEquals(global.identityKey, scheduled.identityKey)
    }
}
