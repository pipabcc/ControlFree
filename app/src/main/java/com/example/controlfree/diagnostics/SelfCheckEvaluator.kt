package com.example.controlfree.diagnostics

/** 将平台采集快照确定性地映射为可测试、可脱敏展示的一键自检报告。 */
object SelfCheckEvaluator {
    fun evaluate(snapshot: SelfCheckSnapshot): SelfCheckReport {
        val items = buildList {
            addRequiredCapability(
                SelfCheckItemKey.USAGE_ACCESS_PERMISSION,
                snapshot.permissions.usageAccess
            )
            addRequiredCapability(
                SelfCheckItemKey.OVERLAY_PERMISSION,
                snapshot.permissions.overlay
            )
            addRequiredCapability(
                SelfCheckItemKey.PHONE_STATE_PERMISSION,
                snapshot.permissions.phoneState
            )
            addRequiredCapability(
                SelfCheckItemKey.NOTIFICATION_PERMISSION,
                snapshot.permissions.notificationRuntime
            )
            addRequiredCapability(
                SelfCheckItemKey.NOTIFICATIONS_ENABLED,
                if (snapshot.permissions.notificationRuntime == CapabilityState.UNAVAILABLE) {
                    CapabilityState.NOT_APPLICABLE
                } else {
                    snapshot.permissions.notificationsEnabled
                }
            )
            addRequiredCapability(
                SelfCheckItemKey.SERVICE_NOTIFICATION_CHANNEL,
                snapshot.permissions.serviceNotificationChannel
            )
            add(
                evaluateRecommendedCapability(
                    SelfCheckItemKey.LOCK_RECOVERY_NOTIFICATION_CHANNEL,
                    snapshot.permissions.lockRecoveryNotificationChannel
                )
            )
            add(
                evaluateRecommendedCapability(
                    SelfCheckItemKey.FULL_SCREEN_INTENT_PERMISSION,
                    snapshot.permissions.fullScreenIntent
                )
            )
            add(evaluateBatteryOptimization(snapshot.permissions.batteryUnrestricted))
            add(evaluateUsageStats(snapshot.usageStats))
            add(evaluateServiceHeartbeat(snapshot.serviceHeartbeat))
            add(evaluateSnapshotRecovery(snapshot.snapshotRecovery))
            add(evaluateAllowlist(snapshot.allowlist))
            add(evaluateMediaGuard(snapshot.mediaGuard))
            add(evaluateStorage(snapshot.diagnosticStorage))
            add(evaluateBootReceiver(snapshot.bootReceiver))
            add(evaluateBackgroundRestriction(snapshot.backgroundRestriction))
            add(
                evaluateOemSetting(
                    SelfCheckItemKey.OEM_AUTO_START,
                    snapshot.manufacturerPolicy.autoStart
                )
            )
            add(
                evaluateOemSetting(
                    SelfCheckItemKey.OEM_BACKGROUND_ACTIVITY,
                    snapshot.manufacturerPolicy.backgroundActivity
                )
            )
        }
        return SelfCheckReport(
            overallStatus = evaluateOverallStatus(items),
            items = items
        )
    }

    private fun MutableList<SelfCheckItemResult>.addRequiredCapability(
        key: SelfCheckItemKey,
        state: CapabilityState
    ) {
        add(
            when (state) {
                CapabilityState.AVAILABLE -> passed(key)
                CapabilityState.UNAVAILABLE -> failed(key, SelfCheckReason.PERMISSION_DENIED)
                CapabilityState.DEGRADED -> SelfCheckItemResult(
                    key,
                    SelfCheckItemStatus.WARNING,
                    SelfCheckReason.CAPABILITY_DEGRADED
                )
                CapabilityState.UNKNOWN -> SelfCheckItemResult(
                    key,
                    SelfCheckItemStatus.WARNING,
                    SelfCheckReason.PERMISSION_STATE_UNKNOWN
                )
                CapabilityState.NOT_APPLICABLE -> notApplicable(key)
            }
        )
    }

    private fun evaluateRecommendedCapability(
        key: SelfCheckItemKey,
        state: CapabilityState
    ): SelfCheckItemResult = when (state) {
        CapabilityState.AVAILABLE -> passed(key)
        CapabilityState.UNAVAILABLE -> SelfCheckItemResult(
            key,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.RECOMMENDED_PERMISSION_DENIED
        )
        CapabilityState.DEGRADED -> SelfCheckItemResult(
            key,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.CAPABILITY_DEGRADED
        )
        CapabilityState.UNKNOWN -> SelfCheckItemResult(
            key,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.PERMISSION_STATE_UNKNOWN
        )
        CapabilityState.NOT_APPLICABLE -> notApplicable(key)
    }

