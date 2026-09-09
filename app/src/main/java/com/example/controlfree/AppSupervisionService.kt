package com.example.controlfree

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.data.AppAllowlistManager
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationStatus
import com.example.controlfree.data.ForegroundObservationWorker
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.supervision.app.AppSupervisionBinaryCodec
import com.example.controlfree.supervision.app.AppSupervisionEngine
import com.example.controlfree.supervision.app.AppSupervisionDailyUsageReadResult
import com.example.controlfree.supervision.app.AppSupervisionDailyUsageState
import com.example.controlfree.supervision.app.AppSupervisionObservation
import com.example.controlfree.supervision.app.AppSupervisionObservationStatus
import com.example.controlfree.supervision.app.AppSupervisionPhase
import com.example.controlfree.supervision.app.AppSupervisionBlockReason
import com.example.controlfree.supervision.app.AppSupervisionOverlayDismissal
import com.example.controlfree.supervision.app.AppSupervisionOverlayDismissalPolicy
import com.example.controlfree.supervision.app.AppSupervisionRule
import com.example.controlfree.supervision.app.AppSupervisionRuntimeSnapshot
import com.example.controlfree.supervision.app.AppSupervisionRuntimeState
import com.example.controlfree.supervision.app.AppSupervisionRuntimeStore
import com.example.controlfree.supervision.app.AppSupervisionServiceRecoveryPolicy
import com.example.controlfree.supervision.app.AppSupervisionTransientSessionTracker
import com.example.controlfree.supervision.app.AppSupervisionSnapshotReadResult
import com.example.controlfree.supervision.app.AppTriggerEdgePolicy
import com.example.controlfree.supervision.app.AppTriggerEdgeState
import com.example.controlfree.supervision.app.AppTriggerRule
import com.example.controlfree.supervision.persistence.AppTriggerActivationResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.app.BlockedAppSupervision
import com.example.controlfree.supervision.app.UNKNOWN_APP_SUPERVISION_BOOT_COUNT
import com.example.controlfree.supervision.app.AppSupervisionEnforcementDecision
import com.example.controlfree.supervision.app.AppSupervisionEnforcementPolicy
import com.example.controlfree.supervision.app.AppSupervisionEnforcementState
import com.example.controlfree.supervision.app.LatestOnlySnapshotWriter
import com.example.controlfree.supervision.app.resetAppSupervisionObservationContinuity
import com.example.controlfree.supervision.history.SupervisionHistoryRecorder
import com.example.controlfree.supervision.commitment.CommitmentAlarmScheduler
import com.example.controlfree.supervision.commitment.CommitmentRuntimePolicy
import com.example.controlfree.supervision.commitment.CommitmentScheduleReceiver
import com.example.controlfree.supervision.runtime.SupervisionAlarmScheduler
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import com.example.controlfree.todo.ActiveCommitmentBlock
import com.example.controlfree.todo.CommitmentRuntimeSnapshot
import com.example.controlfree.todo.TodoRepository
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 一个前台观察循环同时维护所有 App 规则，避免每条计划各起一个服务或 UsageStats 查询线程。
 */
