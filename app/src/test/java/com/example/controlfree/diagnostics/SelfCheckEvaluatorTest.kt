package com.example.controlfree.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfCheckEvaluatorTest {
    @Test
    fun `全部健康时输出健康报告且检查项完整唯一`() {
        val report = SelfCheckEvaluator.evaluate(healthySnapshot())

        assertEquals(SelfCheckOverallStatus.HEALTHY, report.overallStatus)
        assertEquals(SelfCheckItemKey.entries.size, report.items.size)
        assertEquals(SelfCheckItemKey.entries.toSet(), report.items.map { it.key }.toSet())
    }

    @Test
    fun `必需权限缺失失败而不适用权限跳过`() {
        val report = evaluate {
            copy(
                permissions = permissions.copy(
                    overlay = CapabilityState.UNAVAILABLE,
                    phoneState = CapabilityState.NOT_APPLICABLE
                )
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.OVERLAY_PERMISSION,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.PERMISSION_DENIED
        )
        assertResult(
            report,
            SelfCheckItemKey.PHONE_STATE_PERMISSION,
            SelfCheckItemStatus.NOT_APPLICABLE,
            SelfCheckReason.NOT_APPLICABLE
        )
    }

    @Test
    fun `全屏通知缺失只降级为警告`() {
        val report = evaluate {
            copy(
                permissions = permissions.copy(fullScreenIntent = CapabilityState.UNAVAILABLE)
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.FULL_SCREEN_INTENT_PERMISSION,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.RECOMMENDED_PERMISSION_DENIED
        )
        assertEquals(SelfCheckOverallStatus.NEEDS_ATTENTION, report.overallStatus)
    }

    @Test
    fun `通知运行时权限缺失时下游通知开关不重复报错`() {
        val report = evaluate {
            copy(
                permissions = permissions.copy(
                    notificationRuntime = CapabilityState.UNAVAILABLE,
                    notificationsEnabled = CapabilityState.UNAVAILABLE
                )
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.NOTIFICATION_PERMISSION,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.PERMISSION_DENIED
        )
        assertResult(
            report,
            SelfCheckItemKey.NOTIFICATIONS_ENABLED,
            SelfCheckItemStatus.NOT_APPLICABLE,
            SelfCheckReason.NOT_APPLICABLE
        )
    }

    @Test
    fun `锁定恢复频道关闭只产生建议警告`() {
        val report = evaluate {
            copy(
                permissions = permissions.copy(
                    lockRecoveryNotificationChannel = CapabilityState.UNAVAILABLE
                )
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.LOCK_RECOVERY_NOTIFICATION_CHANNEL,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.RECOMMENDED_PERMISSION_DENIED
        )
        assertEquals(SelfCheckOverallStatus.NEEDS_ATTENTION, report.overallStatus)
    }

    @Test
    fun `电池策略未设为不限制时给出警告`() {
        val report = evaluate {
            copy(
                permissions = permissions.copy(
                    batteryUnrestricted = CapabilityState.UNAVAILABLE
                )
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.BATTERY_OPTIMIZATION,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.BATTERY_RESTRICTION_PRESENT
        )
    }

    @Test
    fun `UsageStats各异常状态映射稳定`() {
        val expected = mapOf(
            UsageStatsHealthState.NOT_CHECKED to pair(
                SelfCheckItemStatus.IN_PROGRESS,
                SelfCheckReason.USAGE_STATS_NOT_CHECKED
            ),
            UsageStatsHealthState.NO_DATA to pair(
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.USAGE_STATS_NO_DATA
            ),
            UsageStatsHealthState.WORKER_RECOVERED to pair(
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.USAGE_STATS_WORKER_RECOVERED
            ),
            UsageStatsHealthState.TIMED_OUT to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.USAGE_STATS_TIMED_OUT
            ),
            UsageStatsHealthState.CIRCUIT_OPEN to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.USAGE_STATS_CIRCUIT_OPEN
            ),
            UsageStatsHealthState.QUERY_FAILED to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.USAGE_STATS_QUERY_FAILED
            )
        )

        expected.forEach { (state, result) ->
            val report = evaluate { copy(usageStats = UsageStatsHealthSnapshot(state)) }
            assertResult(report, SelfCheckItemKey.USAGE_STATS_HEALTH, result.first, result.second)
        }
    }

    @Test
    fun `未监督时服务心跳不适用`() {
        val report = evaluate {
            copy(serviceHeartbeat = serviceHeartbeat.copy(monitoringActive = false))
        }

        assertResult(
            report,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.NOT_APPLICABLE,
            SelfCheckReason.MONITORING_INACTIVE
        )
    }

    @Test
    fun `监督中服务停止或心跳缺失均失败`() {
        val stopped = evaluate {
            copy(serviceHeartbeat = serviceHeartbeat.copy(serviceRunning = false))
        }
        val missing = evaluate {
            copy(serviceHeartbeat = serviceHeartbeat.copy(lastHeartbeatElapsedMillis = null))
        }

        assertResult(
            stopped,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.SERVICE_NOT_RUNNING
        )
        assertResult(
            missing,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.HEARTBEAT_MISSING
        )
    }

    @Test
    fun `心跳恰好处于阈值时健康超过阈值时过期`() {
        val boundary = evaluate {
            copy(
                serviceHeartbeat = serviceHeartbeat.copy(
                    observedAtElapsedMillis = 11_000L,
                    lastHeartbeatElapsedMillis = 1_000L,
                    staleAfterMillis = 10_000L
                )
            )
        }
        val stale = evaluate {
            copy(
                serviceHeartbeat = serviceHeartbeat.copy(
                    observedAtElapsedMillis = 11_001L,
                    lastHeartbeatElapsedMillis = 1_000L,
                    staleAfterMillis = 10_000L
                )
            )
        }

        assertResult(
            boundary,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.PASSED,
            SelfCheckReason.AVAILABLE
        )
        assertResult(
            stale,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.HEARTBEAT_STALE
        )
    }

    @Test
    fun `关键接收器和持久化异常会让服务健康检查失败`() {
        val receiverFailure = evaluate {
            copy(
                serviceHeartbeat = serviceHeartbeat.copy(
                    systemDialogReceiverRegistered = false
                )
            )
        }
        val persistenceFailure = evaluate {
            copy(
                serviceHeartbeat = serviceHeartbeat.copy(persistenceHealthy = false)
            )
        }

        assertResult(
            receiverFailure,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.CRITICAL_RECEIVER_MISSING
        )
        assertResult(
            persistenceFailure,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.PERSISTENCE_UNHEALTHY
        )
    }

    @Test
    fun `屏幕广播缺失不能掩盖过期心跳`() {
        val report = evaluate {
            copy(
                serviceHeartbeat = serviceHeartbeat.copy(
                    screenReceiverRegistered = false,
                    observedAtElapsedMillis = 20_001L,
                    lastHeartbeatElapsedMillis = 10_000L,
                    staleAfterMillis = 10_000L
                )
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.SERVICE_HEARTBEAT,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.HEARTBEAT_STALE
        )
    }

    @Test
    fun `心跳时钟回退和非法阈值按无效输入失败`() {
        listOf(
            healthySnapshot().serviceHeartbeat.copy(
                observedAtElapsedMillis = 9_000L,
                lastHeartbeatElapsedMillis = 10_000L
            ),
            healthySnapshot().serviceHeartbeat.copy(staleAfterMillis = 0L),
            healthySnapshot().serviceHeartbeat.copy(observedAtElapsedMillis = -1L)
        ).forEach { heartbeat ->
            val report = evaluate { copy(serviceHeartbeat = heartbeat) }
            assertResult(
                report,
                SelfCheckItemKey.SERVICE_HEARTBEAT,
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.HEARTBEAT_INVALID
            )
        }
    }

    @Test
    fun `快照降级恢复警告而损坏和保护未武装失败`() {
        val expected = mapOf(
            SnapshotRecoveryState.FALLBACK_ARMED to pair(
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.RECOVERY_FALLBACK_ARMED
            ),
            SnapshotRecoveryState.SNAPSHOT_MISSING to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.SNAPSHOT_MISSING
            ),
            SnapshotRecoveryState.SNAPSHOT_CORRUPTED to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.SNAPSHOT_CORRUPTED
            ),
            SnapshotRecoveryState.READ_FAILED to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.SNAPSHOT_READ_FAILED
            ),
            SnapshotRecoveryState.RECOVERY_GUARD_DISARMED to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.RECOVERY_GUARD_DISARMED
            )
        )

        expected.forEach { (state, result) ->
            val report = evaluate {
                copy(snapshotRecovery = SnapshotRecoveryHealthSnapshot(state))
            }
            assertResult(report, SelfCheckItemKey.SNAPSHOT_RECOVERY, result.first, result.second)
        }
    }

    @Test
    fun `白名单五态完整映射`() {
        val expected = mapOf(
            AllowlistCacheState.UNINITIALIZED to pair(
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.ALLOWLIST_UNINITIALIZED
            ),
            AllowlistCacheState.LOADING to pair(
                SelfCheckItemStatus.IN_PROGRESS,
                SelfCheckReason.ALLOWLIST_LOADING
            ),
            AllowlistCacheState.READY to pair(
                SelfCheckItemStatus.PASSED,
                SelfCheckReason.ALLOWLIST_READY
            ),
            AllowlistCacheState.STALE to pair(
                SelfCheckItemStatus.WARNING,
                SelfCheckReason.ALLOWLIST_STALE
            ),
            AllowlistCacheState.FAILED to pair(
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.ALLOWLIST_FAILED
            )
        )

        expected.forEach { (state, result) ->
            val report = evaluate {
                copy(allowlist = allowlist.copy(state = state))
            }
            assertResult(report, SelfCheckItemKey.ALLOWLIST_CACHE, result.first, result.second)
        }
    }

    @Test
    fun `白名单Ready或Stale缺少任一可用分区均失败`() {
        listOf(AllowlistCacheState.READY, AllowlistCacheState.STALE).forEach { state ->
            val report = evaluate {
                copy(
                    allowlist = AllowlistHealthSnapshot(
                        state = state,
                        hasUsableAppPartition = true,
                        hasUsableCallUiPartition = false
                    )
                )
            }
            assertResult(
                report,
                SelfCheckItemKey.ALLOWLIST_CACHE,
                SelfCheckItemStatus.FAILED,
                SelfCheckReason.ALLOWLIST_PARTITION_MISSING
            )
        }
    }

    @Test
    fun `媒体守卫非锁定期不适用且探测不可用时警告`() {
        val notRequired = evaluate {
            copy(mediaGuard = mediaGuard.copy(required = false, state = MediaGuardState.FAILED))
        }
        val unavailable = evaluate {
            copy(mediaGuard = mediaGuard.copy(state = MediaGuardState.PROBE_UNAVAILABLE))
        }

        assertResult(
            notRequired,
            SelfCheckItemKey.MEDIA_REPLAY_GUARD,
            SelfCheckItemStatus.NOT_APPLICABLE,
            SelfCheckReason.MEDIA_GUARD_NOT_REQUIRED
        )
        assertResult(
            unavailable,
            SelfCheckItemKey.MEDIA_REPLAY_GUARD,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.MEDIA_PROBE_UNAVAILABLE
        )
    }

    @Test
    fun `媒体守卫需要运行却禁用或失败时自检失败`() {
        mapOf(
            MediaGuardState.DISABLED to SelfCheckReason.MEDIA_GUARD_DISABLED,
            MediaGuardState.FAILED to SelfCheckReason.MEDIA_GUARD_FAILED
        ).forEach { (state, reason) ->
            val report = evaluate { copy(mediaGuard = mediaGuard.copy(state = state)) }
            assertResult(
                report,
                SelfCheckItemKey.MEDIA_REPLAY_GUARD,
                SelfCheckItemStatus.FAILED,
                reason
            )
        }
    }

    @Test
    fun `诊断存储必须同时可写私有且有界`() {
        val notPrivate = evaluate {
            copy(diagnosticStorage = diagnosticStorage.copy(usesPrivateDirectory = false))
        }
        val unbounded = evaluate {
            copy(diagnosticStorage = diagnosticStorage.copy(withinSizeLimit = false))
        }
        val writeFailed = evaluate {
            copy(
                diagnosticStorage = diagnosticStorage.copy(state = StorageProbeState.WRITE_FAILED)
            )
        }

        assertResult(
            notPrivate,
            SelfCheckItemKey.DIAGNOSTIC_STORAGE,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.STORAGE_NOT_PRIVATE
        )
        assertResult(
            unbounded,
            SelfCheckItemKey.DIAGNOSTIC_STORAGE,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.STORAGE_SIZE_LIMIT_EXCEEDED
        )
        assertResult(
            writeFailed,
            SelfCheckItemKey.DIAGNOSTIC_STORAGE,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.STORAGE_WRITE_FAILED
        )
    }

    @Test
    fun `BootReceiver缺失和禁用使用不同原因码`() {
        val missing = evaluate {
            copy(
                bootReceiver = BootReceiverHealthSnapshot(
                    declared = false,
                    enabled = false,
                    evidence = BootReceiverEvidenceState.CURRENT_BOOT_CONFIRMED
                )
            )
        }
        val disabled = evaluate {
            copy(
                bootReceiver = BootReceiverHealthSnapshot(
                    declared = true,
                    enabled = false,
                    evidence = BootReceiverEvidenceState.CURRENT_BOOT_CONFIRMED
                )
            )
        }

        assertResult(
            missing,
            SelfCheckItemKey.BOOT_RECEIVER,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.BOOT_RECEIVER_MISSING
        )
        assertResult(
            disabled,
            SelfCheckItemKey.BOOT_RECEIVER,
            SelfCheckItemStatus.FAILED,
            SelfCheckReason.BOOT_RECEIVER_DISABLED
        )
    }

    @Test
    fun `BootReceiver查询异常不会误报为组件缺失`() {
        val report = evaluate {
            copy(
                bootReceiver = bootReceiver.copy(
                    declared = false,
                    enabled = false,
                    queryFailed = true
                )
            )
        }

        assertResult(
            report,
            SelfCheckItemKey.BOOT_RECEIVER,
            SelfCheckItemStatus.WARNING,
            SelfCheckReason.BOOT_RECEIVER_STATE_UNKNOWN
        )
    }

    @Test
    fun `BootReceiver当前启动证据缺失或系统启动计数不可读时警告`() {
        val expected = mapOf(
            BootReceiverEvidenceState.CURRENT_BOOT_EVIDENCE_MISSING to
                SelfCheckReason.CURRENT_BOOT_EVIDENCE_MISSING,
            BootReceiverEvidenceState.BOOT_COUNT_UNAVAILABLE to
                SelfCheckReason.BOOT_COUNT_UNAVAILABLE
        )

        expected.forEach { (evidence, reason) ->
            val report = evaluate {
                copy(bootReceiver = bootReceiver.copy(evidence = evidence))
            }
            assertResult(
                report,
                SelfCheckItemKey.BOOT_RECEIVER,
                SelfCheckItemStatus.WARNING,
                reason
            )
        }
    }

    @Test
    fun `后台受限和无法检测均提示警告`() {
        val expected = mapOf(
            BackgroundRestrictionState.RESTRICTED to SelfCheckReason.BACKGROUND_RESTRICTED,
            BackgroundRestrictionState.UNKNOWN to SelfCheckReason.BACKGROUND_STATE_UNKNOWN
        )

        expected.forEach { (state, reason) ->
            val report = evaluate {
                copy(backgroundRestriction = BackgroundRestrictionSnapshot(state))
            }
            assertResult(
                report,
                SelfCheckItemKey.BACKGROUND_RESTRICTION,
                SelfCheckItemStatus.WARNING,
                reason
            )
        }
    }

    @Test
    fun `厂商设置无法读取时明确要求人工确认`() {
        val report = evaluate {
            copy(
                manufacturerPolicy = ManufacturerPolicySnapshot(
                    autoStart = OemSettingState.MANUAL_CONFIRMATION_REQUIRED,
                    backgroundActivity = OemSettingState.MANUAL_CONFIRMATION_REQUIRED
                )
            )
        }

        listOf(
            SelfCheckItemKey.OEM_AUTO_START,
            SelfCheckItemKey.OEM_BACKGROUND_ACTIVITY
        ).forEach { key ->
            assertResult(
                report,
                key,
                SelfCheckItemStatus.MANUAL_CONFIRMATION_REQUIRED,
                SelfCheckReason.OEM_SETTING_REQUIRES_MANUAL_CONFIRMATION
            )
        }
        assertEquals(SelfCheckOverallStatus.NEEDS_ATTENTION, report.overallStatus)
    }

    @Test
    fun `总体状态优先级为失败高于进行中高于提醒`() {
        val failed = evaluate {
            copy(
                usageStats = UsageStatsHealthSnapshot(UsageStatsHealthState.NOT_CHECKED),
                backgroundRestriction = BackgroundRestrictionSnapshot(
                    BackgroundRestrictionState.RESTRICTED
                ),
                bootReceiver = BootReceiverHealthSnapshot(
                    declared = false,
                    enabled = false,
                    evidence = BootReceiverEvidenceState.CURRENT_BOOT_CONFIRMED
                )
            )
        }
        val warning = evaluate {
            copy(
                usageStats = UsageStatsHealthSnapshot(UsageStatsHealthState.NOT_CHECKED),
                backgroundRestriction = BackgroundRestrictionSnapshot(
                    BackgroundRestrictionState.RESTRICTED
                )
            )
        }
        val inProgress = evaluate {
            copy(usageStats = UsageStatsHealthSnapshot(UsageStatsHealthState.NOT_CHECKED))
        }

        assertEquals(SelfCheckOverallStatus.FAILED, failed.overallStatus)
        assertEquals(SelfCheckOverallStatus.IN_PROGRESS, warning.overallStatus)
        assertEquals(SelfCheckOverallStatus.IN_PROGRESS, inProgress.overallStatus)

        val attention = evaluate {
            copy(
                backgroundRestriction = BackgroundRestrictionSnapshot(
                    BackgroundRestrictionState.RESTRICTED
                )
            )
        }
        assertEquals(SelfCheckOverallStatus.NEEDS_ATTENTION, attention.overallStatus)
    }

    private fun evaluate(transform: SelfCheckSnapshot.() -> SelfCheckSnapshot): SelfCheckReport =
        SelfCheckEvaluator.evaluate(healthySnapshot().transform())

    private fun healthySnapshot() = SelfCheckSnapshot(
        permissions = PermissionHealthSnapshot(
            usageAccess = CapabilityState.AVAILABLE,
            overlay = CapabilityState.AVAILABLE,
            phoneState = CapabilityState.AVAILABLE,
            notificationRuntime = CapabilityState.AVAILABLE,
            notificationsEnabled = CapabilityState.AVAILABLE,
            serviceNotificationChannel = CapabilityState.AVAILABLE,
            lockRecoveryNotificationChannel = CapabilityState.AVAILABLE,
            fullScreenIntent = CapabilityState.AVAILABLE,
            batteryUnrestricted = CapabilityState.AVAILABLE
        ),
        usageStats = UsageStatsHealthSnapshot(UsageStatsHealthState.HEALTHY),
        serviceHeartbeat = ServiceHeartbeatSnapshot(
            monitoringActive = true,
            serviceRunning = true,
            observedAtElapsedMillis = 10_000L,
            lastHeartbeatElapsedMillis = 9_000L,
            staleAfterMillis = 5_000L
        ),
        snapshotRecovery = SnapshotRecoveryHealthSnapshot(SnapshotRecoveryState.HEALTHY),
        allowlist = AllowlistHealthSnapshot(
            state = AllowlistCacheState.READY,
            hasUsableAppPartition = true,
            hasUsableCallUiPartition = true
        ),
        mediaGuard = MediaGuardHealthSnapshot(
            required = true,
            state = MediaGuardState.HEALTHY
        ),
        diagnosticStorage = DiagnosticStorageHealthSnapshot(
            state = StorageProbeState.HEALTHY,
            usesPrivateDirectory = true,
            withinSizeLimit = true
        ),
        bootReceiver = BootReceiverHealthSnapshot(
            declared = true,
            enabled = true,
            evidence = BootReceiverEvidenceState.CURRENT_BOOT_CONFIRMED
        ),
        backgroundRestriction = BackgroundRestrictionSnapshot(
            BackgroundRestrictionState.UNRESTRICTED
        ),
        manufacturerPolicy = ManufacturerPolicySnapshot(
            autoStart = OemSettingState.ENABLED,
            backgroundActivity = OemSettingState.ENABLED
        )
    )

    private fun pair(
        status: SelfCheckItemStatus,
        reason: SelfCheckReason
    ): Pair<SelfCheckItemStatus, SelfCheckReason> = status to reason

    private fun assertResult(
        report: SelfCheckReport,
        key: SelfCheckItemKey,
        status: SelfCheckItemStatus,
        reason: SelfCheckReason
    ) {
        assertEquals(SelfCheckItemResult(key, status, reason), report.resultFor(key))
    }
}
