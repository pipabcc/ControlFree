package com.example.controlfree.supervision.history

enum class SupervisionSessionKind(val storedValue: String) {
    MANUAL_GLOBAL("manual_global"),
    SCHEDULED_GLOBAL("scheduled_global"),
    MANUAL_FOCUS("manual_focus"),
    SCHEDULED_FOCUS("scheduled_focus"),
    APP("app");

    companion object {
        fun fromStoredValue(value: String): SupervisionSessionKind? =
            entries.firstOrNull { kind -> kind.storedValue == value }
    }
}

enum class SupervisionSessionEndReason(val storedValue: String) {
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    REPLACED("replaced");

    companion object {
        fun fromStoredValue(value: String): SupervisionSessionEndReason? =
            entries.firstOrNull { reason -> reason.storedValue == value }
    }
}

enum class SupervisionHistoryEventType(val storedValue: String) {
    DEVICE_BOOT("device_boot");

    companion object {
        fun fromStoredValue(value: String): SupervisionHistoryEventType? =
            entries.firstOrNull { type -> type.storedValue == value }
    }
}

enum class SupervisionRecoveryStatus(val storedValue: String) {
    RECOVERY_REQUESTED("recovery_requested"),
    RESTORED("restored"),
    FAILED("failed"),
    NO_ACTIVE_SUPERVISION("no_active_supervision");

    companion object {
        fun fromStoredValue(value: String): SupervisionRecoveryStatus? =
            entries.firstOrNull { status -> status.storedValue == value }
    }
}

data class SupervisionHistoryEventRecord(
    val eventId: String,
    val eventType: SupervisionHistoryEventType,
    val bootInstanceKey: String,
    val bootCount: Int?,
    val occurredAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val sessionId: String?,
    val runtimeSlot: String?,
    val recoveryStatus: SupervisionRecoveryStatus,
    val updatedAtEpochMillis: Long
) {
    init {
        require(eventId.isValidHistoryText(MAX_SESSION_ID_LENGTH)) { "历史事件编号无效" }
        require(bootInstanceKey.isValidHistoryText(MAX_IDENTITY_LENGTH)) {
            "开机实例标识无效"
        }
        require(bootCount == null || bootCount >= 0) { "开机计数无效" }
        require(occurredAtEpochMillis >= 0L) { "开机发生时间无效" }
        require(receivedAtEpochMillis >= occurredAtEpochMillis) { "开机接收时间无效" }
        require(updatedAtEpochMillis >= receivedAtEpochMillis) { "历史事件更新时间无效" }
        require(sessionId == null || sessionId.isValidHistoryText(MAX_SESSION_ID_LENGTH)) {
            "关联监督会话无效"
        }
        require(runtimeSlot == null || runtimeSlot.isValidHistoryText(MAX_RUNTIME_SLOT_LENGTH)) {
            "关联监督槽位无效"
        }
    }
}

data class SupervisionSessionDescriptor(
    val identityKey: String,
    val runtimeSlot: String,
    val kind: SupervisionSessionKind,
    val displayName: String,
    val planId: String?,
    val packageName: String?,
    val usageMinutes: Int?,
    val lockMinutes: Int?
) {
    init {
        require(identityKey.isValidHistoryText(MAX_IDENTITY_LENGTH)) { "历史会话身份无效" }
        require(runtimeSlot.isValidHistoryText(MAX_RUNTIME_SLOT_LENGTH)) { "历史运行槽位无效" }
        require(displayName.isValidHistoryText(MAX_DISPLAY_NAME_LENGTH)) { "历史显示名称无效" }
        require(planId == null || planId.isValidHistoryText(MAX_PLAN_ID_LENGTH)) {
            "历史计划编号无效"
        }
        require(packageName == null || PACKAGE_NAME_PATTERN.matches(packageName)) {
            "历史 App 包名无效"
        }
        require(usageMinutes == null || usageMinutes in 1..1_440) { "历史可用时长无效" }
        require(lockMinutes == null || lockMinutes in 1..1_440) { "历史锁定时长无效" }
        when (kind) {
            SupervisionSessionKind.MANUAL_GLOBAL,
            SupervisionSessionKind.MANUAL_FOCUS -> {
                require(runtimeSlot == GLOBAL_RUNTIME_SLOT)
                require(planId == null && packageName == null)
                require(usageMinutes != null && lockMinutes != null)
            }
            SupervisionSessionKind.SCHEDULED_GLOBAL,
            SupervisionSessionKind.SCHEDULED_FOCUS -> {
                require(runtimeSlot == GLOBAL_RUNTIME_SLOT)
                require(planId != null && packageName == null)
                require(usageMinutes != null && lockMinutes != null)
            }
            SupervisionSessionKind.APP -> {
                require(planId != null && packageName != null)
                require(runtimeSlot == appRuntimeSlot(requireNotNull(planId)))
                require(usageMinutes != null && lockMinutes != null)
            }
        }
    }
}

