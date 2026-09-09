package com.example.controlfree.diagnostics

/** 一键自检的稳定检查项标识；界面文案应由展示层按标识映射。 */
enum class SelfCheckItemKey {
    USAGE_ACCESS_PERMISSION,
    OVERLAY_PERMISSION,
    PHONE_STATE_PERMISSION,
    NOTIFICATION_PERMISSION,
    NOTIFICATIONS_ENABLED,
    SERVICE_NOTIFICATION_CHANNEL,
    LOCK_RECOVERY_NOTIFICATION_CHANNEL,
    FULL_SCREEN_INTENT_PERMISSION,
    BATTERY_OPTIMIZATION,
    USAGE_STATS_HEALTH,
    SERVICE_HEARTBEAT,
    SNAPSHOT_RECOVERY,
    ALLOWLIST_CACHE,
    MEDIA_REPLAY_GUARD,
    DIAGNOSTIC_STORAGE,
    BOOT_RECEIVER,
    BACKGROUND_RESTRICTION,
    OEM_AUTO_START,
    OEM_BACKGROUND_ACTIVITY
}

enum class SelfCheckItemStatus {
    PASSED,
    IN_PROGRESS,
    WARNING,
    FAILED,
    MANUAL_CONFIRMATION_REQUIRED,
    NOT_APPLICABLE
}

enum class SelfCheckOverallStatus {
    HEALTHY,
    IN_PROGRESS,
    NEEDS_ATTENTION,
    FAILED
}

/**
 * 稳定原因码用于日志脱敏、测试和后续多语言映射，不携带原始异常或系统返回文本。
 */
enum class SelfCheckReason {
    AVAILABLE,
    NOT_APPLICABLE,
    PERMISSION_DENIED,
    PERMISSION_STATE_UNKNOWN,
    CAPABILITY_DEGRADED,
    RECOMMENDED_PERMISSION_DENIED,
    BATTERY_RESTRICTION_PRESENT,
    USAGE_STATS_NOT_CHECKED,
    USAGE_STATS_NO_DATA,
    USAGE_STATS_WORKER_RECOVERED,
    USAGE_STATS_TIMED_OUT,
    USAGE_STATS_CIRCUIT_OPEN,
    USAGE_STATS_QUERY_FAILED,
    MONITORING_INACTIVE,
    SERVICE_NOT_RUNNING,
    CRITICAL_RECEIVER_MISSING,
    SCREEN_RECEIVER_MISSING,
    PERSISTENCE_UNHEALTHY,
    TIMER_UNHEALTHY,
    CALL_STATE_MONITOR_UNAVAILABLE,
    HEARTBEAT_MISSING,
    HEARTBEAT_STALE,
    HEARTBEAT_INVALID,
    RECOVERY_NOT_REQUIRED,
    RECOVERY_FALLBACK_ARMED,
    SNAPSHOT_MISSING,
    SNAPSHOT_CORRUPTED,
    SNAPSHOT_READ_FAILED,
    RECOVERY_GUARD_DISARMED,
    ALLOWLIST_UNINITIALIZED,
    ALLOWLIST_LOADING,
    ALLOWLIST_READY,
    ALLOWLIST_STALE,
    ALLOWLIST_FAILED,
    ALLOWLIST_PARTITION_MISSING,
    MEDIA_GUARD_NOT_REQUIRED,
    MEDIA_GUARD_NOT_CHECKED,
    MEDIA_GUARD_DISABLED,
    MEDIA_PROBE_UNAVAILABLE,
    MEDIA_GUARD_FAILED,
    STORAGE_NOT_CHECKED,
    STORAGE_READ_FAILED,
    STORAGE_WRITE_FAILED,
    STORAGE_NOT_PRIVATE,
    STORAGE_SIZE_LIMIT_EXCEEDED,
    BOOT_RECEIVER_MISSING,
    BOOT_RECEIVER_DISABLED,
    BOOT_RECEIVER_STATE_UNKNOWN,
    CURRENT_BOOT_EVIDENCE_MISSING,
    BOOT_COUNT_UNAVAILABLE,
    BACKGROUND_UNRESTRICTED,
    BACKGROUND_RESTRICTED,
    BACKGROUND_STATE_UNKNOWN,
    OEM_SETTING_ENABLED,
    OEM_SETTING_DISABLED,
    OEM_SETTING_REQUIRES_MANUAL_CONFIRMATION
}

data class SelfCheckItemResult(
    val key: SelfCheckItemKey,
    val status: SelfCheckItemStatus,
    val reason: SelfCheckReason
)

data class SelfCheckReport(
    val overallStatus: SelfCheckOverallStatus,
    val items: List<SelfCheckItemResult>
) {
    init {
        require(items.map(SelfCheckItemResult::key).distinct().size == items.size) {
            "Self-check item keys must be unique"
        }
    }

    fun resultFor(key: SelfCheckItemKey): SelfCheckItemResult =
        requireNotNull(items.firstOrNull { it.key == key }) { "Missing self-check item: $key" }
}

