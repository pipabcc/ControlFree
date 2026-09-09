package com.example.controlfree

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.AllowlistRepository
import com.example.controlfree.data.AllowlistRepositoryState
import com.example.controlfree.data.AllowlistSnapshot
import com.example.controlfree.data.AppAllowlistManager
import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationStatus
import com.example.controlfree.data.ForegroundObservationWorker
import com.example.controlfree.data.LockUnlockFeaturePreferences
import com.example.controlfree.data.isLockUnlockFundingMethodEnabled
import com.example.controlfree.data.MonitorRecoveryGuard
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.diagnostics.MonitorRuntimeHealth
import com.example.controlfree.diagnostics.MonitorRuntimeHealthRegistry
import com.example.controlfree.diagnostics.AndroidDiagnostics
import com.example.controlfree.diagnostics.DiagnosticEventType
import com.example.controlfree.growth.ContinuationRewardRequest
import com.example.controlfree.growth.GrowthCycleOutcome
import com.example.controlfree.growth.GrowthCycleSettlementRequest
import com.example.controlfree.growth.GrowthDayWindow
import com.example.controlfree.growth.GrowthPolicy
import com.example.controlfree.growth.GrowthPrepareStatus
import com.example.controlfree.growth.GrowthRepository
import com.example.controlfree.growth.GrowthRuntimeCycle
import com.example.controlfree.growth.GrowthRuntimeStore
import com.example.controlfree.growth.GrowthUnlockActionKind
import com.example.controlfree.growth.GrowthUnlockOrder
import com.example.controlfree.growth.GrowthUnlockOrderState
import com.example.controlfree.growth.PreparePauseOrderRequest
import com.example.controlfree.growth.PrepareSkipOrderRequest
import com.example.controlfree.knowledge.AndroidKnowledgePassRepository
import com.example.controlfree.knowledge.KnowledgeChallengeBinding
import com.example.controlfree.knowledge.KnowledgePassCommitResult
import com.example.controlfree.knowledge.KnowledgePassRepository
import com.example.controlfree.knowledge.KnowledgePassReserveResult
import com.example.controlfree.knowledge.KnowledgePassRollbackResult
import com.example.controlfree.supervision.runtime.ActiveDeviceSupervision
import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import com.example.controlfree.supervision.runtime.ScheduledOccurrenceSuppression
import com.example.controlfree.supervision.runtime.ScheduledOwnerReadResult
import com.example.controlfree.supervision.runtime.SupervisionAlarmScheduler
import com.example.controlfree.supervision.runtime.SupervisionScheduleEvaluation
import com.example.controlfree.supervision.runtime.SupervisionScheduleEvaluator
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import com.example.controlfree.supervision.runtime.toScheduledCycleConfiguration
import com.example.controlfree.supervision.history.SupervisionHistoryRecorder
import com.example.controlfree.supervision.history.SupervisionRecoveryStatus
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.PlanWriteResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.persistence.SupervisionHistoryRepository
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MonitorService : Service() {

    companion object {
        const val ACTION_START_MONITOR = "com.example.controlfree.ACTION_START_MONITOR"
        const val ACTION_START_FOCUS = "com.example.controlfree.ACTION_START_FOCUS"
        const val ACTION_START_SCHEDULED_MONITOR =
            "com.example.controlfree.ACTION_START_SCHEDULED_MONITOR"
        const val ACTION_RESTORE_SCHEDULED_MONITOR =
            "com.example.controlfree.ACTION_RESTORE_SCHEDULED_MONITOR"
        const val ACTION_STOP_MONITOR = "com.example.controlfree.ACTION_STOP_MONITOR"
        const val ACTION_STOP_SCHEDULED_MONITOR =
            "com.example.controlfree.ACTION_STOP_SCHEDULED_MONITOR"
        const val ACTION_TIMER_TICK = "com.example.controlfree.ACTION_TIMER_TICK"
        const val ACTION_MONITOR_STARTED = "com.example.controlfree.ACTION_MONITOR_STARTED"
        const val ACTION_MONITOR_START_FAILED =
            "com.example.controlfree.ACTION_MONITOR_START_FAILED"
        const val ACTION_MONITOR_STOPPED = "com.example.controlfree.ACTION_MONITOR_STOPPED"
        const val ACTION_MONITOR_ERROR = "com.example.controlfree.ACTION_MONITOR_ERROR"
        const val ACTION_FORCE_UNLOCK = "com.example.controlfree.ACTION_FORCE_UNLOCK"
        const val ACTION_RESUME_PAUSED_MONITOR =
            "com.example.controlfree.ACTION_RESUME_PAUSED_MONITOR"
        const val ACTION_RECONCILE_MONITOR_PHASE =
            "com.example.controlfree.ACTION_RECONCILE_MONITOR_PHASE"
        const val ACTION_OPEN_ALLOWED_APP = "com.example.controlfree.ACTION_OPEN_ALLOWED_APP"
        const val ACTION_ALLOWLIST_CHANGED = "com.example.controlfree.ACTION_ALLOWLIST_CHANGED"
        const val ACTION_ARM_SELF_DISCIPLINE =
            "com.example.controlfree.ACTION_ARM_SELF_DISCIPLINE"
        const val ACTION_GROWTH_UPDATED = "com.example.controlfree.ACTION_GROWTH_UPDATED"
        const val ACTION_OVERLAY_PERMISSION_SETTINGS_OPENED =
            "com.example.controlfree.ACTION_OVERLAY_PERMISSION_SETTINGS_OPENED"
        const val ACTION_LOCK_ACTIVITY_VISIBILITY =
            "com.example.controlfree.ACTION_LOCK_ACTIVITY_VISIBILITY"
        const val ACTION_KNOWLEDGE_CHALLENGE_VISIBILITY =
            "com.example.controlfree.ACTION_KNOWLEDGE_CHALLENGE_VISIBILITY"

        const val EXTRA_STATE = "state"
        const val EXTRA_REMAINING_SECONDS = "remaining_seconds"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_COMMAND_ELAPSED_MILLIS = "command_elapsed_millis"
        const val EXTRA_VISIBLE = "visible"
        internal const val EXTRA_VISIBILITY_EVENT_ELAPSED_NANOS =
            "visibility_event_elapsed_nanos"
        internal const val EXTRA_LOCK_ACTIVITY_INSTANCE_TOKEN =
            "lock_activity_instance_token"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_START_ATTEMPT_ID = "start_attempt_id"
        const val EXTRA_LOCK_SESSION_ID = "lock_session_id"
        const val EXTRA_SCHEDULED_PLAN_ID = "scheduled_plan_id"
        const val EXTRA_SCHEDULED_PLAN_NAME = "scheduled_plan_name"
        const val EXTRA_SCHEDULED_PLAN_UPDATED_AT = "scheduled_plan_updated_at"
        const val EXTRA_SCHEDULED_ACTIVE_UNTIL = "scheduled_active_until"
        const val EXTRA_SCHEDULED_USAGE_MINUTES = "scheduled_usage_minutes"
        const val EXTRA_SCHEDULED_LOCK_MINUTES = "scheduled_lock_minutes"
        const val EXTRA_SESSION_MODE = "session_mode"
        const val EXTRA_LOCK_TASK_TITLE = "lock_task_title"
        const val EXTRA_FOCUS_TASK_TITLE = "focus_task_title"
        const val EXTRA_FOCUS_SOURCE_TODO_ID = "focus_source_todo_id"
        internal const val EXTRA_FROM_LOCK_ACTIVITY = "from_lock_activity"

        const val STATE_USAGE = "usage"
        const val STATE_LOCK = "lock"
        const val STATE_PAUSED = "paused"
        const val NOTIFICATION_ID = 1001
        internal const val FALLBACK_NOTIFICATION_ID = 1002
        const val NO_START_ATTEMPT = 0L
        private const val FALLBACK_RETRY_INTERVAL_MILLIS = 2_000L
        private const val FALLBACK_ACTIVITY_MAX_ATTEMPTS_PER_SESSION = 2
        private const val LOCK_ACTIVITY_HANDOFF_GRACE_MILLIS = 1_200L
        private const val OVERLAY_ACTIVITY_HANDOFF_TTL_MILLIS = 10_000L
        private const val PROGRESS_HEARTBEAT_INTERVAL_MILLIS = 10_000L
        private const val ACTIVE_OBSERVATION_STALE_MILLIS = 2_500L
        private const val INTERACTIVE_MEDIA_PROBE_INTERVAL_MILLIS = 1_000L
        private const val SCREEN_OFF_MEDIA_PROBE_INTERVAL_MILLIS = 8_000L
        private const val LOCK_OBSERVATION_LOOKBACK_MILLIS = 5L * 60L * 1_000L
        private const val OVERLAY_SETTINGS_GRACE_MILLIS = 30_000L
        private const val CALL_UI_LAUNCH_GRACE_MILLIS = 12_000L
        private const val LOCK_ACTION_FRESHNESS_MILLIS = 30_000L
        private const val LOCK_ACTION_TRANSITION_WATCHDOG_MILLIS = 20_000L
        private const val MAX_DEFERRED_SNAPSHOT_GROWTH_ORDER_IDS = 64

        /** 清理回调若丢失，超过该时限改由进程级结果轮询收束。 */
        private const val STOP_CLEAR_WATCHDOG_MILLIS = 15_000L
        private const val STOP_CLEAR_RESULT_POLL_MILLIS = 1_000L
        private const val PHASE_BOUNDARY_WAKE_LOCK_TIMEOUT_MILLIS = 10_000L
        private const val GROWTH_ORDER_TTL_MILLIS = 2 * 60_000L
        private const val UNKNOWN_BOOT_COUNT = -1
        private const val TAG = "ControlFreeMonitor"

        private var retainedMediaPlaybackController: MediaPlaybackController? = null
        private val processStopLock = Any()
        private val processStopCoordinator =
            ProcessStopCoordinator<MonitorSnapshotPersistenceWorker>()
        private var currentServiceInstance: WeakReference<MonitorService>? = null
        private var processAllowedAppInvalidationElapsedMillis = 0L
        private var sharedSnapshotPersistenceWorker: MonitorSnapshotPersistenceWorker? = null
        @Volatile private var retainedKnowledgeChallengeHoldSessionId = NO_LOCK_SESSION
        @Volatile private var retainedKnowledgeChallengeHeartbeatElapsedMillis = 0L
        @Volatile private var retainedKnowledgeChallengeVisibilitySessionId = NO_LOCK_SESSION
        @Volatile private var retainedKnowledgeChallengeVisibilityEventElapsedNanos = 0L

        @Volatile
        var isRunning = false
            private set

        internal fun hasScheduledRuntimeOwnership(): Boolean = synchronized(processStopLock) {
            currentServiceInstance?.get()?.let { service ->
                !service.isDestroyed &&
                    (service.scheduledStartAuthorizationInFlight ||
                        service.scheduledMonitorOwner != null)
            } == true
        }
    }

    private lateinit var prefs: PreferenceManager
    private lateinit var allowlistManager: AppAllowlistManager
    private lateinit var allowlistRepository: AllowlistRepository
    private lateinit var usageAccessManager: UsageAccessManager
    private lateinit var mediaPlaybackController: MediaPlaybackController
    private lateinit var mediaPlaybackProbe: MediaPlaybackProbe
    private lateinit var callStateMonitor: CallStateMonitor
    private lateinit var powerManager: PowerManager
    private lateinit var recoveryGuard: MonitorRecoveryGuard
    private lateinit var cycle: MonitorCycle
    private lateinit var snapshot: MonitorCycleSnapshot
    private lateinit var overlayController: LockOverlayController
    private lateinit var foregroundObservationWorker: ForegroundObservationWorker
    private lateinit var snapshotPersistenceWorker: MonitorSnapshotPersistenceWorker
    private lateinit var pauseAlarmScheduler: MonitorPauseAlarmScheduler
    private lateinit var phaseAlarmScheduler: MonitorPhaseAlarmScheduler
    private lateinit var historyRecorder: SupervisionHistoryRecorder
    private lateinit var growthRepository: GrowthRepository
    private lateinit var growthRuntimeStore: GrowthRuntimeStore
    private lateinit var knowledgePassRepository: KnowledgePassRepository
    private lateinit var lockUnlockFeaturePreferences: LockUnlockFeaturePreferences
    private lateinit var allowedAppLaunchExecutor: ExecutorService
    private var allowlistSubscription: AutoCloseable? = null

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foregroundObservationCadencePolicy = ForegroundObservationCadencePolicy()
    private val mediaReplayGuard = MediaReplayGuard()
    private var timerRunnable: Runnable? = null
    private var monitorPauseState: MonitorPauseState? = null
    private var isLockActionTransitionInFlight: Boolean = false
    private var lockActionTransitionGeneration = 0L
    private var hasDeferredSnapshotPersistence = false
    private val deferredSnapshotGrowthOrderIds = LinkedHashSet<String>()
    private val deferredSnapshotCallbacks = mutableListOf<(Boolean) -> Unit>()

    /**
     * 锁定结束落盘失败的连续次数与首次失败时刻。磁盘长期不可写时用于放行阶段推进，
     * 避免监督卡在"每秒算出该解锁又每秒丢弃"的无出口状态。
     */
    private var lockCompletionPersistFailureCount = 0
    private var lockCompletionFirstFailureElapsedMillis = 0L
    private var lockCompletionFailureSessionId = NO_LOCK_SESSION

    private fun resetLockCompletionPersistTracking() {
        lockCompletionPersistFailureCount = 0
        lockCompletionFirstFailureElapsedMillis = 0L
        lockCompletionFailureSessionId = NO_LOCK_SESSION
    }

    private fun recordLockCompletionPersistFailure(nowElapsedMillis: Long) {
        if (lockCompletionFailureSessionId != lockSessionId) {
            resetLockCompletionPersistTracking()
            lockCompletionFailureSessionId = lockSessionId
        }
        if (lockCompletionFirstFailureElapsedMillis == 0L) {
            lockCompletionFirstFailureElapsedMillis = nowElapsedMillis
        }
        lockCompletionPersistFailureCount++
    }

    /**
     * 暂停/跳过过渡依赖异步扣费协程回主线程复位标记；协程若因异常或存储挂起而失联，
     * 标记会永久卡死——计时循环、亮灭屏处理、锁层同步和全部锁定操作都被它拦截，
     * 表现为倒计时冻结、按钮无响应。这里为每次过渡挂一个看门狗强制兜底复位；
     * 正常完成的过渡使代数失配，看门狗触发即为空操作。
     */
    private fun beginLockActionTransition(onTimeout: (() -> Unit)? = null): Long {
        isLockActionTransitionInFlight = true
        lockActionTransitionGeneration = nextGeneration(lockActionTransitionGeneration)
        val generation = lockActionTransitionGeneration
        handler.postDelayed({
            if (
                !isLockActionTransitionInFlight ||
                generation != lockActionTransitionGeneration ||
                isDestroyed ||
                isStoppingIntentionally
            ) {
                return@postDelayed
            }
            Log.e(TAG, "lock_action_transition_watchdog_fired reason=transition_never_completed")
            isLockActionTransitionInFlight = false
            lockActionTransitionGeneration = nextGeneration(lockActionTransitionGeneration)
            try {
                if (onTimeout != null) {
                    onTimeout()
                } else {
                    if (::pauseAlarmScheduler.isInitialized) pauseAlarmScheduler.cancel()
                    if (::snapshot.isInitialized && ::cycle.isInitialized) {
                        try {
                            // 失联事务不得在稍后回调中改变锁定真值；重新落盘当前权威状态，
                            // 覆盖可能已由后台线程写入、但尚未获主线程确认的候选状态。
                            persistSnapshot()
                            publishCurrentState(snapshot.phase)
                        } catch (_: RuntimeException) {
                            // 状态发布失败时由下一次计时心跳自然恢复。
                        }
                    }
                }
            } finally {
                scheduleDeferredSnapshotPersistence()
            }
        }, LOCK_ACTION_TRANSITION_WATCHDOG_MILLIS)
        return generation
    }

    private fun isCurrentLockActionTransition(generation: Long): Boolean =
        isLockActionTransitionInFlight && generation == lockActionTransitionGeneration

    private fun completeLockActionTransition(generation: Long): Boolean {
        if (!isCurrentLockActionTransition(generation)) return false
        isLockActionTransitionInFlight = false
        scheduleDeferredSnapshotPersistence()
        return true
    }

    private fun invalidateLockActionTransition() {
        if (!isLockActionTransitionInFlight) return
        isLockActionTransitionInFlight = false
        lockActionTransitionGeneration = nextGeneration(lockActionTransitionGeneration)
        scheduleDeferredSnapshotPersistence()
    }

    /** 等本次主线程事务完成后再抓取权威快照，避免刷出候选提交前的旧内存状态。 */
    private fun scheduleDeferredSnapshotPersistence() {
        handler.post(::flushDeferredSnapshotPersistence)
    }

    private fun flushDeferredSnapshotPersistence() {
        if (isLockActionTransitionInFlight || !hasDeferredSnapshotPersistence) return
        val appliedGrowthOrderIds = deferredSnapshotGrowthOrderIds.toSet()
        val callbacks = deferredSnapshotCallbacks.toList()
        hasDeferredSnapshotPersistence = false
        deferredSnapshotGrowthOrderIds.clear()
        deferredSnapshotCallbacks.clear()
        persistSnapshot(appliedGrowthOrderIds) { persisted ->
            callbacks.forEach { callback -> callback(persisted) }
        }
    }

    /** 锁动作等待期间暂停计时；提交时无条件建立新锚点，异步等待时间不得计入下一阶段。 */
    private fun reanchorLockActionCandidate(
        candidateSnapshot: MonitorCycleSnapshot
    ): MonitorCycleSnapshot = cycle.reanchor(
        snapshot = candidateSnapshot,
        nowElapsedMillis = SystemClock.elapsedRealtime(),
        bootCount = readBootCount(),
        isInteractive = powerManager.isInteractive
    )

    private var continuationAwardInFlightCycleId: String? = null
    private var isStoppingIntentionally = false
    private var callUiSession = CallUiSessionState()
    private var isLockActivityVisible = false
    private var lockActivityInstanceToken: String? = null
    private var lockActivityVisibilityEventElapsedNanos = 0L
    private var expectedLockActivityHandoff: ExpectedLockActivityHandoff? = null
    private var isOverlayActivityHandoffInProgress = false
    private var knowledgeChallengeHoldSessionId = retainedKnowledgeChallengeHoldSessionId
    private var knowledgeChallengeHeartbeatElapsedMillis =
        retainedKnowledgeChallengeHeartbeatElapsedMillis
    private var knowledgeChallengeVisibilitySessionId =
        retainedKnowledgeChallengeVisibilitySessionId
    private var knowledgeChallengeVisibilityEventElapsedNanos =
        retainedKnowledgeChallengeVisibilityEventElapsedNanos
    private var isOverlayLockVisible = false
    private var isOverlayAttachmentSyncPosted = false
    private var lockActivityLaunchPendingUntilElapsedMillis = 0L
    private var shouldUseOverlayAfterLockActivityTimeout = false
    private var forceMediaReplayProbePending = false
    private var fallbackNotificationActiveSessionId = NO_LOCK_SESSION
    private var fallbackFullScreenIntentSessionId = NO_LOCK_SESSION
    private var fallbackActivityAttemptSessionId = NO_LOCK_SESSION
    private var fallbackActivityAttemptCount = 0
    private var lastFallbackAttemptElapsedMillis = 0L
    private var lastProgressPersistedElapsedMillis = 0L
    private var scheduledPhaseBoundaryElapsedMillis = 0L
    private var lastMediaProbeElapsedMillis = 0L
    private var lastMediaProbeAvailable = false
    private var lastDiagnosticUsageStatus: ForegroundObservationStatus? = null
    private var lastDiagnosticPersistenceHealthy: Boolean? = null
    private var lastDiagnosticAllowlistState: String? = null
    private var lastDiagnosticRetiredExecutorCount = 0
    private var mediaReplayWasDetected = false
    private var hasPersistenceFailure = false
    private var startupFailureMessage: String? = null
    private var suppressLockHandoffOnDestroy = false
    private var internalReceiverRegistered = false
    private var screenReceiverRegistered = false
    private var systemDialogReceiverRegistered = false
    private var callStateMonitorRequired = false
    private var callStateMonitorRegistered = false
    @Volatile
    private var isDestroyed = false
    private var preserveLockResourcesOnDestroy = false
    private var isStopClearInFlight = false
    private var hasStopClearTimedOut = false
    private var isScheduledFocusDisableInFlight = false
    private var coordinatedStopGeneration = 0L
    private var monitorStartupGeneration = 0L
    private var scheduledStartAuthorizationGeneration = 0L
    @Volatile
    private var scheduledStartAuthorizationInFlight = false
    private var isInitialPersistencePending = false
    private var hasTimerRuntimeFailure = false
    private var allowedAppSession = AllowedAppSessionState()
    @Volatile
    private var allowedAppSessionGeneration = 0L
    private var foregroundWindowGeneration = 0L
    private var foregroundWatchScheduled = false
    private var foregroundWatchDueElapsedMillis = 0L
    private var lastForegroundObservation: ForegroundObservation? = null
    private var lastForegroundObservationElapsedMillis = 0L
    private var foregroundObservationRequestSequence = 0L
    private var lastForegroundObservationRequestSequence = 0L
    private var launchObservationSequenceFloor = 0L
    private var cachedAllowedApps: List<AllowedApp> = emptyList()
    private var cachedAllowedPackages: Set<String> = emptySet()
    private var cachedCallUiPackages: Set<String> = emptySet()
    private var overlaySettingsGraceUntilElapsedMillis = 0L
    private var callUiLaunchGraceUntilElapsedMillis = 0L
    private var lastAllowedAppInvalidationElapsedMillis = 0L
    private var lockSessionId = NO_LOCK_SESSION
    @Volatile
    private var scheduledMonitorOwner: ScheduledMonitorOwner? = null
    private var scheduledMonitorPlanName: String? = null
    private var activeFocusTaskTitle: String? = null
    private var activeFocusSourceTodoId: String? = null
    private var activeUsageMinutes = 30
    private var activeLockMinutes = 5
    private var activeSessionMode = MonitorSessionMode.SUPERVISION
    private var pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
    private var pendingBootRecoveryBootCount: Int? = null

    private val foregroundWatchRunnable = object : Runnable {
        override fun run() {
            foregroundWatchScheduled = false
            foregroundWatchDueElapsedMillis = 0L
            if (
                isStoppingIntentionally ||
                isDestroyed ||
                !::snapshot.isInitialized ||
                snapshot.phase != MonitorPhase.LOCK ||
                !snapshot.isInteractive
            ) {
                return
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            if (
                allowedAppSession.phase == AllowedAppSessionPhase.LAUNCHING &&
                nowElapsed - allowedAppSession.launchRequestedElapsedMillis >=
                AllowedAppSessionReducer.DEFAULT_LAUNCH_TIMEOUT_MILLIS
            ) {
                resetAllowedAppSession("launch_timeout")
                syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
                Toast.makeText(
                    this@MonitorService,
                    "无法确认白名单 App，已保持锁定",
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (
                allowedAppSession.phase == AllowedAppSessionPhase.ACTIVE &&
                nowElapsed - lastForegroundObservationElapsedMillis >=
                ACTIVE_OBSERVATION_STALE_MILLIS
            ) {
                resetAllowedAppSession("foreground_observation_stale")
                syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            }
            if (
                callUiSession.isCallUiAllowed &&
                nowElapsed - lastForegroundObservationElapsedMillis >=
                ACTIVE_OBSERVATION_STALE_MILLIS
            ) {
                invalidateCallUiSession("foreground_observation_stale")
                syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            }

            requestForegroundObservation()
            scheduleForegroundWatch()
        }
    }

    private val lockActivityHandoffTimeoutRunnable = Runnable {
        lockActivityLaunchPendingUntilElapsedMillis = 0L
        if (
            isDestroyed ||
            isStoppingIntentionally ||
            !::snapshot.isInitialized ||
            !::cycle.isInitialized ||
            snapshot.phase != MonitorPhase.LOCK ||
            isLockActivityVisible
        ) {
            return@Runnable
        }
        shouldUseOverlayAfterLockActivityTimeout = true
        try {
            syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to show overlay after lock activity handoff timeout")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_FORCE_UNLOCK -> {
                    if (isCurrentLockCommand(intent)) {
                        pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
                        safeStopMonitorAndUnlock()
                    }
                }
                LockActionContract.ACTION_PAUSE_LOCK,
                LockActionContract.ACTION_SKIP_CURRENT_LOCK -> handleVerifiedLockAction(intent)
                ACTION_ARM_SELF_DISCIPLINE -> handleArmSelfDiscipline(intent)
                ACTION_STOP_MONITOR -> {
                    pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
                    safeStopMonitorAndUnlock()
                }
                ACTION_OPEN_ALLOWED_APP -> {
                    if (!isCurrentLockCommand(intent)) return
                    intent.getStringExtra(EXTRA_PACKAGE_NAME)?.let { packageName ->
                        openAllowedApp(
                            packageName,
                            intent.getLongExtra(EXTRA_COMMAND_ELAPSED_MILLIS, 0L),
                            fromLockActivity = intent.getBooleanExtra(
                                EXTRA_FROM_LOCK_ACTIVITY,
                                false
                            )
                        )
                    }
                }
                ACTION_ALLOWLIST_CHANGED -> requestAllowedAppsRefresh()
                ACTION_OVERLAY_PERMISSION_SETTINGS_OPENED -> {
                    if (!isCurrentLockCommand(intent)) return
                    overlaySettingsGraceUntilElapsedMillis = if (
                        intent.getBooleanExtra(EXTRA_VISIBLE, false)
                    ) {
                        SystemClock.elapsedRealtime() + OVERLAY_SETTINGS_GRACE_MILLIS
                    } else {
                        0L
                    }
                }
                ACTION_LOCK_ACTIVITY_VISIBILITY -> {
                    val isVisible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
                    val incomingSessionId = intent.getLongExtra(
                        EXTRA_LOCK_SESSION_ID,
                        NO_LOCK_SESSION
                    )
                    val isExpectedOverlayHandoff = isExpectedLockActivityHandoff(
                        expected = expectedLockActivityHandoff,
                        incomingToken = LockActivityHandoffContract.readToken(intent),
                        incomingSessionId = incomingSessionId,
                        isVisible = isVisible,
                        nowElapsedMillis = SystemClock.elapsedRealtime()
                    )
                    val instanceToken =
                        intent.getStringExtra(EXTRA_LOCK_ACTIVITY_INSTANCE_TOKEN)
                    val eventElapsedNanos = intent.getLongExtra(
                        EXTRA_VISIBILITY_EVENT_ELAPSED_NANOS,
                        0L
                    )
                    // 会话轮换瞬间，前台锁定 Activity 仍持有旧会话号。它的“可见”事件
                    // 必须被接受，否则服务误判无 Activity 而在其上叠加悬浮层，形成
                    // 两层锁屏；“不可见”事件仍要求会话匹配，防止旧界面误清新会话状态。
                    if (
                        !shouldAcceptLockActivityVisibilityCommand(
                            isCurrentSessionCommand = isCurrentLockCommand(intent),
                            isVisible = isVisible
                        )
                    ) {
                        return
                    }
                    if (
                        eventElapsedNanos > 0L &&
                        eventElapsedNanos <= lockActivityVisibilityEventElapsedNanos
                    ) {
                        return
                    }
                    if (
                        !isVisible &&
                        instanceToken != null &&
                        lockActivityInstanceToken != null &&
                        instanceToken != lockActivityInstanceToken
                    ) {
                        return
                    }
                    if (eventElapsedNanos > 0L) {
                        lockActivityVisibilityEventElapsedNanos = eventElapsedNanos
                    }
                    if (isVisible) {
                        lockActivityInstanceToken = instanceToken
                    } else {
                        lockActivityInstanceToken = null
                        isOverlayActivityHandoffInProgress = false
                    }
                    if (isExpectedOverlayHandoff) {
                        expectedLockActivityHandoff = null
                        isOverlayActivityHandoffInProgress = true
                    }
                    isLockActivityVisible = isVisible
                    clearLockActivityLaunchPending()
                    if (isVisible) {
                        // 该闩锁表示"Activity 起不来，改用悬浮层兜底"，只有 Activity
                        // 真的上报可见才证明它能起来。Activity 退到后台（按 Home）时
                        // 一并复位，会让服务立刻再拉一次 Activity，而拉起等待期内
                        // 悬浮层是隐藏的，中间直接暴露桌面——这正是按 Home 后
                        // forceRelockFromNavigation 特意置位它要避免的情况。
                        shouldUseOverlayAfterLockActivityTimeout = false
                    }
                    if (
                        isLockActivityVisible &&
                        (allowedAppSession.phase == AllowedAppSessionPhase.ACTIVE ||
                            callUiSession.isCallUiAllowed)
                    ) {
                        sendUnlockBroadcastSafely()
                    }
                    if (::snapshot.isInitialized && snapshot.phase == MonitorPhase.LOCK) {
                        try {
                            syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
                        } catch (error: RuntimeException) {
                            Log.e(TAG, "Unable to hand off lock surface after activity visibility change")
                        }
                        runMediaReplayCheck()
                    }
                }
                ACTION_KNOWLEDGE_CHALLENGE_VISIBILITY ->
                    handleKnowledgeChallengeVisibility(intent)
            }
        }
    }

    private val systemDialogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            val reason = intent.getStringExtra("reason")
            if (SystemDialogRelockPolicy.shouldRelock(reason)) {
                forceRelockFromNavigation(reason.orEmpty())
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isInteractive = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> return
            }
            if (::snapshot.isInitialized && ::cycle.isInitialized && !isDestroyed) {
                try {
                    val boundary = if (isInteractive) "screen_on" else "screen_off"
                    invalidateTransientAccess(boundary)
                    handleScreenStateChanged(isInteractive)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to process screen-state change")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(processStopLock) {
            currentServiceInstance = WeakReference(this)
        }
        isDestroyed = false
        isRunning = true
        recordDiagnostic(DiagnosticEventType.SERVICE_CREATED)
        MonitorRuntimeHealthRegistry.markServiceCreated(SystemClock.elapsedRealtime())
        try {
            // Android 要求 startForegroundService 后尽快进入前台；先发布最小通知，
            // 再初始化电话、媒体、白名单等可能受厂商系统影响的组件。
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildStartingNotification())
        } catch (error: RuntimeException) {
            startupFailureMessage = "无法建立前台监督服务，请检查通知与后台运行限制"
            // 没有前台运行资格就没有可靠计时器。销毁阶段不得再依据旧磁盘状态
            // 制造一个没有 Service 驱动的恢复锁层。
            suppressLockHandoffOnDestroy = true
            recordDiagnostic(DiagnosticEventType.FOREGROUND_SERVICE_FAILED)
            MonitorRuntimeHealthRegistry.markServiceDestroyed(SystemClock.elapsedRealtime())
            Log.e(TAG, "Failed to promote monitor service to foreground")
            return
        }

        try {
            initializeComponents()
        } catch (error: RuntimeException) {
            startupFailureMessage = "监督服务初始化失败，请重试或检查系统后台限制"
            recordDiagnostic(DiagnosticEventType.SERVICE_INITIALIZATION_FAILED)
            MonitorRuntimeHealthRegistry.markServiceDestroyed(SystemClock.elapsedRealtime())
            Log.e(TAG, "Failed to initialize monitor service")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (processStopCoordinator.hasPending()) {
            holdLockWhileProcessStopCompletes()
            return START_STICKY
        }
        if (MonitorRecoveryBroadcastPolicy.recordsBootEvidence(intent?.action)) {
            pendingBootRecoveryBootCount = readBootCount().takeIf { it >= 0 }
        }
        when (intent?.action) {
            ACTION_START_MONITOR,
            ACTION_START_FOCUS,
            ACTION_RESTORE_SCHEDULED_MONITOR,
            ACTION_STOP_MONITOR,
            ACTION_STOP_SCHEDULED_MONITOR,
            ACTION_FORCE_UNLOCK -> invalidateScheduledStartAuthorization()
        }
        if (isScheduledFocusDisableInFlight) {
            // 用户终止或应急解锁已持久化本周期抑制。关闭计划开关完成前冻结全部
            // 后续命令，包括暂停恢复闹钟和协调器排队的旧启动/边界停止，避免专注
            // 在停用事务期间短暂恢复，或把 CANCELLED 历史原因改写为 COMPLETED。
            return START_STICKY
        }
        if (intent?.action == ACTION_FORCE_UNLOCK && !isCurrentLockCommand(intent)) {
            val hasActiveRuntimeMonitor =
                !isStoppingIntentionally &&
                    ::snapshot.isInitialized &&
                    ::cycle.isInitialized
            if (hasActiveRuntimeMonitor || hasPersistedMonitorState()) return START_STICKY
            isStoppingIntentionally = true
            stopForegroundSafely()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (
            intent?.action == ACTION_STOP_MONITOR ||
            intent?.action == ACTION_STOP_SCHEDULED_MONITOR ||
            intent?.action == ACTION_FORCE_UNLOCK
        ) {
            pendingHistoryEndReason = if (intent.action == ACTION_STOP_SCHEDULED_MONITOR) {
                SupervisionSessionEndReason.COMPLETED
            } else {
                SupervisionSessionEndReason.CANCELLED
            }
            return try {
                when {
                    intent.action == ACTION_STOP_SCHEDULED_MONITOR &&
                        !hasScheduledMonitorOwner() -> {
                        if (hasPersistedMonitorState()) {
                            START_STICKY
                        } else {
                            isStoppingIntentionally = true
                            isRunning = false
                            closeGlobalHistory()
                            stopForegroundSafely()
                            stopSelf(startId)
                            scheduleSupervisionReconciliation()
                            START_NOT_STICKY
                        }
                    }
                    !handleStopCommand(
                        startId = startId,
                        stopAction = intent.action
                    ) -> START_STICKY
                    isStopClearInFlight || isScheduledFocusDisableInFlight -> START_STICKY
                    else -> START_NOT_STICKY
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to stop monitor safely")
                publishMonitorErrorSafely("结束监督失败，已保留监督状态，请稍后重试")
                START_STICKY
            }
        }

        startupFailureMessage?.let { message ->
            val failedStartAttemptId = intent?.getLongExtra(
                EXTRA_START_ATTEMPT_ID,
                NO_START_ATTEMPT
            ) ?: NO_START_ATTEMPT
            terminateUnserviceableMonitorRuntime(message, failedStartAttemptId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESUME_PAUSED_MONITOR) {
            return try {
                if (!::snapshot.isInitialized || !::cycle.isInitialized) {
                    if (!hasPersistedMonitorState()) {
                        stopForegroundSafely()
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    startOrRestoreMonitor(intent = null, startAttemptId = NO_START_ATTEMPT)
                } else {
                    resumePausedMonitorIfDue(force = true)
                }
                START_STICKY
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to resume paused monitor")
                START_STICKY
            }
        }

        if (intent?.action == ACTION_RECONCILE_MONITOR_PHASE) {
            return try {
                if (!::snapshot.isInitialized || !::cycle.isInitialized) {
                    if (!hasPersistedMonitorState()) {
                        stopForegroundSafely()
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    startOrRestoreMonitor(intent = null, startAttemptId = NO_START_ATTEMPT)
                }
                reconcileMonitorPhaseFromWakeAlarm()
                START_STICKY
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to reconcile monitor phase from wake alarm")
                START_STICKY
            }
        }

        if (
            intent?.action == LockActionContract.ACTION_PAUSE_LOCK ||
            intent?.action == LockActionContract.ACTION_SKIP_CURRENT_LOCK ||
            intent?.action == ACTION_ARM_SELF_DISCIPLINE
        ) {
            val commandSessionWasCurrent = intent.getLongExtra(
                EXTRA_LOCK_SESSION_ID,
                NO_LOCK_SESSION
            ).let(RuntimeLockTruthRegistry::isCurrentSession)
            return try {
                if (!::snapshot.isInitialized || !::cycle.isInitialized) {
                    if (!hasPersistedMonitorState()) {
                        stopForegroundSafely()
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    startOrRestoreMonitor(intent = null, startAttemptId = NO_START_ATTEMPT)
                }
                if (isCurrentLockCommand(intent) || commandSessionWasCurrent) {
                    if (intent.action == ACTION_ARM_SELF_DISCIPLINE) {
                        handleArmSelfDiscipline(intent, allowRetainedSession = commandSessionWasCurrent)
                    } else {
                        handleVerifiedLockAction(
                            intent,
                            allowRetainedSession = commandSessionWasCurrent
                        )
                    }
                } else {
                    rejectStaleLockAction()
                }
                START_STICKY
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to process verified lock action")
                keepExistingMonitorLockedAfterStartupFailure(
                    "锁定操作处理失败，已保持监督并等待重试"
                )
                START_STICKY
            }
        }

        if (intent?.action == ACTION_OPEN_ALLOWED_APP) {
            val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            val requestedElapsedMillis = intent.getLongExtra(
                EXTRA_COMMAND_ELAPSED_MILLIS,
                0L
            )
            val hasActiveRuntimeMonitor =
                !isStoppingIntentionally &&
                    ::snapshot.isInitialized &&
                    ::cycle.isInitialized
            if (!hasActiveRuntimeMonitor && !hasPersistedMonitorState()) {
                stopForegroundSafely()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            startupFailureMessage?.let { message ->
                keepExistingMonitorLockedAfterStartupFailure(message)
                return START_STICKY
            }
            return try {
                if (!::snapshot.isInitialized || !::cycle.isInitialized) {
                    startOrRestoreMonitor(intent = null, startAttemptId = NO_START_ATTEMPT)
                }
                if (isCurrentLockCommand(intent)) {
                    targetPackage?.let { packageName ->
                        openAllowedApp(
                            packageName,
                            requestedElapsedMillis,
                            fromLockActivity = intent.getBooleanExtra(
                                EXTRA_FROM_LOCK_ACTIVITY,
                                false
                            )
                        )
                    }
                } else {
                    Log.w(TAG, "allowlist_launch_rejected reason=stale_lock_session")
                }
                START_STICKY
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to restore service for allowlisted app")
                keepExistingMonitorLockedAfterStartupFailure(
                    "监督服务恢复失败，已保持锁定并等待系统重启服务"
                )
                START_STICKY
            }
        }

        val startAttemptId = intent?.getLongExtra(
            EXTRA_START_ATTEMPT_ID,
            NO_START_ATTEMPT
        ) ?: NO_START_ATTEMPT
        val hasActiveRuntimeMonitor =
            !isStoppingIntentionally &&
                ::snapshot.isInitialized &&
                ::cycle.isInitialized
        if (
            intent?.action == ACTION_RESTORE_SCHEDULED_MONITOR &&
            hasActiveRuntimeMonitor
        ) {
            // 开机回退恢复与计划协调器可能先后送达，重复恢复不得重置正在运行的周期。
            return START_STICKY
        }
        if (
            intent?.action != ACTION_START_MONITOR &&
            intent?.action != ACTION_START_FOCUS &&
            intent?.action != ACTION_START_SCHEDULED_MONITOR &&
            !hasActiveRuntimeMonitor &&
            !hasPersistedMonitorState()
        ) {
            isStoppingIntentionally = true
            stopForegroundSafely()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val suppressedScheduledRequest = intent
            ?.takeIf { it.action == ACTION_START_SCHEDULED_MONITOR }
            ?.let(::suppressedScheduledStartRequestOrNull)
        if (suppressedScheduledRequest != null) {
            // 抑制表示用户已经应急解锁该周期，不是一次启动故障。已有运行/持久化状态
            // 需要先恢复才能继续完成清理，不能只留下“正在启动”的空 Service。
            if (isStopClearInFlight || isStoppingIntentionally) return START_STICKY
            if (hasActiveRuntimeMonitor) return START_STICKY
            if (hasPersistedMonitorState()) {
                return try {
                    startOrRestoreMonitor(
                        intent = null,
                        startAttemptId = NO_START_ATTEMPT
                    )
                    val restoredOwner = scheduledMonitorOwner
                    if (
                        restoredOwner != null &&
                        restoredOwner.planId == suppressedScheduledRequest.owner.planId &&
                        restoredOwner.planUpdatedAtEpochMillis ==
                        suppressedScheduledRequest.owner.planUpdatedAtEpochMillis
                    ) {
                        pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
                        handleStopCommand(
                            startId = startId,
                            stopAction = ACTION_FORCE_UNLOCK
                        )
                    }
                    START_STICKY
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to resume suppressed scheduled stop", error)
                    keepExistingMonitorLockedAfterStartupFailure(
                        "应急解锁恢复失败，已保持锁定并等待系统重试"
                    )
                    START_STICKY
                }
            }
            isStoppingIntentionally = true
            isRunning = false
            cancelFallbackLockNotification()
            stopForegroundSafely()
            stopSelf(startId)
            scheduleSupervisionReconciliation()
            return START_NOT_STICKY
        }

        val hadExistingState = hasPersistedMonitorState()
        if (intent?.action == ACTION_START_SCHEDULED_MONITOR) {
            return beginAuthorizedScheduledStart(
                intent = Intent(intent),
                startId = startId,
                startAttemptId = startAttemptId,
                clearNewMonitorStateOnFailure = !hadExistingState
            )
        }
        return try {
            startOrRestoreMonitor(
                intent = intent,
                startAttemptId = startAttemptId,
                clearNewMonitorStateOnFailure =
                    intent?.action.isExplicitMonitorStart() && !hadExistingState
            )
            START_STICKY
        } catch (error: RuntimeException) {
            failMonitorStartup(
                message = "监督计时启动失败，请重试；若仍失败请检查后台运行权限",
                clearNewMonitorState =
                    intent?.action.isExplicitMonitorStart() && !hadExistingState,
                startAttemptId = startAttemptId,
                error = error
            )
            if (hasPersistedMonitorState()) START_STICKY else START_NOT_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "MonitorService onTaskRemoved triggered")
        
        // 方案B保活自愈：如果当前仍在锁定中，则利用闹钟在一秒后重新唤醒并拉起服务
        val hasActiveMonitor = !isStoppingIntentionally && ::snapshot.isInitialized && ::cycle.isInitialized
        val isLocked = (hasActiveMonitor && snapshot.phase == MonitorPhase.LOCK) || hasPersistedMonitorState()
        
        if (isLocked && !isStoppingIntentionally) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            if (alarmManager != null) {
                val restartIntent = Intent(this, MonitorService::class.java).apply {
                    action = ACTION_RESTORE_SCHEDULED_MONITOR
                }
                val pendingIntent = PendingIntent.getService(
                    this,
                    99,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAtMillis = SystemClock.elapsedRealtime() + 1000L
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                    Log.i(TAG, "MonitorService recovery alarm scheduled successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to schedule recovery alarm on task removed", e)
                }
            }
        }
    }

    private fun initializeComponents() {
        prefs = PreferenceManager(this)
        pauseAlarmScheduler = MonitorPauseAlarmScheduler(this)
        phaseAlarmScheduler = MonitorPhaseAlarmScheduler(this)
        historyRecorder = SupervisionHistoryRecorder.getInstance(applicationContext)
        growthRepository = GrowthRepository.getInstance(applicationContext)
        growthRuntimeStore = GrowthRuntimeStore.getInstance(applicationContext)
        knowledgePassRepository = AndroidKnowledgePassRepository.getInstance(applicationContext)
        lockUnlockFeaturePreferences = LockUnlockFeaturePreferences(applicationContext)
        reconcileGrowthOrdersUntilStable()
        recoveryGuard = MonitorRecoveryGuard(this)
        val persistencePrefs = prefs
        val persistenceGuard = recoveryGuard
        val persistenceResultHandler = Handler(Looper.getMainLooper())
        snapshotPersistenceWorker = synchronized(processStopLock) {
            // 共享 worker 一旦被 close() 或被 submitClear() 停止接受快照，这两个状态都
            // 不可逆；唯一例外是进程级停止事务仍持有该 worker。新 Service 必须接管
            // 同一个清理队列，不能另建 worker 让新快照越过尚未返回的旧 clear。
            val worker = processStopCoordinator.pendingWorker()
                ?: sharedSnapshotPersistenceWorker
                    ?.takeIf(MonitorSnapshotPersistenceWorker::isUsable)
                ?: MonitorSnapshotPersistenceWorker(
                    persistOperation = { request ->
                        val guardArmed = persistenceGuard.prepare(request.snapshot)
                        val progressSaved =
                            guardArmed && persistencePrefs.saveMonitorProgress(
                                snapshot = request.snapshot,
                                scheduledOwner = request.scheduledOwner,
                                usageMinutes = request.usageMinutes,
                                lockMinutes = request.lockMinutes,
                                sessionMode = request.sessionMode,
                                pauseState = request.pauseState,
                                appliedGrowthOrderIds = request.appliedGrowthOrderIds,
                                focusTaskTitle = request.focusTaskTitle,
                                focusSourceTodoId = request.focusSourceTodoId
                            )
                        val guardCommitted =
                            progressSaved && persistenceGuard.commit(request.snapshot)
                        val guardCleared = guardCommitted && persistenceGuard.clear()
                        MonitorSnapshotPersistResult(
                            snapshot = request.snapshot,
                            pauseState = request.pauseState,
                            guardArmed = guardArmed,
                            progressSaved = progressSaved,
                            guardCleared = guardCleared,
                            guardCommitted = guardCommitted
                        )
                    },
                    resultExecutor = Executor { command -> persistenceResultHandler.post(command) }
                )
            sharedSnapshotPersistenceWorker = worker
            worker
        }
        val appContext = applicationContext
        allowlistManager = AppAllowlistManager(appContext)
        allowlistRepository = AllowlistRepository.get(appContext)
        allowlistSubscription = allowlistRepository.addListener { state ->
            if (!isDestroyed) {
                recordAllowlistDiagnostic(state)
                if (state is AllowlistRepositoryState.Loading) {
                    invalidateAllowedAppRequests("allowlist_refresh_started")
                }
                state.snapshot?.let(::applyAllowlistSnapshot)
            }
        }
        usageAccessManager = UsageAccessManager(appContext)
        foregroundObservationWorker = ForegroundObservationWorker(
            source = usageAccessManager,
            resultExecutor = Executor { command -> handler.post(command) }
        )
        allowedAppLaunchExecutor = Executors.newSingleThreadExecutor(
            ThreadFactory { task ->
                Thread(task, "controlfree-allowed-app-launch").apply { isDaemon = true }
            }
        )
        mediaPlaybackController = MediaPlaybackController(this)
        mediaPlaybackProbe = MediaPlaybackProbe(this)
        powerManager = requireNotNull(getSystemService(PowerManager::class.java)) {
            "PowerManager unavailable"
        }

        val lockCommands = LockCommandDispatcher(appContext)
        overlayController = LockOverlayController(
            context = appContext,
            hasGesture = lockCommands::hasGesture,
            hasPassword = lockCommands::hasPassword,
            passwordLength = lockCommands::passwordLength,
            onAllowedAppRequest = { packageName ->
                if (!lockCommands.requestAllowedApp(packageName)) {
                    Toast.makeText(appContext, "暂时无法打开白名单 App", Toast.LENGTH_SHORT).show()
                }
            },
            onLockActivityRequest = ::openLockActivityFromOverlay,
            onContinueSelfDiscipline = ::armSelfDisciplineFromOverlay,
            onAttachmentChanged = ::handleOverlayAttachmentChanged
        )

        callStateMonitor = CallStateMonitor(this) { active ->
            if (!isStoppingIntentionally && !isDestroyed) {
                val callStateChanged = callUiSession.isCallActive != active
                applyCallUiSessionUpdate(
                    CallUiSessionReducer.onCallStateChanged(callUiSession, active)
                )
                if (callStateChanged) {
                    invalidateAllowedAppRequests("call_state_changed")
                    if (active) {
                        // 来电让路宽限：锁屏 Activity 在前台会让系统把全屏来电界面
                        // 降级成通知，甚至被沉浸式锁屏完全挡住。宽限期内撤下锁屏，
                        // 给来电界面弹出的机会；确认到通话界面前台后由 callUiSession
                        // 接管豁免，宽限过期仍未确认则由心跳重新上锁。
                        callUiLaunchGraceUntilElapsedMillis =
                            SystemClock.elapsedRealtime() + CALL_UI_LAUNCH_GRACE_MILLIS
                        mediaPlaybackController.pauseMediaDuringCall(forceMediaCommand = true)
                    } else {
                        callUiLaunchGraceUntilElapsedMillis = 0L
                    }
                }
                if (::snapshot.isInitialized) {
                    try {
                        if (snapshot.phase == MonitorPhase.LOCK && snapshot.isInteractive) {
                            ensureForegroundObservationWindow()
                            requestForegroundObservation()
                            scheduleForegroundWatch()
                        }
                        syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
                    } catch (error: RuntimeException) {
                        Log.e(TAG, "Unable to refresh lock UI after call-state change")
                    }
                }
            }
        }
        callStateMonitorRequired = try {
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
        } catch (_: RuntimeException) {
            false
        }
        callStateMonitorRegistered = if (callStateMonitorRequired) {
            callStateMonitor.start()
        } else {
            false
        }
        internalReceiverRegistered = registerInternalReceiver()
        screenReceiverRegistered = registerScreenStateReceiver()
        systemDialogReceiverRegistered = registerSystemDialogReceiver()
        requestAllowedAppsRefresh()
        updateRuntimeHealth(SystemClock.elapsedRealtime())
    }

    private fun invalidateScheduledStartAuthorization() {
        scheduledStartAuthorizationGeneration =
            nextGeneration(scheduledStartAuthorizationGeneration)
        scheduledStartAuthorizationInFlight = false
    }

    private fun beginAuthorizedScheduledStart(
        intent: Intent,
        startId: Int,
        startAttemptId: Long,
        clearNewMonitorStateOnFailure: Boolean
    ): Int {
        val request = try {
            requireScheduledStartRequest(intent)
        } catch (error: RuntimeException) {
            failMonitorStartup(
                message = "定时监督启动参数无效，请重新保存该任务",
                clearNewMonitorState = clearNewMonitorStateOnFailure,
                startAttemptId = startAttemptId,
                error = error
            )
            return if (hasPersistedMonitorState()) START_STICKY else START_NOT_STICKY
        }
        scheduledStartAuthorizationGeneration =
            nextGeneration(scheduledStartAuthorizationGeneration)
        val authorizationGeneration = scheduledStartAuthorizationGeneration
        scheduledStartAuthorizationInFlight = true
        serviceScope.launch {
            val authorization = try {
                authorizeScheduledStart(request)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Unable to authorize scheduled monitor start", error)
                ScheduledStartAuthorization.STORAGE_UNAVAILABLE
            }
            handler.post {
                if (authorizationGeneration != scheduledStartAuthorizationGeneration) {
                    return@post
                }
                scheduledStartAuthorizationInFlight = false
                if (isDestroyed || isStoppingIntentionally) return@post
                when (authorization) {
                    ScheduledStartAuthorization.AUTHORIZED -> {
                        try {
                            startOrRestoreMonitor(
                                intent = intent,
                                startAttemptId = startAttemptId,
                                clearNewMonitorStateOnFailure = clearNewMonitorStateOnFailure
                            )
                        } catch (error: RuntimeException) {
                            failMonitorStartup(
                                message = "定时监督启动失败，请重新打开该任务",
                                clearNewMonitorState = clearNewMonitorStateOnFailure,
                                startAttemptId = startAttemptId,
                                error = error
                            )
                        }
                    }
                    ScheduledStartAuthorization.REJECTED ->
                        rejectStaleScheduledStart(startId)
                    ScheduledStartAuthorization.STORAGE_UNAVAILABLE ->
                        failMonitorStartup(
                            message = "无法核对定时任务状态，已取消本次启动",
                            clearNewMonitorState = clearNewMonitorStateOnFailure,
                            startAttemptId = startAttemptId,
                            error = null
                        )
                }
            }
        }
        return START_STICKY
    }

    private suspend fun authorizeScheduledStart(
        request: ScheduledMonitorStartRequest
    ): ScheduledStartAuthorization {
        val loadResult = SupervisionPlanRepository.getInstance(applicationContext).loadPlans()
        val plans = (loadResult as? PlanLoadResult.Success)?.plans
            ?: return ScheduledStartAuthorization.STORAGE_UNAVAILABLE
        val now = Instant.now()
        val evaluation = SupervisionScheduleEvaluator.evaluate(
            plans = plans,
            now = now,
            deviceZoneId = ZoneId.systemDefault(),
            suppression = prefs.getScheduledOccurrenceSuppression(now.toEpochMilli())
        )
        val active = (evaluation as? SupervisionScheduleEvaluation.Ready)?.active
        return if (
            scheduledStartMatchesActive(request.toAuthorizationRequest(), active)
        ) {
            ScheduledStartAuthorization.AUTHORIZED
        } else {
            ScheduledStartAuthorization.REJECTED
        }
    }

    private fun rejectStaleScheduledStart(startId: Int) {
        val hasActiveRuntimeMonitor =
            !isStoppingIntentionally && ::snapshot.isInitialized && ::cycle.isInitialized
        if (hasActiveRuntimeMonitor || hasPersistedMonitorState()) {
            scheduleSupervisionReconciliation()
            return
        }
        isStoppingIntentionally = true
        isRunning = false
        cancelFallbackLockNotification()
        stopForegroundSafely()
        stopSelf(startId)
        scheduleSupervisionReconciliation()
    }

    private fun startOrRestoreMonitor(
        intent: Intent?,
        startAttemptId: Long,
        clearNewMonitorStateOnFailure: Boolean = false
    ) {
        isStoppingIntentionally = false
        resetAllowedAppSession("service_start")
        monitorStartupGeneration = nextGeneration(monitorStartupGeneration)
        val startupGeneration = monitorStartupGeneration

        when (intent?.action) {
            ACTION_START_MONITOR -> {
                activeSessionMode = MonitorSessionMode.SUPERVISION
                scheduledMonitorOwner = null
                scheduledMonitorPlanName = null
                activeFocusTaskTitle = null
                activeFocusSourceTodoId = null
                activeUsageMinutes = prefs.getUsageTime().coerceIn(1, 1_440)
                activeLockMinutes = prefs.getLockTime().coerceIn(1, 1_440)
                pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
            }
            ACTION_START_FOCUS -> {
                activeSessionMode = MonitorSessionMode.FOCUS
                scheduledMonitorOwner = null
                scheduledMonitorPlanName = null
                activeFocusTaskTitle = intent.readFocusMetadata(EXTRA_FOCUS_TASK_TITLE, 80)
                activeFocusSourceTodoId = intent
                    .readFocusMetadata(EXTRA_FOCUS_SOURCE_TODO_ID, 160)
                    ?.takeIf { activeFocusTaskTitle != null }
                activeUsageMinutes = prefs.getFocusPlayTime().coerceIn(1, 1_440)
                activeLockMinutes = prefs.getFocusLockTime().coerceIn(1, 1_440)
                pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
            }
            ACTION_START_SCHEDULED_MONITOR -> {
                val request = requireScheduledStartRequest(intent)
                val nowEpochMillis = System.currentTimeMillis()
                check(!isScheduledStartRequestSuppressed(request, nowEpochMillis)) {
                    "定时任务当前周期已被用户终止"
                }
                check(request.owner.activeUntilEpochMillis > nowEpochMillis) {
                    "定时监督时间范围已经结束"
                }
                scheduledMonitorOwner = request.owner
                scheduledMonitorPlanName = request.planName
                activeFocusTaskTitle = null
                activeFocusSourceTodoId = null
                activeSessionMode = request.sessionMode
                activeUsageMinutes = request.usageMinutes
                activeLockMinutes = request.lockMinutes
                pendingHistoryEndReason = SupervisionSessionEndReason.COMPLETED
            }
            else -> {
                activeSessionMode = prefs.getMonitorSessionMode()
                scheduledMonitorOwner = (
                    prefs.inspectScheduledMonitorOwner() as? ScheduledOwnerReadResult.Available
                    )?.owner
                scheduledMonitorPlanName = null
                scheduledMonitorOwner?.let(::restoreScheduledMonitorPlanName)
                activeFocusTaskTitle = if (
                    activeSessionMode == MonitorSessionMode.FOCUS && scheduledMonitorOwner == null
                ) {
                    prefs.getActiveFocusTaskTitle()
                } else {
                    null
                }
                activeFocusSourceTodoId = if (activeFocusTaskTitle != null) {
                    prefs.getActiveFocusSourceTodoId()
                } else {
                    null
                }
                if (
                    scheduledMonitorOwner?.activeUntilEpochMillis?.let {
                        System.currentTimeMillis() >= it
                    } == true
                ) {
                    pendingHistoryEndReason = SupervisionSessionEndReason.COMPLETED
                    check(safeStopMonitorAndUnlock()) { "定时监督已到期但未能安全结束" }
                    return
                }
                activeUsageMinutes = storedUsageMinutes(activeSessionMode)
                activeLockMinutes = storedLockMinutes(activeSessionMode)
                pendingHistoryEndReason = if (scheduledMonitorOwner == null) {
                    SupervisionSessionEndReason.CANCELLED
                } else {
                    SupervisionSessionEndReason.COMPLETED
                }
            }
        }

        cycle = MonitorCycle(
            usageDurationMillis = activeUsageMinutes * 60_000L,
            lockDurationMillis = activeLockMinutes * 60_000L,
            initialPhase = activeSessionMode.initialPhase,
            lockCountsDownWhileInteractive = activeSessionMode == MonitorSessionMode.FOCUS
        )

        val nowElapsed = SystemClock.elapsedRealtime()
        val currentBootCount = readBootCount()
        val currentInteractive = powerManager.isInteractive
        val recoveryLockRequired = recoveryGuard.requiresLock()
        val storedProgress = prefs.inspectMonitorProgress()
        val shouldStartNewCycle =
            intent?.action == ACTION_START_MONITOR ||
                intent?.action == ACTION_START_FOCUS ||
                intent?.action == ACTION_START_SCHEDULED_MONITOR ||
                !prefs.isMonitorActive()
        val retainedRuntimeSessionId = RuntimeLockTruthRegistry.currentSessionId()
        val retainedKnowledgeChallengeWasActive =
            !shouldStartNewCycle &&
                !recoveryLockRequired &&
                retainedRuntimeSessionId != NO_LOCK_SESSION &&
                retainedKnowledgeChallengeHoldSessionId == retainedRuntimeSessionId &&
                isKnowledgeChallengeHeartbeatFresh(
                    lastHeartbeatElapsedMillis =
                        retainedKnowledgeChallengeHeartbeatElapsedMillis,
                    nowElapsedMillis = nowElapsed
                )
        if (shouldStartNewCycle) {
            try {
                growthRuntimeStore.startNewRun()
            } catch (error: RuntimeException) {
                Log.w(TAG, "growth_run_start_failed")
            }
        }

        val persistedPauseWasActive = storedProgress.pauseState?.isActive == true
        val persistedPauseIsActiveNow =
            !shouldStartNewCycle &&
                storedProgress.pauseState?.isActiveAt(
                    nowEpochMillis = System.currentTimeMillis(),
                    nowElapsedMillis = nowElapsed,
                    currentBootCount = currentBootCount
                ) == true
        monitorPauseState = when {
            shouldStartNewCycle -> null
            persistedPauseIsActiveNow -> storedProgress.pauseState
            recoveryLockRequired -> null
            persistedPauseWasActive -> requireNotNull(storedProgress.pauseState).clearActive()
            else -> storedProgress.pauseState
        }
        if (persistedPauseIsActiveNow && recoveryLockRequired) {
            // 有效暂停快照已经由同一次原子保存完成；此时 guard 仅可能是保存后的
            // 延迟清理残留，不能再覆盖暂停并制造无法操作的恢复锁屏。
            try {
                recoveryGuard.clear()
            } catch (_: RuntimeException) {
                // LockTruthResolver 同样让完整暂停快照优先，清理失败不会复活锁层。
            }
        }
        if (
            monitorPauseState?.isActive == true &&
            !pauseAlarmScheduler.schedule(requireNotNull(monitorPauseState).untilEpochMillis)
        ) {
            monitorPauseState = monitorPauseState?.clearActive()
        }
        if (monitorPauseState?.isActive != true) pauseAlarmScheduler.cancel()

        snapshot = if (recoveryLockRequired && !persistedPauseIsActiveNow) {
            retainedLockSnapshotForRecovery(storedProgress.snapshot)
                ?.let { savedLock ->
                    // 中断写入前已有可信锁定快照时保留其剩余时间，只重建单调时钟锚点；
                    // 不能因恢复保护把最后一分钟扩回完整配置时长。
                    cycle.reanchor(
                        snapshot = savedLock,
                        nowElapsedMillis = nowElapsed,
                        bootCount = currentBootCount,
                        isInteractive = currentInteractive
                    )
                }
                ?: storedProgress.snapshot?.let { saved ->
                    // 有可信快照但阶段已是玩机：中断写入不等于欠一段锁定。按快照本身
                    // 恢复，不再凭空重建完整锁机时长。
                    MonitorProgressRestorer(cycle).restore(
                        saved = saved,
                        nowElapsedMillis = nowElapsed,
                        currentBootCount = currentBootCount,
                        currentInteractive = currentInteractive
                    )
                }
                ?: fullPhaseSnapshot(
                    // 真的没有任何快照可依据时才保守锁定。
                    phase = MonitorPhase.LOCK,
                    nowElapsed = nowElapsed,
                    bootCount = currentBootCount,
                    isInteractive = currentInteractive
                )
        } else if (shouldStartNewCycle) {
            cycle.start(
                nowElapsedMillis = nowElapsed,
                bootCount = currentBootCount,
                isInteractive = currentInteractive
            )
        } else {
            val restorer = MonitorProgressRestorer(cycle)
            val savedProgress = storedProgress.snapshot
            when {
                savedProgress != null &&
                    (persistedPauseWasActive || retainedKnowledgeChallengeWasActive) ->
                    cycle.reanchor(
                    snapshot = savedProgress,
                    nowElapsedMillis = nowElapsed,
                    bootCount = currentBootCount,
                    isInteractive = currentInteractive
                )
                savedProgress != null -> restorer.restore(
                    saved = savedProgress,
                    nowElapsedMillis = nowElapsed,
                    currentBootCount = currentBootCount,
                    currentInteractive = currentInteractive
                )
                prefs.getMonitorSchemaVersion() >= 2 -> fullPhaseSnapshot(
                    phase = MonitorPhase.LOCK,
                    nowElapsed = nowElapsed,
                    bootCount = currentBootCount,
                    isInteractive = currentInteractive
                )
                prefs.getLegacyMonitorPhase() == null ||
                    prefs.getLegacyPhaseEndsAtMillis() <= 0L -> fullPhaseSnapshot(
                        phase = MonitorPhase.LOCK,
                        nowElapsed = nowElapsed,
                        bootCount = currentBootCount,
                        isInteractive = currentInteractive
                    )
                else -> restorer.migrateLegacy(
                    legacyPhase = requireNotNull(prefs.getLegacyMonitorPhase()),
                    legacyPhaseEndsAtWallMillis = prefs.getLegacyPhaseEndsAtMillis(),
                    nowWallMillis = System.currentTimeMillis(),
                    nowElapsedMillis = nowElapsed,
                    currentBootCount = currentBootCount,
                    currentInteractive = currentInteractive
                )
            }
        }

        val remainingSeconds = cycle.remainingSeconds(snapshot)
        val shouldShowLockUi = snapshot.phase == MonitorPhase.LOCK && !isMonitorPauseActive()
        val retainedSessionResumed =
            retainedRuntimeSessionId != NO_LOCK_SESSION &&
                !shouldStartNewCycle &&
                !recoveryLockRequired &&
                RuntimeLockTruthRegistry.publish(
                    sessionId = retainedRuntimeSessionId,
                    phase = snapshot.phase,
                    shouldShowLockUi = shouldShowLockUi,
                    remainingSeconds = remainingSeconds
                )
        lockSessionId = if (retainedSessionResumed) {
            retainedRuntimeSessionId
        } else {
            clearKnowledgeChallengeHold(clearVisibilityOrdering = true)
            RuntimeLockTruthRegistry.beginSession(
                phase = snapshot.phase,
                shouldShowLockUi = shouldShowLockUi,
                remainingSeconds = remainingSeconds,
                lockCountsDownWhileInteractive =
                    activeSessionMode == MonitorSessionMode.FOCUS
            )
        }
        if (snapshot.phase == MonitorPhase.LOCK) {
            try {
                val activeGrowthCycle = if (shouldStartNewCycle) {
                    growthRuntimeStore.beginLockCycle(
                        lockSessionId = lockSessionId,
                        mode = activeSessionMode.storedValue,
                        configuredDurationSeconds = activeLockMinutes * 60L
                    )
                } else {
                    growthRuntimeStore.restoreOrBeginLockCycle(
                        lockSessionId = lockSessionId,
                        mode = activeSessionMode.storedValue,
                        configuredDurationSeconds = activeLockMinutes * 60L
                    )
                }
                reconcilePendingGrowthSettlements(excludedCycleId = activeGrowthCycle.cycleId)
            } catch (error: RuntimeException) {
                Log.w(TAG, "growth_lock_cycle_restore_failed")
            }
        } else if (!shouldStartNewCycle) {
            reconcileGrowthRuntimeAfterRestore()
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(notificationText(snapshot.phase, remainingSeconds))
        )

        // 历史开始事件必须先于锁定界面暴露。应急解锁即使发生在初始快照
        // 落盘期间，单线程记录器也会按“开始 -> 结束”的顺序完整写入。
        recordStartedHistory(intent?.action)
        isInitialPersistencePending = true
        syncLockUi(previousPhase = null, remainingSeconds = remainingSeconds)
        syncPhaseBoundaryAlarm()
        releaseRetainedLockResources()
        persistSnapshot { persisted ->
            isInitialPersistencePending = false
            if (
                startupGeneration != monitorStartupGeneration ||
                isDestroyed ||
                isStoppingIntentionally
            ) {
                return@persistSnapshot
            }
            if (!persisted) {
                failMonitorStartup(
                    message = "监督状态无法安全保存，请检查设备存储后重试",
                    clearNewMonitorState = clearNewMonitorStateOnFailure,
                    startAttemptId = startAttemptId,
                    error = null
                )
                return@persistSnapshot
            }
            // 初始写入期间屏幕状态可能已变化；成功确认后立即合并最新快照。
            persistSnapshot()
            startTimer()
            monitorPauseState?.takeIf { isMonitorPauseActive() }?.let { pause ->
                pauseAlarmScheduler.schedule(pause.untilEpochMillis)
            }
            publishMonitorStarted(remainingSeconds, startAttemptId)
            publishTimerState(remainingSeconds)
            markPendingBootRecovery(SupervisionRecoveryStatus.RESTORED)
            scheduleSupervisionReconciliation()
        }
    }

    private fun handleStopCommand(
        startId: Int,
        stopAction: String?
    ): Boolean {
        resetAllowedAppSession("stop_requested")
        val preferenceManager = if (::prefs.isInitialized) prefs else PreferenceManager(this)
        val sessionMode = resolveStopSessionMode(
            hasActiveRuntimeMonitor = ::snapshot.isInitialized && ::cycle.isInitialized,
            runtimeSessionMode = activeSessionMode,
            persistedSessionMode = preferenceManager.getMonitorSessionMode()
        )
        val owner = scheduledMonitorOwner ?: (
            preferenceManager.inspectScheduledMonitorOwner() as?
                ScheduledOwnerReadResult.Available
            )?.owner
        if (shouldSuppressScheduledOccurrence(stopAction, sessionMode)) {
            if (owner != null && !preferenceManager.suppressScheduledOccurrence(owner)) {
                Log.w(TAG, "Unable to persist scheduled occurrence suppression")
                if (shouldDisableScheduledFocusPlan(stopAction, sessionMode, owner)) {
                    publishMonitorErrorSafely("无法停用定时专注任务，已保持锁定，请重试")
                    return false
                }
            }
        }
        if (shouldDisableScheduledFocusPlan(stopAction, sessionMode, owner)) {
            return beginDisableScheduledFocusAndStop(
                owner = requireNotNull(owner),
                preferenceManager = preferenceManager
            )
        }
        val canUseNormalStop =
            ::prefs.isInitialized &&
                ::recoveryGuard.isInitialized &&
                ::snapshotPersistenceWorker.isInitialized
        if (canUseNormalStop) return safeStopMonitorAndUnlock()

        isStoppingIntentionally = true
        val guard = if (::recoveryGuard.isInitialized) {
            recoveryGuard
        } else {
            MonitorRecoveryGuard(this)
        }
        val primaryCleared = preferenceManager.clearMonitorProgress()
        val guardCleared = primaryCleared && guard.clear()
        if (!primaryCleared || !guardCleared) {
            isStoppingIntentionally = false
            preserveLockResourcesOnDestroy = true
            retainCurrentLockMediaWithoutReplacingExisting()
            keepExistingMonitorLockedAfterStartupFailure(
                "无法清除监督状态，已保留锁定保护，请稍后重试"
            )
            return false
        }

        preserveLockResourcesOnDestroy = false
        releaseRetainedLockResources()
        if (::overlayController.isInitialized) overlayController.hide()
        cancelFallbackLockNotification()
        if (::mediaPlaybackController.isInitialized) mediaPlaybackController.allowMediaPlayback()
        sendUnlockBroadcastSafely()
        endRuntimeLockSession()
        closeGlobalHistory()
        sendBroadcastSafely(Intent(ACTION_MONITOR_STOPPED).apply { setPackage(packageName) })
        isRunning = false
        stopForegroundSafely()
        stopSelf(startId)
        scheduleSupervisionReconciliation()
        return true
    }

    private fun beginDisableScheduledFocusAndStop(
        owner: ScheduledMonitorOwner,
        preferenceManager: PreferenceManager
    ): Boolean {
        if (isScheduledFocusDisableInFlight) return true
        isScheduledFocusDisableInFlight = true
        freezeMonitorWhileScheduledFocusIsDisabled()
        serviceScope.launch {
            val result = try {
                SupervisionPlanRepository.getInstance(applicationContext)
                    .disableScheduledFocusPlan(
                        planId = owner.planId,
                        nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
                    )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Unable to disable scheduled focus plan", error)
                null
            }
            handler.post {
                if (isDestroyed) return@post
                isScheduledFocusDisableInFlight = false
                when (result) {
                    is PlanWriteResult.Success,
                    is PlanWriteResult.NotFound -> {
                        resumeMonitorAfterScheduledFocusDisable(restartTimer = false)
                        safeStopMonitorAndUnlock()
                    }
                    else -> {
                        if (!preferenceManager.clearScheduledOccurrenceSuppression()) {
                            Log.w(TAG, "Unable to clear scheduled occurrence suppression")
                        }
                        resumeMonitorAfterScheduledFocusDisable(restartTimer = true)
                        publishMonitorErrorSafely(
                            "无法关闭定时专注任务开关，已保持锁定，请重试"
                        )
                    }
                }
            }
        }
        return true
    }

    private fun freezeMonitorWhileScheduledFocusIsDisabled() {
        if (::snapshot.isInitialized && ::cycle.isInitialized && ::powerManager.isInitialized) {
            try {
                snapshot = cycle.reanchor(
                    snapshot = snapshot,
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                    bootCount = readBootCount(),
                    isInteractive = powerManager.isInteractive
                )
                persistSnapshot()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to checkpoint monitor before disabling scheduled focus")
            }
        }
        preserveLockResourcesOnDestroy = true
        isStoppingIntentionally = true
        timerRunnable?.let(handler::removeCallbacks)
    }

    private fun resumeMonitorAfterScheduledFocusDisable(restartTimer: Boolean) {
        isStoppingIntentionally = false
        preserveLockResourcesOnDestroy = false
        if (::snapshot.isInitialized && ::cycle.isInitialized && ::powerManager.isInitialized) {
            try {
                // 排除等待 Room 事务的时间，避免失败恢复或清除失败后突然跨越锁定阶段。
                snapshot = cycle.reanchor(
                    snapshot = snapshot,
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                    bootCount = readBootCount(),
                    isInteractive = powerManager.isInteractive
                )
                if (restartTimer) {
                    persistSnapshot()
                    startTimer()
                    publishCurrentState(snapshot.phase)
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to resume monitor after disabling scheduled focus")
                if (restartTimer) startTimer()
            }
        } else if (restartTimer && ::cycle.isInitialized && ::snapshot.isInitialized) {
            startTimer()
        }
    }

    private fun hasPersistedMonitorState(): Boolean = try {
        val preferenceManager = if (::prefs.isInitialized) prefs else PreferenceManager(this)
        val guard = if (::recoveryGuard.isInitialized) {
            recoveryGuard
        } else {
            MonitorRecoveryGuard(this)
        }
        preferenceManager.isMonitorActive() || guard.requiresLock()
    } catch (_: RuntimeException) {
        // 无法确认时按仍有恢复状态处理，禁止失败路径误解锁。
        true
    }

    private fun hasScheduledMonitorOwner(): Boolean {
        if (scheduledMonitorOwner != null) return true
        val preferenceManager = if (::prefs.isInitialized) prefs else PreferenceManager(this)
        return preferenceManager.inspectScheduledMonitorOwner() is
            ScheduledOwnerReadResult.Available
    }

    private fun registerInternalReceiver(): Boolean {
        val filter = IntentFilter().apply {
            addAction(ACTION_FORCE_UNLOCK)
            addAction(ACTION_STOP_MONITOR)
            addAction(ACTION_OPEN_ALLOWED_APP)
            addAction(ACTION_ALLOWLIST_CHANGED)
            addAction(ACTION_OVERLAY_PERMISSION_SETTINGS_OPENED)
            addAction(ACTION_LOCK_ACTIVITY_VISIBILITY)
            addAction(ACTION_KNOWLEDGE_CHALLENGE_VISIBILITY)
        }
        return try {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to register internal monitor receiver")
            false
        }
    }

    private fun registerScreenStateReceiver(): Boolean {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        return try {
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            true
        } catch (error: RuntimeException) {
            // 每秒读取 PowerManager 仍可校准亮灭屏，只损失精确的广播边沿。
            Log.w(TAG, "Unable to register screen-state receiver")
            false
        }
    }

    private fun registerSystemDialogReceiver(): Boolean = try {
        ContextCompat.registerReceiver(
            this,
            systemDialogReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED
        )
        true
    } catch (error: RuntimeException) {
        Log.w(TAG, "Unable to register Home navigation receiver")
        false
    }

    private fun handleScreenStateChanged(isInteractive: Boolean) {
        if (isStoppingIntentionally) return
        val previousPhase = snapshot.phase
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (
            isLockActionTransitionInFlight
        ) {
            // 关键候选快照正在单线程落盘时，不能再提交普通屏幕快照。Worker 会合并
            // 最新请求并复用候选回调，后来的普通快照会覆盖暂停、跳过或阶段完成事务。
            // 这里只更新内存；候选提交时再合并最终 PowerManager 状态。
            snapshot = cycle.reanchor(
                snapshot = snapshot,
                nowElapsedMillis = nowElapsedMillis,
                bootCount = readBootCount(),
                isInteractive = isInteractive
            )
            publishCurrentState(previousPhase)
            return
        }
        if (
            isKnowledgeChallengeCountdownHeld() ||
            isMonitorPauseActive()
        ) {
            // 这些状态下不推进阶段，但屏幕状态必须落到快照上。直接丢弃熄屏事件会让
            // 锁定阶段一直以为屏幕还亮着——普通监督亮屏本就不倒计时，于是既不计时
            // 也不再安排阶段边界闹钟，表现为"熄屏也不倒计时"。
            snapshot = cycle.reanchor(
                snapshot = snapshot,
                nowElapsedMillis = nowElapsedMillis,
                bootCount = readBootCount(),
                isInteractive = isInteractive
            )
            persistSnapshot()
            publishCurrentState(previousPhase)
            return
        }
        if (isInteractive && snapshot.phase == MonitorPhase.LOCK) {
            foregroundObservationCadencePolicy.onTrigger(
                ForegroundObservationCadenceTrigger.SCREEN_ON,
                nowElapsedMillis
            )
            requestImmediateMediaReplayProbe()
        }
        snapshot = cycle.updateInteractiveState(
            snapshot = snapshot,
            nowElapsedMillis = nowElapsedMillis,
            isInteractive = isInteractive
        )
        persistSnapshot()
        publishCurrentState(previousPhase)
    }

    private fun handleKnowledgeChallengeVisibility(intent: Intent) {
        if (!::snapshot.isInitialized || !::cycle.isInitialized || !::powerManager.isInitialized) {
            return
        }
        val requestedSessionId = intent.getLongExtra(EXTRA_LOCK_SESSION_ID, NO_LOCK_SESSION)
        if (requestedSessionId != lockSessionId) return
        val eventElapsedNanos = intent.getLongExtra(
            EXTRA_VISIBILITY_EVENT_ELAPSED_NANOS,
            0L
        )
        if (knowledgeChallengeVisibilitySessionId != requestedSessionId) {
            knowledgeChallengeVisibilitySessionId = requestedSessionId
            knowledgeChallengeVisibilityEventElapsedNanos = 0L
            retainedKnowledgeChallengeVisibilitySessionId = requestedSessionId
            retainedKnowledgeChallengeVisibilityEventElapsedNanos = 0L
        }
        if (
            eventElapsedNanos > 0L &&
            eventElapsedNanos <= knowledgeChallengeVisibilityEventElapsedNanos
        ) {
            return
        }
        if (eventElapsedNanos > 0L) {
            knowledgeChallengeVisibilityEventElapsedNanos = eventElapsedNanos
            retainedKnowledgeChallengeVisibilityEventElapsedNanos = eventElapsedNanos
        }
        val requestedVisible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
        val shouldHold = shouldHoldKnowledgeChallengeCountdown(
            requestedSessionId = requestedSessionId,
            currentSessionId = lockSessionId,
            phase = snapshot.phase,
            // 当前锁屏 Activity 发出的挑战可见事件本身即可证明可见，不能依赖两路
            // 广播的到达顺序，否则 challenge=true 先到时会漏掉冻结。
            isLockActivityVisible = isLockActivityVisible || requestedVisible,
            requestedVisible = requestedVisible
        )

        val nowElapsed = SystemClock.elapsedRealtime()
        val previousPhase = snapshot.phase
        snapshot = cycle.reanchor(
            snapshot = snapshot,
            nowElapsedMillis = nowElapsed,
            bootCount = readBootCount(),
            isInteractive = powerManager.isInteractive
        )
        if (shouldHold) {
            setKnowledgeChallengeHold(lockSessionId, nowElapsed)
        } else {
            clearKnowledgeChallengeHold()
        }
        persistSnapshot()
        publishCurrentState(previousPhase)
    }

    private fun isKnowledgeChallengeCountdownHeld(): Boolean {
        val heartbeatFresh = isKnowledgeChallengeHeartbeatFresh(
            lastHeartbeatElapsedMillis = knowledgeChallengeHeartbeatElapsedMillis,
            nowElapsedMillis = SystemClock.elapsedRealtime()
        )
        if (!heartbeatFresh) {
            clearKnowledgeChallengeHold()
            return false
        }
        return shouldHoldKnowledgeChallengeCountdown(
            requestedSessionId = knowledgeChallengeHoldSessionId,
            currentSessionId = lockSessionId,
            phase = snapshot.phase,
            isLockActivityVisible = true,
            requestedVisible = knowledgeChallengeHoldSessionId != NO_LOCK_SESSION
        )
    }

    private fun setKnowledgeChallengeHold(sessionId: Long, heartbeatElapsedMillis: Long) {
        knowledgeChallengeHoldSessionId = sessionId
        knowledgeChallengeHeartbeatElapsedMillis = heartbeatElapsedMillis
        retainedKnowledgeChallengeHoldSessionId = sessionId
        retainedKnowledgeChallengeHeartbeatElapsedMillis = heartbeatElapsedMillis
    }

    private fun clearKnowledgeChallengeHold(clearVisibilityOrdering: Boolean = false) {
        knowledgeChallengeHoldSessionId = NO_LOCK_SESSION
        knowledgeChallengeHeartbeatElapsedMillis = 0L
        retainedKnowledgeChallengeHoldSessionId = NO_LOCK_SESSION
        retainedKnowledgeChallengeHeartbeatElapsedMillis = 0L
        if (clearVisibilityOrdering) {
            knowledgeChallengeVisibilitySessionId = NO_LOCK_SESSION
            knowledgeChallengeVisibilityEventElapsedNanos = 0L
            retainedKnowledgeChallengeVisibilitySessionId = NO_LOCK_SESSION
            retainedKnowledgeChallengeVisibilityEventElapsedNanos = 0L
        }
    }

    private fun handleVerifiedLockAction(
        intent: Intent,
        allowRetainedSession: Boolean = false
    ) {
        if (!isFreshLockActionCommand(intent)) return
        if (!allowRetainedSession && !isCurrentLockCommand(intent)) return
        val request = LockActionContract.readServiceRequest(intent) ?: run {
            Log.w(TAG, "lock_action_rejected reason=missing_growth_authorization")
            Toast.makeText(this, "解锁凭证不完整，请重新操作", Toast.LENGTH_LONG).show()
            return
        }
        if (!isFundingMethodEnabled(request.authorization.fundingMethod)) {
            Log.w(TAG, "lock_action_rejected reason=unlock_method_disabled")
            Toast.makeText(this, "该解锁方式已在设置中关闭", Toast.LENGTH_LONG).show()
            return
        }
        if (
            isStoppingIntentionally ||
            isLockActionTransitionInFlight ||
            !::snapshot.isInitialized ||
            !::cycle.isInitialized ||
            snapshot.phase != MonitorPhase.LOCK
        ) {
            return
        }
        if (isMonitorPauseActive()) return
        if (monitorPauseState?.isActive == true) {
            resumePausedMonitorIfDue()
            return
        }
        val commandSessionId = intent.getLongExtra(EXTRA_LOCK_SESSION_ID, NO_LOCK_SESSION)
        if (commandSessionId == NO_LOCK_SESSION) return
        val transitionGeneration = beginLockActionTransition()
        serviceScope.launch {
            val preparation = try {
                prepareGrowthAuthorizedLockAction(
                    request = request,
                    commandSessionId = commandSessionId
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 任何意外异常都必须回主线程复位过渡标记，否则监督整体冻结。
                LockActionPreparation(LockActionPreparationStatus.STORAGE_UNAVAILABLE)
            }
            handler.post {
                if (isDestroyed || isStoppingIntentionally) {
                    refundGrowthOrderAsync(preparation)
                    return@post
                }
                if (!isCurrentLockActionTransition(transitionGeneration)) {
                    refundGrowthOrderAsync(preparation)
                    return@post
                }
                if (
                    snapshot.phase != MonitorPhase.LOCK ||
                    !adoptRegistryAcknowledgedSession(commandSessionId)
                ) {
                    completeLockActionTransition(transitionGeneration)
                    refundGrowthOrderAsync(preparation)
                    return@post
                }
                when (preparation.status) {
                    LockActionPreparationStatus.READY -> when (request.action.kind) {
                        LockPendingActionKind.PAUSE -> beginPauseCurrentLock(
                            minutes = requireNotNull(request.action.pauseMinutes),
                            preparation = preparation,
                            transitionGeneration = transitionGeneration
                        )
                        LockPendingActionKind.SKIP -> beginSkipCurrentLock(
                            preparation = preparation,
                            transitionGeneration = transitionGeneration
                        )
                    }
                    LockActionPreparationStatus.INSUFFICIENT_BALANCE -> {
                        completeLockActionTransition(transitionGeneration)
                        Toast.makeText(
                            this@MonitorService,
                            "成长值不足，请完成百科挑战或继续当前锁定",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    LockActionPreparationStatus.INVALID_CHALLENGE -> {
                        completeLockActionTransition(transitionGeneration)
                        Toast.makeText(
                            this@MonitorService,
                            "百科挑战凭证已过期，请重新挑战",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    LockActionPreparationStatus.STORAGE_UNAVAILABLE -> {
                        completeLockActionTransition(transitionGeneration)
                        Toast.makeText(
                            this@MonitorService,
                            "成长记录暂时无法安全保存，已保持锁定",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private suspend fun prepareGrowthAuthorizedLockAction(
        request: AuthorizedLockAction,
        commandSessionId: Long
    ): LockActionPreparation {
        val nowEpoch = System.currentTimeMillis().coerceAtLeast(0L)
        if (!RuntimeLockTruthRegistry.isCurrentSession(commandSessionId)) {
            return LockActionPreparation(LockActionPreparationStatus.STORAGE_UNAVAILABLE)
        }
        val waiveCost = request.authorization.fundingMethod ==
            LockActionFundingMethod.KNOWLEDGE_CHALLENGE
        val challengeBinding = if (waiveCost) {
            KnowledgeChallengeBinding(commandSessionId, request.action.kind)
        } else {
            null
        }
        val authenticationKind = if (waiveCost) {
            "knowledge_plus_device_credential"
        } else {
            "device_credential"
        }
        val authenticationId = request.authorization.challengeTokenId
            ?: request.authorization.orderId
        val expiresAt = if (nowEpoch > Long.MAX_VALUE - GROWTH_ORDER_TTL_MILLIS) {
            Long.MAX_VALUE
        } else {
            nowEpoch + GROWTH_ORDER_TTL_MILLIS
        }
        val result = try {
            when (request.action.kind) {
                LockPendingActionKind.PAUSE -> {
                    val day = GrowthDayWindow.containing(nowEpoch)
                    growthRepository.preparePauseOrder(
                        PreparePauseOrderRequest(
                            orderId = request.authorization.orderId,
                            lockSessionId = commandSessionId.toString(),
                            requestedPauseMinutes = requireNotNull(request.action.pauseMinutes),
                            authenticationKind = authenticationKind,
                            authenticationId = authenticationId,
                            preparedAtEpochMillis = nowEpoch,
                            expiresAtEpochMillis = expiresAt,
                            localDayStartEpochMillis = day.startEpochMillis,
                            localDayEndExclusiveEpochMillis = day.endExclusiveEpochMillis,
                            waiveCost = waiveCost
                        )
                    )
                }
                LockPendingActionKind.SKIP -> growthRepository.prepareSkipOrder(
                    PrepareSkipOrderRequest(
                        orderId = request.authorization.orderId,
                        lockSessionId = commandSessionId.toString(),
                        authenticationKind = authenticationKind,
                        authenticationId = authenticationId,
                        preparedAtEpochMillis = nowEpoch,
                        expiresAtEpochMillis = expiresAt,
                        waiveCost = waiveCost
                    )
                )
            }
        } catch (_: RuntimeException) {
            return LockActionPreparation(LockActionPreparationStatus.STORAGE_UNAVAILABLE)
        }
        return when (result.status) {
            GrowthPrepareStatus.INSUFFICIENT_BALANCE ->
                LockActionPreparation(LockActionPreparationStatus.INSUFFICIENT_BALANCE)
            GrowthPrepareStatus.PREPARED,
            GrowthPrepareStatus.ALREADY_EXISTS -> {
                val order = result.order
                if (order?.state == GrowthUnlockOrderState.PREPARED) {
                    if (waiveCost) {
                        val tokenId = request.authorization.challengeTokenId
                            ?: return LockActionPreparation(
                                LockActionPreparationStatus.INVALID_CHALLENGE
                            )
                        if (!RuntimeLockTruthRegistry.isCurrentSession(commandSessionId)) {
                            try {
                                growthRepository.refundUnlockOrder(order.orderId, nowEpoch)
                            } catch (_: RuntimeException) {
                                // 未落盘动作的 PREPARED 订单会由启动过期对账释放。
                            }
                            return LockActionPreparation(
                                LockActionPreparationStatus.STORAGE_UNAVAILABLE
                            )
                        }
                        val reserved = try {
                            knowledgePassRepository.reserve(
                                tokenId = tokenId,
                                orderId = order.orderId,
                                expectedBinding = requireNotNull(challengeBinding)
                            )
                        } catch (_: RuntimeException) {
                            KnowledgePassReserveResult.STORAGE_UNAVAILABLE
                        }
                        if (
                            reserved != KnowledgePassReserveResult.RESERVED &&
                            reserved != KnowledgePassReserveResult.ALREADY_RESERVED
                        ) {
                            try {
                                growthRepository.refundUnlockOrder(order.orderId, nowEpoch)
                            } catch (_: RuntimeException) {
                                // 退款失败仍保持 PREPARED，绝不会执行锁屏动作。
                            }
                            try {
                                knowledgePassRepository.rollback(
                                    tokenId = tokenId,
                                    orderId = order.orderId,
                                    expectedBinding = requireNotNull(challengeBinding)
                                )
                            } catch (_: RuntimeException) {
                                // 可恢复凭证记录会在后续对账或过期清理时再次处理。
                            }
                            return LockActionPreparation(
                                if (reserved == KnowledgePassReserveResult.STORAGE_UNAVAILABLE) {
                                    LockActionPreparationStatus.STORAGE_UNAVAILABLE
                                } else {
                                    LockActionPreparationStatus.INVALID_CHALLENGE
                                }
                            )
                        }
                    }
                    LockActionPreparation(
                        status = LockActionPreparationStatus.READY,
                        orderId = order.orderId,
                        challengeTokenId = request.authorization.challengeTokenId,
                        challengeBinding = challengeBinding
                    )
                } else {
                    LockActionPreparation(LockActionPreparationStatus.STORAGE_UNAVAILABLE)
                }
            }
        }
    }

    private fun beginPauseCurrentLock(
        minutes: Int,
        preparation: LockActionPreparation,
        transitionGeneration: Long
    ) {
        val orderId = requireNotNull(preparation.orderId)
        if (minutes !in MIN_LOCK_PAUSE_MINUTES..MAX_LOCK_PAUSE_MINUTES) {
            completeLockActionTransition(transitionGeneration)
            refundGrowthOrderAsync(preparation)
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis().coerceAtLeast(0L)
        val bootCount = readBootCount()
        val settled = cycle.advance(snapshot, nowElapsed)
        if (settled.phase != MonitorPhase.LOCK) {
            val previousPhase = snapshot.phase
            snapshot = settled
            monitorPauseState = null
            // 这是锁动作自身的阶段完成候选，不能被过渡期普通写入延迟。
            persistLockActionCandidate(
                candidateSnapshot = settled,
                candidatePauseState = null,
                appliedGrowthOrderIds = emptySet()
            ) { result ->
                completeLockActionTransition(transitionGeneration)
                refundGrowthOrderAsync(preparation)
                if (!isDestroyed && !isStoppingIntentionally) {
                    publishCurrentState(previousPhase)
                }
            }
            return
        }
        val requestedMillis = minutes * 60_000L
        val candidatePause = MonitorPauseState.begin(
            previous = monitorPauseState?.takeUnless(MonitorPauseState::isActive),
            requestedMillis = requestedMillis,
            nowEpochMillis = nowEpoch,
            nowElapsedMillis = nowElapsed,
            bootCount = bootCount,
            hardStopEpochMillis = scheduledMonitorOwner?.activeUntilEpochMillis
        )
        if (candidatePause == null) {
            val remainingMinutes = (monitorPauseState?.remainingBudgetMillis
                ?: MAX_LOCK_PAUSE_MILLIS) / 60_000L
            Toast.makeText(
                this,
                "本次锁定阶段累计最多暂停30分钟，还可暂停${remainingMinutes}分钟",
                Toast.LENGTH_LONG
            ).show()
            completeLockActionTransition(transitionGeneration)
            refundGrowthOrderAsync(preparation)
            return
        }
        if (!pauseAlarmScheduler.schedule(candidatePause.untilEpochMillis)) {
            Toast.makeText(
                this,
                "无法可靠安排恢复时间，请先开启精确定时权限",
                Toast.LENGTH_LONG
            ).show()
            completeLockActionTransition(transitionGeneration)
            refundGrowthOrderAsync(preparation)
            return
        }

        val candidateSnapshot = cycle.reanchor(
            snapshot = settled,
            nowElapsedMillis = nowElapsed,
            bootCount = bootCount,
            isInteractive = powerManager.isInteractive
        )
        persistLockActionCandidate(
            candidateSnapshot = candidateSnapshot,
            candidatePauseState = candidatePause,
            appliedGrowthOrderIds = setOf(orderId)
        ) { result ->
            val persisted = result.isSuccess &&
                result.snapshot == candidateSnapshot &&
                result.pauseState == candidatePause
            if (!isCurrentLockActionTransition(transitionGeneration)) {
                pauseAlarmScheduler.cancel()
                refundGrowthOrderAsync(preparation)
                if (persisted) persistSnapshot()
                return@persistLockActionCandidate
            }
            completeLockActionTransition(transitionGeneration)
            if (
                isDestroyed ||
                isStoppingIntentionally ||
                (isStopClearInFlight && !hasStopClearTimedOut)
            ) {
                if (persisted) {
                    commitGrowthOrderAsync(preparation)
                } else {
                    refundGrowthOrderAsync(preparation)
                }
                return@persistLockActionCandidate
            }
            if (!persisted) {
                refundGrowthOrderAsync(preparation)
                pauseAlarmScheduler.cancel()
                persistSnapshot()
                publishCurrentState(MonitorPhase.LOCK)
                return@persistLockActionCandidate
            }
            val committedSnapshot = reanchorLockActionCandidate(candidateSnapshot)
            snapshot = committedSnapshot
            monitorPauseState = candidatePause
            commitGrowthOrderAsync(preparation)
            growthRuntimeStore.activeCycle()?.let { activeCycle ->
                growthRuntimeStore.clearContinuation(activeCycle.cycleId)
            }
            resetAllowedAppSession("monitor_paused")
            invalidateCallUiSession("monitor_paused")
            persistSnapshot()
            publishCurrentState(MonitorPhase.LOCK)
            Toast.makeText(
                this,
                "监督已暂停${minutes}分钟",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun beginSkipCurrentLock(
        preparation: LockActionPreparation,
        transitionGeneration: Long
    ) {
        val orderId = requireNotNull(preparation.orderId)
        val skippedCycle = growthRuntimeStore.activeCycle()
        skippedCycle?.let { cycleState ->
            growthRuntimeStore.markPendingOutcome(
                cycleState.cycleId,
                GrowthCycleOutcome.SKIPPED
            )
        }
        val candidateSnapshot = cycle.skipCurrentPhase(
            snapshot = snapshot,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            bootCount = readBootCount(),
            isInteractive = powerManager.isInteractive
        )
        persistLockActionCandidate(
            candidateSnapshot = candidateSnapshot,
            candidatePauseState = null,
            appliedGrowthOrderIds = setOf(orderId)
        ) { result ->
            val persisted = result.isSuccess &&
                result.snapshot == candidateSnapshot &&
                result.pauseState == null
            if (!isCurrentLockActionTransition(transitionGeneration)) {
                refundGrowthOrderAsync(preparation)
                skippedCycle?.let { growthRuntimeStore.clearPendingOutcome(it.cycleId) }
                if (persisted) persistSnapshot()
                return@persistLockActionCandidate
            }
            completeLockActionTransition(transitionGeneration)
            if (
                isDestroyed ||
                isStoppingIntentionally ||
                (isStopClearInFlight && !hasStopClearTimedOut)
            ) {
                if (persisted) {
                    commitGrowthOrderAsync(preparation)
                    skippedCycle?.let { settleGrowthCycleAsync(it, GrowthCycleOutcome.SKIPPED, 0L) }
                } else {
                    refundGrowthOrderAsync(preparation)
                    skippedCycle?.let { growthRuntimeStore.clearPendingOutcome(it.cycleId) }
                }
                return@persistLockActionCandidate
            }
            if (!persisted) {
                refundGrowthOrderAsync(preparation)
                skippedCycle?.let { growthRuntimeStore.clearPendingOutcome(it.cycleId) }
                persistSnapshot()
                publishCurrentState(MonitorPhase.LOCK)
                return@persistLockActionCandidate
            }
            val committedSnapshot = reanchorLockActionCandidate(candidateSnapshot)
            snapshot = committedSnapshot
            monitorPauseState = null
            commitGrowthOrderAsync(preparation)
            skippedCycle?.let { settleGrowthCycleAsync(it, GrowthCycleOutcome.SKIPPED, 0L) }
            pauseAlarmScheduler.cancel()
            recordDiagnostic(DiagnosticEventType.LOCK_PHASE_FINISHED)
            persistSnapshot()
            publishCurrentState(MonitorPhase.LOCK)
            Toast.makeText(this, "已跳过本次锁定，重新开始监督", Toast.LENGTH_SHORT).show()
        }
    }

    private fun commitGrowthOrderAsync(preparation: LockActionPreparation) {
        val orderId = requireNotNull(preparation.orderId)
        serviceScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                repeat(3) { attempt ->
                    try {
                        val committed = growthRepository.commitUnlockOrder(
                            orderId = orderId,
                            finalizedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
                        )
                        val knowledgeFinalized = finalizeKnowledgeReservation(committed)
                        check(knowledgeFinalized) { "百科挑战凭证尚未完成提交" }
                        if (
                            committed.state == GrowthUnlockOrderState.APPLIED &&
                            knowledgeFinalized
                        ) {
                            prefs.clearAppliedGrowthOrderId(orderId)
                            committed.lockSessionId.toLongOrNull()?.let { sessionId ->
                                knowledgePassRepository.revokeSession(sessionId)
                            }
                        }
                        publishGrowthUpdated()
                        return@withContext
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "growth_order_commit_failed attempt=${attempt + 1}")
                        if (attempt < 2) delay(150L * (attempt + 1))
                    }
                }
                reconcileGrowthOrdersUntilStable(initialDelayMillis = 2_000L)
            }
        }
    }

    /**
     * 对账顺序不可颠倒：快照已证明动作生效的订单必须先提交，之后才允许释放普通过期预留。
     * 标记与监督快照由同一次 SharedPreferences commit 写入，因此进程在任意时刻退出都能恢复。
     */
    private fun reconcileGrowthOrdersUntilStable(initialDelayMillis: Long = 0L) {
        serviceScope.launch {
            if (initialDelayMillis > 0L) delay(initialDelayMillis)
            var retryDelayMillis = 1_000L
            while (true) {
                val appliedOrderIds = prefs.getAppliedGrowthOrderIds()
                var hasTransientFailure = false
                for (orderId in appliedOrderIds) {
                    try {
                        val order = growthRepository.getUnlockOrder(orderId)
                        when (order?.state) {
                            GrowthUnlockOrderState.PREPARED -> {
                                val committed = growthRepository.commitUnlockOrder(
                                    orderId = orderId,
                                    finalizedAtEpochMillis = System.currentTimeMillis()
                                        .coerceAtLeast(order.preparedAtEpochMillis)
                                )
                                val knowledgeFinalized =
                                    finalizeKnowledgeReservation(committed)
                                if (committed.state == GrowthUnlockOrderState.APPLIED &&
                                    knowledgeFinalized
                                ) {
                                    prefs.clearAppliedGrowthOrderId(orderId)
                                    committed.lockSessionId.toLongOrNull()?.let { sessionId ->
                                        knowledgePassRepository.revokeSession(sessionId)
                                    }
                                    publishGrowthUpdated()
                                } else if (!knowledgeFinalized) {
                                    hasTransientFailure = true
                                }
                            }
                            GrowthUnlockOrderState.APPLIED -> {
                                if (finalizeKnowledgeReservation(order)) {
                                    prefs.clearAppliedGrowthOrderId(orderId)
                                    order.lockSessionId.toLongOrNull()?.let { sessionId ->
                                        knowledgePassRepository.revokeSession(sessionId)
                                    }
                                } else {
                                    hasTransientFailure = true
                                }
                            }
                            GrowthUnlockOrderState.REFUNDED,
                            GrowthUnlockOrderState.EXPIRED,
                            null -> {
                                Log.e(
                                    TAG,
                                    "applied_growth_order_unrecoverable id=$orderId state=${order?.state}"
                                )
                                prefs.clearAppliedGrowthOrderId(orderId)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        hasTransientFailure = true
                        Log.w(TAG, "applied_growth_order_reconcile_failed id=$orderId")
                    }
                }

                val unresolvedOrderIds = prefs.getAppliedGrowthOrderIds()
                if (unresolvedOrderIds.isEmpty()) {
                    try {
                        val expiredOrders = growthRepository.expirePreparedOrders(
                                System.currentTimeMillis().coerceAtLeast(0L)
                            )
                        expiredOrders.forEach { expiredOrder ->
                            rollbackKnowledgeReservation(expiredOrder)
                        }
                        if (expiredOrders.isNotEmpty()) {
                            publishGrowthUpdated()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "growth_order_expiration_failed")
                    }
                    return@launch
                }
                if (!hasTransientFailure) return@launch
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(30_000L)
            }
        }
    }

    private fun refundGrowthOrderAsync(preparation: LockActionPreparation) {
        val orderId = preparation.orderId ?: return
        serviceScope.launch {
            repeat(3) { attempt ->
                try {
                    val refunded = growthRepository.refundUnlockOrder(
                        orderId = orderId,
                        finalizedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
                    )
                    rollbackKnowledgeReservation(
                        order = refunded,
                        tokenIdOverride = preparation.challengeTokenId,
                        bindingOverride = preparation.challengeBinding
                    )
                    publishGrowthUpdated()
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RuntimeException) {
                    Log.w(TAG, "growth_order_refund_failed attempt=${attempt + 1}")
                    if (attempt < 2) delay(150L * (attempt + 1L))
                }
            }
        }
    }

    private suspend fun finalizeKnowledgeReservation(order: GrowthUnlockOrder): Boolean {
        val reservation = knowledgeReservationForOrder(order) ?: return true
        return when (
            knowledgePassRepository.commit(
                tokenId = reservation.tokenId,
                orderId = order.orderId,
                expectedBinding = reservation.binding
            )
        ) {
            KnowledgePassCommitResult.COMMITTED,
            KnowledgePassCommitResult.ALREADY_COMMITTED -> true
            KnowledgePassCommitResult.STORAGE_UNAVAILABLE -> false
            else -> {
                Log.e(TAG, "knowledge_pass_commit_invalid order=${order.orderId}")
                true
            }
        }
    }

    private suspend fun rollbackKnowledgeReservation(
        order: GrowthUnlockOrder,
        tokenIdOverride: String? = null,
        bindingOverride: KnowledgeChallengeBinding? = null
    ): Boolean {
        val reservation = if (tokenIdOverride != null && bindingOverride != null) {
            KnowledgeReservation(tokenIdOverride, bindingOverride)
        } else {
            knowledgeReservationForOrder(order)
        } ?: return true
        return when (
            knowledgePassRepository.rollback(
                tokenId = reservation.tokenId,
                orderId = order.orderId,
                expectedBinding = reservation.binding
            )
        ) {
            KnowledgePassRollbackResult.ROLLED_BACK,
            KnowledgePassRollbackResult.ALREADY_AVAILABLE,
            KnowledgePassRollbackResult.EXPIRED,
            KnowledgePassRollbackResult.NOT_FOUND -> true
            KnowledgePassRollbackResult.STORAGE_UNAVAILABLE -> false
            else -> {
                Log.e(TAG, "knowledge_pass_rollback_invalid order=${order.orderId}")
                true
            }
        }
    }

    private fun knowledgeReservationForOrder(order: GrowthUnlockOrder): KnowledgeReservation? {
        if (order.authenticationKind != "knowledge_plus_device_credential") return null
        val sessionId = order.lockSessionId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val actionKind = when (order.actionKind) {
            GrowthUnlockActionKind.PAUSE -> LockPendingActionKind.PAUSE
            GrowthUnlockActionKind.SKIP -> LockPendingActionKind.SKIP
            GrowthUnlockActionKind.EMERGENCY_END -> return null
        }
        return KnowledgeReservation(
            tokenId = order.authenticationId,
            binding = KnowledgeChallengeBinding(sessionId, actionKind)
        )
    }

    private fun settleGrowthCycleAsync(
        cycleState: GrowthRuntimeCycle,
        outcome: GrowthCycleOutcome,
        pausedSeconds: Long
    ) {
        serviceScope.launch {
            settleGrowthCycleWithRetry(cycleState, outcome, pausedSeconds)
        }
    }

    private suspend fun settleGrowthCycleWithRetry(
        cycleState: GrowthRuntimeCycle,
        outcome: GrowthCycleOutcome,
        pausedSeconds: Long
    ): Boolean {
        repeat(3) { attempt ->
            val nowEpoch = System.currentTimeMillis().coerceAtLeast(0L)
            val day = GrowthDayWindow.containing(nowEpoch)
            try {
                growthRepository.settleCycle(
                    GrowthCycleSettlementRequest(
                        cycleId = cycleState.cycleId,
                        runId = cycleState.runId,
                        cycleOrdinal = cycleState.cycleOrdinal,
                        mode = cycleState.mode,
                        configuredDurationSeconds = cycleState.configuredDurationSeconds,
                        validElapsedSeconds = if (outcome == GrowthCycleOutcome.TIMER_COMPLETED) {
                            cycleState.configuredDurationSeconds
                        } else {
                            0L
                        },
                        pausedSeconds = pausedSeconds.coerceAtLeast(0L),
                        outcome = outcome,
                        occurredAtEpochMillis = nowEpoch,
                        localDayStartEpochMillis = day.startEpochMillis,
                        localDayEndExclusiveEpochMillis = day.endExclusiveEpochMillis
                    )
                )
                growthRuntimeStore.completePendingSettlement(cycleState.cycleId)
                publishGrowthUpdated()
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.w(
                    TAG,
                    "growth_cycle_settlement_failed outcome=${outcome.storedValue} " +
                        "attempt=${attempt + 1}"
                )
                if (attempt < 2) delay(250L * (attempt + 1L))
            }
        }
        return false
    }

    private fun reconcilePendingGrowthSettlements(excludedCycleId: String? = null) {
        serviceScope.launch {
            val pendingCycles = try {
                growthRuntimeStore.pendingSettlementCycles()
            } catch (error: RuntimeException) {
                Log.w(TAG, "growth_runtime_outbox_read_failed")
                return@launch
            }
            pendingCycles
                .filterNot { cycleState -> cycleState.cycleId == excludedCycleId }
                .forEach { cycleState ->
                    settleGrowthCycleWithRetry(
                        cycleState = cycleState,
                        outcome = cycleState.pendingOutcome
                            ?: GrowthCycleOutcome.RECOVERY_UNCERTAIN,
                        pausedSeconds = 0L
                    )
                }
        }
    }

    private fun reconcileGrowthRuntimeAfterRestore() {
        val active = try {
            growthRuntimeStore.activeCycle()
        } catch (error: RuntimeException) {
            Log.w(TAG, "growth_runtime_reconcile_read_failed")
            null
        }
        active?.let { cycleState ->
            val outcome = cycleState.pendingOutcome ?: GrowthCycleOutcome.RECOVERY_UNCERTAIN
            if (cycleState.pendingOutcome == null) {
                growthRuntimeStore.markPendingOutcome(cycleState.cycleId, outcome)
            }
        }
        reconcilePendingGrowthSettlements()
    }

    private fun revokeKnowledgePassesForSession(sessionId: Long) {
        if (sessionId == NO_LOCK_SESSION) return
        serviceScope.launch {
            try {
                knowledgePassRepository.revokeSession(sessionId)
            } catch (error: RuntimeException) {
                Log.w(TAG, "knowledge_pass_revocation_failed")
            }
        }
    }

    private fun publishGrowthUpdated() {
        sendBroadcastSafely(Intent(ACTION_GROWTH_UPDATED).apply { setPackage(packageName) })
    }

    private fun armSelfDisciplineFromOverlay(): Boolean {
        val sessionId = lockSessionId
        if (sessionId == NO_LOCK_SESSION) return false
        return handleArmSelfDiscipline(Intent(ACTION_ARM_SELF_DISCIPLINE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOCK_SESSION_ID, sessionId)
            putExtra(EXTRA_COMMAND_ELAPSED_MILLIS, SystemClock.elapsedRealtime())
        })
    }

    private fun openLockActivityFromOverlay(request: OverlayLockActivityRequest): Boolean {
        // 方案B：悬浮窗不再作为锁屏界面，不需要从悬浮窗向 LockActivity 交接
        return false
    }

    private fun isFundingMethodEnabled(method: LockActionFundingMethod): Boolean =
        isLockUnlockFundingMethodEnabled(
            fundingMethod = method,
            growthUnlockEnabled = lockUnlockFeaturePreferences.growthUnlockEnabled,
            knowledgeChallengeEnabled = lockUnlockFeaturePreferences.knowledgeChallengeEnabled
        )

    private fun handleArmSelfDiscipline(
        intent: Intent,
        allowRetainedSession: Boolean = false
    ): Boolean {
        if (!isFreshLockActionCommand(intent)) return false
        if (!allowRetainedSession && !isCurrentLockCommand(intent)) return false
        if (
            isStoppingIntentionally ||
            isLockActionTransitionInFlight ||
            !::snapshot.isInitialized ||
            !::cycle.isInitialized ||
            snapshot.phase != MonitorPhase.LOCK ||
            isMonitorPauseActive()
        ) {
            return false
        }
        val remainingSeconds = cycle.remainingSeconds(snapshot)
        if (remainingSeconds.toLong() < GrowthPolicy.CONTINUATION_REWARD_SECONDS) {
            Toast.makeText(
                this,
                "本轮不足5分钟，完成倒计时即可结算成长",
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
        val commitment = try {
            val active = growthRuntimeStore.activeCycle()
                ?: growthRuntimeStore.restoreOrBeginLockCycle(
                    lockSessionId = lockSessionId,
                    mode = activeSessionMode.storedValue,
                    configuredDurationSeconds = activeLockMinutes * 60L
                )
            growthRuntimeStore.armContinuation(
                cycleId = active.cycleId,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                bootCount = readBootCount(),
                remainingSeconds = remainingSeconds
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "growth_continuation_arm_failed")
            return false
        }
        Toast.makeText(
            this,
            if (commitment == null) {
                "继续自律承诺已记录，本轮只能获得一次奖励"
            } else {
                "再坚持5分钟，小芽将获得1点成长值"
            },
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    private fun maybeAwardContinuation(nowElapsedMillis: Long) {
        val active = growthRuntimeStore.activeCycle() ?: return
        if (continuationAwardInFlightCycleId == active.cycleId) return
        if (!growthRuntimeStore.isContinuationDue(active.cycleId, nowElapsedMillis, readBootCount())) {
            return
        }
        continuationAwardInFlightCycleId = active.cycleId
        serviceScope.launch {
            val nowEpoch = System.currentTimeMillis().coerceAtLeast(0L)
            val day = GrowthDayWindow.containing(nowEpoch)
            try {
                growthRepository.awardContinuation(
                    ContinuationRewardRequest(
                        sourceKey = "continuation:${active.cycleId}",
                        cycleId = active.cycleId,
                        additionalValidSeconds = GrowthPolicy.CONTINUATION_REWARD_SECONDS,
                        occurredAtEpochMillis = nowEpoch,
                        localDayStartEpochMillis = day.startEpochMillis,
                        localDayEndExclusiveEpochMillis = day.endExclusiveEpochMillis
                    )
                )
                growthRuntimeStore.clearContinuation(active.cycleId)
                publishGrowthUpdated()
                handler.post {
                    if (!isDestroyed) {
                        Toast.makeText(this@MonitorService, "继续自律达成，成长值 +1", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.w(TAG, "continuation_reward_failed")
            } finally {
                continuationAwardInFlightCycleId = null
            }
        }
    }

    private fun resumePausedMonitorIfDue(force: Boolean = false): Boolean {
        val pause = monitorPauseState ?: return false
        if (!pause.isActive) return false
        if (!force && isMonitorPauseActive()) return false
        if (force && isMonitorPauseActive()) return false
        monitorPauseState = pause.clearActive()
        snapshot = cycle.reanchor(
            snapshot = snapshot,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            bootCount = readBootCount(),
            isInteractive = powerManager.isInteractive
        )
        pauseAlarmScheduler.cancel()
        RuntimeLockTruthRegistry.endSession(lockSessionId)
        lockSessionId = RuntimeLockTruthRegistry.beginSession(
            phase = MonitorPhase.LOCK,
            shouldShowLockUi = true,
            remainingSeconds = cycle.remainingSeconds(snapshot),
            lockCountsDownWhileInteractive = activeSessionMode == MonitorSessionMode.FOCUS
        )
        growthRuntimeStore.restoreOrBeginLockCycle(
            lockSessionId = lockSessionId,
            mode = activeSessionMode.storedValue,
            configuredDurationSeconds = activeLockMinutes * 60L
        )
        persistSnapshot()
        publishCurrentState(previousPhase = null)
        return true
    }

    private fun isMonitorPauseActive(
        nowEpochMillis: Long = System.currentTimeMillis(),
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
        currentBootCount: Int = readBootCount()
    ): Boolean = !isLockActionTransitionInFlight &&
        monitorPauseState?.isActiveAt(
            nowEpochMillis = nowEpochMillis,
            nowElapsedMillis = nowElapsedMillis,
            currentBootCount = currentBootCount
        ) == true

    private fun pauseRemainingSeconds(): Int {
        val pause = monitorPauseState ?: return 0
        val remainingMillis = if (
            pause.bootCount >= 0 && pause.bootCount == readBootCount()
        ) {
            pause.deadlineElapsedMillis - SystemClock.elapsedRealtime()
        } else {
            pause.untilEpochMillis - System.currentTimeMillis()
        }
        return ((remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun isFreshLockActionCommand(intent: Intent): Boolean {
        val requestedAt = intent.getLongExtra(EXTRA_COMMAND_ELAPSED_MILLIS, -1L)
        return isFreshLockActionRequest(
            requestedAtElapsedMillis = requestedAt,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            maximumAgeMillis = LOCK_ACTION_FRESHNESS_MILLIS
        )
    }

    private fun reconcileMonitorPhaseFromWakeAlarm() {
        scheduledPhaseBoundaryElapsedMillis = 0L
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:monitor-phase-boundary"
        )
        try {
            wakeLock.acquire(PHASE_BOUNDARY_WAKE_LOCK_TIMEOUT_MILLIS)
            val runnable = timerRunnable
            if (runnable != null) {
                handler.removeCallbacks(runnable)
                runnable.run()
            } else if (
                !isInitialPersistencePending &&
                ::snapshot.isInitialized &&
                ::cycle.isInitialized
            ) {
                startTimer()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun startTimer() {
        timerRunnable?.let(handler::removeCallbacks)
        timerRunnable = object : Runnable {
            override fun run() {
                if (isStoppingIntentionally || isDestroyed) return
                try {
                    if (
                        scheduledMonitorOwner?.activeUntilEpochMillis?.let { deadline ->
                            System.currentTimeMillis() >= deadline
                        } == true
                    ) {
                        pendingHistoryEndReason = SupervisionSessionEndReason.COMPLETED
                        safeStopMonitorAndUnlock()
                        return
                    }
                    if (isLockActionTransitionInFlight) return
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val previousPhase = snapshot.phase
                    val actualInteractive = powerManager.isInteractive
                    if (isKnowledgeChallengeCountdownHeld()) {
                        snapshot = cycle.reanchor(
                            snapshot = snapshot,
                            nowElapsedMillis = nowElapsed,
                            bootCount = readBootCount(),
                            isInteractive = actualInteractive
                        )
                        val heartbeatDue =
                            nowElapsed - lastProgressPersistedElapsedMillis >=
                                PROGRESS_HEARTBEAT_INTERVAL_MILLIS
                        if (heartbeatDue) persistSnapshot()
                        publishCurrentState(previousPhase)
                        hasTimerRuntimeFailure = false
                        return
                    } else if (knowledgeChallengeHoldSessionId != NO_LOCK_SESSION) {
                        clearKnowledgeChallengeHold()
                    }
                    val pauseState = monitorPauseState
                    if (pauseState?.isActive == true) {
                        if (isMonitorPauseActive(nowElapsedMillis = nowElapsed)) {
                            snapshot = cycle.reanchor(
                                snapshot = snapshot,
                                nowElapsedMillis = nowElapsed,
                                bootCount = readBootCount(),
                                isInteractive = actualInteractive
                            )
                            publishCurrentState(previousPhase)
                        } else {
                            resumePausedMonitorIfDue()
                        }
                        return
                    }
                    val screenStateChanged = actualInteractive != snapshot.isInteractive
                    if (screenStateChanged) {
                        invalidateTransientAccess("screen_state_reconciled")
                        if (actualInteractive && previousPhase == MonitorPhase.LOCK) {
                            // SCREEN_ON 广播可能因注册失败或厂商限制而丢失，校准路径必须补齐
                            // 同一快速观察边沿，避免亮屏后仍按稳定锁层低频等待。
                            foregroundObservationCadencePolicy.onTrigger(
                                ForegroundObservationCadenceTrigger.SCREEN_ON,
                                nowElapsed
                            )
                            requestImmediateMediaReplayProbe()
                        }
                    }
                    val advancedSnapshot = if (screenStateChanged) {
                        // 广播缺失时以发现变化的时刻作为保守边界：旧屏幕状态在此刻前
                        // 持续有效。尤其从熄屏恢复亮屏时，必须结算休眠期间的锁定时长，
                        // 不能 reanchor 后把整段熄屏时间静默丢弃。
                        cycle.updateInteractiveState(
                            snapshot = snapshot,
                            nowElapsedMillis = nowElapsed,
                            isInteractive = actualInteractive
                        )
                    } else {
                        cycle.advance(snapshot, nowElapsed)
                    }
                    val phaseChanged = advancedSnapshot.phase != previousPhase
                    if (
                        phaseChanged &&
                        shouldPersistBeforePublishingPhaseTransition(snapshot, advancedSnapshot)
                    ) {
                        beginPersistedLockCompletion(advancedSnapshot, nowElapsed)
                        return
                    }
                    snapshot = advancedSnapshot
                    val completedLockCycle = if (
                        phaseChanged &&
                        previousPhase == MonitorPhase.LOCK &&
                        snapshot.phase == MonitorPhase.USAGE
                    ) {
                        growthRuntimeStore.activeCycle()
                    } else {
                        null
                    }
                    val completedPauseSeconds = if (completedLockCycle != null) {
                        (monitorPauseState?.accumulatedPauseMillis ?: 0L) / 1_000L
                    } else {
                        0L
                    }
                    if (completedLockCycle != null) {
                        maybeAwardContinuation(nowElapsed)
                        growthRuntimeStore.markPendingOutcome(
                            completedLockCycle.cycleId,
                            GrowthCycleOutcome.TIMER_COMPLETED
                        )
                    }
                    if (phaseChanged) {
                        clearKnowledgeChallengeHold(clearVisibilityOrdering = true)
                        monitorPauseState = null
                        pauseAlarmScheduler.cancel()
                        if (snapshot.phase == MonitorPhase.LOCK) {
                            resetLockCompletionPersistTracking()
                            RuntimeLockTruthRegistry.endSession(lockSessionId)
                            lockSessionId = RuntimeLockTruthRegistry.beginSession(
                                phase = MonitorPhase.LOCK,
                                shouldShowLockUi = true,
                                remainingSeconds = cycle.remainingSeconds(snapshot),
                                lockCountsDownWhileInteractive =
                                    activeSessionMode == MonitorSessionMode.FOCUS
                            )
                            growthRuntimeStore.beginLockCycle(
                                lockSessionId = lockSessionId,
                                mode = activeSessionMode.storedValue,
                                configuredDurationSeconds = activeLockMinutes * 60L
                            )
                        } else {
                            revokeKnowledgePassesForSession(lockSessionId)
                        }
                        recordDiagnostic(
                            if (snapshot.phase == MonitorPhase.LOCK) {
                                DiagnosticEventType.LOCK_PHASE_STARTED
                            } else {
                                DiagnosticEventType.LOCK_PHASE_FINISHED
                            }
                        )
                    }
                    val heartbeatDue =
                        nowElapsed - lastProgressPersistedElapsedMillis >=
                            PROGRESS_HEARTBEAT_INTERVAL_MILLIS
                    if (completedLockCycle != null) {
                        persistSnapshot { persisted ->
                            if (persisted) {
                                settleGrowthCycleAsync(
                                    completedLockCycle,
                                    GrowthCycleOutcome.TIMER_COMPLETED,
                                    completedPauseSeconds
                                )
                            } else {
                                growthRuntimeStore.clearPendingOutcome(completedLockCycle.cycleId)
                            }
                        }
                    } else if (phaseChanged || screenStateChanged || heartbeatDue) {
                        persistSnapshot()
                    }

                    if (!phaseChanged && snapshot.phase == MonitorPhase.LOCK) {
                        maybeAwardContinuation(nowElapsed)
                    }

                    publishCurrentState(previousPhase)
                    hasTimerRuntimeFailure = false
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Monitor timer tick failed; scheduling next tick")
                    if (!hasTimerRuntimeFailure) {
                        hasTimerRuntimeFailure = true
                        publishMonitorErrorSafely("监督刷新遇到系统异常，计时服务正在自动重试")
                    }
                } finally {
                    updateRuntimeHealth(SystemClock.elapsedRealtime())
                    if (!isStoppingIntentionally && !isDestroyed) {
                        handler.postDelayed(this, 1_000L)
                    }
                }
            }
        }
        handler.post(timerRunnable!!)
    }

    /** 锁定结束必须先提交可恢复快照，磁盘确认前继续保留当前锁层。 */
    private fun beginPersistedLockCompletion(
        candidateSnapshot: MonitorCycleSnapshot,
        nowElapsedMillis: Long
    ) {
        val completedLockCycle = runCatching { growthRuntimeStore.activeCycle() }
            .onFailure { Log.w(TAG, "growth_cycle_read_failed_at_lock_completion") }
            .getOrNull()
        val completedPauseSeconds = if (completedLockCycle != null) {
            (monitorPauseState?.accumulatedPauseMillis ?: 0L) / 1_000L
        } else {
            0L
        }
        if (completedLockCycle != null) {
            runCatching {
                maybeAwardContinuation(nowElapsedMillis)
                growthRuntimeStore.markPendingOutcome(
                    completedLockCycle.cycleId,
                    GrowthCycleOutcome.TIMER_COMPLETED
                )
            }.onFailure { Log.w(TAG, "growth_cycle_prepare_failed_at_lock_completion") }
        }
        val transitionGeneration = beginLockActionTransition(
            onTimeout = {
                // I/O 永不返回时失败计数不会增长，必须由回调看门狗直接结束锁定。
                // 迟到的回调因 generation 已失配，只会尝试把当前权威快照补写回去。
                Log.e(TAG, "lock_completion_forced reason=persistence_callback_timeout")
                recordDiagnostic(DiagnosticEventType.SNAPSHOT_WRITE_FAILED)
                completedLockCycle?.let { cycleState ->
                    runCatching { growthRuntimeStore.clearPendingOutcome(cycleState.cycleId) }
                }
                resetLockCompletionPersistTracking()
                applyLockCompletion(candidateSnapshot)
                persistSnapshot()
                publishMonitorErrorSafely("进度保存超时，已按计时结束本轮锁定")
            }
        )
        persistLockActionCandidate(
            candidateSnapshot = candidateSnapshot,
            candidatePauseState = null,
            appliedGrowthOrderIds = emptySet()
        ) { result ->
            val persisted = result.isSuccess &&
                result.snapshot == candidateSnapshot &&
                result.pauseState == null
            if (!isCurrentLockActionTransition(transitionGeneration)) {
                completedLockCycle?.let { cycleState ->
                    runCatching { growthRuntimeStore.clearPendingOutcome(cycleState.cycleId) }
                }
                if (persisted) persistSnapshot()
                return@persistLockActionCandidate
            }
            completeLockActionTransition(transitionGeneration)
            if (
                isDestroyed ||
                isStoppingIntentionally ||
                (isStopClearInFlight && !hasStopClearTimedOut)
            ) {
                if (persisted && completedLockCycle != null) {
                    settleGrowthCycleAsync(
                        completedLockCycle,
                        GrowthCycleOutcome.TIMER_COMPLETED,
                        completedPauseSeconds
                    )
                } else {
                    completedLockCycle?.let { cycleState ->
                        runCatching { growthRuntimeStore.clearPendingOutcome(cycleState.cycleId) }
                    }
                }
                return@persistLockActionCandidate
            }
            if (!persisted) {
                val nowElapsed = SystemClock.elapsedRealtime()
                recordLockCompletionPersistFailure(nowElapsed)
                if (
                    !shouldForceLockCompletionWithoutPersistence(
                        failureCount = lockCompletionPersistFailureCount,
                        firstFailureElapsedMillis = lockCompletionFirstFailureElapsedMillis,
                        nowElapsedMillis = nowElapsed
                    )
                ) {
                    completedLockCycle?.let { cycleState ->
                        runCatching { growthRuntimeStore.clearPendingOutcome(cycleState.cycleId) }
                    }
                    publishCurrentState(MonitorPhase.LOCK)
                    return@persistLockActionCandidate
                }
                // 落盘持续失败：以内存真值强制结束本轮锁定。成长值结算依赖磁盘确认，
                // 因此强制路径不发奖，只把用户从无出口的锁屏里放出来。
                Log.e(TAG, "lock_completion_forced reason=persistence_unavailable")
                recordDiagnostic(DiagnosticEventType.SNAPSHOT_WRITE_FAILED)
                completedLockCycle?.let { cycleState ->
                    runCatching { growthRuntimeStore.clearPendingOutcome(cycleState.cycleId) }
                }
                resetLockCompletionPersistTracking()
                applyLockCompletion(candidateSnapshot)
                // 磁盘若在此刻恢复，这次补写能让持久化真值立刻跟上内存真值；
                // 仍然失败也无妨：推进后的快照剩余为零，下次恢复会自然进入玩机阶段。
                persistSnapshot()
                publishMonitorErrorSafely("进度保存失败，已按计时结束本轮锁定")
                return@persistLockActionCandidate
            }

            resetLockCompletionPersistTracking()
            applyLockCompletion(candidateSnapshot)
            persistSnapshot()
            completedLockCycle?.let { cycleState ->
                settleGrowthCycleAsync(
                    cycleState,
                    GrowthCycleOutcome.TIMER_COMPLETED,
                    completedPauseSeconds
                )
            }
        }
    }

    /** 提交锁定结束：推进内存真值并收敛所有锁定期状态。 */
    private fun applyLockCompletion(candidateSnapshot: MonitorCycleSnapshot) {
        val committedSnapshot = reanchorLockActionCandidate(candidateSnapshot)
        snapshot = committedSnapshot
        clearKnowledgeChallengeHold(clearVisibilityOrdering = true)
        monitorPauseState = null
        pauseAlarmScheduler.cancel()
        revokeKnowledgePassesForSession(lockSessionId)
        recordDiagnostic(DiagnosticEventType.LOCK_PHASE_FINISHED)
        publishCurrentState(MonitorPhase.LOCK)
    }

    private fun publishCurrentState(previousPhase: MonitorPhase?) {
        if (isStoppingIntentionally || isDestroyed) return
        val remainingSeconds = cycle.remainingSeconds(snapshot)
        try {
            publishTimerState(remainingSeconds)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to publish monitor tick")
        }
        try {
            syncLockUi(previousPhase, remainingSeconds)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to refresh lock UI")
        }
        try {
            updateNotification(notificationText(snapshot.phase, remainingSeconds))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to update monitor notification")
        }
        syncPhaseBoundaryAlarm()
    }

    private fun syncPhaseBoundaryAlarm() {
        if (!::phaseAlarmScheduler.isInitialized || !::snapshot.isInitialized || !::cycle.isInitialized) {
            return
        }
        val deadline = if (
            isStoppingIntentionally ||
            isDestroyed ||
            isInitialPersistencePending ||
            isLockActionTransitionInFlight ||
            monitorPauseState?.isActive == true ||
            knowledgeChallengeHoldSessionId != NO_LOCK_SESSION
        ) {
            null
        } else {
            cycle.nextPhaseDeadlineElapsedMillis(snapshot)
        }
        if (deadline == null) {
            if (scheduledPhaseBoundaryElapsedMillis != 0L) {
                phaseAlarmScheduler.cancel()
                scheduledPhaseBoundaryElapsedMillis = 0L
            }
            return
        }
        if (deadline == scheduledPhaseBoundaryElapsedMillis) return
        scheduledPhaseBoundaryElapsedMillis = if (phaseAlarmScheduler.schedule(deadline)) {
            deadline
        } else {
            0L
        }
    }

    private fun handleOverlayAttachmentChanged(attached: Boolean) {
        isOverlayLockVisible = attached
        if (!attached) isOverlayActivityHandoffInProgress = false
        if (attached || isOverlayAttachmentSyncPosted || isDestroyed || isStoppingIntentionally) {
            return
        }
        // removeViewImmediate 可能在 syncLockUi 调用栈内同步完成。通过主线程下一轮
        // 再做界面仲裁，避免递归重入；只有收到真实脱离回调后才允许启动 Activity。
        isOverlayAttachmentSyncPosted = true
        handler.post {
            isOverlayAttachmentSyncPosted = false
            if (
                !isDestroyed &&
                !isStoppingIntentionally &&
                ::snapshot.isInitialized &&
                ::cycle.isInitialized
            ) {
                try {
                    syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to reconcile lock surface after overlay removal")
                }
            }
        }
    }

    private fun syncLockUi(previousPhase: MonitorPhase?, remainingSeconds: Int) {
        // 暂停/跳过必须先完成原子落盘；过渡期间保持当前锁层原样不动。
        if (isLockActionTransitionInFlight) return
        val isLockPhase = snapshot.phase == MonitorPhase.LOCK
        if (!isLockPhase) {
            expectedLockActivityHandoff = null
            isOverlayActivityHandoffInProgress = false
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            resetAllowedAppSession("phase_changed")
            clearCallUiAllowancePreservingCall()
            stopForegroundObservation()
            publishRuntimeLockTruth(
                shouldShowLockUi = false,
                remainingSeconds = remainingSeconds
            )
            dismissAllLockSurfaces()
            cancelFallbackLockNotification()
            mediaPlaybackController.allowMediaPlayback()
            stopMediaReplayGuard()
            return
        }

        if (isMonitorPauseActive()) {
            expectedLockActivityHandoff = null
            isOverlayActivityHandoffInProgress = false
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            resetAllowedAppSession("monitor_paused")
            clearCallUiAllowancePreservingCall()
            stopForegroundObservation()
            publishRuntimeLockTruth(
                shouldShowLockUi = false,
                remainingSeconds = remainingSeconds
            )
            dismissAllLockSurfaces()
            cancelFallbackLockNotification()
            mediaPlaybackController.allowMediaPlayback()
            stopMediaReplayGuard()
            return
        }

        if (snapshot.isInteractive) {
            ensureForegroundObservationWindow()
        } else {
            resetAllowedAppSession("screen_not_interactive")
            invalidateCallUiSession("screen_not_interactive")
            stopForegroundObservation()
        }

        if (callUiSession.isCallUiAllowed) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            publishRuntimeLockTruth(
                shouldShowLockUi = false,
                remainingSeconds = remainingSeconds
            )
            overlayController.hide()
            cancelFallbackLockNotification()
            mediaPlaybackController.pauseMediaDuringCall()
            stopMediaReplayGuard()
            sendUnlockBroadcastSafely()
            return
        }

        if (
            callUiSession.isCallActive &&
            SystemClock.elapsedRealtime() < callUiLaunchGraceUntilElapsedMillis
        ) {
            // 来电让路宽限：通话界面还没被前台观察确认，但必须先把锁屏撤下，
            // 系统的全屏来电界面才可能弹出并被确认。宽限内不重新拉起锁屏；
            // 宽限过期仍未确认（用户趁响铃打开了其它 App）时走常规上锁分支。
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            publishRuntimeLockTruth(
                shouldShowLockUi = false,
                remainingSeconds = remainingSeconds
            )
            overlayController.hide()
            cancelFallbackLockNotification()
            mediaPlaybackController.pauseMediaDuringCall()
            stopMediaReplayGuard()
            sendUnlockBroadcastSafely()
            return
        }

        if (allowedAppSession.phase == AllowedAppSessionPhase.ACTIVE) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            publishRuntimeLockTruth(
                shouldShowLockUi = false,
                remainingSeconds = remainingSeconds
            )
            overlayController.hide()
            cancelFallbackLockNotification()
            if (callUiSession.isCallActive) {
                mediaPlaybackController.pauseMediaDuringCall()
            } else {
                mediaPlaybackController.allowMediaPlayback()
            }
            stopMediaReplayGuard()
            if (isLockActivityVisible) sendUnlockBroadcast()
            return
        }

        publishRuntimeLockTruth(
            shouldShowLockUi = true,
            remainingSeconds = remainingSeconds
        )
        if (previousPhase != MonitorPhase.LOCK) {
            if (::prefs.isInitialized) {
                prefs.incrementInterceptCount()
            }
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
        }
        val dispatchedMediaPause = if (callUiSession.isCallActive) {
            mediaPlaybackController.pauseMediaDuringCall()
        } else {
            mediaPlaybackController.pauseMediaDuringLock()
        }

        if (isLockActivityVisible) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            // 方案B：LockActivity 是唯一锁屏界面，隐藏任何残留悬浮窗
            if (::overlayController.isInitialized) overlayController.hide()
            cancelFallbackLockNotification()
            finishLockSurfaceSync(dispatchedMediaPause)
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val isPermissionSettingsGraceActive =
            nowElapsed < overlaySettingsGraceUntilElapsedMillis
        val isAllowedAppLaunchGraceActive = isAllowedAppLaunchGraceActive(nowElapsed)
        if (isPermissionSettingsGraceActive || isAllowedAppLaunchGraceActive) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            overlayController.hide()
            cancelFallbackLockNotification()
            finishLockSurfaceSync(dispatchedMediaPause)
            return
        }
        if (
            lockActivityLaunchPendingUntilElapsedMillis > 0L &&
            nowElapsed >= lockActivityLaunchPendingUntilElapsedMillis
        ) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = true
        }
        val isLockActivityLaunchPending =
            nowElapsed < lockActivityLaunchPendingUntilElapsedMillis

        if (isLockActivityLaunchPending) {
            // 拉起等待期内绝不撤悬浮层：Activity 还没接管，撤下会直接暴露桌面，
            // 用户可以在这段空窗里点开任意应用。悬浮层由 Activity 上报可见后的
            // 交接分支撤下；Activity 起不来时超时兜底也会把它留在原地。
            cancelFallbackLockNotification()
            finishLockSurfaceSync(dispatchedMediaPause)
            return
        }

        // 方案B：熄屏时无需展示锁屏 UI，亮屏后会重新拉起 LockActivity
        if (!snapshot.isInteractive) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            cancelFallbackLockNotification()
            finishLockSurfaceSync(dispatchedMediaPause)
            return
        }

        if (isLockActivityVisible) {
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = false
            cancelFallbackLockNotification()
            finishLockSurfaceSync(dispatchedMediaPause)
            return
        }

        // 方案B：LockActivity 为唯一锁屏界面，亮屏时始终尝试拉起
        if (tryLaunchPrimaryLockActivity(remainingSeconds)) {
            cancelFallbackLockNotification()
            finishLockSurfaceSync(dispatchedMediaPause)
            return
        }
        // LockActivity 启动失败（如系统后台启动限制），使用通知兜底
        showFallbackLockNotificationAndActivity(remainingSeconds)
        finishLockSurfaceSync(dispatchedMediaPause)
    }

    private fun tryLaunchPrimaryLockActivity(remainingSeconds: Int): Boolean {
        beginLockActivityLaunchPending(LOCK_ACTIVITY_HANDOFF_GRACE_MILLIS)
        return try {
            startActivity(createLockIntent(remainingSeconds))
            true
        } catch (error: RuntimeException) {
            clearLockActivityLaunchPending()
            Log.w(TAG, "Unable to launch primary lock activity")
            false
        }
    }

    /**
     * 占住锁定 Activity 的交接窗口。窗口期内计时心跳不会把悬浮层重新盖回来；
     * 超时则由 [lockActivityHandoffTimeoutRunnable] 兜底回到悬浮层，不会两边都没有。
     */
    private fun beginLockActivityLaunchPending(graceMillis: Long) {
        lockActivityLaunchPendingUntilElapsedMillis =
            SystemClock.elapsedRealtime() + graceMillis
        handler.removeCallbacks(lockActivityHandoffTimeoutRunnable)
        handler.postDelayed(lockActivityHandoffTimeoutRunnable, graceMillis)
    }

    private fun clearLockActivityLaunchPending() {
        handler.removeCallbacks(lockActivityHandoffTimeoutRunnable)
        lockActivityLaunchPendingUntilElapsedMillis = 0L
    }

    private fun finishLockSurfaceSync(dispatchedMediaPause: Boolean) {
        if (dispatchedMediaPause && !callUiSession.isCallActive) {
            mediaReplayGuard.deferProbeAfterPause(SystemClock.elapsedRealtime())
        }
        runMediaReplayCheck()
    }

    private fun openAllowedApp(
        packageName: String,
        requestedElapsedMillis: Long,
        fromLockActivity: Boolean
    ) {
        if (isStoppingIntentionally || isDestroyed) return
        if (!::snapshot.isInitialized || snapshot.phase != MonitorPhase.LOCK) return
        if (!snapshot.isInteractive || packageName.isBlank()) return
        if (!allowedAppSession.isLocked || !::allowedAppLaunchExecutor.isInitialized) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (!isCurrentAllowedAppRequest(requestedElapsedMillis, nowElapsed)) {
            Log.w(TAG, "allowlist_launch_rejected reason=stale_navigation_command")
            return
        }

        allowedAppSessionGeneration = nextGeneration(allowedAppSessionGeneration)
        val resolveGeneration = allowedAppSessionGeneration
        val usageAccessGranted = ::usageAccessManager.isInitialized &&
            usageAccessManager.hasUsageAccess()
        if (!usageAccessGranted) {
            // 缺少使用情况访问权限时前台确认必然超时重锁；明确告知原因而不是静默失败。
            Toast.makeText(
                this,
                "缺少\"使用情况访问\"权限，白名单 App 可能会在打开后被重新锁定，请在系统设置中授权",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "正在打开白名单 App…", Toast.LENGTH_SHORT).show()
        }

        try {
            allowedAppLaunchExecutor.execute {
                val launchIntents = try {
                    allowlistManager.resolveLaunchIntents(packageName)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Unable to resolve allowlisted app")
                    recordDiagnostic(DiagnosticEventType.ALLOWLIST_LAUNCH_FAILED, packageName)
                    emptyList()
                }
                handler.post {
                    beginResolvedAllowedAppLaunch(
                        resolveGeneration = resolveGeneration,
                        packageName = packageName,
                        requestedElapsedMillis = requestedElapsedMillis,
                        fromLockActivity = fromLockActivity,
                        launchIntents = launchIntents
                    )
                }
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to schedule allowlisted app launch")
            Toast.makeText(this, "白名单 App 打开失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginResolvedAllowedAppLaunch(
        resolveGeneration: Long,
        packageName: String,
        requestedElapsedMillis: Long,
        fromLockActivity: Boolean,
        launchIntents: List<Intent>
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (
            isDestroyed ||
            resolveGeneration != allowedAppSessionGeneration ||
            !allowedAppSession.isLocked ||
            !::snapshot.isInitialized ||
            snapshot.phase != MonitorPhase.LOCK ||
            !snapshot.isInteractive ||
            packageName !in cachedAllowedPackages ||
            !isCurrentAllowedAppRequest(requestedElapsedMillis, nowElapsed)
        ) {
            return
        }
        if (launchIntents.isEmpty()) {
            recordDiagnostic(DiagnosticEventType.ALLOWLIST_LAUNCH_FAILED, packageName)
            Toast.makeText(this, "白名单 App 已失效或无法打开", Toast.LENGTH_SHORT).show()
            syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            return
        }

        allowedAppSession = AllowedAppSessionReducer.beginLaunch(
            packageName = packageName,
            nowElapsedMillis = nowElapsed,
            nowWallMillis = System.currentTimeMillis(),
            allowPreConfirmationUiHandoff = fromLockActivity
        )
        foregroundObservationCadencePolicy.onTrigger(
            ForegroundObservationCadenceTrigger.ALLOWLIST_LAUNCH,
            nowElapsed
        )
        Log.i(TAG, "allowlist_session phase=LAUNCHING reason=launch_resolved")

        launchAllowedAppAfterRevalidation(
            resolveGeneration = resolveGeneration,
            packageName = packageName,
            requestedElapsedMillis = requestedElapsedMillis
        )
    }

    private fun launchAllowedAppAfterRevalidation(
        resolveGeneration: Long,
        packageName: String,
        requestedElapsedMillis: Long
    ) {
        try {
            allowedAppLaunchExecutor.execute {
                val revalidatedIntents = try {
                    allowlistManager.resolveLaunchIntents(packageName)
                } catch (_: RuntimeException) {
                    emptyList()
                }
                val launched =
                    !isDestroyed &&
                        resolveGeneration == allowedAppSessionGeneration &&
                        revalidatedIntents.isNotEmpty() &&
                        allowlistManager.launchResolvedIntents(revalidatedIntents)
                handler.post {
                    completeAllowedAppLaunch(
                        resolveGeneration = resolveGeneration,
                        packageName = packageName,
                        requestedElapsedMillis = requestedElapsedMillis,
                        launched = launched
                    )
                }
            }
        } catch (_: RuntimeException) {
            completeAllowedAppLaunch(
                resolveGeneration = resolveGeneration,
                packageName = packageName,
                requestedElapsedMillis = requestedElapsedMillis,
                launched = false
            )
        }
    }

    private fun completeAllowedAppLaunch(
        resolveGeneration: Long,
        packageName: String,
        requestedElapsedMillis: Long,
        launched: Boolean
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (
            isDestroyed ||
            resolveGeneration != allowedAppSessionGeneration ||
            allowedAppSession.targetPackage != packageName ||
            !::snapshot.isInitialized ||
            snapshot.phase != MonitorPhase.LOCK ||
            !snapshot.isInteractive ||
            packageName !in cachedAllowedPackages ||
            !isCurrentAllowedAppRequest(requestedElapsedMillis, nowElapsed)
        ) {
            return
        }
        if (!launched) {
            recordDiagnostic(DiagnosticEventType.ALLOWLIST_LAUNCH_FAILED, packageName)
            applyAllowedAppSessionUpdate(AllowedAppSessionReducer.launchFailed())
            syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            Toast.makeText(this, "白名单 App 已失效或打开失败", Toast.LENGTH_SHORT).show()
            return
        }

        allowedAppSession = AllowedAppSessionReducer.markLaunchSucceeded(
            state = allowedAppSession,
            nowElapsedMillis = nowElapsed
        )
        recordDiagnostic(DiagnosticEventType.ALLOWLIST_LAUNCH_SUCCEEDED, packageName)
        launchObservationSequenceFloor = foregroundObservationRequestSequence
        syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
        requestForegroundObservation()
        scheduleForegroundWatch()
    }

    private fun isCurrentAllowedAppRequest(
        requestedElapsedMillis: Long,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val processInvalidation = synchronized(processStopLock) {
            processAllowedAppInvalidationElapsedMillis
        }
        return AllowedAppOpenRequestPolicy.isCurrent(
            requestedElapsedMillis = requestedElapsedMillis,
            nowElapsedMillis = nowElapsedMillis,
            lastInvalidationElapsedMillis = maxOf(
                lastAllowedAppInvalidationElapsedMillis,
                processInvalidation
            )
        )
    }

    private fun isAllowedAppLaunchGraceActive(nowElapsedMillis: Long): Boolean =
        AllowedAppSessionReducer.isLaunchGraceActive(
            state = allowedAppSession,
            nowElapsedMillis = nowElapsedMillis
        )

    private fun applyAllowedAppSessionUpdate(update: AllowedAppSessionUpdate) {
        val previous = allowedAppSession
        allowedAppSession = update.state
        if (
            previous.phase != update.state.phase ||
            previous.targetPackage != update.state.targetPackage
        ) {
            Log.i(
                TAG,
                "allowlist_session phase=${update.state.phase} " +
                    "reason=${update.reason}"
            )
        }

        if (!update.state.isLocked) scheduleForegroundWatch()
    }

    private fun scheduleForegroundWatch(replaceExisting: Boolean = false) {
        if (
            isStoppingIntentionally ||
            isDestroyed ||
            !::snapshot.isInitialized
        ) {
            return
        }

        val nowElapsedMillis = SystemClock.elapsedRealtime()
        val delayMillis = foregroundObservationCadencePolicy.nextDelayMillis(
            ForegroundObservationCadenceInput(
                nowElapsedMillis = nowElapsedMillis,
                isLockPhase = snapshot.phase == MonitorPhase.LOCK,
                isInteractive = snapshot.isInteractive,
                mode = foregroundObservationCadenceMode()
            )
        )
        if (
            delayMillis == null ||
            foregroundWindowGeneration == 0L &&
                allowedAppSession.isLocked &&
                !callUiSession.isCallActive
        ) {
            cancelForegroundWatch(resetPolicy = false)
            return
        }

        val requestedDueElapsedMillis = nowElapsedMillis + delayMillis
        if (foregroundWatchScheduled) {
            val existingRunIsSooner =
                foregroundWatchDueElapsedMillis <= requestedDueElapsedMillis
            if (!replaceExisting && existingRunIsSooner) return
            handler.removeCallbacks(foregroundWatchRunnable)
            foregroundWatchScheduled = false
            foregroundWatchDueElapsedMillis = 0L
        }

        foregroundWatchScheduled = true
        foregroundWatchDueElapsedMillis = requestedDueElapsedMillis
        handler.postDelayed(foregroundWatchRunnable, delayMillis)
    }

    private fun foregroundObservationCadenceMode(): ForegroundObservationCadenceMode = when {
        callUiSession.isCallActive -> ForegroundObservationCadenceMode.CALL_ACTIVE
        allowedAppSession.phase == AllowedAppSessionPhase.LAUNCHING ->
            ForegroundObservationCadenceMode.ALLOWLIST_LAUNCHING
        allowedAppSession.phase == AllowedAppSessionPhase.ACTIVE ->
            ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE
        else -> ForegroundObservationCadenceMode.LOCKED
    }

    private fun cancelForegroundWatch(resetPolicy: Boolean) {
        if (foregroundWatchScheduled) handler.removeCallbacks(foregroundWatchRunnable)
        foregroundWatchScheduled = false
        foregroundWatchDueElapsedMillis = 0L
        if (resetPolicy) foregroundObservationCadencePolicy.reset()
    }

    private fun resetAllowedAppSession(reason: String) {
        val previous = allowedAppSession
        allowedAppSession = AllowedAppSessionState()
        allowedAppSessionGeneration = nextGeneration(allowedAppSessionGeneration)
        launchObservationSequenceFloor = 0L
        if (!previous.isLocked) {
            Log.i(
                TAG,
                "allowlist_session phase=LOCKED reason=$reason"
            )
        }
    }

    private fun ensureForegroundObservationWindow() {
        if (isDestroyed || isStoppingIntentionally) return
        if (foregroundWindowGeneration != 0L || !::foregroundObservationWorker.isInitialized) {
            scheduleForegroundWatch()
            return
        }
        val anchor = (System.currentTimeMillis() - LOCK_OBSERVATION_LOOKBACK_MILLIS)
            .coerceAtLeast(0L)
        foregroundWindowGeneration = try {
            foregroundObservationWorker.beginObservationWindow(anchor)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to open foreground observation window")
            0L
        }
        lastForegroundObservation = null
        lastForegroundObservationElapsedMillis = 0L
        foregroundObservationRequestSequence = 0L
        lastForegroundObservationRequestSequence = 0L
        launchObservationSequenceFloor = 0L
        requestForegroundObservation()
        scheduleForegroundWatch()
    }

    private fun stopForegroundObservation() {
        cancelForegroundWatch(resetPolicy = true)
        foregroundWindowGeneration = 0L
        lastForegroundObservation = null
        lastForegroundObservationElapsedMillis = 0L
        foregroundObservationRequestSequence = 0L
        lastForegroundObservationRequestSequence = 0L
        launchObservationSequenceFloor = 0L
        if (::foregroundObservationWorker.isInitialized) {
            foregroundObservationWorker.resetObservationWindow()
        }
    }

    private fun requestForegroundObservation() {
        val workerGeneration = foregroundWindowGeneration
        if (workerGeneration == 0L || !::foregroundObservationWorker.isInitialized) return
        val requestSessionGeneration = allowedAppSessionGeneration
        val requestedElapsedMillis = SystemClock.elapsedRealtime()
        val requestSequence = nextGeneration(foregroundObservationRequestSequence)
        val accepted = foregroundObservationWorker.requestObservation(
            expectedGeneration = workerGeneration,
            nowWallMillis = System.currentTimeMillis()
        ) { observation ->
            recordUsageObservationDiagnostic(observation.status)
            foregroundObservationCadencePolicy.recordObservationResult(
                isAvailable = observation.isAvailable
            )
            lastForegroundObservation = observation
            lastForegroundObservationElapsedMillis = SystemClock.elapsedRealtime()
            lastForegroundObservationRequestSequence = requestSequence
            val callUiChanged = evaluateObservationForCallUi(observation)
            if (
                requestSessionGeneration == allowedAppSessionGeneration &&
                !allowedAppSession.isLocked
            ) {
                evaluateObservationForSession(observation)
            } else if (callUiChanged && ::cycle.isInitialized && ::snapshot.isInitialized) {
                syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            }
            // 成功时仅在新频率更快时提前，不把查询耗时叠加到既有周期；失败时则主动
            // 延后到退避间隔，避免下一次旧任务过早触发。
            scheduleForegroundWatch(replaceExisting = !observation.isAvailable)
        }
        if (accepted) foregroundObservationRequestSequence = requestSequence
    }

    private fun evaluateObservationForSession(observation: ForegroundObservation) {
        if (allowedAppSession.isLocked || snapshot.phase != MonitorPhase.LOCK) return
        if (
            allowedAppSession.phase == AllowedAppSessionPhase.LAUNCHING &&
            lastForegroundObservationRequestSequence <= launchObservationSequenceFloor
        ) {
            return
        }
        val previous = allowedAppSession
        val update = AllowedAppSessionReducer.evaluate(
            state = previous,
            observation = observation,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            isTargetAllowed = previous.targetPackage in
                cachedAllowedPackages,
            isInteractive = snapshot.isInteractive
        )
        logAllowlistEvents(previous, update.state, observation.newEvents)
        applyAllowedAppSessionUpdate(update)
        syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
    }

    private fun evaluateObservationForCallUi(observation: ForegroundObservation): Boolean {
        if (
            !callUiSession.isCallActive ||
            !::snapshot.isInitialized ||
            snapshot.phase != MonitorPhase.LOCK
        ) {
            return false
        }
        val previous = callUiSession
        applyCallUiSessionUpdate(
            CallUiSessionReducer.evaluate(
                state = previous,
                observation = observation,
                allowedCallPackages = cachedCallUiPackages,
                isInteractive = snapshot.isInteractive,
                nowWallMillis = System.currentTimeMillis()
            )
        )
        return callUiSession != previous
    }

    private fun applyCallUiSessionUpdate(update: CallUiSessionUpdate) {
        val previous = callUiSession
        callUiSession = update.state
        if (
            previous.isCallActive != update.state.isCallActive ||
            previous.allowedPackage != update.state.allowedPackage ||
            previous.requiresFreshForegroundEvent != update.state.requiresFreshForegroundEvent
        ) {
            Log.i(
                TAG,
                "call_ui_session active=${update.state.isCallActive} " +
                    "reason=${update.reason}"
            )
        }
    }

    private fun invalidateCallUiSession(reason: String) {
        val previous = callUiSession
        applyCallUiSessionUpdate(
            CallUiSessionReducer.invalidateNavigation(
                state = previous,
                nowWallMillis = System.currentTimeMillis()
            )
        )
        if (previous.isCallUiAllowed) Log.i(TAG, "call_ui_relocked reason=$reason")
    }

    private fun clearCallUiAllowancePreservingCall() {
        callUiSession = if (callUiSession.isCallActive) {
            CallUiSessionState(isCallActive = true)
        } else {
            CallUiSessionState()
        }
    }

    private fun invalidateTransientAccess(reason: String) {
        invalidateAllowedAppRequests(reason)
        invalidateCallUiSession(reason)
    }

    private fun forceRelockFromNavigation(reason: String) {
        overlaySettingsGraceUntilElapsedMillis = 0L
        invalidateTransientAccess("system_navigation_$reason")
        if (
            ::snapshot.isInitialized &&
            ::cycle.isInitialized &&
            snapshot.phase == MonitorPhase.LOCK &&
            // 暂停生效期间阶段仍是 LOCK，但用户本就可以自由离开：此时强行重锁
            // 只会误暂停用户正在播放的媒体并翻转锁层兜底闩锁；暂停到期后的
            // 重锁由计时心跳与恢复闹钟负责。
            !isMonitorPauseActive()
        ) {
            // 普通应用无法拦截系统 Home，但悬浮窗权限允许在导航广播到达时立即接管。
            // 不再等待后台 Activity 的启动超时，避免桌面短暂暴露和重复重入。
            isLockActivityVisible = false
            lockActivityInstanceToken = null
            // 用户主动发起、尚在有效期内的交接不能清掉。导航广播可能是上一次按键的
            // 延迟投递，或由 ROM 重复发送；清掉令牌会让随后上报可见的 Activity 校验
            // 失败，被服务自己关掉，用户看到的就是"点了暂停完全没反应"。
            val nowElapsedMillis = SystemClock.elapsedRealtime()
            val hasPendingHandoff = expectedLockActivityHandoff
                ?.let { nowElapsedMillis <= it.expiresAtElapsedMillis } == true
            if (!hasPendingHandoff) {
                expectedLockActivityHandoff = null
            }
            isOverlayActivityHandoffInProgress = false
            clearLockActivityLaunchPending()
            shouldUseOverlayAfterLockActivityTimeout = true
            foregroundObservationCadencePolicy.onTrigger(
                ForegroundObservationCadenceTrigger.SYSTEM_NAVIGATION,
                SystemClock.elapsedRealtime()
            )
            if (callUiSession.isCallActive) {
                mediaPlaybackController.pauseMediaDuringCall(forceMediaCommand = true)
            } else {
                mediaPlaybackController.pauseMediaDuringLock(forceMediaCommand = true)
                mediaReplayGuard.deferProbeAfterPause(SystemClock.elapsedRealtime())
            }
            syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
        }
    }

    private fun requestImmediateMediaReplayProbe() {
        forceMediaReplayProbePending = true
    }

    private fun runMediaReplayCheck(forceImmediate: Boolean = false) {
        if (
            !::snapshot.isInitialized ||
            !::mediaPlaybackProbe.isInitialized ||
            !::mediaPlaybackController.isInitialized
        ) {
            return
        }

        val shouldEnable = shouldEnableMediaReplayGuard(
            isLockPhase = snapshot.phase == MonitorPhase.LOCK,
            isLockSurfaceVisible = isOverlayLockVisible || isLockActivityVisible,
            isAllowedAppMediaTransition =
                allowedAppSession.phase == AllowedAppSessionPhase.ACTIVE ||
                    allowedAppSession.phase == AllowedAppSessionPhase.LAUNCHING &&
                    allowedAppSession.launchSucceeded,
            isCallActive = callUiSession.isCallActive,
            isInteractive = snapshot.isInteractive
        )
        if (shouldEnable) {
            mediaPlaybackController.enforceMuteIfNecessary()
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldProbeImmediately = forceImmediate || forceMediaReplayProbePending
        mediaReplayGuard.setEnabled(
            shouldEnable = shouldEnable,
            nowElapsedMillis = nowElapsed,
            probeImmediately = shouldProbeImmediately
        )
        if (!shouldEnable) return
        forceMediaReplayProbePending = false
        if (!mediaReplayGuard.shouldProbe(nowElapsed)) return

        val mediaProbeResult = mediaPlaybackProbe.probe()
        lastMediaProbeElapsedMillis = nowElapsed
        lastMediaProbeAvailable = mediaProbeResult.isAvailable
        val action = mediaReplayGuard.recordProbeResult(
            nowElapsedMillis = nowElapsed,
            isMediaPlaying = mediaProbeResult.isMediaPlaying,
            quietProbeIntervalMillis = if (snapshot.isInteractive) {
                INTERACTIVE_MEDIA_PROBE_INTERVAL_MILLIS
            } else {
                SCREEN_OFF_MEDIA_PROBE_INTERVAL_MILLIS
            }
        )
        if (action == MediaReplayAction.REAPPLY_MEDIA_PAUSE) {
            if (!mediaReplayWasDetected) {
                recordDiagnostic(DiagnosticEventType.MEDIA_REPLAY_DETECTED)
            }
            mediaReplayWasDetected = true
            mediaPlaybackController.enforceMediaPauseDuringLock()
            recordDiagnostic(DiagnosticEventType.MEDIA_PAUSE_DISPATCHED)
        } else {
            mediaReplayWasDetected = false
        }
    }

    private fun stopMediaReplayGuard() {
        forceMediaReplayProbePending = false
        mediaReplayGuard.disable()
        lastMediaProbeElapsedMillis = 0L
        lastMediaProbeAvailable = false
        mediaReplayWasDetected = false
    }

    private fun updateRuntimeHealth(nowElapsedMillis: Long) {
        val phase = if (::snapshot.isInitialized) snapshot.phase else null
        val observerHealth = if (::foregroundObservationWorker.isInitialized) {
            try {
                foregroundObservationWorker.healthSnapshot()
            } catch (_: RuntimeException) {
                null
            }
        } else {
            null
        }
        observerHealth?.retiredExecutorCount?.let { retiredCount ->
            if (retiredCount > lastDiagnosticRetiredExecutorCount) {
                recordDiagnostic(DiagnosticEventType.USAGE_WORKER_REPLACED)
            }
            lastDiagnosticRetiredExecutorCount = retiredCount
        }
        MonitorRuntimeHealthRegistry.update(
            MonitorRuntimeHealth(
                serviceAvailable = true,
                lastHeartbeatElapsedMillis = nowElapsedMillis,
                phase = phase,
                persistenceHealthy = !hasPersistenceFailure,
                timerHealthy = !hasTimerRuntimeFailure,
                foregroundObserverHealth = observerHealth,
                mediaReplayGuardEnabled = mediaReplayGuard.isEnabled,
                lastMediaProbeElapsedMillis = lastMediaProbeElapsedMillis,
                mediaProbeAvailable = lastMediaProbeAvailable,
                internalReceiverRegistered = internalReceiverRegistered,
                screenReceiverRegistered = screenReceiverRegistered,
                systemDialogReceiverRegistered = systemDialogReceiverRegistered,
                callStateMonitorRequired = callStateMonitorRequired,
                callStateMonitorRegistered = callStateMonitorRegistered
            )
        )
    }

    private fun recordUsageObservationDiagnostic(status: ForegroundObservationStatus) {
        if (status == lastDiagnosticUsageStatus) return
        val event = when (status) {
            ForegroundObservationStatus.AVAILABLE -> DiagnosticEventType.USAGE_QUERY_SUCCEEDED
            ForegroundObservationStatus.TIMEOUT -> DiagnosticEventType.USAGE_QUERY_TIMED_OUT
            ForegroundObservationStatus.CIRCUIT_OPEN -> DiagnosticEventType.USAGE_CIRCUIT_OPENED
            ForegroundObservationStatus.ACCESS_DENIED,
            ForegroundObservationStatus.QUERY_FAILED -> DiagnosticEventType.USAGE_QUERY_FAILED
        }
        lastDiagnosticUsageStatus = status
        recordDiagnostic(event)
    }

    private fun recordAllowlistDiagnostic(state: AllowlistRepositoryState) {
        val key = when (state) {
            AllowlistRepositoryState.Uninitialized -> return
            is AllowlistRepositoryState.Loading -> {
                lastDiagnosticAllowlistState = null
                return
            }
            is AllowlistRepositoryState.Ready -> "ready"
            is AllowlistRepositoryState.Stale -> "stale"
            is AllowlistRepositoryState.Failed -> "failed"
        }
        if (key == lastDiagnosticAllowlistState) return
        lastDiagnosticAllowlistState = key
        recordDiagnostic(
            when (state) {
                is AllowlistRepositoryState.Ready ->
                    DiagnosticEventType.ALLOWLIST_REFRESH_SUCCEEDED
                is AllowlistRepositoryState.Stale ->
                    DiagnosticEventType.ALLOWLIST_REFRESH_PARTIAL
                is AllowlistRepositoryState.Failed ->
                    DiagnosticEventType.ALLOWLIST_REFRESH_FAILED
            }
        )
    }

    private fun recordDiagnostic(
        event: DiagnosticEventType,
        packageName: String? = null
    ) {
        try {
            AndroidDiagnostics.record(applicationContext, event, packageName)
        } catch (_: RuntimeException) {
            // 诊断失败不得改变监督逻辑。
        }
    }

    private fun invalidateAllowedAppRequests(reason: String) {
        lastAllowedAppInvalidationElapsedMillis = SystemClock.elapsedRealtime()
        synchronized(processStopLock) {
            processAllowedAppInvalidationElapsedMillis = maxOf(
                processAllowedAppInvalidationElapsedMillis,
                lastAllowedAppInvalidationElapsedMillis
            )
        }
        resetAllowedAppSession(reason)
    }

    private fun requestAllowedAppsRefresh() {
        if (!::allowlistRepository.isInitialized || isDestroyed) return
        allowlistRepository.refresh()
    }

    private fun applyAllowlistSnapshot(allowlistSnapshot: AllowlistSnapshot) {
        if (isDestroyed) return
        cachedAllowedApps = allowlistSnapshot.allowedApps
        cachedAllowedPackages = allowlistSnapshot.allowedPackages
        cachedCallUiPackages = allowlistSnapshot.callUiPackages
        overlayController.updateAllowedApps(allowlistSnapshot.allowedApps)
        val callUiWasAllowed = callUiSession.isCallUiAllowed
        if (
            callUiSession.allowedPackage != null &&
            callUiSession.allowedPackage !in cachedCallUiPackages
        ) {
            invalidateCallUiSession("dialer_no_longer_allowed")
        }
        val target = allowedAppSession.targetPackage
        if (target != null && target !in allowlistSnapshot.allowedPackages) {
            resetAllowedAppSession("target_no_longer_allowed")
            if (::snapshot.isInitialized && snapshot.phase == MonitorPhase.LOCK) {
                syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            }
        }
        if (
            callUiWasAllowed &&
            !callUiSession.isCallUiAllowed &&
            ::snapshot.isInitialized &&
            snapshot.phase == MonitorPhase.LOCK
        ) {
            syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
        }
    }

    private fun logAllowlistEvents(
        previous: AllowedAppSessionState,
        current: AllowedAppSessionState,
        events: List<ForegroundAppEvent>
    ) {
        if (previous.isLocked && current.isLocked) return
        events.forEach { event ->
            Log.d(
                TAG,
                "allowlist_event kind=${event.kind} timestamp=${event.timestampMillis}"
            )
        }
    }

    private fun showFallbackLockNotificationAndActivity(remainingSeconds: Int) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val isPermissionSettingsGraceActive =
            nowElapsed < overlaySettingsGraceUntilElapsedMillis
        val isAllowedAppLaunchGraceActive = isAllowedAppLaunchGraceActive(nowElapsed)
        if (isPermissionSettingsGraceActive || isAllowedAppLaunchGraceActive) return

        showFallbackLockSurface(
            remainingSeconds = remainingSeconds,
            title = "强制锁定中",
            message = "悬浮权限不可用，点按返回锁定界面",
            pendingIntentRequestCode = 1,
            mayLaunchActivity = snapshot.isInteractive && !isLockActivityVisible
        )
    }

    private fun showEmergencyFallbackLockNotificationAndActivity(
        remainingSeconds: Int,
        message: String
    ) {
        ensureEmergencyRuntimeLockTruth(remainingSeconds)
        if (isDestroyed) {
            markRuntimeLockUnavailable()
        }
        showFallbackLockSurface(
            remainingSeconds = remainingSeconds,
            title = "强制锁定保护中",
            message = message,
            pendingIntentRequestCode = 2,
            mayLaunchActivity = true
        )
    }

    private fun showFallbackLockSurface(
        remainingSeconds: Int,
        title: String,
        message: String,
        pendingIntentRequestCode: Int,
        mayLaunchActivity: Boolean
    ) {
        val sessionId = lockSessionId
        if (sessionId == NO_LOCK_SESSION) return
        val lockIntent = createLockIntent(remainingSeconds)
        val lockPendingIntent = PendingIntent.getActivity(
            this,
            pendingIntentRequestCode,
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (fallbackNotificationActiveSessionId != sessionId) {
            try {
                val notificationManager = getSystemService(NotificationManager::class.java)
                val canUseFullScreenIntent =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                        notificationManager.canUseFullScreenIntent()
                val attachFullScreenIntent = shouldAttachFallbackFullScreenIntent(
                    sessionId = sessionId,
                    dispatchedSessionId = fallbackFullScreenIntentSessionId,
                    canUseFullScreenIntent = canUseFullScreenIntent
                )
                val builder = NotificationCompat.Builder(
                    this,
                    MonitorNotificationChannels.LOCK_RECOVERY_CHANNEL_ID
                )
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentIntent(lockPendingIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                if (attachFullScreenIntent) {
                    builder.setFullScreenIntent(lockPendingIntent, true)
                }
                notificationManager.notify(FALLBACK_NOTIFICATION_ID, builder.build())
                fallbackNotificationActiveSessionId = sessionId
                if (attachFullScreenIntent) fallbackFullScreenIntentSessionId = sessionId
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to publish fallback lock notification")
            }
        }

        if (!mayLaunchActivity || !consumeFallbackActivityAttempt(sessionId)) return
        try {
            startActivity(lockIntent)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to show fallback lock activity")
        }
    }

    private fun consumeFallbackActivityAttempt(sessionId: Long): Boolean {
        if (fallbackActivityAttemptSessionId != sessionId) {
            fallbackActivityAttemptSessionId = sessionId
            fallbackActivityAttemptCount = 0
            lastFallbackAttemptElapsedMillis = 0L
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        if (
            !shouldAttemptFallbackActivity(
                sessionId = sessionId,
                attemptSessionId = fallbackActivityAttemptSessionId,
                attemptCount = fallbackActivityAttemptCount,
                lastAttemptElapsedMillis = lastFallbackAttemptElapsedMillis,
                nowElapsedMillis = nowElapsed,
                maximumAttempts = FALLBACK_ACTIVITY_MAX_ATTEMPTS_PER_SESSION,
                retryIntervalMillis = FALLBACK_RETRY_INTERVAL_MILLIS
            )
        ) {
            return false
        }
        fallbackActivityAttemptCount++
        lastFallbackAttemptElapsedMillis = nowElapsed
        return true
    }

    private fun cancelFallbackLockNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .cancel(FALLBACK_NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to cancel fallback lock notification")
        } finally {
            fallbackNotificationActiveSessionId = NO_LOCK_SESSION
        }
    }

    private fun createLockIntent(remainingSeconds: Int) = Intent(this, LockActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(EXTRA_REMAINING_SECONDS, remainingSeconds)
        putExtra(EXTRA_LOCK_SESSION_ID, lockSessionId)
        putExtra(EXTRA_SESSION_MODE, activeSessionMode.storedValue)
        putExtra(EXTRA_LOCK_TASK_TITLE, currentLockTaskTitle())
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isLocking = snapshot.phase == MonitorPhase.LOCK
        return NotificationCompat.Builder(this, MonitorNotificationChannels.SERVICE_CHANNEL_ID)
            .setContentTitle(if (isLocking) "强制锁定中" else "玩机监督进行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildStartingNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MonitorNotificationChannels.SERVICE_CHANNEL_ID)
            .setContentTitle("正在启动玩机监督")
            .setContentText("正在初始化计时与锁定服务…")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        MonitorNotificationChannels.ensureCreated(this)
    }

    private fun persistSnapshot(
        appliedGrowthOrderIds: Set<String> = emptySet(),
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (isInitialPersistencePending && onComplete == null) return true
        if (
            isStoppingIntentionally ||
            !::snapshotPersistenceWorker.isInitialized ||
            !::snapshot.isInitialized
        ) {
            onComplete?.invoke(false)
            return false
        }
        if (shouldDeferSnapshotPersistence(isLockActionTransitionInFlight)) {
            return deferSnapshotPersistence(appliedGrowthOrderIds, onComplete)
        }
        val requestedSnapshot = snapshot
        val requestedPauseState = monitorPauseState
        val accepted = snapshotPersistenceWorker.submit(
            request = MonitorSnapshotPersistRequest(
                snapshot = requestedSnapshot,
                scheduledOwner = scheduledMonitorOwner,
                sessionMode = activeSessionMode,
                usageMinutes = activeUsageMinutes,
                lockMinutes = activeLockMinutes,
                pauseState = requestedPauseState,
                appliedGrowthOrderIds = appliedGrowthOrderIds,
                focusTaskTitle = activeFocusTaskTitle,
                focusSourceTodoId = activeFocusSourceTodoId
            ),
            onResult = { result ->
                handleSnapshotPersistResult(result)
                onComplete?.invoke(
                    isSnapshotPersistResultForRequest(
                        result = result,
                        requestedSnapshot = requestedSnapshot,
                        requestedPauseState = requestedPauseState
                    )
                )
            }
        )
        if (!accepted) {
            handleSnapshotPersistResult(
                MonitorSnapshotPersistResult(
                    snapshot = requestedSnapshot,
                    pauseState = requestedPauseState,
                    guardArmed = false,
                    progressSaved = false,
                    guardCleared = false
                )
            )
            onComplete?.invoke(false)
        }
        return accepted
    }

    /**
     * Worker 会用最新请求覆盖尚未执行的普通请求并复用全部回调。锁动作候选在途时若
     * 继续提交普通快照，暂停、跳过或锁定完成候选就可能被覆盖。这里只记录“完成后
     * 还需补写”，待过渡结束后再抓取最终权威状态。
     */
    private fun deferSnapshotPersistence(
        appliedGrowthOrderIds: Set<String>,
        onComplete: ((Boolean) -> Unit)?
    ): Boolean {
        val mergedOrderIds = deferredSnapshotGrowthOrderIds + appliedGrowthOrderIds
        if (mergedOrderIds.size > MAX_DEFERRED_SNAPSHOT_GROWTH_ORDER_IDS) {
            onComplete?.invoke(false)
            return false
        }
        hasDeferredSnapshotPersistence = true
        deferredSnapshotGrowthOrderIds.clear()
        deferredSnapshotGrowthOrderIds.addAll(mergedOrderIds)
        onComplete?.let(deferredSnapshotCallbacks::add)
        return true
    }

    private fun persistLockActionCandidate(
        candidateSnapshot: MonitorCycleSnapshot,
        candidatePauseState: MonitorPauseState?,
        appliedGrowthOrderIds: Set<String>,
        onComplete: (MonitorSnapshotPersistResult) -> Unit
    ): Boolean {
        if (
            isStoppingIntentionally ||
            !::snapshotPersistenceWorker.isInitialized ||
            !::snapshot.isInitialized
        ) {
            onComplete(
                MonitorSnapshotPersistResult(
                    snapshot = candidateSnapshot,
                    pauseState = candidatePauseState,
                    guardArmed = false,
                    progressSaved = false,
                    guardCleared = false
                )
            )
            return false
        }
        val request = MonitorSnapshotPersistRequest(
            snapshot = candidateSnapshot,
            scheduledOwner = scheduledMonitorOwner,
            sessionMode = activeSessionMode,
            usageMinutes = activeUsageMinutes,
            lockMinutes = activeLockMinutes,
            pauseState = candidatePauseState,
            appliedGrowthOrderIds = appliedGrowthOrderIds,
            focusTaskTitle = activeFocusTaskTitle,
            focusSourceTodoId = activeFocusSourceTodoId
        )
        val accepted = snapshotPersistenceWorker.submit(request) { result ->
            handleSnapshotPersistResult(result)
            onComplete(result)
        }
        if (!accepted) {
            val failure = MonitorSnapshotPersistResult(
                snapshot = candidateSnapshot,
                pauseState = candidatePauseState,
                guardArmed = false,
                progressSaved = false,
                guardCleared = false
            )
            handleSnapshotPersistResult(failure)
            onComplete(failure)
        }
        return accepted
    }

    private fun handleSnapshotPersistResult(result: MonitorSnapshotPersistResult) {
        if (isDestroyed || isStoppingIntentionally || isStopClearInFlight) return
        val wasFailing = hasPersistenceFailure
        hasPersistenceFailure = !result.isSuccess
        if (lastDiagnosticPersistenceHealthy != result.isSuccess) {
            recordDiagnostic(
                if (result.isSuccess) {
                    DiagnosticEventType.SNAPSHOT_WRITE_SUCCEEDED
                } else {
                    DiagnosticEventType.SNAPSHOT_WRITE_FAILED
                }
            )
            lastDiagnosticPersistenceHealthy = result.isSuccess
        }
        if (result.isSuccess) {
            if (result.snapshot.phase == MonitorPhase.LOCK) {
                // 一次健康 LOCK 写入切断此前失败链，避免旧时间戳污染下一次阶段完成。
                resetLockCompletionPersistTracking()
            }
            lastProgressPersistedElapsedMillis = maxOf(
                lastProgressPersistedElapsedMillis,
                result.snapshot.checkpointElapsedMillis
            )
        } else if (!wasFailing) {
            publishMonitorError(
                if (result.guardArmed) {
                    "监督状态保存失败，已启用强制锁定恢复保护并继续重试"
                } else {
                    "监督状态与恢复保护均写入失败，请保持服务运行并检查设备存储"
                }
            )
        }
    }

    private fun fullPhaseSnapshot(
        phase: MonitorPhase,
        nowElapsed: Long,
        bootCount: Int,
        isInteractive: Boolean
    ) = MonitorCycleSnapshot(
        phase = phase,
        remainingMillis = cycle.durationMillis(phase),
        checkpointElapsedMillis = nowElapsed,
        bootCount = bootCount,
        isInteractive = isInteractive
    )

    private fun readBootCount(): Int = try {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT)
    } catch (_: RuntimeException) {
        UNKNOWN_BOOT_COUNT
    }

    private fun publishTimerState(remainingSeconds: Int) {
        sendBroadcastSafely(Intent(ACTION_TIMER_TICK).apply {
            putExtra(
                EXTRA_STATE,
                if (isMonitorPauseActive()) STATE_PAUSED else snapshot.phase.storedValue
            )
            putExtra(EXTRA_REMAINING_SECONDS, remainingSeconds)
            putExtra(EXTRA_IS_RUNNING, true)
            putExtra(EXTRA_LOCK_SESSION_ID, lockSessionId)
            putExtra(EXTRA_SESSION_MODE, activeSessionMode.storedValue)
            putExtra(EXTRA_LOCK_TASK_TITLE, currentLockTaskTitle())
            setPackage(packageName)
        })
    }

    private fun publishMonitorStarted(remainingSeconds: Int, startAttemptId: Long) {
        recordDiagnostic(DiagnosticEventType.MONITOR_STARTED)
        if (snapshot.phase == MonitorPhase.LOCK) {
            recordDiagnostic(DiagnosticEventType.LOCK_PHASE_STARTED)
        }
        sendBroadcastSafely(Intent(ACTION_MONITOR_STARTED).apply {
            putExtra(EXTRA_STATE, snapshot.phase.storedValue)
            putExtra(EXTRA_REMAINING_SECONDS, remainingSeconds)
            putExtra(EXTRA_IS_RUNNING, true)
            putExtra(EXTRA_START_ATTEMPT_ID, startAttemptId)
            putExtra(EXTRA_SESSION_MODE, activeSessionMode.storedValue)
            putExtra(EXTRA_LOCK_TASK_TITLE, currentLockTaskTitle())
            setPackage(packageName)
        })
    }

    private fun terminateUnserviceableMonitorRuntime(
        message: String,
        startAttemptId: Long = NO_START_ATTEMPT
    ) {
        markPendingBootRecovery(SupervisionRecoveryStatus.FAILED)
        suppressLockHandoffOnDestroy = true
        isStoppingIntentionally = true
        preserveLockResourcesOnDestroy = false
        isInitialPersistencePending = false
        timerRunnable?.let(handler::removeCallbacks)

        val preferenceManager = if (::prefs.isInitialized) prefs else PreferenceManager(this)
        val guard = if (::recoveryGuard.isInitialized) {
            recoveryGuard
        } else {
            MonitorRecoveryGuard(this)
        }
        val progressCleared = try {
            preferenceManager.clearMonitorProgress()
        } catch (_: RuntimeException) {
            false
        }
        val guardCleared = try {
            guard.clear()
        } catch (_: RuntimeException) {
            false
        }
        if (!progressCleared || !guardCleared) {
            Log.e(
                TAG,
                "Unable to clear unserviceable monitor state " +
                    "progressCleared=$progressCleared guardCleared=$guardCleared"
            )
        }

        try {
            if (::pauseAlarmScheduler.isInitialized) {
                pauseAlarmScheduler.cancel()
            } else {
                MonitorPauseAlarmScheduler(this).cancel()
            }
        } catch (_: RuntimeException) {
            // 即使闹钟服务不可用，也必须继续撤销无计时器锁层。
        }
        monitorPauseState = null
        if (::overlayController.isInitialized) overlayController.hide()
        releaseRetainedLockResources()
        cancelFallbackLockNotification()

        val failedSessionId = lockSessionId.takeIf { it != NO_LOCK_SESSION }
            ?: RuntimeLockTruthRegistry.currentSessionId()
        lockSessionId = failedSessionId
        sendUnlockBroadcastSafely()
        if (failedSessionId != NO_LOCK_SESSION) {
            RuntimeLockTruthRegistry.endSession(failedSessionId)
        }
        lockSessionId = NO_LOCK_SESSION

        closeGlobalHistory()
        sendBroadcastSafely(Intent(ACTION_MONITOR_START_FAILED).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
            putExtra(EXTRA_START_ATTEMPT_ID, startAttemptId)
            setPackage(packageName)
        })
        sendBroadcastSafely(Intent(ACTION_MONITOR_STOPPED).apply { setPackage(packageName) })
        isRunning = false
        stopForegroundSafely()
        stopSelf()
    }

    private fun keepExistingMonitorLockedAfterStartupFailure(message: String) {
        isStoppingIntentionally = false
        isRunning = true
        preserveLockResourcesOnDestroy = true
        publishMonitorErrorSafely(message)

        val primaryLockReady =
            ::snapshot.isInitialized &&
                ::cycle.isInitialized &&
                ::overlayController.isInitialized &&
                ::mediaPlaybackController.isInitialized
        if (!primaryLockReady) {
            terminateUnserviceableMonitorRuntime(message)
            return
        }

        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            val bootCount = readBootCount()
            val interactive =
                if (::powerManager.isInitialized) powerManager.isInteractive else true
            val persistedSnapshot = if (::prefs.isInitialized) {
                runCatching { prefs.loadMonitorProgress() }.getOrNull()
            } else {
                null
            }
            val guardRequiresLock = if (::recoveryGuard.isInitialized) {
                runCatching { recoveryGuard.requiresLock() }.getOrDefault(true)
            } else {
                true
            }
            snapshot = when {
                snapshot.phase == MonitorPhase.LOCK -> snapshot
                shouldRebuildLockAfterRuntimeFailure(
                    currentPhase = snapshot.phase,
                    persistedPhase = persistedSnapshot?.phase,
                    recoveryGuardRequiresLock = guardRequiresLock
                ) -> cycle.reanchor(
                    // 采用持久化真值自己的剩余时间，绝不回落完整锁机时长。
                    snapshot = requireNotNull(persistedSnapshot),
                    nowElapsedMillis = nowElapsed,
                    bootCount = bootCount,
                    isInteractive = interactive
                )
                // 玩机阶段的运行时失败只重建单调锚点：监督继续运行，USAGE 到期后
                // LOCK 会按正常规则回来，不构成绕过。凭空重建满时长锁定才是
                // "暂停/跳过后 1 分钟变 10 分钟"的来源。
                else -> cycle.reanchor(snapshot, nowElapsed, bootCount, interactive)
            }
            val remainingSeconds = cycle.remainingSeconds(snapshot)
            syncLockUi(snapshot.phase, remainingSeconds)
            retainCurrentLockMediaWithoutReplacingExisting()
            startTimer()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to restore primary lock after startup failure")
            terminateUnserviceableMonitorRuntime(message)
        }
    }

    private fun failMonitorStartup(
        message: String,
        clearNewMonitorState: Boolean,
        startAttemptId: Long,
        error: RuntimeException?
    ) {
        markPendingBootRecovery(SupervisionRecoveryStatus.FAILED)
        if (error != null) {
            Log.e(TAG, "Monitor startup failed")
        } else {
            Log.e(TAG, "Monitor startup failed")
        }
        isStoppingIntentionally = true
        invalidateScheduledStartAuthorization()
        monitorStartupGeneration = nextGeneration(monitorStartupGeneration)
        isInitialPersistencePending = false
        timerRunnable?.let(handler::removeCallbacks)
        resetAllowedAppSession("startup_failed")

        val rollbackSucceeded = if (clearNewMonitorState) {
            val primaryCleared = !::prefs.isInitialized || prefs.clearMonitorProgress()
            val guardCleared = primaryCleared &&
                (!::recoveryGuard.isInitialized || recoveryGuard.clear())
            primaryCleared && guardCleared
        } else {
            false
        }
        val activeStateRemains = hasPersistedMonitorState()
        preserveLockResourcesOnDestroy = activeStateRemains || !rollbackSucceeded
        if (preserveLockResourcesOnDestroy) {
            retainCurrentLockMediaWithoutReplacingExisting()
        } else {
            pendingHistoryEndReason = SupervisionSessionEndReason.CANCELLED
            closeGlobalHistory()
            releaseRetainedLockResources()
            if (::overlayController.isInitialized) overlayController.hide()
            cancelFallbackLockNotification()
            if (::mediaPlaybackController.isInitialized) {
                mediaPlaybackController.allowMediaPlayback()
            }
            sendUnlockBroadcastSafely()
            endRuntimeLockSession()
        }
        sendBroadcastSafely(Intent(ACTION_MONITOR_START_FAILED).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
            putExtra(EXTRA_START_ATTEMPT_ID, startAttemptId)
            setPackage(packageName)
        })
        if (preserveLockResourcesOnDestroy) {
            keepExistingMonitorLockedAfterStartupFailure(message)
            return
        }
        isRunning = false
        stopForegroundSafely()
        stopSelf()
        scheduleSupervisionReconciliation(immediate = false)
    }

    private fun safeStopMonitorAndUnlock(): Boolean = try {
        beginStopMonitorAndUnlock()
    } catch (error: RuntimeException) {
        Log.e(TAG, "Unable to stop monitor from callback")
        handler.removeCallbacks(stopClearWatchdog)
        isStopClearInFlight = false
        hasStopClearTimedOut = false
        preserveLockResourcesOnDestroy = false
        val coordinatedWorker = coordinatedStopGeneration
            .takeIf { generation -> generation != 0L }
            ?.let(processStopCoordinator::cancel)
        coordinatedStopGeneration = 0L
        if (coordinatedWorker != null) {
            coordinatedWorker.resumeSnapshotsAfterClearFailure()
        } else if (::snapshotPersistenceWorker.isInitialized) {
            snapshotPersistenceWorker.resumeSnapshotsAfterClearFailure()
        }
        if (!hasPersistedMonitorState()) {
            preserveLockResourcesOnDestroy = false
            releaseRetainedLockResources()
            if (::overlayController.isInitialized) overlayController.hide()
            cancelFallbackLockNotification()
            if (::mediaPlaybackController.isInitialized) {
                mediaPlaybackController.allowMediaPlayback()
            }
            sendUnlockBroadcastSafely()
            endRuntimeLockSession()
            closeGlobalHistory()
            isRunning = false
            stopForegroundSafely()
            stopSelf()
            true
        } else {
            isStoppingIntentionally = false
            publishMonitorErrorSafely("结束监督遇到系统异常，已保留监督状态，请稍后重试")
            if (::cycle.isInitialized && ::snapshot.isInitialized && !isDestroyed) startTimer()
            false
        }
    }

    private fun beginStopMonitorAndUnlock(): Boolean {
        if (isStopClearInFlight) return true
        if (isStoppingIntentionally) return false
        val coordination = processStopCoordinator.begin(
            owner = this,
            worker = snapshotPersistenceWorker,
            startedElapsedMillis = SystemClock.elapsedRealtime()
        ) ?: run {
            holdLockWhileProcessStopCompletes()
            return true
        }
        val stopGeneration = coordination.generation
        coordinatedStopGeneration = stopGeneration
        // 停止清理会串行丢弃或覆盖尚未提交的锁动作。先使旧回调失效；若清理失败，
        // 恢复后的计时器也不会被遗留的 transition 标记永久挡住。
        invalidateLockActionTransition()
        isStoppingIntentionally = true
        isStopClearInFlight = true
        hasStopClearTimedOut = false
        monitorStartupGeneration = nextGeneration(monitorStartupGeneration)
        isInitialPersistencePending = false
        preserveLockResourcesOnDestroy = true
        retainCurrentLockMediaWithoutReplacingExisting()
        timerRunnable?.let(handler::removeCallbacks)
        resetAllowedAppSession("monitor_stopped")
        val accepted = snapshotPersistenceWorker.submitClear(
            clearOperation = {
                val result = try {
                    val progressCleared = prefs.clearMonitorProgress()
                    val guardCleared = progressCleared && recoveryGuard.clear()
                    MonitorStateClearResult(progressCleared, guardCleared)
                } catch (_: RuntimeException) {
                    MonitorStateClearResult(progressCleared = false, guardCleared = false)
                }
                // 必须先登记进程级结果再投递主线程回调；即使 Handler 丢弃回调，
                // 当前或后继 Service 的看门狗仍能按 generation 精确收束事务。
                processStopCoordinator.recordResult(stopGeneration, result)
                result
            },
            onResult = { completeStopMonitorAndUnlock(stopGeneration) }
        )
        if (accepted) {
            scheduleStopClearWatchdog(coordination)
            return true
        }

        processStopCoordinator.cancel(stopGeneration)
        coordinatedStopGeneration = 0L
        isStopClearInFlight = false
        hasStopClearTimedOut = false
        isStoppingIntentionally = false
        preserveLockResourcesOnDestroy = false
        snapshotPersistenceWorker.resumeSnapshotsAfterClearFailure()
        resumeMonitorAfterFailedClear("无法提交结束监督操作，已保持监督运行，请稍后重试")
        return false
    }

    private val stopClearWatchdog = Runnable(::pollProcessStopCoordination)

    private fun scheduleStopClearWatchdog(
        coordination: ProcessStopAdoption<MonitorSnapshotPersistenceWorker>
    ) {
        handler.removeCallbacks(stopClearWatchdog)
        val delayMillis = if (coordination.resultReady) {
            0L
        } else {
            (coordination.startedElapsedMillis + STOP_CLEAR_WATCHDOG_MILLIS -
                SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
        handler.postDelayed(stopClearWatchdog, delayMillis)
    }

    private fun pollProcessStopCoordination() {
        if (!isStopClearInFlight || isDestroyed) return
        val generation = coordinatedStopGeneration
        processStopCoordinator.claimResult(generation)?.let { claim ->
            handleProcessStopClaim(claim)
            return
        }
        if (!processStopCoordinator.isOwnedBy(generation, this)) return
        if (!processStopCoordinator.isPending(generation)) {
            isStopClearInFlight = false
            coordinatedStopGeneration = 0L
            return
        }
        if (!hasStopClearTimedOut) {
            Log.e(TAG, "stop_clear_watchdog_fired reason=clear_io_pending")
            hasStopClearTimedOut = true
            isStoppingIntentionally = false
            preserveLockResourcesOnDestroy = false
            // 只恢复内存计时和界面；事务中的原 worker 仍拒绝任何快照，迟到 clear
            // 因而不可能越过恢复写入。结果到达后再统一停止或恢复持久化。
            resumeMonitorAfterFailedClear(
                message = "结束监督清理超时，已恢复监督计时并继续等待存储结果",
                persistCurrentState = false
            )
        }
        // I/O 尚未返回时绝不能恢复旧 worker 或创建新 worker；否则迟到的 clear
        // 可能删除恢复后的新快照。继续轮询已登记结果即可。
        handler.postDelayed(stopClearWatchdog, STOP_CLEAR_RESULT_POLL_MILLIS)
    }

    private fun completeStopMonitorAndUnlock(generation: Long) {
        val claim = processStopCoordinator.claimResult(generation) ?: return
        handler.removeCallbacks(stopClearWatchdog)
        handleProcessStopClaim(claim)
    }

    private fun handleProcessStopClaim(
        claim: ProcessStopClaim<MonitorSnapshotPersistenceWorker>
    ) {
        handler.removeCallbacks(stopClearWatchdog)
        isStopClearInFlight = false
        hasStopClearTimedOut = false
        coordinatedStopGeneration = 0L
        val activeInstance = currentProcessService()
        val liveTarget = activeInstance?.takeUnless(MonitorService::isDestroyed)
            ?: this.takeUnless(MonitorService::isDestroyed)
        if (!claim.result.isSuccess) {
            claim.worker.resumeSnapshotsAfterClearFailure()
            liveTarget?.resumeAfterCoordinatedStopFailure()
            return
        }

        releaseProcessStopWorker(claim.worker)
        val completionTarget = liveTarget ?: this
        completionTarget.finishLocalServiceAfterSuccessfulStop(sendStoppedBroadcast = true)
        if (completionTarget !== this && !isDestroyed) {
            finishLocalServiceAfterSuccessfulStop(sendStoppedBroadcast = false)
        }
    }

    private fun finishLocalServiceAfterSuccessfulStop(sendStoppedBroadcast: Boolean) {
        isStoppingIntentionally = true
        isStopClearInFlight = false
        hasStopClearTimedOut = false
        // 停止流程无论是否广播都必须交还共享 worker：submitClear() 已把
        // acceptsSnapshots 永久置 false，留着它会把这个状态泄漏给下一个 Service 实例。
        releaseSharedSnapshotPersistenceWorker()
        preserveLockResourcesOnDestroy = false
        val cancelledGrowthCycle = if (::growthRuntimeStore.isInitialized) {
            growthRuntimeStore.activeCycle()
        } else {
            null
        }
        cancelledGrowthCycle?.let { cycleState ->
            val finalOutcome = cycleState.pendingOutcome ?: GrowthCycleOutcome.CANCELLED
            if (cycleState.pendingOutcome == null) {
                growthRuntimeStore.markPendingOutcome(cycleState.cycleId, finalOutcome)
            }
            settleGrowthCycleAsync(
                cycleState,
                finalOutcome,
                (monitorPauseState?.accumulatedPauseMillis ?: 0L) / 1_000L
            )
        }
        if (::pauseAlarmScheduler.isInitialized) pauseAlarmScheduler.cancel()
        monitorPauseState = null
        releaseRetainedLockResources()
        if (::overlayController.isInitialized) overlayController.hide()
        cancelFallbackLockNotification()
        if (::mediaPlaybackController.isInitialized) mediaPlaybackController.allowMediaPlayback()
        sendUnlockBroadcastSafely()
        if (::knowledgePassRepository.isInitialized) revokeKnowledgePassesForSession(lockSessionId)
        endRuntimeLockSession()
        if (sendStoppedBroadcast) {
            recordDiagnostic(DiagnosticEventType.MONITOR_STOPPED)
            sendBroadcastSafely(Intent(ACTION_MONITOR_STOPPED).apply { setPackage(packageName) })
            scheduleSupervisionReconciliation()
        }
        closeGlobalHistory()
        scheduledMonitorOwner = null
        scheduledMonitorPlanName = null
        activeFocusTaskTitle = null
        activeFocusSourceTodoId = null
        isRunning = false
        stopForegroundSafely()
        if (!isDestroyed) stopSelf()
    }

    private fun resumeAfterCoordinatedStopFailure() {
        if (isDestroyed) return
        handler.removeCallbacks(stopClearWatchdog)
        coordinatedStopGeneration = 0L
        isStopClearInFlight = false
        hasStopClearTimedOut = false
        if (::snapshotPersistenceWorker.isInitialized) {
            snapshotPersistenceWorker.resumeSnapshotsAfterClearFailure()
        }
        isStoppingIntentionally = false
        preserveLockResourcesOnDestroy = false
        isRunning = true
        try {
            startupFailureMessage?.let { message ->
                keepExistingMonitorLockedAfterStartupFailure(message)
                return
            }
            startOrRestoreMonitor(intent = null, startAttemptId = NO_START_ATTEMPT)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to resume monitor after coordinated clear failure")
            keepExistingMonitorLockedAfterStartupFailure(
                "结束监督失败，已保持锁定并等待服务恢复"
            )
        }
    }

    private fun holdLockWhileProcessStopCompletes() {
        val coordination = processStopCoordinator.adopt(this) ?: return
        coordinatedStopGeneration = coordination.generation
        isStopClearInFlight = true
        hasStopClearTimedOut = false
        isStoppingIntentionally = true
        preserveLockResourcesOnDestroy = true
        isRunning = true
        invalidateLockActionTransition()
        timerRunnable?.let(handler::removeCallbacks)
        retainCurrentLockMediaWithoutReplacingExisting()
        showProcessStopHoldingState()
        scheduleStopClearWatchdog(coordination)
    }

    private fun showProcessStopHoldingState() {
        if (
            !::prefs.isInitialized ||
            !::powerManager.isInitialized ||
            !::overlayController.isInitialized ||
            !::mediaPlaybackController.isInitialized
        ) {
            return
        }
        try {
            ensureSafeSnapshotAfterClearFailure()
            val remainingSeconds = cycle.remainingSeconds(snapshot)
            if (snapshot.phase == MonitorPhase.LOCK) {
                ensureEmergencyRuntimeLockTruth(remainingSeconds)
                mediaPlaybackController.pauseMediaDuringLock()
            }
            startForeground(
                NOTIFICATION_ID,
                buildNotification(notificationText(snapshot.phase, remainingSeconds))
            )
            syncLockUi(snapshot.phase, remainingSeconds)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to present coordinated stop holding state")
        }
    }

    private fun currentProcessService(): MonitorService? = synchronized(processStopLock) {
        currentServiceInstance?.get()
    }

    private fun releaseProcessStopWorker(worker: MonitorSnapshotPersistenceWorker) {
        synchronized(processStopLock) {
            if (sharedSnapshotPersistenceWorker === worker) {
                sharedSnapshotPersistenceWorker = null
            }
        }
        worker.close()
    }

    private fun releaseSharedSnapshotPersistenceWorker() {
        val workerToClose = synchronized(processStopLock) {
            if (
                ::snapshotPersistenceWorker.isInitialized &&
                sharedSnapshotPersistenceWorker === snapshotPersistenceWorker
            ) {
                sharedSnapshotPersistenceWorker = null
                snapshotPersistenceWorker
            } else {
                null
            }
        }
        workerToClose?.close()
    }

    private fun resumeMonitorAfterFailedClear(
        message: String,
        persistCurrentState: Boolean = true
    ) {
        if (
            !::powerManager.isInitialized ||
            !::overlayController.isInitialized ||
            !::mediaPlaybackController.isInitialized
        ) {
            keepExistingMonitorLockedAfterStartupFailure(message)
            return
        }
        ensureSafeSnapshotAfterClearFailure()
        if (persistCurrentState) persistSnapshot()
        publishMonitorError(message)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(notificationText(snapshot.phase, cycle.remainingSeconds(snapshot)))
        )
        releaseRetainedLockResources()
        startTimer()
        publishCurrentState(snapshot.phase)
    }

    private fun ensureSafeSnapshotAfterClearFailure() {
        if (!::cycle.isInitialized) {
            activeSessionMode = prefs.getMonitorSessionMode()
            activeUsageMinutes = storedUsageMinutes(activeSessionMode)
            activeLockMinutes = storedLockMinutes(activeSessionMode)
            cycle = MonitorCycle(
                usageDurationMillis = activeUsageMinutes * 60_000L,
                lockDurationMillis = activeLockMinutes * 60_000L,
                initialPhase = activeSessionMode.initialPhase,
                lockCountsDownWhileInteractive = activeSessionMode == MonitorSessionMode.FOCUS
            )
        }
        if (!::snapshot.isInitialized) {
            val nowElapsed = SystemClock.elapsedRealtime()
            snapshot = prefs.loadMonitorProgress()?.let { saved ->
                MonitorProgressRestorer(cycle).restore(
                    saved = saved,
                    nowElapsedMillis = nowElapsed,
                    currentBootCount = readBootCount(),
                    currentInteractive = powerManager.isInteractive
                )
            } ?: fullPhaseSnapshot(
                phase = MonitorPhase.LOCK,
                nowElapsed = nowElapsed,
                bootCount = readBootCount(),
                isInteractive = powerManager.isInteractive
            )
        }
    }

    private fun publishMonitorError(message: String) {
        sendBroadcastSafely(Intent(ACTION_MONITOR_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
    }

    private fun publishMonitorErrorSafely(message: String) {
        publishMonitorError(message)
    }

    private fun releaseRetainedLockResources() {
        retainedMediaPlaybackController?.allowMediaPlayback()
        retainedMediaPlaybackController = null
    }

    private fun retainCurrentLockMediaWithoutReplacingExisting() {
        // Window 由各 Service 实例自行移除；这里只允许短暂交接无输入能力的媒体保护。
        if (!::mediaPlaybackController.isInitialized) return
        val existingMedia = retainedMediaPlaybackController
        if (existingMedia == null) {
            retainedMediaPlaybackController = mediaPlaybackController
            return
        }

        // 媒体重放保护不接收用户输入，可以在服务交接的短窗口内继续工作。
        if (existingMedia !== mediaPlaybackController) mediaPlaybackController.allowMediaPlayback()
    }

    private fun sendUnlockBroadcast() {
        sendUnlockBroadcastSafely()
    }

    private fun sendUnlockBroadcastSafely() {
        clearLockActivityLaunchPending()
        shouldUseOverlayAfterLockActivityTimeout = false
        isLockActivityVisible = false
        lockActivityInstanceToken = null
        sendBroadcastSafely(Intent(LockActivity.ACTION_UNLOCK).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOCK_SESSION_ID, lockSessionId)
        })
    }

    private fun dismissAllLockSurfaces() {
        clearLockActivityLaunchPending()
        shouldUseOverlayAfterLockActivityTimeout = false
        if (::overlayController.isInitialized) overlayController.hide()
        isLockActivityVisible = false
        lockActivityInstanceToken = null
        sendBroadcastSafely(Intent(LockActivity.ACTION_DISMISS_ALL_LOCK_SURFACES).apply {
            setPackage(packageName)
        })
    }

    private fun sendBroadcastSafely(intent: Intent) {
        try {
            sendBroadcast(intent)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to send monitor broadcast ${intent.action}")
        }
    }

    /**
     * 命令携带的会话已被进程内注册表认可，但本实例尚未绑定它（服务重启，或上一个
     * 实例的会话被保留）。此时安全接管：只改本实例的绑定，不新建会话、不触碰任何
     * 计时状态，因此不构成绕过。注册表是进程内私有单例、不落盘，且只有 Service
     * 能写入，所以会话号相等即可证明该命令来自服务自己授权过的锁定界面。
     *
     * 此前这里是裸的 lockSessionId != commandSessionId 比较，服务重启后必然不等，
     * 暂停与跳过就被静默丢弃了。
     */
    private fun adoptRegistryAcknowledgedSession(commandSessionId: Long): Boolean {
        if (commandSessionId == NO_LOCK_SESSION) return false
        if (!RuntimeLockTruthRegistry.isCurrentSession(commandSessionId)) return false
        if (lockSessionId == commandSessionId) return true
        Log.i(TAG, "lock_session_adopted reason=registry_acknowledged")
        lockSessionId = commandSessionId
        return true
    }

    private fun isCurrentLockCommand(intent: Intent): Boolean =
        adoptRegistryAcknowledgedSession(
            intent.getLongExtra(EXTRA_LOCK_SESSION_ID, NO_LOCK_SESSION)
        )

    /**
     * 静默丢弃会让用户以为按钮坏了。除了给出可见反馈，还要立刻把最新会话号推给
     * 前台锁层，用户重试一次即可命中。
     */
    private fun rejectStaleLockAction() {
        Log.w(TAG, "lock_action_rejected reason=stale_lock_session")
        try {
            Toast.makeText(this, "锁定会话已刷新，请再点一次", Toast.LENGTH_LONG).show()
        } catch (_: RuntimeException) {
            // 无法弹出提示时仍要继续同步真值。
        }
        if (
            ::snapshot.isInitialized &&
            ::cycle.isInitialized &&
            snapshot.phase == MonitorPhase.LOCK
        ) {
            try {
                publishCurrentState(snapshot.phase)
                syncLockUi(snapshot.phase, cycle.remainingSeconds(snapshot))
            } catch (_: RuntimeException) {
                // 下一次计时心跳会再次收敛。
            }
        }
    }

    private fun publishRuntimeLockTruth(
        shouldShowLockUi: Boolean,
        remainingSeconds: Int
    ) {
        if (!::snapshot.isInitialized) return
        val published = RuntimeLockTruthRegistry.publish(
            sessionId = lockSessionId,
            phase = snapshot.phase,
            shouldShowLockUi = shouldShowLockUi,
            remainingSeconds = remainingSeconds
        )
        if (!published && !isStoppingIntentionally && !isDestroyed) {
            lockSessionId = RuntimeLockTruthRegistry.beginSession(
                phase = snapshot.phase,
                shouldShowLockUi = shouldShowLockUi,
                remainingSeconds = remainingSeconds,
                lockCountsDownWhileInteractive =
                    activeSessionMode == MonitorSessionMode.FOCUS
            )
        }
    }

    private fun ensureEmergencyRuntimeLockTruth(remainingSeconds: Int) {
        if (lockSessionId == NO_LOCK_SESSION) {
            lockSessionId = RuntimeLockTruthRegistry.currentSessionId()
        }
        if (lockSessionId == NO_LOCK_SESSION) {
            lockSessionId = RuntimeLockTruthRegistry.beginSession(
                phase = MonitorPhase.LOCK,
                shouldShowLockUi = true,
                remainingSeconds = remainingSeconds,
                lockCountsDownWhileInteractive =
                    activeSessionMode == MonitorSessionMode.FOCUS
            )
            return
        }
        RuntimeLockTruthRegistry.publish(
            sessionId = lockSessionId,
            phase = MonitorPhase.LOCK,
            shouldShowLockUi = true,
            remainingSeconds = remainingSeconds
        )
    }

    /**
     * 标记运行时失联。必须带上衰减所需的时钟锚点、屏幕状态与锁定时长，
     * 否则保留下来的锁层会变成没有期限、谁也清不掉的僵尸真值。
     */
    private fun markRuntimeLockUnavailable() {
        RuntimeLockTruthRegistry.markRuntimeUnavailable(
            sessionId = lockSessionId,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            isInteractive =
                if (::powerManager.isInitialized) powerManager.isInteractive else true,
            lockDurationMillis = if (::cycle.isInitialized) {
                cycle.durationMillis(MonitorPhase.LOCK)
            } else {
                0L
            }
        )
    }

    private fun endRuntimeLockSession() {
        clearKnowledgeChallengeHold(clearVisibilityOrdering = true)
        RuntimeLockTruthRegistry.endSession(lockSessionId)
        lockSessionId = NO_LOCK_SESSION
    }

    private fun stopForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to remove foreground notification")
        }
    }

    private fun notificationText(phase: MonitorPhase, remainingSeconds: Int): String =
        (if (hasPersistenceFailure) "状态保存异常；" else "") +
            (if (scheduledMonitorOwner != null) "定时任务；" else "") +
            (if (activeSessionMode == MonitorSessionMode.FOCUS) "专注任务；" else "") + when {
            isMonitorPauseActive() ->
                "监督已暂停，${formatTime(pauseRemainingSeconds())} 后继续"
            phase == MonitorPhase.LOCK && activeSessionMode == MonitorSessionMode.FOCUS ->
                "专注锁屏中（持续计时）：${formatTime(remainingSeconds)}"
            phase == MonitorPhase.LOCK && snapshot.isInteractive ->
                "监督锁定倒计时已暂停（熄屏后继续）：${formatTime(remainingSeconds)}"
            phase == MonitorPhase.LOCK ->
                "监督锁定中（仅熄屏计时）：${formatTime(remainingSeconds)}"
            snapshot.isInteractive ->
                "还能玩手机：${formatTime(remainingSeconds)}"
            else ->
                "熄屏恢复玩机时间（每5分钟恢复1分钟）：${formatTime(remainingSeconds)}"
        }

    private fun formatTime(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", safeSeconds / 60, safeSeconds % 60)
    }

    private fun currentLockTaskTitle(): String = resolveRuntimeLockTaskTitle(
        taskTitle = scheduledMonitorPlanName ?: activeFocusTaskTitle,
        internalPlanId = scheduledMonitorOwner?.planId,
        sessionMode = activeSessionMode
    )

    /**
     * 进程恢复数据只保存定时任务所有权，不保存展示名称。名称从计划仓库异步恢复；
     * 在读取完成前锁页使用会话类型回退，绝不把内部 planId 当作用户标题。
     */
    private fun restoreScheduledMonitorPlanName(owner: ScheduledMonitorOwner) {
        serviceScope.launch {
            val planName = (
                SupervisionPlanRepository.getInstance(applicationContext).loadPlans()
                    as? PlanLoadResult.Success
                )
                ?.plans
                ?.firstOrNull { plan ->
                    scheduledPlanMatchesOwner(
                        planId = plan.id,
                        planUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
                        owner = owner
                    )
                }
                ?.name
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@launch
            handler.post {
                if (
                    !isDestroyed &&
                    !isStoppingIntentionally &&
                    scheduledMonitorOwner == owner
                ) {
                    scheduledMonitorPlanName = planName
                }
            }
        }
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun markPendingBootRecovery(status: SupervisionRecoveryStatus) {
        val bootCount = pendingBootRecoveryBootCount ?: return
        pendingBootRecoveryBootCount = null
        serviceScope.launch {
            repeat(5) { attempt ->
                val updated = try {
                    SupervisionHistoryRepository.getInstance(applicationContext)
                        .markDeviceBootRecoveryStatus(
                            bootCount = bootCount,
                            status = status,
                            nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
                        )
                } catch (_: RuntimeException) {
                    false
                }
                if (updated) return@launch
                if (attempt < 4) delay(200L)
            }
        }
    }

    private fun recordStartedHistory(startAction: String?) {
        if (!::historyRecorder.isInitialized) return
        val owner = scheduledMonitorOwner
        if (owner != null) {
            historyRecorder.startOrRestoreScheduled(
                planId = owner.planId,
                planUpdatedAtEpochMillis = owner.planUpdatedAtEpochMillis,
                activeUntilEpochMillis = owner.activeUntilEpochMillis,
                planName = scheduledMonitorPlanName ?: owner.planId,
                usageMinutes = activeUsageMinutes,
                lockMinutes = activeLockMinutes,
                sessionMode = activeSessionMode
            )
        } else if (startAction == ACTION_START_MONITOR || startAction == ACTION_START_FOCUS) {
            historyRecorder.startManual(
                usageMinutes = activeUsageMinutes,
                lockMinutes = activeLockMinutes,
                sessionMode = activeSessionMode,
                displayName = activeFocusTaskTitle
            )
        } else {
            historyRecorder.restoreManual(
                usageMinutes = activeUsageMinutes,
                lockMinutes = activeLockMinutes,
                sessionMode = activeSessionMode,
                displayName = activeFocusTaskTitle
            )
        }
    }

    private fun closeGlobalHistory() {
        if (::historyRecorder.isInitialized) {
            historyRecorder.closeGlobal(pendingHistoryEndReason)
        }
    }

    private fun requireScheduledStartRequest(intent: Intent): ScheduledMonitorStartRequest {
        val owner = ScheduledMonitorOwner(
            planId = intent.getStringExtra(EXTRA_SCHEDULED_PLAN_ID).orEmpty().trim(),
            planUpdatedAtEpochMillis = intent.getLongExtra(
                EXTRA_SCHEDULED_PLAN_UPDATED_AT,
                -1L
            ),
            activeUntilEpochMillis = intent.getLongExtra(
                EXTRA_SCHEDULED_ACTIVE_UNTIL,
                -1L
            )
        )
        val usageMinutes = intent.getIntExtra(EXTRA_SCHEDULED_USAGE_MINUTES, -1)
        val lockMinutes = intent.getIntExtra(EXTRA_SCHEDULED_LOCK_MINUTES, -1)
        val planName = intent.getStringExtra(EXTRA_SCHEDULED_PLAN_NAME)
            ?.trim()
            .orEmpty()
            .ifEmpty { owner.planId }
        val storedSessionMode = intent.getStringExtra(EXTRA_SESSION_MODE)
        val sessionMode = if (storedSessionMode == null) {
            MonitorSessionMode.SUPERVISION
        } else {
            requireNotNull(
                MonitorSessionMode.entries.firstOrNull { it.storedValue == storedSessionMode }
            ) { "定时任务模式无效" }
        }
        require(usageMinutes in 1..1_440) { "定时任务玩机时长无效" }
        require(lockMinutes in 1..1_440) { "定时任务锁定时长无效" }
        return ScheduledMonitorStartRequest(
            owner,
            planName,
            usageMinutes,
            lockMinutes,
            sessionMode
        )
    }

    private fun suppressedScheduledStartRequestOrNull(
        intent: Intent
    ): ScheduledMonitorStartRequest? {
        val nowEpochMillis = System.currentTimeMillis()
        return try {
            requireScheduledStartRequest(intent).takeIf { request ->
                isScheduledStartRequestSuppressed(
                    request = request,
                    nowEpochMillis = nowEpochMillis
                )
            }
        } catch (_: RuntimeException) {
            // 非法启动参数仍交给原有启动失败路径处理，不能在预检查阶段让 Service 崩溃。
            null
        }
    }

    private fun isScheduledStartRequestSuppressed(
        request: ScheduledMonitorStartRequest,
        nowEpochMillis: Long
    ): Boolean = isScheduledStartSuppressed(
        owner = request.owner,
        suppression = prefs.getScheduledOccurrenceSuppression(nowEpochMillis),
        nowEpochMillis = nowEpochMillis
    )

    private fun String?.isExplicitMonitorStart(): Boolean =
        this == ACTION_START_MONITOR ||
            this == ACTION_START_FOCUS ||
            this == ACTION_START_SCHEDULED_MONITOR

    private fun Intent.readFocusMetadata(key: String, maxLength: Int): String? =
        getStringExtra(key)
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() && value.length <= maxLength }

    private fun storedUsageMinutes(mode: MonitorSessionMode): Int = when (mode) {
        MonitorSessionMode.SUPERVISION -> prefs.getUsageTime()
        MonitorSessionMode.FOCUS -> prefs.getFocusPlayTime()
    }.coerceIn(1, 1_440)

    private fun storedLockMinutes(mode: MonitorSessionMode): Int = when (mode) {
        MonitorSessionMode.SUPERVISION -> prefs.getLockTime()
        MonitorSessionMode.FOCUS -> prefs.getFocusLockTime()
    }.coerceIn(1, 1_440)

    private fun scheduleSupervisionReconciliation(immediate: Boolean = true) {
        try {
            if (immediate) {
                SupervisionScheduleReceiver.requestReconciliation(
                    applicationContext,
                    reason = "monitor_stopped"
                )
                SupervisionAlarmScheduler(applicationContext).scheduleRetry(2_000L)
            } else {
                SupervisionAlarmScheduler(applicationContext).scheduleRetry()
            }
        } catch (_: RuntimeException) {
            // 已保存的下一个计划边界、打开 App 及系统时间广播仍会再次校准。
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        recordDiagnostic(DiagnosticEventType.SERVICE_DESTROYED)
        MonitorRuntimeHealthRegistry.markServiceDestroyed(SystemClock.elapsedRealtime())
        serviceScope.cancel()
        timerRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacks(stopClearWatchdog)
        clearLockActivityLaunchPending()
        stopMediaReplayGuard()
        cancelForegroundWatch(resetPolicy = true)
        foregroundWindowGeneration = 0L
        if (::foregroundObservationWorker.isInitialized) foregroundObservationWorker.close()
        allowlistSubscription?.close()
        allowlistSubscription = null
        if (::allowedAppLaunchExecutor.isInitialized) allowedAppLaunchExecutor.shutdownNow()
        resetAllowedAppSession("service_destroyed")
        // Service 消失后已无法继续观察拨号器前台状态，必须先撤销通话界面豁免。
        invalidateCallUiSession("service_destroyed")
        val hasActiveMonitor =
            !isStoppingIntentionally &&
                ::snapshot.isInitialized &&
                ::cycle.isInitialized &&
                ::powerManager.isInitialized
        if (hasActiveMonitor) {
            try {
                snapshot = if (
                    isMonitorPauseActive() || isKnowledgeChallengeCountdownHeld()
                ) {
                    cycle.reanchor(
                        snapshot = snapshot,
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        bootCount = readBootCount(),
                        isInteractive = powerManager.isInteractive
                    )
                } else {
                    cycle.updateInteractiveState(
                        snapshot = snapshot,
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        isInteractive = powerManager.isInteractive
                    )
                }
                persistSnapshot()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to persist final monitor state during destroy")
            }
        }
        val persistedStateRemains = hasPersistedMonitorState()
        val shouldRecoverMonitor =
            !suppressLockHandoffOnDestroy &&
                (hasActiveMonitor || preserveLockResourcesOnDestroy || persistedStateRemains)
        val persistedSnapshotForRecovery = if (::snapshot.isInitialized) {
            snapshot
        } else {
            try {
                val preferenceManager = if (::prefs.isInitialized) prefs else PreferenceManager(this)
                preferenceManager.loadMonitorProgress()
            } catch (_: RuntimeException) {
                null
            }
        }
        val recoveryGuardRequiresLock = try {
            val guard = if (::recoveryGuard.isInitialized) {
                recoveryGuard
            } else {
                MonitorRecoveryGuard(this)
            }
            guard.requiresLock()
        } catch (_: RuntimeException) {
            // 无法确认恢复保护状态时按锁定处理，避免初始化失败路径误解锁。
            true
        }
        val pauseForRecovery = if (::prefs.isInitialized) {
            val persistedPause = try {
                prefs.inspectMonitorProgress().pauseState
            } catch (_: RuntimeException) {
                null
            }
            monitorPauseState?.takeIf { isMonitorPauseActive() }
                ?: persistedPause?.takeIf { pause ->
                    pause.isActiveAt(
                        nowEpochMillis = System.currentTimeMillis(),
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        currentBootCount = readBootCount()
                    )
                }
        } else {
            null
        }
        val pauseActiveForRecovery = pauseForRecovery?.let { pause ->
            ::pauseAlarmScheduler.isInitialized &&
                pauseAlarmScheduler.schedule(pause.untilEpochMillis)
        } == true
        if (pauseForRecovery != null && !pauseActiveForRecovery) {
            try {
                recoveryGuard.markLockRequired()
            } catch (_: RuntimeException) {
                // 后续锁层交接仍按暂停不可靠处理。
            }
        }
        val shouldHandOffLockSurface =
            shouldHandOffLockSurfaceOnDestroy(
                shouldRecoverMonitor = shouldRecoverMonitor,
                recoveryGuardRequiresLock = recoveryGuardRequiresLock,
                recoveryPhase = persistedSnapshotForRecovery?.phase,
                pauseActive = pauseActiveForRecovery,
                hasTimedLockState =
                    (::snapshot.isInitialized && ::cycle.isInitialized) ||
                        persistedSnapshotForRecovery != null
            )

        if (shouldHandOffLockSurface) {
            val observedRemainingSeconds = when {
                ::snapshot.isInitialized && ::cycle.isInitialized ->
                    cycle.remainingSeconds(snapshot)
                persistedSnapshotForRecovery != null -> {
                    val remainingMillis = persistedSnapshotForRecovery.remainingMillis
                        .coerceAtLeast(0L)
                    (remainingMillis / 1_000L +
                        if (remainingMillis % 1_000L == 0L) 0L else 1L)
                        .coerceIn(1L, Int.MAX_VALUE.toLong())
                        .toInt()
                }
                else -> error("timed recovery state missing")
            }
            val configuredLockSeconds = (activeLockMinutes.coerceAtLeast(1).toLong() * 60L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val remainingSeconds = retainedHandoffRemainingSeconds(
                recoveryGuardRequiresLock = recoveryGuardRequiresLock,
                recoveryPhase = persistedSnapshotForRecovery?.phase,
                observedRemainingSeconds = observedRemainingSeconds,
                configuredLockSeconds = configuredLockSeconds
            )
            if (::mediaPlaybackController.isInitialized) {
                mediaPlaybackController.pauseMediaDuringLock()
                if (::mediaPlaybackProbe.isInitialized) {
                    mediaPlaybackController.startRetainedReplayProtection(mediaPlaybackProbe)
                }
                retainCurrentLockMediaWithoutReplacingExisting()
            }
            val showEmergencyHandoff = {
                val replacementServiceIsAlive = synchronized(processStopLock) {
                    currentServiceInstance?.get()?.let { instance ->
                        instance !== this && !instance.isDestroyed
                    } == true
                }
                if (!replacementServiceIsAlive) {
                    showEmergencyFallbackLockNotificationAndActivity(
                        remainingSeconds = remainingSeconds,
                        message = "监督服务正在恢复，锁定界面已安全接管"
                    )
                }
            }
            if (::overlayController.isInitialized && overlayController.isAttached()) {
                // 必须等悬浮 Window 真正脱离后再启动 Activity；hide() 只是发起移除，
                // 立即启动会把两套全屏界面叠在一起并让旧层拦截全部触摸。
                overlayController.hide(onRemoved = showEmergencyHandoff)
            } else {
                showEmergencyHandoff()
            }
        } else {
            releaseRetainedLockResources()
            cancelFallbackLockNotification()
            if (::mediaPlaybackController.isInitialized) {
                mediaPlaybackController.allowMediaPlayback()
            }
            sendUnlockBroadcast()
        }
        if (::callStateMonitor.isInitialized) callStateMonitor.stop()

        // 不论销毁原因如何，都必须持续移除 Service 创建的 Window，避免僵尸层拦截输入。
        if (::overlayController.isInitialized) overlayController.release()
        isOverlayLockVisible = false
        if (internalReceiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // Receiver 可能已被系统回收。
            }
        }
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: RuntimeException) {
                // Receiver 可能尚未注册或已被系统回收。
            }
        }
        if (systemDialogReceiverRegistered) {
            try {
                unregisterReceiver(systemDialogReceiver)
            } catch (_: RuntimeException) {
                // Receiver 可能尚未注册或已被系统回收。
            }
        }
        when (runtimeSessionDisposalOnDestroy(shouldHandOffLockSurface)) {
            RuntimeSessionDisposal.RETAIN -> markRuntimeLockUnavailable()
            // 不需要交接锁层却仍标记"运行时不可用"，会留下 isMonitorActive=true 且
            // shouldShowLockUi=true 的僵尸真值。它不落盘、是进程内单例，而锁定 Activity
            // 又是 singleInstance + excludeFromRecents，用户只能重启手机才能清除。
            RuntimeSessionDisposal.END -> {
                RuntimeLockTruthRegistry.endSession(lockSessionId)
                lockSessionId = NO_LOCK_SESSION
            }
        }
        val wasCurrentInstance = synchronized(processStopLock) {
            if (currentServiceInstance?.get() === this) {
                currentServiceInstance = null
                true
            } else {
                false
            }
        }
        if (wasCurrentInstance) isRunning = false
        if (shouldRecoverMonitor) scheduleSupervisionReconciliation()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class LockActionPreparationStatus {
        READY,
        INSUFFICIENT_BALANCE,
        INVALID_CHALLENGE,
        STORAGE_UNAVAILABLE
    }

    private data class LockActionPreparation(
        val status: LockActionPreparationStatus,
        val orderId: String? = null,
        val challengeTokenId: String? = null,
        val challengeBinding: KnowledgeChallengeBinding? = null
    )

    private data class KnowledgeReservation(
        val tokenId: String,
        val binding: KnowledgeChallengeBinding
    )

    private data class ScheduledMonitorStartRequest(
        val owner: ScheduledMonitorOwner,
        val planName: String,
        val usageMinutes: Int,
        val lockMinutes: Int,
        val sessionMode: MonitorSessionMode
    ) {
        fun toAuthorizationRequest() = ScheduledStartAuthorizationRequest(
            owner = owner,
            usageMinutes = usageMinutes,
            lockMinutes = lockMinutes,
            sessionMode = sessionMode
        )
    }

    private enum class ScheduledStartAuthorization {
        AUTHORIZED,
        REJECTED,
        STORAGE_UNAVAILABLE
    }
}

internal data class ScheduledStartAuthorizationRequest(
    val owner: ScheduledMonitorOwner,
    val usageMinutes: Int,
    val lockMinutes: Int,
    val sessionMode: MonitorSessionMode
)

internal fun scheduledPlanMatchesOwner(
    planId: String,
    planUpdatedAtEpochMillis: Long,
    owner: ScheduledMonitorOwner
): Boolean = planId == owner.planId &&
    planUpdatedAtEpochMillis == owner.planUpdatedAtEpochMillis

internal fun scheduledStartMatchesActive(
    request: ScheduledStartAuthorizationRequest,
    active: ActiveDeviceSupervision?
): Boolean {
    active ?: return false
    val configuration = active.plan.toScheduledCycleConfiguration()
    return active.plan.enabled &&
        request.owner.planId == active.plan.id &&
        request.owner.planUpdatedAtEpochMillis == active.plan.updatedAtEpochMillis &&
        request.owner.activeUntilEpochMillis == active.activeUntil.toEpochMilli() &&
        request.sessionMode == configuration.sessionMode &&
        request.usageMinutes == configuration.usageMinutes &&
        request.lockMinutes == configuration.lockMinutes
}

internal fun shouldSuppressScheduledOccurrence(
    stopAction: String?,
    sessionMode: MonitorSessionMode
): Boolean = when (stopAction) {
    MonitorService.ACTION_STOP_MONITOR -> true
    MonitorService.ACTION_FORCE_UNLOCK -> sessionMode == MonitorSessionMode.FOCUS
    else -> false
}

internal fun shouldAttemptPrimaryLockActivity(
    isInteractive: Boolean,
    isActivityVisible: Boolean,
    isOverlayVisible: Boolean,
    isLaunchPending: Boolean,
    mustUseOverlayFallback: Boolean
): Boolean =
    isInteractive &&
        !isActivityVisible &&
        !isOverlayVisible &&
        !isLaunchPending &&
        !mustUseOverlayFallback

internal fun shouldPersistBeforePublishingPhaseTransition(
    current: MonitorCycleSnapshot,
    candidate: MonitorCycleSnapshot
): Boolean = current.phase == MonitorPhase.LOCK && candidate.phase == MonitorPhase.USAGE

internal fun shouldDeferSnapshotPersistence(
    isLockActionTransitionInFlight: Boolean
): Boolean = isLockActionTransitionInFlight

internal fun isSnapshotPersistResultForRequest(
    result: MonitorSnapshotPersistResult,
    requestedSnapshot: MonitorCycleSnapshot,
    requestedPauseState: MonitorPauseState?
): Boolean =
    result.isSuccess &&
        result.snapshot == requestedSnapshot &&
        result.pauseState == requestedPauseState

internal const val MAX_LOCK_COMPLETION_PERSIST_FAILURES = 5
internal const val MAX_LOCK_COMPLETION_PERSIST_WAIT_MILLIS = 30_000L

/**
 * 锁定结束前的落盘保护必须有时限。磁盘持续不可写时继续把用户关在锁屏里不会让
 * 状态更安全，只会让阶段推进永远无法发布：每秒都算出"该进入玩机阶段"又被丢弃，
 * 表现为倒计时冻结、熄屏也不动、只能重启手机。
 *
 * 到达上限后按内存真值推进阶段并不构成绕过：磁盘上残留的旧 LOCK 快照
 * remainingMillis 已归零，下次 restore/reanchor 都会自然 phase.next()。
 */
internal fun shouldForceLockCompletionWithoutPersistence(
    failureCount: Int,
    firstFailureElapsedMillis: Long,
    nowElapsedMillis: Long,
    maxFailureCount: Int = MAX_LOCK_COMPLETION_PERSIST_FAILURES,
    maxWaitMillis: Long = MAX_LOCK_COMPLETION_PERSIST_WAIT_MILLIS
): Boolean =
    firstFailureElapsedMillis > 0L &&
        (failureCount >= maxFailureCount ||
            nowElapsedMillis - firstFailureElapsedMillis >= maxWaitMillis)

internal fun retainedLockSnapshotForRecovery(
    persistedSnapshot: MonitorCycleSnapshot?
): MonitorCycleSnapshot? = persistedSnapshot
    ?.takeIf { it.phase == MonitorPhase.LOCK }
    ?.let { saved -> saved.copy(remainingMillis = maxOf(1L, saved.remainingMillis)) }

/**
 * 启动或锁定操作失败不是"用户欠一段锁定"的证据。此前已合法进入玩机阶段时凭空
 * 重建满时长锁定，正是"暂停/跳过后 1 分钟变 10 分钟"的直接来源——该兜底恰好挂在
 * 暂停/跳过命令的 catch 分支上。只有持久化真值本身仍处于 LOCK 才允许重建锁定阶段。
 */
internal fun shouldRebuildLockAfterRuntimeFailure(
    currentPhase: MonitorPhase,
    persistedPhase: MonitorPhase?,
    recoveryGuardRequiresLock: Boolean
): Boolean =
    currentPhase != MonitorPhase.LOCK &&
        recoveryGuardRequiresLock &&
        persistedPhase == MonitorPhase.LOCK

internal enum class RuntimeSessionDisposal {
    /** 锁层要交给下一个承接者，运行时真值必须保留。 */
    RETAIN,

    /** 没有任何锁层需要交接，必须彻底结束会话，避免留下无人能清的僵尸真值。 */
    END
}

internal fun runtimeSessionDisposalOnDestroy(
    shouldHandOffLockSurface: Boolean
): RuntimeSessionDisposal =
    if (shouldHandOffLockSurface) RuntimeSessionDisposal.RETAIN else RuntimeSessionDisposal.END

internal fun shouldHoldKnowledgeChallengeCountdown(
    requestedSessionId: Long,
    currentSessionId: Long,
    phase: MonitorPhase,
    isLockActivityVisible: Boolean,
    requestedVisible: Boolean
): Boolean =
    requestedVisible &&
        isLockActivityVisible &&
        phase == MonitorPhase.LOCK &&
        requestedSessionId != NO_LOCK_SESSION &&
        requestedSessionId == currentSessionId

internal fun isKnowledgeChallengeHeartbeatFresh(
    lastHeartbeatElapsedMillis: Long,
    nowElapsedMillis: Long,
    timeoutMillis: Long = 6_000L
): Boolean =
    lastHeartbeatElapsedMillis > 0L &&
        nowElapsedMillis >= lastHeartbeatElapsedMillis &&
        nowElapsedMillis - lastHeartbeatElapsedMillis <= timeoutMillis.coerceAtLeast(1L)

internal fun shouldAttachFallbackFullScreenIntent(
    sessionId: Long,
    dispatchedSessionId: Long,
    canUseFullScreenIntent: Boolean
): Boolean =
    canUseFullScreenIntent &&
        sessionId != NO_LOCK_SESSION &&
        sessionId != dispatchedSessionId

internal fun shouldAttemptFallbackActivity(
    sessionId: Long,
    attemptSessionId: Long,
    attemptCount: Int,
    lastAttemptElapsedMillis: Long,
    nowElapsedMillis: Long,
    maximumAttempts: Int = 2,
    retryIntervalMillis: Long = 2_000L
): Boolean {
    if (sessionId == NO_LOCK_SESSION || maximumAttempts <= 0 || retryIntervalMillis < 0L) {
        return false
    }
    if (sessionId != attemptSessionId) return true
    if (attemptCount < 0 || attemptCount >= maximumAttempts) return false
    if (attemptCount == 0) return true
    if (nowElapsedMillis < lastAttemptElapsedMillis) return false
    return nowElapsedMillis - lastAttemptElapsedMillis >= retryIntervalMillis
}

internal fun shouldHandOffLockSurfaceOnDestroy(
    shouldRecoverMonitor: Boolean,
    recoveryGuardRequiresLock: Boolean,
    recoveryPhase: MonitorPhase?,
    pauseActive: Boolean = false,
    hasTimedLockState: Boolean = true
): Boolean =
    shouldRecoverMonitor &&
        hasTimedLockState &&
        (recoveryGuardRequiresLock || (!pauseActive && recoveryPhase != MonitorPhase.USAGE))

/** Guard 覆盖 USAGE 时使用独立锁定上限，绝不能把完整玩机额度伪装成锁定剩余。 */
internal fun retainedHandoffRemainingSeconds(
    recoveryGuardRequiresLock: Boolean,
    recoveryPhase: MonitorPhase?,
    observedRemainingSeconds: Int,
    configuredLockSeconds: Int
): Int = if (recoveryGuardRequiresLock && recoveryPhase != MonitorPhase.LOCK) {
    configuredLockSeconds.coerceAtLeast(1)
} else {
    observedRemainingSeconds.coerceAtLeast(1)
}

/**
 * 锁定 Activity 的可见性广播接受策略：屏幕上真实存在的锁定界面（visible=true）
 * 即使携带轮换前的旧会话号也必须承认，否则服务会在其上再叠加悬浮层；
 * 隐藏事件（visible=false）仍要求会话匹配，防止已过期界面清掉新会话的可见状态。
 */
internal fun shouldAcceptLockActivityVisibilityCommand(
    isCurrentSessionCommand: Boolean,
    isVisible: Boolean
): Boolean = isCurrentSessionCommand || isVisible

internal fun shouldDisableScheduledFocusPlan(
    stopAction: String?,
    sessionMode: MonitorSessionMode,
    owner: ScheduledMonitorOwner?
): Boolean =
    (
        stopAction == MonitorService.ACTION_STOP_MONITOR ||
            stopAction == MonitorService.ACTION_FORCE_UNLOCK
        ) &&
        sessionMode == MonitorSessionMode.FOCUS &&
        owner != null

internal fun isFreshLockActionRequest(
    requestedAtElapsedMillis: Long,
    nowElapsedMillis: Long,
    maximumAgeMillis: Long = 30_000L
): Boolean =
    requestedAtElapsedMillis >= 0L &&
        maximumAgeMillis >= 0L &&
        nowElapsedMillis >= requestedAtElapsedMillis &&
        nowElapsedMillis - requestedAtElapsedMillis <= maximumAgeMillis

internal fun isScheduledStartSuppressed(
    owner: ScheduledMonitorOwner,
    suppression: ScheduledOccurrenceSuppression?,
    nowEpochMillis: Long
): Boolean = suppression != null &&
    owner.planId == suppression.planId &&
    owner.planUpdatedAtEpochMillis == suppression.planUpdatedAtEpochMillis &&
    nowEpochMillis < suppression.suppressUntilEpochMillis

internal fun resolveStopSessionMode(
    hasActiveRuntimeMonitor: Boolean,
    runtimeSessionMode: MonitorSessionMode,
    persistedSessionMode: MonitorSessionMode
): MonitorSessionMode = if (hasActiveRuntimeMonitor) {
    runtimeSessionMode
} else {
    persistedSessionMode
}

internal data class ProcessStopAdoption<W : Any>(
    val generation: Long,
    val startedElapsedMillis: Long,
    val worker: W,
    val resultReady: Boolean
)

internal data class ProcessStopClaim<W : Any>(
    val generation: Long,
    val worker: W,
    val result: MonitorStateClearResult
)

/**
 * 保存跨 Service 实例的停止清理真值。清理结果只允许按 generation 消费一次；owner
 * 仅决定哪个实例负责轮询，不参与结果有效性判断，因此旧回调和新看门狗可以安全竞速。
 */
internal class ProcessStopCoordinator<W : Any> {
    private val stateLock = Any()
    private var generationCounter = 0L
    private var activeStop: ActiveStop<W>? = null

    fun begin(
        owner: Any,
        worker: W,
        startedElapsedMillis: Long
    ): ProcessStopAdoption<W>? = synchronized(stateLock) {
        val existing = activeStop
        if (existing != null) {
            return@synchronized if (
                existing.owner.get() === owner && existing.worker === worker
            ) {
                existing.toAdoption()
            } else {
                null
            }
        }
        generationCounter = if (generationCounter == Long.MAX_VALUE) {
            1L
        } else {
            generationCounter + 1L
        }
        ActiveStop(
            generation = generationCounter,
            startedElapsedMillis = startedElapsedMillis,
            worker = worker,
            owner = WeakReference(owner)
        ).also { active -> activeStop = active }
            .toAdoption()
    }

    fun adopt(owner: Any): ProcessStopAdoption<W>? = synchronized(stateLock) {
        activeStop?.let { active ->
            active.owner = WeakReference(owner)
            active.toAdoption()
        }
    }

    fun recordResult(generation: Long, result: MonitorStateClearResult): Boolean =
        synchronized(stateLock) {
            val active = activeStop
            if (active == null || active.generation != generation) return@synchronized false
            val recorded = active.result
            if (recorded != null) return@synchronized recorded == result
            active.result = result
            true
        }

    fun claimResult(generation: Long): ProcessStopClaim<W>? = synchronized(stateLock) {
        val active = activeStop
        if (active == null || active.generation != generation) return@synchronized null
        val result = active.result ?: return@synchronized null
        activeStop = null
        ProcessStopClaim(
            generation = active.generation,
            worker = active.worker,
            result = result
        )
    }

    fun cancel(generation: Long): W? = synchronized(stateLock) {
        val active = activeStop
        if (active == null || active.generation != generation) return@synchronized null
        activeStop = null
        active.worker
    }

    fun hasPending(): Boolean = synchronized(stateLock) { activeStop != null }

    fun isPending(generation: Long): Boolean = synchronized(stateLock) {
        activeStop?.generation == generation
    }

    fun isOwnedBy(generation: Long, owner: Any): Boolean = synchronized(stateLock) {
        activeStop?.let { active ->
            active.generation == generation && active.owner.get() === owner
        } == true
    }

    fun pendingWorker(): W? = synchronized(stateLock) { activeStop?.worker }

    private fun ActiveStop<W>.toAdoption(): ProcessStopAdoption<W> = ProcessStopAdoption(
        generation = generation,
        startedElapsedMillis = startedElapsedMillis,
        worker = worker,
        resultReady = result != null
    )

    private data class ActiveStop<W : Any>(
        val generation: Long,
        val startedElapsedMillis: Long,
        val worker: W,
        var owner: WeakReference<Any>,
        var result: MonitorStateClearResult? = null
    )
}
