package com.example.controlfree.supervision.history

import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionHistoryCsvExporterTest {
    @Test
    fun `CSV使用UTF8签名转义引号并阻止表格公式注入`() {
        val record = SupervisionSessionRecord(
            sessionId = "session",
            identityKey = "manual:one",
            runtimeSlot = null,
            kind = SupervisionSessionKind.MANUAL_GLOBAL,
            displayName = "=SUM(1,2)\"测试",
            planId = null,
            packageName = null,
            usageMinutes = 30,
            lockMinutes = 5,
            startedAtEpochMillis = 1_000L,
            endedAtEpochMillis = 61_000L,
            endReason = SupervisionSessionEndReason.CANCELLED,
            updatedAtEpochMillis = 61_000L
        )

        val bytes = SupervisionHistoryCsvExporter.encodeUtf8(
            records = listOf(record),
            nowEpochMillis = 61_000L,
            zoneId = ZoneId.of("Asia/Shanghai")
        )
        val csv = bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)

        assertTrue(bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertTrue(csv.contains("\"'=SUM(1,2)\"\"测试\""))
        assertTrue(csv.contains("\"已取消\""))
        assertTrue(csv.contains("\"60\""))
    }

    @Test
    fun `CSV区分新专注类型并诚实标记旧版即时记录`() {
        fun record(
            id: String,
            kind: SupervisionSessionKind,
            displayName: String
        ) = SupervisionSessionRecord(
            sessionId = id,
            identityKey = "identity-$id",
            runtimeSlot = null,
            kind = kind,
            displayName = displayName,
            planId = if (kind == SupervisionSessionKind.SCHEDULED_FOCUS) "plan-$id" else null,
            packageName = null,
            usageMinutes = 3,
            lockMinutes = 15,
            startedAtEpochMillis = 1_000L,
            endedAtEpochMillis = 61_000L,
            endReason = SupervisionSessionEndReason.COMPLETED,
            updatedAtEpochMillis = 61_000L
        )
        val bytes = SupervisionHistoryCsvExporter.encodeUtf8(
            records = listOf(
                record("manual-focus", SupervisionSessionKind.MANUAL_FOCUS, "即时专注"),
                record("scheduled-focus", SupervisionSessionKind.SCHEDULED_FOCUS, "晨间专注"),
                record(
                    "legacy",
                    SupervisionSessionKind.MANUAL_GLOBAL,
                    LEGACY_AMBIGUOUS_MANUAL_DISPLAY_NAME
                )
            ),
            nowEpochMillis = 61_000L,
            zoneId = ZoneId.of("Asia/Shanghai")
        )
        val csv = bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)

        assertTrue(csv.contains("\"即时专注\""))
        assertTrue(csv.contains("\"定时专注\""))
        assertTrue(csv.contains("\"旧版即时任务（监督/专注无法区分）\""))
    }

    @Test
    fun `CSV附带设备重启和监督恢复结果`() {
        val event = SupervisionHistoryEventRecord(
            eventId = "boot-event",
            eventType = SupervisionHistoryEventType.DEVICE_BOOT,
            bootInstanceKey = "device-boot:8",
            bootCount = 8,
            occurredAtEpochMillis = 1_000L,
            receivedAtEpochMillis = 2_000L,
            sessionId = "session",
            runtimeSlot = GLOBAL_RUNTIME_SLOT,
            recoveryStatus = SupervisionRecoveryStatus.RESTORED,
            updatedAtEpochMillis = 3_000L
        )

        val bytes = SupervisionHistoryCsvExporter.encodeUtf8(
            records = emptyList(),
            nowEpochMillis = 3_000L,
            zoneId = ZoneId.of("Asia/Shanghai"),
            events = listOf(event)
        )
        val csv = bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)

        assertTrue(csv.contains("\"设备重启\""))
        assertTrue(csv.contains("\"监督已恢复\""))
        assertTrue(csv.contains("\"8\""))
    }
}