    private fun evaluateUsageStats(snapshot: UsageStatsHealthSnapshot): SelfCheckItemResult =
        when (snapshot.state) {
            UsageStatsHealthState.HEALTHY -> passed(SelfCheckItemKey.USAGE_STATS_HEALTH)
            UsageStatsHealthState.NOT_CHECKED -> SelfCheckItemResult(
                SelfCheckItemKey.USAGE_STATS_HEALTH,
                SelfCheckItemStatus.IN_PROGRESS,
                SelfCheckReason.USAGE_STATS_NOT_CHECKED
            )
            UsageStatsHealthState.NO_DATA -> SelfCheckItemResult(
                SelfCheckItemKey.USAGE_STATS_HEALTH,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.USAGE_STATS_NO_DATA
            )
            UsageStatsHealthState.WORKER_RECOVERED -> SelfCheckItemResult(
                SelfCheckItemKey.USAGE_STATS_HEALTH,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.USAGE_STATS_WORKER_RECOVERED
            )
            UsageStatsHealthState.TIMED_OUT -> failed(
                SelfCheckItemKey.USAGE_STATS_HEALTH,
                SelfCheckReason.USAGE_STATS_TIMED_OUT
            )
            UsageStatsHealthState.CIRCUIT_OPEN -> failed(
                SelfCheckItemKey.USAGE_STATS_HEALTH,
                SelfCheckReason.USAGE_STATS_CIRCUIT_OPEN
            )
            UsageStatsHealthState.QUERY_FAILED -> failed(
                SelfCheckItemKey.USAGE_STATS_HEALTH,
                SelfCheckReason.USAGE_STATS_QUERY_FAILED
            )
        }

    private fun evaluateBatteryOptimization(state: CapabilityState): SelfCheckItemResult =
        when (state) {
            CapabilityState.AVAILABLE -> passed(SelfCheckItemKey.BATTERY_OPTIMIZATION)
            CapabilityState.UNAVAILABLE -> SelfCheckItemResult(
                SelfCheckItemKey.BATTERY_OPTIMIZATION,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.BATTERY_RESTRICTION_PRESENT
            )
            CapabilityState.DEGRADED -> SelfCheckItemResult(
                SelfCheckItemKey.BATTERY_OPTIMIZATION,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.CAPABILITY_DEGRADED
            )
            CapabilityState.UNKNOWN -> SelfCheckItemResult(
                SelfCheckItemKey.BATTERY_OPTIMIZATION,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.PERMISSION_STATE_UNKNOWN
            )
            CapabilityState.NOT_APPLICABLE -> notApplicable(SelfCheckItemKey.BATTERY_OPTIMIZATION)
        }