enum class CapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    DEGRADED,
    UNKNOWN,
    NOT_APPLICABLE
}

data class PermissionHealthSnapshot(
    val usageAccess: CapabilityState,
    val overlay: CapabilityState,
    val phoneState: CapabilityState,
    val notificationRuntime: CapabilityState,
    val notificationsEnabled: CapabilityState,
    val serviceNotificationChannel: CapabilityState,
    val lockRecoveryNotificationChannel: CapabilityState,
    val fullScreenIntent: CapabilityState,
    val batteryUnrestricted: CapabilityState
)

enum class UsageStatsHealthState {
    HEALTHY,
    NOT_CHECKED,
    NO_DATA,
    WORKER_RECOVERED,
    TIMED_OUT,
    CIRCUIT_OPEN,
    QUERY_FAILED
}

data class UsageStatsHealthSnapshot(
    val state: UsageStatsHealthState
)

data class ServiceHeartbeatSnapshot(
    val monitoringActive: Boolean,
    val serviceRunning: Boolean,
    val observedAtElapsedMillis: Long,
    val lastHeartbeatElapsedMillis: Long?,
    val staleAfterMillis: Long,
    val persistenceHealthy: Boolean = true,
    val timerHealthy: Boolean = true,
    val internalReceiverRegistered: Boolean = true,
    val screenReceiverRegistered: Boolean = true,
    val systemDialogReceiverRegistered: Boolean = true,
    val callStateMonitorRequired: Boolean = false,
    val callStateMonitorRegistered: Boolean = false
)

enum class SnapshotRecoveryState {
    NOT_REQUIRED,
    HEALTHY,
    FALLBACK_ARMED,
    SNAPSHOT_MISSING,
    SNAPSHOT_CORRUPTED,
    READ_FAILED,
    RECOVERY_GUARD_DISARMED
}

data class SnapshotRecoveryHealthSnapshot(
    val state: SnapshotRecoveryState
)

/** 与仓库的 Uninitialized、Loading、Ready、Stale、Failed 五态一一对应。 */
enum class AllowlistCacheState {
    UNINITIALIZED,
    LOADING,
    READY,
    STALE,
    FAILED
}

data class AllowlistHealthSnapshot(
    val state: AllowlistCacheState,
    val hasUsableAppPartition: Boolean,
    val hasUsableCallUiPartition: Boolean
) {
    val hasCompleteUsableSnapshot: Boolean
        get() = hasUsableAppPartition && hasUsableCallUiPartition
}

enum class MediaGuardState {
    HEALTHY,
    NOT_CHECKED,
    DISABLED,
    PROBE_UNAVAILABLE,
    FAILED
}

data class MediaGuardHealthSnapshot(
    val required: Boolean,
    val state: MediaGuardState
)

enum class StorageProbeState {
    HEALTHY,
    NOT_CHECKED,
    READ_FAILED,
    WRITE_FAILED
}

data class DiagnosticStorageHealthSnapshot(
    val state: StorageProbeState,
    val usesPrivateDirectory: Boolean,
    val withinSizeLimit: Boolean
)

enum class BootReceiverEvidenceState {
    CURRENT_BOOT_CONFIRMED,
    CURRENT_BOOT_EVIDENCE_MISSING,
    BOOT_COUNT_UNAVAILABLE
}

data class BootReceiverHealthSnapshot(
    val declared: Boolean,
    val enabled: Boolean,
    val evidence: BootReceiverEvidenceState,
    val queryFailed: Boolean = false
)

enum class BackgroundRestrictionState {
    UNRESTRICTED,
    RESTRICTED,
    UNKNOWN
}

data class BackgroundRestrictionSnapshot(
    val state: BackgroundRestrictionState
)

/** 厂商无公开读取 API 时必须使用 MANUAL_CONFIRMATION_REQUIRED，不能猜测开关状态。 */
enum class OemSettingState {
    ENABLED,
    DISABLED,
    MANUAL_CONFIRMATION_REQUIRED,
    NOT_APPLICABLE
}

data class ManufacturerPolicySnapshot(
    val autoStart: OemSettingState,
    val backgroundActivity: OemSettingState
)

/** 一键自检只消费采集层生成的不可变快照，不依赖 Android Context。 */
data class SelfCheckSnapshot(
    val permissions: PermissionHealthSnapshot,
    val usageStats: UsageStatsHealthSnapshot,
    val serviceHeartbeat: ServiceHeartbeatSnapshot,
    val snapshotRecovery: SnapshotRecoveryHealthSnapshot,
    val allowlist: AllowlistHealthSnapshot,
    val mediaGuard: MediaGuardHealthSnapshot,
    val diagnosticStorage: DiagnosticStorageHealthSnapshot,
    val bootReceiver: BootReceiverHealthSnapshot,
    val backgroundRestriction: BackgroundRestrictionSnapshot,
    val manufacturerPolicy: ManufacturerPolicySnapshot
)
