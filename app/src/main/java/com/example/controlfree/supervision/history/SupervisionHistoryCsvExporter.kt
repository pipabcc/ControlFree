package com.example.controlfree.supervision.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SupervisionHistoryCsvExporter {
    private val timestampFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun encodeUtf8(
        records: Collection<SupervisionSessionRecord>,
        nowEpochMillis: Long,
        zoneId: ZoneId,
        events: Collection<SupervisionHistoryEventRecord> = emptyList()
    ): ByteArray {
        require(nowEpochMillis >= 0L) { "导出时间无效" }
        val csv = buildString {
            appendLine(
                listOf(
                    "开始时间",
                    "结束时间",
                    "状态",
                    "类型",
                    "名称",
                    "计划ID",
                    "App包名",
                    "可用或额度分钟",
                    "锁定或休息分钟",
                    "持续秒数"
                ).joinToString(",", transform = ::csvCell)
            )
            records.sortedWith(
                compareBy<SupervisionSessionRecord>(SupervisionSessionRecord::startedAtEpochMillis)
                    .thenBy(SupervisionSessionRecord::sessionId)
            ).forEach { record ->
                appendLine(
                    listOf(
                        formatTimestamp(record.startedAtEpochMillis, zoneId),
                        record.endedAtEpochMillis?.let { formatTimestamp(it, zoneId) }.orEmpty(),
                        record.endReason?.displayName ?: "进行中",
                        record.historyKindDisplayName,
                        record.displayName,
                        record.planId.orEmpty(),
                        record.packageName.orEmpty(),
                        record.usageMinutes?.toString().orEmpty(),
                        record.lockMinutes?.toString().orEmpty(),
                        (record.effectiveDurationMillis(nowEpochMillis) / 1_000L).toString()
                    ).joinToString(",", transform = ::csvCell)
                )
            }
            if (events.isNotEmpty()) {
                appendLine()
                appendLine(
                    listOf(
                        "设备事件时间",
                        "事件类型",
                        "恢复状态",
                        "开机计数",
                        "关联会话ID"
                    ).joinToString(",", transform = ::csvCell)
                )
                events.sortedWith(
                    compareBy<SupervisionHistoryEventRecord>(
                        SupervisionHistoryEventRecord::occurredAtEpochMillis
                    ).thenBy(SupervisionHistoryEventRecord::eventId)
                ).forEach { event ->
                    appendLine(
                        listOf(
                            formatTimestamp(event.occurredAtEpochMillis, zoneId),
                            "设备重启",
                            event.recoveryStatus.displayName,
                            event.bootCount?.toString().orEmpty(),
                            event.sessionId.orEmpty()
                        ).joinToString(",", transform = ::csvCell)
                    )
                }
            }
        }
        return UTF8_BOM + csv.toByteArray(Charsets.UTF_8)
    }

    private fun formatTimestamp(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(timestampFormatter)

    private fun csvCell(rawValue: String): String {
        val safeValue = if (rawValue.firstOrNull() in DANGEROUS_SPREADSHEET_PREFIXES) {
            "'$rawValue"
        } else {
            rawValue
        }
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }

    private val SupervisionSessionRecord.historyKindDisplayName: String
        get() = when {
            isLegacyAmbiguousManual -> "旧版即时任务（监督/专注无法区分）"
            else -> when (kind) {
                SupervisionSessionKind.MANUAL_GLOBAL -> "即时监督"
                SupervisionSessionKind.SCHEDULED_GLOBAL -> "定时全局监督"
                SupervisionSessionKind.MANUAL_FOCUS -> "即时专注"
                SupervisionSessionKind.SCHEDULED_FOCUS -> "定时专注"
                SupervisionSessionKind.APP -> "App独立监督"
            }
        }

    private val SupervisionSessionEndReason.displayName: String
        get() = when (this) {
            SupervisionSessionEndReason.COMPLETED -> "已完成"
            SupervisionSessionEndReason.CANCELLED -> "已取消"
            SupervisionSessionEndReason.REPLACED -> "已替换"
        }

    private val SupervisionRecoveryStatus.displayName: String
        get() = when (this) {
            SupervisionRecoveryStatus.RECOVERY_REQUESTED -> "已请求恢复监督"
            SupervisionRecoveryStatus.RESTORED -> "监督已恢复"
            SupervisionRecoveryStatus.FAILED -> "监督恢复失败"
            SupervisionRecoveryStatus.NO_ACTIVE_SUPERVISION -> "当时无活动监督"
        }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val DANGEROUS_SPREADSHEET_PREFIXES = setOf('=', '+', '-', '@')
}
