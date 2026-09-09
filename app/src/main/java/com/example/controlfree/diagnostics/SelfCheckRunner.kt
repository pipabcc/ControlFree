package com.example.controlfree.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.BootReceiver
import com.example.controlfree.MonitorNotificationChannels
import com.example.controlfree.MonitorPhase
import com.example.controlfree.data.AllowlistRepository
import com.example.controlfree.data.AllowlistRepositoryState
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationCircuitState
import com.example.controlfree.data.ForegroundObservationRetirementRegistry
import com.example.controlfree.data.ForegroundObservationStatus
import com.example.controlfree.data.ForegroundObservationWorker
import com.example.controlfree.data.MonitorRecoveryGuard
import com.example.controlfree.data.MonitorProgressReadStatus
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.data.UsageAccessManager
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class SelfCheckRunner(context: Context) {
    private val appContext = context.applicationContext
    private val allowlistRepository = AllowlistRepository.get(appContext)

    suspend fun run(): SelfCheckReport = withTimeout(SELF_CHECK_TOTAL_TIMEOUT_MILLIS) {
        runInternal()
    }

    private suspend fun runInternal(): SelfCheckReport {
        val diagnosticProbeTimestamp = System.currentTimeMillis().coerceAtLeast(0L)
        val diagnosticWrite = AndroidDiagnostics.record(
            context = appContext,
            type = DiagnosticEventType.SELF_CHECK_STARTED,
            occurredAtEpochMillis = diagnosticProbeTimestamp
        )
        val permissions = withContext(Dispatchers.IO) { readPermissionHealth() }
        val usageProbe = probeUsageStats()
        val allowlist = probeAllowlist()
        val platformSnapshot = withContext(Dispatchers.IO) {
            val preferences = PreferenceManager(appContext)
            val monitoringActive = preferences.isMonitorActive()
            val runtimeHealth = MonitorRuntimeHealthRegistry.snapshot()
            PlatformSelfCheckSnapshot(
                runtimeHealth = runtimeHealth,
                heartbeat = readHeartbeat(monitoringActive, runtimeHealth),
                recovery = readSnapshotRecovery(monitoringActive, preferences),
                mediaGuard = readMediaGuard(monitoringActive, runtimeHealth),
                storage = probeDiagnosticStorage(
                    diagnosticWrite = diagnosticWrite,
                    diagnosticProbeTimestamp = diagnosticProbeTimestamp
                ),
                bootReceiver = readBootReceiverHealth(),
                backgroundRestriction = readBackgroundRestriction()
            )
        }
        val report = SelfCheckEvaluator.evaluate(
            SelfCheckSnapshot(
                permissions = permissions,
                usageStats = mergeUsageHealth(usageProbe, platformSnapshot.runtimeHealth),
                serviceHeartbeat = platformSnapshot.heartbeat,
                snapshotRecovery = platformSnapshot.recovery,
                allowlist = allowlist,
                mediaGuard = platformSnapshot.mediaGuard,
                diagnosticStorage = platformSnapshot.storage,
                bootReceiver = platformSnapshot.bootReceiver,
                backgroundRestriction = platformSnapshot.backgroundRestriction,
                manufacturerPolicy = ManufacturerPolicySnapshot(
                    autoStart = OemSettingState.MANUAL_CONFIRMATION_REQUIRED,
                    backgroundActivity = OemSettingState.MANUAL_CONFIRMATION_REQUIRED
                )
            )
        )
        AndroidDiagnostics.record(appContext, DiagnosticEventType.SELF_CHECK_COMPLETED)
        return report
    }

    private fun readPermissionHealth(): PermissionHealthSnapshot {
        val notificationManager = try {
            appContext.getSystemService(NotificationManager::class.java)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
        val channelsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val telephonySupported = try {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        } catch (_: RuntimeException) {
            null
        }
        val fullScreenRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val powerManager = try {
            appContext.getSystemService(PowerManager::class.java)
        } catch (_: RuntimeException) {
            null
        }
        return PermissionHealthSnapshot(
            usageAccess = probeCapability {
                UsageAccessManager(appContext).hasUsageAccess()
            },
            overlay = probeCapability { Settings.canDrawOverlays(appContext) },
            phoneState = when (telephonySupported) {
                false -> CapabilityState.NOT_APPLICABLE
                true -> probeCapability { hasPermission(Manifest.permission.READ_PHONE_STATE) }
                null -> CapabilityState.UNKNOWN
            },
            notificationRuntime = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                CapabilityState.NOT_APPLICABLE
            } else {
                probeCapability { hasPermission(Manifest.permission.POST_NOTIFICATIONS) }
            },
            notificationsEnabled = probeCapability {
                NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            },
            serviceNotificationChannel = if (!channelsSupported) {
                CapabilityState.NOT_APPLICABLE
            } else if (notificationManager == null) {
                CapabilityState.UNKNOWN
            } else {
                probeCapability {
                    notificationManager
                        .getNotificationChannel(MonitorNotificationChannels.SERVICE_CHANNEL_ID)
                        ?.importance
                        ?.let { importance -> importance != NotificationManager.IMPORTANCE_NONE } == true
                }
            },
            lockRecoveryNotificationChannel = if (!channelsSupported) {
                CapabilityState.NOT_APPLICABLE
            } else if (notificationManager == null) {
                CapabilityState.UNKNOWN
            } else {
                try {
                    when (
                        notificationManager
                            .getNotificationChannel(
                                MonitorNotificationChannels.LOCK_RECOVERY_CHANNEL_ID
                            )
                            ?.importance
                    ) {
                        null, NotificationManager.IMPORTANCE_NONE -> CapabilityState.UNAVAILABLE
                        in NotificationManager.IMPORTANCE_MIN until
                            NotificationManager.IMPORTANCE_HIGH -> CapabilityState.DEGRADED
                        else -> CapabilityState.AVAILABLE
                    }
                } catch (_: RuntimeException) {
                    CapabilityState.UNKNOWN
                }
            },
            fullScreenIntent = if (!fullScreenRequired) {
                CapabilityState.NOT_APPLICABLE
            } else if (notificationManager == null) {
                CapabilityState.UNKNOWN
            } else {
                probeCapability { notificationManager.canUseFullScreenIntent() }
            },
            batteryUnrestricted = if (powerManager == null) {
                CapabilityState.UNKNOWN
            } else {
                probeCapability {
                    powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
                }
            }
        )
    }

    private suspend fun probeUsageStats(): UsageStatsHealthSnapshot {
        val manager = UsageAccessManager(appContext)
        if (!manager.hasUsageAccess()) {
            return UsageStatsHealthSnapshot(UsageStatsHealthState.QUERY_FAILED)
        }
        val worker = ForegroundObservationWorker(
            source = manager,
            resultExecutor = Executor(Runnable::run),
            maxRetiredExecutors = SELF_CHECK_MAX_RETIRED_EXECUTORS,
            retirementRegistry = selfCheckRetirementRegistry
        )
        return try {
            val now = System.currentTimeMillis().coerceAtLeast(0L)
            val generation = worker.beginObservationWindow((now - USAGE_LOOKBACK_MILLIS).coerceAtLeast(0L))
            val observation = withTimeoutOrNull(USAGE_PROBE_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<ForegroundObservation?> { continuation ->
                    val accepted = worker.requestObservation(generation, now) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    if (!accepted && continuation.isActive) continuation.resume(null)
                }
            }
            UsageStatsHealthSnapshot(observation.toUsageHealthState())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            UsageStatsHealthSnapshot(UsageStatsHealthState.QUERY_FAILED)
        } finally {
            worker.close()
        }
    }

    private suspend fun probeAllowlist(): AllowlistHealthSnapshot {
        val state = coroutineScope {
            val refreshResult = async(start = CoroutineStart.UNDISPATCHED) {
                var sawLoading = false
                withTimeoutOrNull(ALLOWLIST_PROBE_TIMEOUT_MILLIS) {
                    allowlistRepository.state.first { current ->
                        if (current is AllowlistRepositoryState.Loading) sawLoading = true
                        sawLoading && current !is AllowlistRepositoryState.Loading
                    }
                }
            }
            // 先建立 StateFlow 订阅，再触发刷新，避免快速刷新跨过 Loading/终态。
            allowlistRepository.refresh()
            refreshResult.await() ?: allowlistRepository.state.value
        }
        val snapshot = state.snapshot
        return AllowlistHealthSnapshot(
            state = when (state) {
                AllowlistRepositoryState.Uninitialized -> AllowlistCacheState.UNINITIALIZED
                is AllowlistRepositoryState.Loading -> AllowlistCacheState.LOADING
                is AllowlistRepositoryState.Ready -> AllowlistCacheState.READY
                is AllowlistRepositoryState.Stale -> AllowlistCacheState.STALE
                is AllowlistRepositoryState.Failed -> AllowlistCacheState.FAILED
            },
            hasUsableAppPartition = snapshot?.hasAppPartitionData == true,
            hasUsableCallUiPartition = snapshot?.hasCallUiPartitionData == true
        )
    }

    private fun readHeartbeat(
        monitoringActive: Boolean,
        health: MonitorRuntimeHealth
    ) = ServiceHeartbeatSnapshot(
        monitoringActive = monitoringActive,
        serviceRunning = health.serviceAvailable,
        observedAtElapsedMillis = SystemClock.elapsedRealtime(),
        lastHeartbeatElapsedMillis = health.lastHeartbeatElapsedMillis.takeIf { it > 0L },
        staleAfterMillis = HEARTBEAT_STALE_MILLIS,
        persistenceHealthy = health.persistenceHealthy,
        timerHealthy = health.timerHealthy,
        internalReceiverRegistered = health.internalReceiverRegistered,
        screenReceiverRegistered = health.screenReceiverRegistered,
        systemDialogReceiverRegistered = health.systemDialogReceiverRegistered,
        callStateMonitorRequired = health.callStateMonitorRequired,
        callStateMonitorRegistered = health.callStateMonitorRegistered
    )

    private fun mergeUsageHealth(
        probe: UsageStatsHealthSnapshot,
        runtimeHealth: MonitorRuntimeHealth
    ): UsageStatsHealthSnapshot {
        if (
            probe.state == UsageStatsHealthState.TIMED_OUT ||
            probe.state == UsageStatsHealthState.CIRCUIT_OPEN ||
            probe.state == UsageStatsHealthState.QUERY_FAILED
        ) {
            return probe
        }
        val observer = runtimeHealth.foregroundObserverHealth ?: return probe
        return when {
            observer.circuit.state == ForegroundObservationCircuitState.OPEN ->
                UsageStatsHealthSnapshot(UsageStatsHealthState.CIRCUIT_OPEN)
            observer.circuit.state == ForegroundObservationCircuitState.HALF_OPEN ||
                observer.retiredExecutorCount > 0 ->
                UsageStatsHealthSnapshot(UsageStatsHealthState.WORKER_RECOVERED)
            else -> probe
        }
    }

    private fun readSnapshotRecovery(
        monitoringActive: Boolean,
        preferences: PreferenceManager
    ): SnapshotRecoveryHealthSnapshot {
        val guardArmed = MonitorRecoveryGuard(appContext).requiresLock()
        val progress = preferences.inspectMonitorProgress()
        return SnapshotRecoveryHealthSnapshot(
            resolveSnapshotRecoveryState(
                monitoringActive = monitoringActive,
                guardArmed = guardArmed,
                progressStatus = progress.status
            )
        )
    }

    private fun readMediaGuard(
        monitoringActive: Boolean,
        health: MonitorRuntimeHealth
    ): MediaGuardHealthSnapshot = resolveMediaGuardHealth(
        monitoringActive = monitoringActive,
        health = health,
        nowElapsedMillis = SystemClock.elapsedRealtime(),
        staleAfterMillis = MEDIA_PROBE_STALE_MILLIS
    )

    private fun probeDiagnosticStorage(
        diagnosticWrite: java.util.concurrent.CompletableFuture<DiagnosticWriteResult>,
        diagnosticProbeTimestamp: Long
    ): DiagnosticStorageHealthSnapshot {
        val diagnosticsDirectory = File(appContext.noBackupFilesDir, DIAGNOSTICS_DIRECTORY_NAME)
        val usesPrivateDirectory = try {
            diagnosticsDirectory.canonicalPath.startsWith(appContext.noBackupFilesDir.canonicalPath)
        } catch (_: Exception) {
            false
        }
        val logBytes = try {
            diagnosticsDirectory.listFiles()
                .orEmpty()
                .filter { file -> file.extension == "log" }
                .sumOf(File::length)
        } catch (_: SecurityException) {
            Long.MAX_VALUE
        }
        val probe = File(diagnosticsDirectory, STORAGE_PROBE_FILE_NAME)
        val fileProbeState = try {
            if (!diagnosticsDirectory.exists() && !diagnosticsDirectory.mkdirs()) {
                StorageProbeState.WRITE_FAILED
            } else {
                probe.writeText(STORAGE_PROBE_CONTENT)
                if (probe.readText() == STORAGE_PROBE_CONTENT) {
                    StorageProbeState.HEALTHY
                } else {
                    StorageProbeState.READ_FAILED
                }
            }
        } catch (_: java.io.IOException) {
            StorageProbeState.WRITE_FAILED
        } catch (_: SecurityException) {
            StorageProbeState.WRITE_FAILED
        } finally {
            try {
                probe.delete()
            } catch (_: SecurityException) {
                // 临时探针会在下次自检覆盖，不包含用户数据。
            }
        }
        val state = if (fileProbeState != StorageProbeState.HEALTHY) {
            fileProbeState
        } else {
            try {
                val writeResult = diagnosticWrite.get(DIAGNOSTIC_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (writeResult != DiagnosticWriteResult.WRITTEN) {
                    StorageProbeState.WRITE_FAILED
                } else {
                    val readResult = AndroidDiagnostics.read(appContext)
                        .get(DIAGNOSTIC_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (
                        readResult.inaccessibleFileCount == 0 &&
                        readResult.records.any { record ->
                            record.type == DiagnosticEventType.SELF_CHECK_STARTED &&
                                record.occurredAtEpochMillis == diagnosticProbeTimestamp
                        }
                    ) {
                        StorageProbeState.HEALTHY
                    } else {
                        StorageProbeState.READ_FAILED
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("diagnostic probe interrupted").also {
                    it.initCause(interrupted)
                }
            } catch (_: Exception) {
                StorageProbeState.WRITE_FAILED
            }
        }
        return DiagnosticStorageHealthSnapshot(
            state = state,
            usesPrivateDirectory = usesPrivateDirectory,
            withinSizeLimit = logBytes <= DiagnosticLogStore.MAX_TOTAL_LOG_BYTES
        )
    }

    private fun readBootReceiverHealth(): BootReceiverHealthSnapshot {
        val component = ComponentName(appContext, BootReceiver::class.java)
        var queryFailed = false
        val declared = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getReceiverInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getReceiverInfo(component, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            queryFailed = true
            false
        }
        val enabled = try {
            val componentState = appContext.packageManager.getComponentEnabledSetting(component)
            componentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                componentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                componentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        } catch (_: RuntimeException) {
            queryFailed = true
            false
        }
        val currentBootCount = try {
            Settings.Global.getInt(
                appContext.contentResolver,
                Settings.Global.BOOT_COUNT,
                BootEvidenceStore.UNKNOWN_BOOT_COUNT
            )
        } catch (_: RuntimeException) {
            BootEvidenceStore.UNKNOWN_BOOT_COUNT
        }
        val evidence = BootEvidenceStore(appContext).read()
        val evidenceState = when {
            currentBootCount == BootEvidenceStore.UNKNOWN_BOOT_COUNT ->
                BootReceiverEvidenceState.BOOT_COUNT_UNAVAILABLE
            evidence?.bootCount == currentBootCount ->
                BootReceiverEvidenceState.CURRENT_BOOT_CONFIRMED
            else -> BootReceiverEvidenceState.CURRENT_BOOT_EVIDENCE_MISSING
        }
        return BootReceiverHealthSnapshot(
            declared = declared,
            enabled = enabled,
            evidence = evidenceState,
            queryFailed = queryFailed
        )
    }

    private fun readBackgroundRestriction(): BackgroundRestrictionSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return BackgroundRestrictionSnapshot(BackgroundRestrictionState.UNKNOWN)
        }
        return try {
            val activityManager = appContext.getSystemService(ActivityManager::class.java)
            BackgroundRestrictionSnapshot(
                when {
                    activityManager == null -> BackgroundRestrictionState.UNKNOWN
                    activityManager.isBackgroundRestricted -> BackgroundRestrictionState.RESTRICTED
                    else -> BackgroundRestrictionState.UNRESTRICTED
                }
            )
        } catch (_: RuntimeException) {
            BackgroundRestrictionSnapshot(BackgroundRestrictionState.UNKNOWN)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun capability(available: Boolean): CapabilityState =
        if (available) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE

    private inline fun probeCapability(probe: () -> Boolean): CapabilityState = try {
        capability(probe())
    } catch (_: RuntimeException) {
        CapabilityState.UNKNOWN
    }

    private fun ForegroundObservation?.toUsageHealthState(): UsageStatsHealthState = when {
        this == null -> UsageStatsHealthState.QUERY_FAILED
        status == ForegroundObservationStatus.AVAILABLE &&
            (packageName != null || newEvents.isNotEmpty()) -> UsageStatsHealthState.HEALTHY
        status == ForegroundObservationStatus.AVAILABLE -> UsageStatsHealthState.NO_DATA
        status == ForegroundObservationStatus.TIMEOUT -> UsageStatsHealthState.TIMED_OUT
        status == ForegroundObservationStatus.CIRCUIT_OPEN -> UsageStatsHealthState.CIRCUIT_OPEN
        else -> UsageStatsHealthState.QUERY_FAILED
    }

    private data class PlatformSelfCheckSnapshot(
        val runtimeHealth: MonitorRuntimeHealth,
        val heartbeat: ServiceHeartbeatSnapshot,
        val recovery: SnapshotRecoveryHealthSnapshot,
        val mediaGuard: MediaGuardHealthSnapshot,
        val storage: DiagnosticStorageHealthSnapshot,
        val bootReceiver: BootReceiverHealthSnapshot,
        val backgroundRestriction: BackgroundRestrictionSnapshot
    )

    private companion object {
        const val USAGE_LOOKBACK_MILLIS = 5L * 60L * 1_000L
        const val USAGE_PROBE_TIMEOUT_MILLIS = 3_500L
        const val ALLOWLIST_PROBE_TIMEOUT_MILLIS = 3_000L
        const val HEARTBEAT_STALE_MILLIS = 5_000L
        const val MEDIA_PROBE_STALE_MILLIS = 20_000L
        const val DIAGNOSTICS_DIRECTORY_NAME = "diagnostics"
        const val STORAGE_PROBE_FILE_NAME = "self-check.tmp"
        const val STORAGE_PROBE_CONTENT = "ok"
        const val DIAGNOSTIC_PROBE_TIMEOUT_SECONDS = 3L
        const val SELF_CHECK_TOTAL_TIMEOUT_MILLIS = 15_000L
        const val SELF_CHECK_MAX_RETIRED_EXECUTORS = 1
        val selfCheckRetirementRegistry = ForegroundObservationRetirementRegistry()
    }
}

internal fun resolveMediaGuardHealth(
    monitoringActive: Boolean,
    health: MonitorRuntimeHealth,
    nowElapsedMillis: Long,
    staleAfterMillis: Long
): MediaGuardHealthSnapshot {
    val required = monitoringActive && health.phase == MonitorPhase.LOCK
    val state = when {
        !required -> MediaGuardState.HEALTHY
        !health.serviceAvailable -> MediaGuardState.FAILED
        !health.mediaReplayGuardEnabled -> MediaGuardState.DISABLED
        health.lastMediaProbeElapsedMillis <= 0L -> MediaGuardState.NOT_CHECKED
        !health.mediaProbeAvailable -> MediaGuardState.PROBE_UNAVAILABLE
        nowElapsedMillis < health.lastMediaProbeElapsedMillis || staleAfterMillis <= 0L ->
            MediaGuardState.FAILED
        nowElapsedMillis - health.lastMediaProbeElapsedMillis > staleAfterMillis ->
            MediaGuardState.PROBE_UNAVAILABLE
        else -> MediaGuardState.HEALTHY
    }
    return MediaGuardHealthSnapshot(required = required, state = state)
}

internal fun resolveSnapshotRecoveryState(
    monitoringActive: Boolean,
    guardArmed: Boolean,
    progressStatus: MonitorProgressReadStatus
): SnapshotRecoveryState {
    if (!monitoringActive) {
        return if (guardArmed) SnapshotRecoveryState.FALLBACK_ARMED
        else SnapshotRecoveryState.NOT_REQUIRED
    }
    return when {
        progressStatus == MonitorProgressReadStatus.AVAILABLE ->
            SnapshotRecoveryState.HEALTHY
        progressStatus == MonitorProgressReadStatus.CORRUPTED ->
            SnapshotRecoveryState.SNAPSHOT_CORRUPTED
        progressStatus == MonitorProgressReadStatus.READ_FAILED ->
            SnapshotRecoveryState.READ_FAILED
        guardArmed -> SnapshotRecoveryState.FALLBACK_ARMED
        else -> SnapshotRecoveryState.SNAPSHOT_MISSING
    }
}