    private fun evaluateServiceHeartbeat(
        snapshot: ServiceHeartbeatSnapshot
    ): SelfCheckItemResult {
        val key = SelfCheckItemKey.SERVICE_HEARTBEAT
        if (!snapshot.monitoringActive) {
            return SelfCheckItemResult(
                key,
                SelfCheckItemStatus.NOT_APPLICABLE,
                SelfCheckReason.MONITORING_INACTIVE
            )
        }
        if (!snapshot.serviceRunning) return failed(key, SelfCheckReason.SERVICE_NOT_RUNNING)
        if (!snapshot.persistenceHealthy) return failed(key, SelfCheckReason.PERSISTENCE_UNHEALTHY)
        if (!snapshot.timerHealthy) return failed(key, SelfCheckReason.TIMER_UNHEALTHY)
        if (!snapshot.internalReceiverRegistered || !snapshot.systemDialogReceiverRegistered) {
            return failed(key, SelfCheckReason.CRITICAL_RECEIVER_MISSING)
        }
        if (snapshot.callStateMonitorRequired && !snapshot.callStateMonitorRegistered) {
            return failed(key, SelfCheckReason.CALL_STATE_MONITOR_UNAVAILABLE)
        }
        val heartbeat = snapshot.lastHeartbeatElapsedMillis
            ?: return failed(key, SelfCheckReason.HEARTBEAT_MISSING)
        if (
            snapshot.observedAtElapsedMillis < 0L ||
            heartbeat < 0L ||
            heartbeat > snapshot.observedAtElapsedMillis ||
            snapshot.staleAfterMillis <= 0L
        ) {
            return failed(key, SelfCheckReason.HEARTBEAT_INVALID)
        }
        if (snapshot.observedAtElapsedMillis - heartbeat > snapshot.staleAfterMillis) {
            return failed(key, SelfCheckReason.HEARTBEAT_STALE)
        }
        return if (snapshot.screenReceiverRegistered) {
            passed(key)
        } else {
            SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.SCREEN_RECEIVER_MISSING
            )
        }
    }

    private fun evaluateSnapshotRecovery(
        snapshot: SnapshotRecoveryHealthSnapshot
    ): SelfCheckItemResult {
        val key = SelfCheckItemKey.SNAPSHOT_RECOVERY
        return when (snapshot.state) {
            SnapshotRecoveryState.NOT_REQUIRED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.NOT_APPLICABLE,
                SelfCheckReason.RECOVERY_NOT_REQUIRED
            )
            SnapshotRecoveryState.HEALTHY -> passed(key)
            SnapshotRecoveryState.FALLBACK_ARMED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.RECOVERY_FALLBACK_ARMED
            )
            SnapshotRecoveryState.SNAPSHOT_MISSING -> failed(
                key,
                SelfCheckReason.SNAPSHOT_MISSING
            )
            SnapshotRecoveryState.SNAPSHOT_CORRUPTED -> failed(
                key,
                SelfCheckReason.SNAPSHOT_CORRUPTED
            )
            SnapshotRecoveryState.READ_FAILED -> failed(
                key,
                SelfCheckReason.SNAPSHOT_READ_FAILED
            )
            SnapshotRecoveryState.RECOVERY_GUARD_DISARMED -> failed(
                key,
                SelfCheckReason.RECOVERY_GUARD_DISARMED
            )
        }
    }

    private fun evaluateAllowlist(snapshot: AllowlistHealthSnapshot): SelfCheckItemResult {
        val key = SelfCheckItemKey.ALLOWLIST_CACHE
        return when (snapshot.state) {
            AllowlistCacheState.UNINITIALIZED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.ALLOWLIST_UNINITIALIZED
            )
            AllowlistCacheState.LOADING -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.IN_PROGRESS,
                SelfCheckReason.ALLOWLIST_LOADING
            )
            AllowlistCacheState.READY -> if (snapshot.hasCompleteUsableSnapshot) {
                SelfCheckItemResult(key, SelfCheckItemStatus.PASSED, SelfCheckReason.ALLOWLIST_READY)
            } else {
                failed(key, SelfCheckReason.ALLOWLIST_PARTITION_MISSING)
            }
            AllowlistCacheState.STALE -> if (snapshot.hasCompleteUsableSnapshot) {
                SelfCheckItemResult(key, SelfCheckItemStatus.WARNING, SelfCheckReason.ALLOWLIST_STALE)
            } else {
                failed(key, SelfCheckReason.ALLOWLIST_PARTITION_MISSING)
            }
            AllowlistCacheState.FAILED -> failed(key, SelfCheckReason.ALLOWLIST_FAILED)
        }
    }

    private fun evaluateMediaGuard(snapshot: MediaGuardHealthSnapshot): SelfCheckItemResult {
        val key = SelfCheckItemKey.MEDIA_REPLAY_GUARD
        if (!snapshot.required) {
            return SelfCheckItemResult(
                key,
                SelfCheckItemStatus.NOT_APPLICABLE,
                SelfCheckReason.MEDIA_GUARD_NOT_REQUIRED
            )
        }
        return when (snapshot.state) {
            MediaGuardState.HEALTHY -> passed(key)
            MediaGuardState.NOT_CHECKED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.IN_PROGRESS,
                SelfCheckReason.MEDIA_GUARD_NOT_CHECKED
            )
            MediaGuardState.DISABLED -> failed(key, SelfCheckReason.MEDIA_GUARD_DISABLED)
            MediaGuardState.PROBE_UNAVAILABLE -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.MEDIA_PROBE_UNAVAILABLE
            )
            MediaGuardState.FAILED -> failed(key, SelfCheckReason.MEDIA_GUARD_FAILED)
        }
    }

    private fun evaluateStorage(
        snapshot: DiagnosticStorageHealthSnapshot
    ): SelfCheckItemResult {
        val key = SelfCheckItemKey.DIAGNOSTIC_STORAGE
        if (!snapshot.usesPrivateDirectory) {
            return failed(key, SelfCheckReason.STORAGE_NOT_PRIVATE)
        }
        if (!snapshot.withinSizeLimit) {
            return failed(key, SelfCheckReason.STORAGE_SIZE_LIMIT_EXCEEDED)
        }
        return when (snapshot.state) {
            StorageProbeState.HEALTHY -> passed(key)
            StorageProbeState.NOT_CHECKED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.IN_PROGRESS,
                SelfCheckReason.STORAGE_NOT_CHECKED
            )
            StorageProbeState.READ_FAILED -> failed(key, SelfCheckReason.STORAGE_READ_FAILED)
            StorageProbeState.WRITE_FAILED -> failed(key, SelfCheckReason.STORAGE_WRITE_FAILED)
        }
    }

    private fun evaluateBootReceiver(snapshot: BootReceiverHealthSnapshot): SelfCheckItemResult {
        val key = SelfCheckItemKey.BOOT_RECEIVER
        return when {
            snapshot.queryFailed -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.BOOT_RECEIVER_STATE_UNKNOWN
            )
            !snapshot.declared -> failed(key, SelfCheckReason.BOOT_RECEIVER_MISSING)
            !snapshot.enabled -> failed(key, SelfCheckReason.BOOT_RECEIVER_DISABLED)
            snapshot.evidence == BootReceiverEvidenceState.CURRENT_BOOT_CONFIRMED -> passed(key)
            snapshot.evidence == BootReceiverEvidenceState.CURRENT_BOOT_EVIDENCE_MISSING ->
                SelfCheckItemResult(
                    key,
                    SelfCheckItemStatus.WARNING,
                    SelfCheckReason.CURRENT_BOOT_EVIDENCE_MISSING
                )
            else -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.BOOT_COUNT_UNAVAILABLE
            )
        }
    }

    private fun evaluateBackgroundRestriction(
        snapshot: BackgroundRestrictionSnapshot
    ): SelfCheckItemResult {
        val key = SelfCheckItemKey.BACKGROUND_RESTRICTION
        return when (snapshot.state) {
            BackgroundRestrictionState.UNRESTRICTED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.PASSED,
                SelfCheckReason.BACKGROUND_UNRESTRICTED
            )
            BackgroundRestrictionState.RESTRICTED -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.BACKGROUND_RESTRICTED
            )
            BackgroundRestrictionState.UNKNOWN -> SelfCheckItemResult(
                key,
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.BACKGROUND_STATE_UNKNOWN
            )
        }
    }

    private fun evaluateOemSetting(
        key: SelfCheckItemKey,
        state: OemSettingState
    ): SelfCheckItemResult = when (state) {
        OemSettingState.ENABLED -> SelfCheckItemResult(
            key,
            SelfCheckItemStatus.PASSED,
            SelfCheckReason.OEM_SETTING_ENABLED
        )
        OemSettingState.DISABLED -> SelfCheckItemResult(
            key,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.OEM_SETTING_DISABLED
        )
        OemSettingState.MANUAL_CONFIRMATION_REQUIRED -> SelfCheckItemResult(
            key,
            SelfCheckItemStatus.MANUAL_CONFIRMATION_REQUIRED,
            SelfCheckReason.OEM_SETTING_REQUIRES_MANUAL_CONFIRMATION
        )
        OemSettingState.NOT_APPLICABLE -> notApplicable(key)
    }

    private fun evaluateOverallStatus(items: List<SelfCheckItemResult>): SelfCheckOverallStatus =
        when {
            items.any { it.status == SelfCheckItemStatus.FAILED } -> SelfCheckOverallStatus.FAILED
            // 只要仍有探测未完成，报告就不是最终结论，避免提前显示“需处理”。
            items.any { it.status == SelfCheckItemStatus.IN_PROGRESS } ->
                SelfCheckOverallStatus.IN_PROGRESS
            items.any {
                it.status == SelfCheckItemStatus.WARNING ||
                    it.status == SelfCheckItemStatus.MANUAL_CONFIRMATION_REQUIRED
            } -> SelfCheckOverallStatus.NEEDS_ATTENTION
            else -> SelfCheckOverallStatus.HEALTHY
        }

    private fun passed(key: SelfCheckItemKey) = SelfCheckItemResult(
        key,
        SelfCheckItemStatus.PASSED,
        SelfCheckReason.AVAILABLE
    )

    private fun failed(key: SelfCheckItemKey, reason: SelfCheckReason) = SelfCheckItemResult(
        key,
        SelfCheckItemStatus.FAILED,
        reason
    )

    private fun notApplicable(key: SelfCheckItemKey) = SelfCheckItemResult(
        key,
        SelfCheckItemStatus.NOT_APPLICABLE,
        SelfCheckReason.NOT_APPLICABLE
    )
}