class AppSupervisionService : Service() {
    companion object {
        const val ACTION_APPLY_ACTIVE_RULES =
            "com.example.controlfree.ACTION_APPLY_ACTIVE_APP_SUPERVISION_RULES"
        const val ACTION_STOP_APP_SUPERVISION =
            "com.example.controlfree.ACTION_STOP_APP_SUPERVISION"
        const val ACTION_RECONCILE_COMMITMENTS =
            "com.example.controlfree.ACTION_RECONCILE_COMMITMENTS_RUNTIME"
        const val EXTRA_ACTIVE_RULES = "active_app_supervision_rules"
        const val EXTRA_TRIGGER_RULES = "app_trigger_rules"
        internal const val NOTIFICATION_ID = 1101
        private const val OBSERVATION_LOOKBACK_MILLIS = 5L * 60L * 1_000L
        private const val SNAPSHOT_HEARTBEAT_MILLIS = 10_000L
        private const val PROTECTED_PACKAGE_CACHE_MILLIS = 30_000L
        private const val RETRY_REQUEST_DELAY_MILLIS = 2_000L
        private const val MAX_COMMITMENT_LOCK_MILLIS = 24L * 60L * 60L * 1_000L
        private val ALWAYS_PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager
    private lateinit var runtimeStore: AppSupervisionRuntimeStore
    private lateinit var observationWorker: ForegroundObservationWorker
    private lateinit var overlayController: AppRestOverlayController
    private lateinit var mediaPlaybackController: MediaPlaybackController
    private lateinit var mediaPlaybackProbe: MediaPlaybackProbe
    private lateinit var callStateMonitor: CallStateMonitor
    private lateinit var persistenceExecutor: ExecutorService
    private lateinit var protectedPackageExecutor: ExecutorService
    private lateinit var snapshotWriter: LatestOnlySnapshotWriter<AppSupervisionRuntimeSnapshot>
    private lateinit var appAllowlistManager: AppAllowlistManager
    private lateinit var historyRecorder: SupervisionHistoryRecorder
    private lateinit var todoRepository: TodoRepository
    private lateinit var supervisionPlanRepository: SupervisionPlanRepository
    private val cadencePolicy = ForegroundObservationCadencePolicy()
    private val transientSessionTracker = AppSupervisionTransientSessionTracker()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var commitmentSnapshot = CommitmentRuntimeSnapshot(
        policies = emptyList(),
        openOccurrences = emptyList()
    )
    private var currentCommitmentBlock: ActiveCommitmentBlock? = null
    private val activationWritesInFlight = mutableSetOf<String>()

    private var rules: List<AppSupervisionRule> = emptyList()
    private var triggerRules: List<AppTriggerRule> = emptyList()
    private var triggerEdgeState = AppTriggerEdgeState()
    private val triggerActivationsInFlight = mutableSetOf<String>()
    private var states: List<AppSupervisionRuntimeState> = emptyList()
    private var dailyUsageStates: List<AppSupervisionDailyUsageState> = emptyList()
    private var observationWindowGeneration = 0L
    private var runtimeGeneration = 0L
    private var lastObservation = ForegroundObservation.unavailable(
        ForegroundObservationStatus.QUERY_FAILED
    )
    private var enforcementState = AppSupervisionEnforcementState()
    private var lastPersistElapsedMillis = 0L
    private var blockedPlanId: String? = null
    private var visibleBlockedOverlay: BlockedAppSupervision? = null
    private var visibleBlockedOverlayShownAtEpochMillis = 0L
    private var overlayDismissal: AppSupervisionOverlayDismissal? = null
    private var mediaGuardActive = false
    private var isCallActive = false
    private var isStoppingIntentionally = false
    private var startupFailure = false
    private var receiverRegistered = false
    private var systemNavigationReceiverRegistered = false
    private var currentBootCount = UNKNOWN_APP_SUPERVISION_BOOT_COUNT
    private var lastNotificationText: String? = null
    private val appLabelCache = mutableMapOf<String, String>()
    private var lastProtectedPackage: String? = null
    private var lastProtectedPackageResult = false
    private var lastProtectedPackageCheckElapsedMillis = Long.MIN_VALUE
    private var protectedPackageQueryInFlight: String? = null
    private var hasReceivedStartCommand = false
    private var commitmentRefreshGeneration = 0L
    private var commitmentRefreshInFlight = false

    private val observationRunnable = Runnable { requestObservation() }
    private val deadlineRunnable = Runnable {
        if (isStoppingIntentionally) return@Runnable
        if (!hasRuntimeWork()) {
            currentCommitmentBlock = null
            hideBlockedOverlay()
            requestScheduleReconciliation("runtime_boundary_expired")
            requestCommitmentReconciliation("commitment_boundary_expired")
            if (!hasRuntimeRules()) beginStop(startId = 0, clearSnapshot = true)
            return@Runnable
        }
        applyEngineTick(unavailableObservation(), forcePersist = true)
        if (rules.isNotEmpty()) requestScheduleReconciliation("app_rule_deadline")
        requestCommitmentReconciliation("commitment_boundary")
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(observationRunnable)
                    transientSessionTracker.reset()
                    applyEngineTick(
                        observation = lastObservation.toAppObservation(isInteractive = false),
                        forcePersist = true
                    )
                    hideBlockedOverlay()
                }
                Intent.ACTION_SCREEN_ON -> {
                    cadencePolicy.onTrigger(
                        ForegroundObservationCadenceTrigger.SCREEN_ON,
                        SystemClock.elapsedRealtime()
                    )
                    applyEngineTick(unavailableObservation(), forcePersist = false)
                    scheduleObservation(0L)
                }
                TelecomManager.ACTION_DEFAULT_DIALER_CHANGED,
                Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED -> {
                    resetProtectedPackageCache()
                    applyEngineTick(lastObservation.toAppObservation(), forcePersist = false)
                }
            }
        }
    }
    private val systemNavigationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            if (!SystemDialogRelockPolicy.shouldRelock(intent.getStringExtra("reason"))) return

            cadencePolicy.onTrigger(
                ForegroundObservationCadenceTrigger.SYSTEM_NAVIGATION,
                SystemClock.elapsedRealtime()
            )
            if (
                ::overlayController.isInitialized &&
                (overlayController.isShowing || AppRestOverlayWindowRegistry.hasRegisteredWindow())
            ) {
                dismissVisibleOverlayForNavigation()
            } else if (::powerManager.isInitialized && powerManager.isInteractive) {
                scheduleObservation(0L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Android 要求前台服务尽快提升；在此之前不创建观察器、电话监听或覆盖层等资源。
        startupFailure = try {
            MonitorNotificationChannels.ensureCreated(applicationContext)
            startForeground(NOTIFICATION_ID, createNotification("正在准备 App 监督"))
            false
        } catch (_: RuntimeException) {
            true
        }
        if (startupFailure) {
            isRunning = false
            if (
                AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                    startupFailed = true,
                    isStoppingIntentionally = false,
                    activeRuleCount = 0
                )
            ) {
                scheduleRetry()
                scheduleCommitmentRetry()
            }
            stopSelf()
            return
        }

        try {
            runtimeStore = AppSupervisionRuntimeStore(applicationContext)
            historyRecorder = SupervisionHistoryRecorder.getInstance(applicationContext)
            powerManager = requireNotNull(getSystemService(PowerManager::class.java))
            currentBootCount = readBootCount()
            persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ControlFree-AppSupervision-Persistence").apply {
                    isDaemon = true
                }
            }
            protectedPackageExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ControlFree-AppSupervision-ProtectedPackage").apply {
                    isDaemon = true
                }
            }
            snapshotWriter = LatestOnlySnapshotWriter(
                executor = persistenceExecutor,
                write = runtimeStore::save,
                clear = runtimeStore::clear,
                onWriteFailure = {
                    if (!isStoppingIntentionally) scheduleRetry()
                }
            )
            appAllowlistManager = AppAllowlistManager(applicationContext)
            mediaPlaybackController = MediaPlaybackController(applicationContext)
            mediaPlaybackProbe = MediaPlaybackProbe(applicationContext)
            overlayController = AppRestOverlayController(
                applicationContext,
                ::returnToHomeFromOverlay
            )
            callStateMonitor = CallStateMonitor(applicationContext) { active ->
                isCallActive = active
                applyEngineTick(lastObservation.toAppObservation())
                if (active && powerManager.isInteractive) scheduleObservation(0L)
            }
            observationWorker = ForegroundObservationWorker(
                source = UsageAccessManager(applicationContext),
                resultExecutor = Executor { command -> handler.post(command) }
            )
            todoRepository = TodoRepository.getInstance(applicationContext)
            supervisionPlanRepository = SupervisionPlanRepository.getInstance(applicationContext)
            startObservingCommitments()
            receiverRegistered = try {
                ContextCompat.registerReceiver(
                    this,
                    screenReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED)
                        addAction(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                true
            } catch (_: RuntimeException) {
                false
            }
            systemNavigationReceiverRegistered = try {
                ContextCompat.registerReceiver(
                    this,
                    systemNavigationReceiver,
                    IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    ContextCompat.RECEIVER_EXPORTED
                )
                true
            } catch (_: RuntimeException) {
                false
            }
            callStateMonitor.start()
            isRunning = true
        } catch (_: RuntimeException) {
            startupFailure = true
            isRunning = false
            if (
                AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
                    startupFailed = true,
                    isStoppingIntentionally = false,
                    activeRuleCount = rules.size + triggerRules.size
                )
            ) {
                scheduleRetry()
                scheduleCommitmentRetry()
            }
            releaseInitializedResources()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: RuntimeException) {
                // 前台提升已成功但初始化失败时，继续停止服务实例。
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        hasReceivedStartCommand = true
        if (startupFailure) {
            scheduleRetry()
            scheduleCommitmentRetry()
            try {
                if (startId > 0) stopSelf(startId) else stopSelf()
            } catch (_: RuntimeException) {
                // 服务可能已由系统销毁。
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_APP_SUPERVISION) {
            clearAppRulesAndReconcileCommitments(startId)
            return START_STICKY
        }
        return when (intent?.action) {
            ACTION_RECONCILE_COMMITMENTS -> {
                refreshCommitmentRuntime(startId)
                START_STICKY
            }
            ACTION_APPLY_ACTIVE_RULES -> {
                val decodedRules = intent.getByteArrayExtra(EXTRA_ACTIVE_RULES)
                    ?.let(AppSupervisionBinaryCodec::decodeRules)
                val decodedTriggerRules = intent.getByteArrayExtra(EXTRA_TRIGGER_RULES)
                    ?.let(AppSupervisionBinaryCodec::decodeTriggerRules)
                if (decodedRules == null || decodedTriggerRules == null ||
                    (decodedRules.isEmpty() && decodedTriggerRules.isEmpty())) {
                    scheduleRetry()
                    clearAppRulesAndReconcileCommitments(startId)
                    START_STICKY
                } else {
                    applyRules(
                        requestedRules = decodedRules,
                        requestedTriggerRules = decodedTriggerRules,
                        startId = startId
                    )
                    START_STICKY
                }
            }
            else -> restorePersistedRuntime(startId)
        }
    }

    private fun restorePersistedRuntime(startId: Int): Int = when (val stored = runtimeStore.read()) {
        is AppSupervisionSnapshotReadResult.Available -> {
            val activeRules = stored.snapshot.rules.filter {
                System.currentTimeMillis() < it.occurrenceEndEpochMillis
            }
            val activeTriggerRules = stored.snapshot.triggerRules.filter {
                System.currentTimeMillis() < it.occurrenceEndEpochMillis
            }
            if (activeRules.isEmpty() && activeTriggerRules.isEmpty()) {
                refreshCommitmentRuntime(startId, clearAppSnapshot = true)
                START_STICKY
            } else {
                applyRules(
                    requestedRules = activeRules,
                    requestedTriggerRules = activeTriggerRules,
                    restoredStates = stored.snapshot.states,
                    restoredEnforcementState = stored.snapshot.enforcementState,
                    restoredDailyUsageStates = stored.snapshot.dailyUsageStates,
                    startId = startId
                )
                START_STICKY
            }
        }
        AppSupervisionSnapshotReadResult.None -> {
            // 没有常规 App 快照时仍需查询防拖延 occurrence，不能直接停止恢复入口。
            refreshCommitmentRuntime(startId)
            START_STICKY
        }
        AppSupervisionSnapshotReadResult.Corrupted -> {
            refreshCommitmentRuntime(startId)
            START_STICKY
        }
    }

    private fun clearAppRulesAndReconcileCommitments(startId: Int) {
        clearAppRuleState(clearSnapshot = true)
        if (hasActiveCommitmentWindow()) {
            applyEngineTick(lastObservation.toAppObservation(), forcePersist = false)
        } else {
            hideBlockedOverlay()
        }
        refreshCommitmentRuntime(startId)
    }

    private fun clearAppRuleState(clearSnapshot: Boolean) {
        rules = emptyList()
        triggerRules = emptyList()
        triggerEdgeState = AppTriggerEdgeState()
        triggerActivationsInFlight.clear()
        states = emptyList()
        dailyUsageStates = emptyList()
        enforcementState = AppSupervisionEnforcementState()
        overlayDismissal = null
        transientSessionTracker.reset()
        handler.removeCallbacks(deadlineRunnable)
        if (::historyRecorder.isInitialized) historyRecorder.reconcileApps(emptyList())
        if (clearSnapshot && ::snapshotWriter.isInitialized) {
            try {
                if (!snapshotWriter.requestClear { cleared ->
                        if (!cleared) scheduleRetry()
                    }
                ) {
                    scheduleRetry()
                }
            } catch (_: RuntimeException) {
                scheduleRetry()
            }
        }
    }

    private fun refreshCommitmentRuntime(
        startId: Int,
        clearAppSnapshot: Boolean = false
    ) {
        runtimeGeneration = nextGeneration(runtimeGeneration)
        isStoppingIntentionally = false
        commitmentRefreshGeneration = nextGeneration(commitmentRefreshGeneration)
        val generation = commitmentRefreshGeneration
        commitmentRefreshInFlight = true
        serviceScope.launch {
            val snapshot = try {
                todoRepository.reconcileCommitmentOccurrences(System.currentTimeMillis())
                todoRepository.getCommitmentRuntimeSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            handler.post {
                if (generation != commitmentRefreshGeneration || isStoppingIntentionally) {
                    return@post
                }
                commitmentRefreshInFlight = false
                if (clearAppSnapshot) clearAppRuleState(clearSnapshot = true)
                if (snapshot == null) {
                    scheduleCommitmentRetry()
                    if (hasActiveCommitmentWindow()) {
                        if (observationWindowGeneration == 0L) beginObservationWindow()
                        applyEngineTick(lastObservation.toAppObservation(), forcePersist = false)
                        scheduleDeadlineCheck()
                    } else if (!hasRuntimeRules()) {
                        beginStop(startId, clearSnapshot = false)
                    }
                    return@post
                }
                updateCommitmentSnapshot(snapshot, startId)
            }
        }
    }

    private fun startObservingCommitments() {
        serviceScope.launch {
            todoRepository.observeCommitmentRuntime().collect { snapshot ->
                handler.post {
                    if (!isStoppingIntentionally) updateCommitmentSnapshot(snapshot, startId = 0)
                }
            }
        }
    }

    private fun updateCommitmentSnapshot(
        snapshot: CommitmentRuntimeSnapshot,
        startId: Int
    ) {
        commitmentSnapshot = snapshot
        val now = System.currentTimeMillis()
        if (CommitmentRuntimePolicy.hasActiveWindow(snapshot, now)) {
            isStoppingIntentionally = false
            if (observationWindowGeneration == 0L) beginObservationWindow()
            applyEngineTick(lastObservation.toAppObservation(), forcePersist = false)
            scheduleDeadlineCheck()
            return
        }

        currentCommitmentBlock = null
        activationWritesInFlight.clear()
        if (hasRuntimeRules()) {
            applyEngineTick(lastObservation.toAppObservation(), forcePersist = false)
            scheduleDeadlineCheck()
        } else if (hasReceivedStartCommand && !commitmentRefreshInFlight) {
            hideBlockedOverlay()
            beginStop(startId, clearSnapshot = true)
        }
    }

    private fun applyRules(
        requestedRules: List<AppSupervisionRule>,
        requestedTriggerRules: List<AppTriggerRule> = emptyList(),
        restoredStates: List<AppSupervisionRuntimeState>? = null,
        restoredEnforcementState: AppSupervisionEnforcementState? = null,
        restoredDailyUsageStates: List<AppSupervisionDailyUsageState>? = null,
        startId: Int = 0
    ) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val activeRules = requestedRules
            .filter { nowWall < it.occurrenceEndEpochMillis }
            .sortedBy(AppSupervisionRule::planId)
        val activeTriggers = requestedTriggerRules
            .filter { nowWall < it.occurrenceEndEpochMillis }
            .sortedBy(AppTriggerRule::planId)
        if (activeRules.isEmpty() && activeTriggers.isEmpty()) {
            clearAppRulesAndReconcileCommitments(startId)
            return
        }
        runtimeGeneration = nextGeneration(runtimeGeneration)
        isStoppingIntentionally = false
        val hasContinuousLiveState = restoredStates == null && hasRuntimeRules()
        transientSessionTracker.reset()
        val storedRuntime = if (restoredStates == null && !hasContinuousLiveState) {
            runtimeStore.read()
        } else {
            null
        }
        val storedSnapshot = (storedRuntime as? AppSupervisionSnapshotReadResult.Available)?.snapshot
        val candidateStates = when {
            restoredStates != null -> restoredStates
            hasContinuousLiveState -> states
            storedRuntime == AppSupervisionSnapshotReadResult.Corrupted ->
                createFailSafeRestStates(activeRules)
            else -> storedSnapshot?.states.orEmpty()
        }
        val candidateEnforcementState = when {
            restoredStates != null -> restoredEnforcementState
            hasContinuousLiveState -> enforcementState
            else -> storedSnapshot?.enforcementState
        }
        val candidateDailyStates = when {
            restoredDailyUsageStates != null -> restoredDailyUsageStates
            hasContinuousLiveState -> dailyUsageStates
            storedSnapshot?.dailyUsageStates?.isNotEmpty() == true ->
                storedSnapshot.dailyUsageStates
            else -> when (val storedDaily = runtimeStore.readDailyUsageStates()) {
                is AppSupervisionDailyUsageReadResult.Available -> storedDaily.states
                AppSupervisionDailyUsageReadResult.None,
                AppSupervisionDailyUsageReadResult.Corrupted -> emptyList()
            }
        }
        val safeStates = if (hasContinuousLiveState) {
            candidateStates
        } else {
            resetAppSupervisionObservationContinuity(
                states = candidateStates,
                nowElapsedMillis = nowElapsed,
                bootCount = currentBootCount,
                isInteractive = powerManager.isInteractive
            )
        }
        rules = activeRules
        triggerRules = activeTriggers
        triggerEdgeState = AppTriggerEdgePolicy.reconcile(triggerEdgeState, triggerRules)
        triggerActivationsInFlight.retainAll(triggerRules.mapTo(hashSetOf(), AppTriggerRule::planId))
        overlayDismissal = overlayDismissal?.takeIf { dismissal ->
            activeRules.any { rule -> rule == dismissal.rule }
        }
        appLabelCache.keys.retainAll(activeRules.mapTo(hashSetOf(), AppSupervisionRule::packageName))
        states = safeStates.filter { state -> activeRules.any(state::matches) }
        dailyUsageStates = candidateDailyStates.filter { state -> activeRules.any(state::matches) }
        enforcementState = if (hasContinuousLiveState) {
            enforcementState.retainActiveRule(activeRules)
        } else {
            AppSupervisionEnforcementPolicy.restore(
                persisted = candidateEnforcementState,
                rules = activeRules,
                states = candidateStates,
                dailyUsageStates = candidateDailyStates,
                nowWallEpochMillis = nowWall,
                nowElapsedMillis = nowElapsed,
                currentBootCount = currentBootCount
            )
        }
        val result = AppSupervisionEngine.tick(
            rules = rules,
            previousStates = states,
            observation = unavailableObservation(),
            nowWallEpochMillis = nowWall,
            nowElapsedMillis = nowElapsed,
            bootCount = currentBootCount,
            previousDailyUsageStates = dailyUsageStates
        )
        states = result.states
        dailyUsageStates = result.dailyUsageStates
        lastObservation = ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
        val enforcement = resolveEnforcement(result.blocked, unavailableObservation())
        updateEnforcement(enforcement, unavailableObservation())
        persistSnapshot(force = true)
        historyRecorder.reconcileApps(rules)
        beginObservationWindow()
        scheduleDeadlineCheck()
        updateNotification(enforcement)
    }

    private fun beginObservationWindow() {
        if (observationWindowGeneration != 0L) {
            try {
                observationWorker.resetObservationWindow()
            } catch (_: RuntimeException) {
                // 新窗口创建仍会给出明确失败并安排重试。
            }
        }
        val anchor = (System.currentTimeMillis() - OBSERVATION_LOOKBACK_MILLIS).coerceAtLeast(0L)
        observationWindowGeneration = try {
            observationWorker.beginObservationWindow(anchor)
        } catch (_: RuntimeException) {
            0L
        }
        if (observationWindowGeneration == 0L) {
            scheduleRetry()
        } else if (powerManager.isInteractive) {
            scheduleObservation(0L)
        }
    }

    private fun requestObservation() {
        if (
            isStoppingIntentionally ||
            !hasRuntimeWork() ||
            observationWindowGeneration == 0L ||
            !powerManager.isInteractive
        ) {
            return
        }
        val accepted = observationWorker.requestObservation(
            expectedGeneration = observationWindowGeneration,
            nowWallMillis = System.currentTimeMillis()
        ) { observation ->
            if (isStoppingIntentionally || !hasRuntimeWork()) return@requestObservation
            val visibleBlock = visibleBlockedOverlay
            if (
                visibleBlock != null &&
                AppSupervisionOverlayDismissalPolicy.shouldDismissVisibleOverlay(
                    blocked = visibleBlock,
                    shownAtEpochMillis = visibleBlockedOverlayShownAtEpochMillis,
                    observation = observation
                )
            ) {
                overlayDismissal = AppSupervisionOverlayDismissalPolicy.dismiss(
                    blocked = visibleBlock,
                    nowEpochMillis = observation.transitionTimestampMillis
                )
                hideBlockedOverlay()
            }
            val dismissalBeforeReconcile = overlayDismissal
            overlayDismissal = AppSupervisionOverlayDismissalPolicy.reconcile(
                dismissalBeforeReconcile,
                observation
            )
            if (
                dismissalBeforeReconcile != null &&
                overlayDismissal == null &&
                observation.packageName?.let { foregroundPackage ->
                    rules.any { rule -> rule.packageName == foregroundPackage }
                } == true
            ) {
                // 重新进入目标 App 后使用快速节奏恢复拦截，避免退出令牌解除后仍等待
                // 普通轮询周期，出现数秒可操作空窗。
                cadencePolicy.onTrigger(
                    ForegroundObservationCadenceTrigger.SYSTEM_NAVIGATION,
                    SystemClock.elapsedRealtime()
                )
            }
            val extraUsage = transientSessionTracker.recordObservation(
                sampledAtEpochMillis = observation.observedAtEpochMillis,
                foregroundPackage = observation.packageName,
                isInteractive = powerManager.isInteractive,
                events = observation.newEvents,
                isAvailable = observation.isAvailable
            )
            lastObservation = observation
            cadencePolicy.recordObservationResult(observation.isAvailable)
            processAppTriggers(observation)
            applyEngineTick(
                observation.toAppObservation(
                    isInteractive = powerManager.isInteractive,
                    unobservedForegroundMillisByPackage = extraUsage
                )
            )
            scheduleNextObservation(observation)
        }
        if (!accepted) scheduleObservation(ForegroundObservationCadencePolicy.RAPID_INTERVAL_MILLIS)
    }

    private fun applyEngineTick(
        observation: AppSupervisionObservation,
        forcePersist: Boolean = false
    ) {
        if (!hasRuntimeWork() || isStoppingIntentionally) return
        val previousStates = states
        val previousRules = rules
        val nowWall = System.currentTimeMillis()
        val result = if (rules.isEmpty()) {
            null
        } else {
            AppSupervisionEngine.tick(
                rules = rules,
                previousStates = previousStates,
                observation = observation,
                nowWallEpochMillis = nowWall,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                bootCount = currentBootCount,
                previousDailyUsageStates = dailyUsageStates
            )
        }
        states = result?.states.orEmpty()
        dailyUsageStates = result?.dailyUsageStates ?: dailyUsageStates
        rules = rules.filter { rule -> nowWall < rule.occurrenceEndEpochMillis }
        triggerRules = triggerRules.filter { rule -> nowWall < rule.occurrenceEndEpochMillis }
        triggerEdgeState = AppTriggerEdgePolicy.reconcile(triggerEdgeState, triggerRules)
        if (rules != previousRules) historyRecorder.reconcileApps(rules)
        if (!hasRuntimeRules() && !hasActiveCommitmentWindow(nowWall)) {
            requestScheduleReconciliation("app_rules_expired")
            requestCommitmentReconciliation("commitment_window_expired")
            beginStop(startId = 0, clearSnapshot = true)
            return
        }
        val phaseChanged = previousStates.associateBy(AppSupervisionRuntimeState::planId)
            .any { (planId, previous) ->
                states.firstOrNull { it.planId == planId }?.phase != previous.phase
            }
        val standardEnforcement = if (result == null) {
            enforcementState = AppSupervisionEnforcementState()
            AppSupervisionEnforcementDecision(
                state = enforcementState,
                blocked = null,
                observationDegraded = !observation.isAvailable,
                requestSafeRecovery = false,
                recoveryRule = null
            )
        } else {
            resolveEnforcement(result.blocked, observation)
        }
        val enforcement = resolveCommitmentEnforcement(
            standardDecision = standardEnforcement,
            observation = observation,
            nowEpochMillis = nowWall
        )
        updateEnforcement(enforcement, observation)
        if (hasRuntimeRules()) persistSnapshot(forcePersist || phaseChanged)
        updateNotification(enforcement)
    }

    private fun processAppTriggers(observation: ForegroundObservation) {
        if (triggerRules.isNotEmpty() || observation.packageName != null) {
            Log.d("AppTriggerDebug", "processAppTriggers: packageName=${observation.packageName}, isAvailable=${observation.isAvailable}, triggerRulesSize=${triggerRules.size}, triggerRules=$triggerRules")
        }
        if (triggerRules.isEmpty() || !observation.isAvailable) return
        val decision = AppTriggerEdgePolicy.onObservation(
            previous = triggerEdgeState,
            rules = triggerRules,
            observation = observation
        )
        triggerEdgeState = decision.state
        val triggeredRule = decision.triggeredRule ?: return
        val packageName = decision.triggerPackageName ?: return
        Log.i("AppTriggerDebug", "App triggered! planId=${triggeredRule.planId}, planName=${triggeredRule.planName}, triggerPackageName=$packageName")
        if (!triggerActivationsInFlight.add(triggeredRule.planId)) {
            Log.w("AppTriggerDebug", "Activation already in flight for planId=${triggeredRule.planId}")
            return
        }

        serviceScope.launch {
            Log.d("AppTriggerDebug", "Launching activateFromAppTrigger on repository...")
            val result = supervisionPlanRepository.activateFromAppTrigger(
                planId = triggeredRule.planId,
                expectedUpdatedAtEpochMillis = triggeredRule.planUpdatedAtEpochMillis,
                triggerPackageName = packageName,
                nowEpochMillis = System.currentTimeMillis()
            )
            Log.i("AppTriggerDebug", "activateFromAppTrigger repository result = $result")
            handler.post {
                triggerActivationsInFlight.remove(triggeredRule.planId)
                when (result) {
                    is AppTriggerActivationResult.Activated,
                    AppTriggerActivationResult.AlreadyEnabled,
                    AppTriggerActivationResult.NoLongerEligible,
                    AppTriggerActivationResult.NotFound -> {
                        Log.d("AppTriggerDebug", "Requesting reconciliation with reason: app_trigger_result")
                        requestScheduleReconciliation("app_trigger_result")
                    }
                    is AppTriggerActivationResult.Conflicts,
                    AppTriggerActivationResult.StorageFailure -> {
                        Log.w("AppTriggerDebug", "Trigger failed with conflict/failure. Scheduling retry.")
                        scheduleRetry()
                    }
                }
            }
        }
    }

    private fun resolveCommitmentEnforcement(
        standardDecision: AppSupervisionEnforcementDecision,
        observation: AppSupervisionObservation,
        nowEpochMillis: Long
    ): AppSupervisionEnforcementDecision {
        if (standardDecision.blocked != null || standardDecision.requestSafeRecovery) {
            currentCommitmentBlock = null
            return standardDecision
        }
        val foregroundPackage = observation.foregroundPackage
        if (
            foregroundPackage == null ||
            !CommitmentRuntimePolicy.isActiveBlockedPackage(
                snapshot = commitmentSnapshot,
                packageName = foregroundPackage,
                nowEpochMillis = nowEpochMillis
            )
        ) {
            currentCommitmentBlock = null
            return standardDecision
        }
        val protectedStatus = protectedPackageStatus(foregroundPackage)
        val block = CommitmentRuntimePolicy.evaluate(
            snapshot = commitmentSnapshot,
            foregroundPackage = foregroundPackage,
            isObservationAvailable = observation.isAvailable,
            isInteractive = observation.isInteractive,
            isCallActive = isCallActive,
            isProtectedPackage = protectedStatus,
            nowEpochMillis = nowEpochMillis
        )
        currentCommitmentBlock = block
        val runtimeBlock = block?.toBlockedAppSupervision()
            ?: return standardDecision
        return standardDecision.copy(
            blocked = runtimeBlock,
            observationDegraded = false,
            requestSafeRecovery = false,
            recoveryRule = null
        )
    }

    private fun ActiveCommitmentBlock.toBlockedAppSupervision(): BlockedAppSupervision {
        // 防拖延 occurrence 没有自然过期时间；给监督引擎一个滚动复核窗口，
        // 每次前台采样都会续期，只有承诺满足或策略撤销才真正放行。
        val remainingMillis = MAX_COMMITMENT_LOCK_MILLIS
        return BlockedAppSupervision(
            rule = AppSupervisionRule(
                planId = "commitment:$occurrenceId",
                planUpdatedAtEpochMillis = deadlineEpochMillis,
                planName = "防拖延任务未完成",
                packageName = packageName,
                occurrenceEndEpochMillis = Long.MAX_VALUE,
                usageAllowanceMillis = 1L,
                restDurationMillis = remainingMillis.coerceAtMost(MAX_COMMITMENT_LOCK_MILLIS)
            ),
            remainingRestMillis = remainingMillis
        )
    }

    private fun resolveEnforcement(
        candidateBlocked: BlockedAppSupervision?,
        observation: AppSupervisionObservation
    ): AppSupervisionEnforcementDecision {
        val decision = AppSupervisionEnforcementPolicy.reduce(
            previous = enforcementState,
            candidateBlocked = candidateBlocked,
            observation = observation,
            nowWallEpochMillis = System.currentTimeMillis(),
            nowElapsedMillis = SystemClock.elapsedRealtime()
        )
        enforcementState = decision.state
        return decision
    }

    private fun updateEnforcement(decision: AppSupervisionEnforcementDecision, observation: AppSupervisionObservation) {
        if (decision.requestSafeRecovery) {
            val recoveryRule = decision.recoveryRule
            val canReturnHome = recoveryRule == null ||
                !isProtectedPackage(recoveryRule.packageName)
            if (canReturnHome) {
                returnToHome()
                // startActivity 未抛异常不代表系统真的切到了 Home。保留可信遮罩，
                // 直到后续 AVAILABLE 观察明确确认目标 App 已离开。
                val fallback = AppSupervisionEnforcementPolicy.recoveryFallbackBlock(
                    state = decision.state,
                    nowWallEpochMillis = System.currentTimeMillis()
                )
                if (fallback == null) {
                    hideBlockedOverlay()
                } else {
                    updateEnforcement(
                        decision.copy(
                            blocked = fallback,
                            requestSafeRecovery = false
                        ),
                        observation
                    )
                }
            } else {
                // 只有正向确认的系统受保护包才走明确的放行例外。
                hideBlockedOverlay()
            }
            // 旧查询可能仍被厂商 Binder 卡住；新窗口会使迟到结果失效，并安排新的探针。
            beginObservationWindow()
            // 每个新观察窗口都重新计算故障宽限，不能因一次回桌面请求失败后永久放行。
            enforcementState = enforcementState.copy(
                unavailableSinceElapsedMillis = SystemClock.elapsedRealtime(),
                recoveryRequested = false
            )
            return
        }

        val blocked = decision.blocked ?: run {
            hideBlockedOverlay()
            return
        }
        if (AppSupervisionOverlayDismissalPolicy.shouldSuppress(overlayDismissal, blocked)) {
            hideBlockedOverlay()
            return
        }
        if (overlayDismissal != null) overlayDismissal = null
        if (isProtectedPackage(blocked.rule.packageName)) {
            hideBlockedOverlay(resetProtectedPackageCache = false)
            return
        }
        val label = resolveAppLabel(blocked.rule)
        val icon = try {
            appAllowlistManager.getApplicationIcon(blocked.rule.packageName)
        } catch (_: RuntimeException) {
            null
        }
        val wasSameBlock = blockedPlanId == blocked.rule.planId && overlayController.isShowing
        val showResult = overlayController.show(blocked, label, icon)
        when (showResult) {
            OverlayShowResult.SHOWN -> {
                blockedPlanId = blocked.rule.planId
                visibleBlockedOverlay = blocked
                if (!wasSameBlock) {
                    visibleBlockedOverlayShownAtEpochMillis = System.currentTimeMillis()
                }
                markCommitmentActivatedIfNeeded(blocked.rule.planId)
                if (!mediaGuardActive || !wasSameBlock) {
                    mediaPlaybackController.pauseMediaDuringLock(forceMediaCommand = true)
                    mediaPlaybackController.startRetainedReplayProtection(mediaPlaybackProbe)
                    mediaGuardActive = true
                }
            }

            OverlayShowResult.DEFERRED -> {
                // 旧窗口尚在移除或系统窗口事务收敛期。保持监督真值并快速重试，
                // 但禁止把暂态当成窗口故障，也禁止叠加第二层。
                blockedPlanId = null
                visibleBlockedOverlay = null
                visibleBlockedOverlayShownAtEpochMillis = 0L
                scheduleObservation(ForegroundObservationCadencePolicy.RAPID_INTERVAL_MILLIS)
            }

            OverlayShowResult.PERMISSION_REVOKED,
            OverlayShowResult.WINDOW_ERROR -> {
                blockedPlanId = null
                visibleBlockedOverlay = null
                visibleBlockedOverlayShownAtEpochMillis = 0L
                returnToHome()
            }
        }
    }

    private fun hideBlockedOverlay(resetProtectedPackageCache: Boolean = true) {
        overlayController.hide()
        blockedPlanId = null
        visibleBlockedOverlay = null
        visibleBlockedOverlayShownAtEpochMillis = 0L
        if (mediaGuardActive) {
            mediaPlaybackController.allowMediaPlayback()
            mediaGuardActive = false
        }
        if (resetProtectedPackageCache) {
            resetProtectedPackageCache()
        }
    }

    private fun resetProtectedPackageCache() {
        lastProtectedPackage = null
        lastProtectedPackageResult = false
        lastProtectedPackageCheckElapsedMillis = Long.MIN_VALUE
    }

    private fun isProtectedPackage(packageName: String): Boolean =
        protectedPackageStatus(packageName) == true

    private fun protectedPackageStatus(packageName: String): Boolean? {
        if (packageName == this.packageName || packageName in ALWAYS_PROTECTED_PACKAGES) {
            return true
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val hasFreshCache = packageName == lastProtectedPackage &&
            nowElapsed >= lastProtectedPackageCheckElapsedMillis &&
            nowElapsed - lastProtectedPackageCheckElapsedMillis < PROTECTED_PACKAGE_CACHE_MILLIS
        if (hasFreshCache) return lastProtectedPackageResult

        // Telecom、Telephony 和 PackageManager 都可能进入厂商 Binder。主线程只消费已确认
        // 缓存；常规 App 监督由调用方保持原失败安全语义，防拖延对 null 失败开放。
        if (protectedPackageQueryInFlight == null && ::protectedPackageExecutor.isInitialized) {
            protectedPackageQueryInFlight = packageName
            val accepted = try {
                protectedPackageExecutor.execute {
                    val result = try {
                        appAllowlistManager.isProtectedFromSupervision(packageName)
                    } catch (_: RuntimeException) {
                        null
                    }
                    handler.post {
                        if (protectedPackageQueryInFlight == packageName) {
                            protectedPackageQueryInFlight = null
                        }
                        if (!isRunning || isStoppingIntentionally || result == null) {
                            return@post
                        }
                        lastProtectedPackage = packageName
                        lastProtectedPackageResult =
                            AppSupervisionEnforcementPolicy.isProtectedPackageBypassAllowed(result)
                        lastProtectedPackageCheckElapsedMillis = SystemClock.elapsedRealtime()
                        applyEngineTick(lastObservation.toAppObservation())
                    }
                }
                true
            } catch (_: RuntimeException) {
                false
            }
            if (!accepted) protectedPackageQueryInFlight = null
        }
        return null
    }

    private fun scheduleNextObservation(observation: ForegroundObservation) {
        val now = System.currentTimeMillis()
        val foregroundIsTarget = observation.packageName?.let { packageName ->
            rules.any { it.packageName == packageName } ||
                CommitmentRuntimePolicy.isActiveBlockedPackage(
                    commitmentSnapshot,
                    packageName,
                    now
                )
        } == true
        val mode = when {
            isCallActive -> ForegroundObservationCadenceMode.CALL_ACTIVE
            foregroundIsTarget || overlayController.isShowing ->
                ForegroundObservationCadenceMode.ALLOWLIST_ACTIVE
            else -> ForegroundObservationCadenceMode.LOCKED
        }
        val delay = cadencePolicy.nextDelayMillis(
            ForegroundObservationCadenceInput(
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                isLockPhase = hasRuntimeWork(now),
                isInteractive = powerManager.isInteractive,
                mode = mode
            )
        ) ?: return
        scheduleObservation(delay)
    }

    private fun scheduleObservation(delayMillis: Long) {
        handler.removeCallbacks(observationRunnable)
        if (!isStoppingIntentionally && hasRuntimeWork()) {
            handler.postDelayed(observationRunnable, delayMillis.coerceAtLeast(0L))
        }
    }

    private fun scheduleDeadlineCheck() {
        handler.removeCallbacks(deadlineRunnable)
        val now = System.currentTimeMillis()
        val nearestEnd = listOfNotNull(
            rules.minOfOrNull(AppSupervisionRule::occurrenceEndEpochMillis),
            triggerRules.minOfOrNull(AppTriggerRule::occurrenceEndEpochMillis),
            CommitmentRuntimePolicy.nextBoundaryEpochMillis(commitmentSnapshot, now)
        ).minOrNull() ?: return
        if (nearestEnd == Long.MAX_VALUE) return
        val delay = (nearestEnd - now).coerceAtLeast(1L)
        handler.postDelayed(deadlineRunnable, delay)
    }

    private fun persistSnapshot(force: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && nowElapsed - lastPersistElapsedMillis < SNAPSHOT_HEARTBEAT_MILLIS) return
        lastPersistElapsedMillis = nowElapsed
        val snapshot = try {
            AppSupervisionRuntimeSnapshot(
                rules = rules,
                states = states,
                savedAtEpochMillis = System.currentTimeMillis(),
                enforcementState = enforcementState,
                dailyUsageStates = dailyUsageStates,
                triggerRules = triggerRules
            )
        } catch (_: IllegalArgumentException) {
            scheduleRetry()
            return
        }
        try {
            if (!snapshotWriter.submit(snapshot)) scheduleRetry()
        } catch (_: RuntimeException) {
            scheduleRetry()
        }
    }

    private fun beginStop(startId: Int, clearSnapshot: Boolean) {
        val generation = nextGeneration(runtimeGeneration)
        runtimeGeneration = generation
        isStoppingIntentionally = true
        rules = emptyList()
        triggerRules = emptyList()
        triggerEdgeState = AppTriggerEdgeState()
        triggerActivationsInFlight.clear()
        states = emptyList()
        dailyUsageStates = emptyList()
        enforcementState = AppSupervisionEnforcementState()
        overlayDismissal = null
        transientSessionTracker.reset()
        handler.removeCallbacks(observationRunnable)
        handler.removeCallbacks(deadlineRunnable)
        cadencePolicy.reset()
        try {
            observationWorker.resetObservationWindow()
        } catch (_: RuntimeException) {
            // 停止路径继续释放其他资源。
        }
        observationWindowGeneration = 0L
        hideBlockedOverlay()
        if (clearSnapshot && ::historyRecorder.isInitialized) {
            historyRecorder.reconcileApps(emptyList())
        }
        val finishStop = Runnable {
            if (generation != runtimeGeneration || !isStoppingIntentionally) return@Runnable
            isRunning = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: RuntimeException) {
                // 前台通知可能已被系统移除，仍继续停止服务实例。
            }
            try {
                if (startId > 0) stopSelf(startId) else stopSelf()
            } catch (_: RuntimeException) {
                // 系统已销毁服务时无需重复停止。
            }
        }
        if (!clearSnapshot) {
            handler.post(finishStop)
            return
        }
        val accepted = try {
            snapshotWriter.requestClear { cleared ->
                handler.post {
                    if (!cleared) scheduleRetry()
                    finishStop.run()
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            scheduleRetry()
            handler.post(finishStop)
        }
    }

    private fun createFailSafeRestStates(
        activeRules: List<AppSupervisionRule>
    ): List<AppSupervisionRuntimeState> {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val bootCount = currentBootCount
        return AppSupervisionServiceRecoveryPolicy.failSafeRestRules(activeRules).map { rule ->
            AppSupervisionRuntimeState(
                planId = rule.planId,
                planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
                occurrenceEndEpochMillis = rule.resolvedCycleOccurrenceEndEpochMillis,
                phase = AppSupervisionPhase.REST,
                remainingAllowanceMillis = 0L,
                restUntilEpochMillis = minOf(
                    rule.occurrenceEndEpochMillis,
                    saturatedAdd(nowWall, rule.restDurationMillis)
                ),
                checkpointElapsedMillis = nowElapsed,
                bootCount = bootCount,
                wasTargetForeground = false,
                wasInteractive = powerManager.isInteractive
            )
        }
    }

    private fun updateNotification(decision: AppSupervisionEnforcementDecision) {
        val blocked = decision.blocked
        val text = if (blocked != null && decision.observationDegraded) {
            "前台识别异常，暂时保持 ${resolveAppLabel(blocked.rule)} 阻止"
        } else if (blocked != null) {
            val reason = when (blocked.reason) {
                AppSupervisionBlockReason.REST -> "休息中"
                AppSupervisionBlockReason.DAILY_LIMIT -> "今日额度已用完"
                AppSupervisionBlockReason.DISABLED_TIME -> "禁用时段"
            }
            "${resolveAppLabel(blocked.rule)}$reason：" +
                formatSeconds(millisToDisplaySeconds(blocked.remainingRestMillis))
        } else if (decision.observationDegraded && rules.isEmpty() && triggerRules.isEmpty()) {
            "前台识别不可用，防拖延暂不拦截"
        } else if (decision.observationDegraded) {
            "前台识别异常，正在恢复 App 监督"
        } else if (rules.isEmpty() && triggerRules.isEmpty() && hasActiveCommitmentWindow()) {
            "防拖延已生效，仅限制任务显式绑定的 App"
        } else if (rules.isEmpty() && triggerRules.isNotEmpty()) {
            "正在等待 ${triggerRules.size} 个监督任务的 App 触发"
        } else {
            "正在监督 ${rules.size} 个 App，仅前台亮屏时计入额度"
        }
        if (text == lastNotificationText) return
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, createNotification(text))
            lastNotificationText = text
        } catch (_: RuntimeException) {
            // 前台服务已建立，通知更新失败等待下一观察周期重试。
        }
    }

    private fun createNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MonitorNotificationChannels.SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${getString(R.string.app_name)} · App 监督")
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun resolveAppLabel(rule: AppSupervisionRule): String =
        appLabelCache.getOrPut(rule.packageName) {
            try {
                val info = packageManager.getApplicationInfo(rule.packageName, 0)
                packageManager.getApplicationLabel(info).toString().ifBlank { rule.planName }
            } catch (_: RuntimeException) {
                rule.planName
            }
        }

    private fun returnToHome(): Boolean {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (_: RuntimeException) {
            // Home 启动被系统限制时继续保留遮罩，用户仍可使用系统 Home 键离开。
            return false
        }
    }

    private fun returnToHomeFromOverlay() {
        val blocked = visibleBlockedOverlay
        // 先记录退出并移除可触摸窗口，再启动桌面，避免焦点切换时留下无法管理的孤儿层。
        dismissVisibleOverlayForNavigation(blocked)
        returnToHome()
    }

    private fun dismissVisibleOverlayForNavigation(
        blocked: BlockedAppSupervision? = visibleBlockedOverlay
    ) {
        if (blocked != null) {
            overlayDismissal = AppSupervisionOverlayDismissalPolicy.dismiss(
                blocked = blocked,
                nowEpochMillis = System.currentTimeMillis()
            )
        }
        hideBlockedOverlay()
        // Home 广播可能正好发生在 Service 重建、旧窗口尚未被新 Controller 接管的间隙。
        // 这是用户明确的系统导航动作，可以安全移除注册表中仍存活的旧实例窗口。
        AppRestOverlayWindowRegistry.hideAny()
        if (::powerManager.isInitialized && powerManager.isInteractive) {
            scheduleObservation(0L)
        }
    }

    private fun requestScheduleReconciliation(reason: String) {
        try {
            SupervisionScheduleReceiver.requestReconciliation(applicationContext, reason)
        } catch (_: RuntimeException) {
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        try {
            SupervisionAlarmScheduler(applicationContext).scheduleRetry(
                RETRY_REQUEST_DELAY_MILLIS
            )
        } catch (_: RuntimeException) {
            // 进入 App、系统时间变化或下一边界仍会触发校准。
        }
    }

    private fun requestCommitmentReconciliation(reason: String) {
        try {
            CommitmentScheduleReceiver.requestReconciliation(applicationContext, reason)
        } catch (_: RuntimeException) {
            scheduleCommitmentRetry()
        }
    }

    private fun scheduleCommitmentRetry() {
        try {
            CommitmentAlarmScheduler(applicationContext).scheduleRetry(
                RETRY_REQUEST_DELAY_MILLIS
            )
        } catch (_: RuntimeException) {
            // 保存策略、进入 App 或下一次系统广播仍会再次校准。
        }
    }

    private fun readBootCount(): Int = try {
        Settings.Global.getInt(
            contentResolver,
            Settings.Global.BOOT_COUNT,
            UNKNOWN_APP_SUPERVISION_BOOT_COUNT
        )
    } catch (_: RuntimeException) {
        UNKNOWN_APP_SUPERVISION_BOOT_COUNT
    }

    private fun ForegroundObservation.toAppObservation(
        isInteractive: Boolean = powerManager.isInteractive,
        unobservedForegroundMillisByPackage: Map<String, Long> = emptyMap()
    ) = AppSupervisionObservation(
        isAvailable = isAvailable,
        foregroundPackage = packageName.takeIf { isAvailable },
        isInteractive = isInteractive,
        status = toAppObservationStatus(),
        unobservedForegroundMillisByPackage = unobservedForegroundMillisByPackage
    )

    private fun unavailableObservation() = AppSupervisionObservation(
        isAvailable = false,
        foregroundPackage = null,
        isInteractive = powerManager.isInteractive,
        status = AppSupervisionObservationStatus.QUERY_FAILED
    )

    private fun ForegroundObservation.toAppObservationStatus() = when (status) {
        ForegroundObservationStatus.AVAILABLE -> AppSupervisionObservationStatus.AVAILABLE
        ForegroundObservationStatus.ACCESS_DENIED -> AppSupervisionObservationStatus.ACCESS_DENIED
        ForegroundObservationStatus.QUERY_FAILED -> AppSupervisionObservationStatus.QUERY_FAILED
        ForegroundObservationStatus.TIMEOUT -> AppSupervisionObservationStatus.TIMEOUT
        ForegroundObservationStatus.CIRCUIT_OPEN -> AppSupervisionObservationStatus.CIRCUIT_OPEN
    }

    private fun AppSupervisionEnforcementState.retainActiveRule(
        activeRules: List<AppSupervisionRule>
    ): AppSupervisionEnforcementState {
        val block = trustedBlock ?: return this
        return if (activeRules.any {
            block.rule.planId == it.planId &&
                block.rule.planUpdatedAtEpochMillis == it.planUpdatedAtEpochMillis &&
                block.rule.packageName == it.packageName &&
                block.rule.occurrenceEndEpochMillis == it.occurrenceEndEpochMillis
        }) {
            this
        } else {
            AppSupervisionEnforcementState()
        }
    }

    private fun formatSeconds(seconds: Int): String = String.format(
        java.util.Locale.US,
        "%02d:%02d",
        seconds.coerceAtLeast(0) / 60,
        seconds.coerceAtLeast(0) % 60
    )

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun hasActiveCommitmentWindow(
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean = CommitmentRuntimePolicy.hasActiveWindow(commitmentSnapshot, nowEpochMillis)

    private fun hasRuntimeWork(
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean = hasRuntimeRules() || hasActiveCommitmentWindow(nowEpochMillis)

    private fun hasRuntimeRules(): Boolean = rules.isNotEmpty() || triggerRules.isNotEmpty()

    private fun markCommitmentActivatedIfNeeded(planId: String) {
        val block = currentCommitmentBlock ?: return
        if (planId != "commitment:${block.occurrenceId}") return
        val occurrence = commitmentSnapshot.openOccurrences
            .firstOrNull { item -> item.id == block.occurrenceId }
            ?: return
        if (occurrence.activatedAtEpochMillis != null || !activationWritesInFlight.add(occurrence.id)) {
            return
        }
        serviceScope.launch {
            try {
                todoRepository.markCommitmentOccurrenceActivated(occurrence.id)
            } finally {
                handler.post { activationWritesInFlight.remove(occurrence.id) }
            }
        }
    }

    private fun releaseInitializedResources() {
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::overlayController.isInitialized && ::mediaPlaybackController.isInitialized) {
            hideBlockedOverlay()
        }
        if (::callStateMonitor.isInitialized) callStateMonitor.stop()
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: RuntimeException) {
                // Receiver 已由系统移除。
            }
            receiverRegistered = false
        }
        if (systemNavigationReceiverRegistered) {
            try {
                unregisterReceiver(systemNavigationReceiver)
            } catch (_: RuntimeException) {
                // Receiver 已由系统移除。
            }
            systemNavigationReceiverRegistered = false
        }
        if (::observationWorker.isInitialized) {
            try {
                observationWorker.close()
            } catch (_: RuntimeException) {
                // 查询线程清理失败不阻止 Service 销毁。
            }
        }
        if (::snapshotWriter.isInitialized) snapshotWriter.close()
        if (::persistenceExecutor.isInitialized) persistenceExecutor.shutdown()
        if (::protectedPackageExecutor.isInitialized) protectedPackageExecutor.shutdownNow()
    }

    override fun onDestroy() {
        val shouldRetry = AppSupervisionServiceRecoveryPolicy.shouldScheduleRetry(
            startupFailed = false,
            isStoppingIntentionally = isStoppingIntentionally,
            activeRuleCount = rules.size + triggerRules.size,
            hasActiveCommitment = hasActiveCommitmentWindow()
        )
        if (shouldRetry && ::snapshotWriter.isInitialized) {
            // 系统回收服务前保留最新运行态；writer.close 会排空该快照后再结束线程。
            if (hasRuntimeRules()) persistSnapshot(force = true)
            // START_STICKY 在部分厂商系统上并不可靠，闹钟重试提供独立恢复入口。
            scheduleRetry()
            if (hasActiveCommitmentWindow()) scheduleCommitmentRetry()
        }
        releaseInitializedResources()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