data class SupervisionSessionRecord(
    val sessionId: String,
    val identityKey: String,
    val runtimeSlot: String?,
    val kind: SupervisionSessionKind,
    val displayName: String,
    val planId: String?,
    val packageName: String?,
    val usageMinutes: Int?,
    val lockMinutes: Int?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val endReason: SupervisionSessionEndReason?,
    val updatedAtEpochMillis: Long
) {
    init {
        require(sessionId.isValidHistoryText(MAX_SESSION_ID_LENGTH)) { "历史会话编号无效" }
        require(startedAtEpochMillis >= 0L) { "历史开始时间无效" }
        require(updatedAtEpochMillis >= startedAtEpochMillis) { "历史更新时间无效" }
        require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis) {
            "历史结束时间无效"
        }
        require((runtimeSlot == null) == (endedAtEpochMillis != null)) { "历史活动状态不一致" }
        require((endReason == null) == (endedAtEpochMillis == null)) { "历史结束原因不一致" }
        SupervisionSessionDescriptor(
            identityKey = identityKey,
            runtimeSlot = runtimeSlot ?: expectedRuntimeSlot(kind, planId),
            kind = kind,
            displayName = displayName,
            planId = planId,
            packageName = packageName,
            usageMinutes = usageMinutes,
            lockMinutes = lockMinutes
        )
    }

    val isActive: Boolean
        get() = endedAtEpochMillis == null

    fun effectiveDurationMillis(nowEpochMillis: Long): Long =
        ((endedAtEpochMillis ?: nowEpochMillis.coerceAtLeast(startedAtEpochMillis)) -
            startedAtEpochMillis).coerceAtLeast(0L)
}

const val GLOBAL_RUNTIME_SLOT = "global"
internal const val LEGACY_AMBIGUOUS_MANUAL_DISPLAY_NAME = "即时专注"

fun appRuntimeSlot(planId: String): String = "app:$planId"

internal val SupervisionSessionRecord.isLegacyAmbiguousManual: Boolean
    get() = kind == SupervisionSessionKind.MANUAL_GLOBAL &&
        displayName == LEGACY_AMBIGUOUS_MANUAL_DISPLAY_NAME

private fun expectedRuntimeSlot(kind: SupervisionSessionKind, planId: String?): String =
    when (kind) {
        SupervisionSessionKind.MANUAL_GLOBAL,
        SupervisionSessionKind.SCHEDULED_GLOBAL,
        SupervisionSessionKind.MANUAL_FOCUS,
        SupervisionSessionKind.SCHEDULED_FOCUS -> GLOBAL_RUNTIME_SLOT
        SupervisionSessionKind.APP -> appRuntimeSlot(requireNotNull(planId))
    }

private fun String.isValidHistoryText(maxLength: Int): Boolean =
    isNotBlank() && this == trim() && length <= maxLength

private val PACKAGE_NAME_PATTERN = Regex(
    "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"
)

private const val MAX_SESSION_ID_LENGTH = 80
private const val MAX_IDENTITY_LENGTH = 200
private const val MAX_RUNTIME_SLOT_LENGTH = 200
private const val MAX_PLAN_ID_LENGTH = 160
private const val MAX_DISPLAY_NAME_LENGTH = 80
