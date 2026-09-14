package com.example.controlfree

import android.app.NotificationManager
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.AllowlistRepository
import com.example.controlfree.data.LockUnlockFeaturePreferences
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.growth.GrowthRepository
import com.example.controlfree.ai.AiInterventionCoordinator
import com.example.controlfree.ai.AiInterventionRequest
import com.example.controlfree.ai.AiChatRequest
import com.example.controlfree.ai.AiChatMessage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.example.controlfree.sensor.EyeCareSensorManager
import com.example.controlfree.sensor.EyeDistanceStatus
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import com.example.controlfree.knowledge.KnowledgeChallengeBinding
import com.example.controlfree.knowledge.KnowledgeChallengeCoordinator
import com.example.controlfree.knowledge.KnowledgeChallengeSession
import com.example.controlfree.knowledge.KnowledgeChallengeSubmissionResult
import com.example.controlfree.knowledge.KnowledgeRoundReview
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.security.PatternLockView
import com.example.controlfree.security.VerificationResult
import com.example.controlfree.security.VerificationStatus
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.theme.ControlFreeTheme
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.ui.main.NumericPasswordPad
import com.example.controlfree.ui.main.NumericPasswordEdit
import com.example.controlfree.ui.main.NumericPasswordInputBuffer
import com.example.controlfree.ui.main.numericPasswordInputLimit
import com.example.controlfree.ui.layout.rememberResponsiveLayoutSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val KNOWLEDGE_CHALLENGE_VISIBILITY_HEARTBEAT_MILLIS = 2_000L
private const val LOCK_TRUTH_SYNC_INTERVAL_MILLIS = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private val LOCK_DATE_TIME_FORMATTER =
    DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.SIMPLIFIED_CHINESE)

/** 服务失联期间催醒监督的节流间隔，避免每秒心跳都触发一次前台服务启动。 */
private const val MONITOR_RECOVERY_REQUEST_INTERVAL_MILLIS = 10_000L

internal fun shouldResetLockActivityUi(
    currentSessionId: Long,
    incomingSessionId: Long
): Boolean = incomingSessionId != NO_LOCK_SESSION && incomingSessionId != currentSessionId

class LockActivity : ComponentActivity() {
    companion object {
        const val ACTION_UNLOCK = "com.example.controlfree.ACTION_UNLOCK"
        const val ACTION_DISMISS_ALL_LOCK_SURFACES =
            "com.example.controlfree.ACTION_DISMISS_ALL_LOCK_SURFACES"
        const val EXTRA_OPEN_QUICK_NOTE = "com.example.controlfree.EXTRA_OPEN_QUICK_NOTE"
    }

    private var remainingSecondsState = mutableIntStateOf(0)
    private var sessionModeState = mutableStateOf(MonitorSessionMode.SUPERVISION)
    private var taskTitleState = mutableStateOf<String?>(null)
    private lateinit var lockTruthRepository: LockTruthRepository
    private lateinit var credentials: CredentialStore
    private lateinit var allowlistRepository: AllowlistRepository
    private lateinit var authenticationExecutor: LockAuthenticationExecutor
    private lateinit var knowledgeChallengeCoordinator: KnowledgeChallengeCoordinator
    private var aiInterventionCoordinator: AiInterventionCoordinator? = null
    private var overlayPermissionAvailableState = mutableStateOf(false)
    private val incomingInterventionAction = mutableStateOf<LockPendingAction?>(null)
    private val incomingOpenChat = mutableStateOf<Boolean>(false)
    private val incomingOpenQuickNote = mutableStateOf<Boolean>(false)
    private val sessionResetTrigger = mutableStateOf(0)
    private lateinit var lockUnlockFeaturePreferences: LockUnlockFeaturePreferences
    private var growthUnlockEnabledState = mutableStateOf(true)
    private var knowledgeChallengeEnabledState = mutableStateOf(true)
    private var authenticationGenerationState = mutableIntStateOf(0)
    private var lockSessionIdState = mutableLongStateOf(NO_LOCK_SESSION)
    private var lockSessionId: Long
        get() = lockSessionIdState.longValue
        set(value) {
            if (
                lockSessionIdState.longValue != value &&
                ::authenticationExecutor.isInitialized
            ) {
                invalidateAuthenticationSession()
            }
            lockSessionIdState.longValue = value
        }
    private var pendingVerifiedActionHandoff: VerifiedActionHandoff? = null
    private var requestedKnowledgeActionState = mutableStateOf<LockPendingAction?>(null)
    private var overlayHandoffToken: String? = null
    private var isActivityResumed = false
    private var isActivityResumedState = mutableStateOf(false)
    private var lastMonitorRecoveryRequestElapsedMillis = 0L
    private val activityInstanceToken =
        "${SystemClock.elapsedRealtimeNanos()}:${System.identityHashCode(this)}"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DISMISS_ALL_LOCK_SURFACES -> finish()
                ACTION_UNLOCK -> {
                    val incomingSessionId = intent.getLongExtra(
                        MonitorService.EXTRA_LOCK_SESSION_ID,
                        NO_LOCK_SESSION
                    )
                    if (
                        lockSessionId == NO_LOCK_SESSION ||
                        incomingSessionId == lockSessionId
                    ) {
                        finish()
                    } else {
                        // 会话不匹配说明本界面可能已过期：向锁定真值仲裁，
                        // 锁定确已结束则同样关闭，避免残留僵尸锁屏。
                        refreshRemainingTime()
                    }
                }
                MonitorService.ACTION_TIMER_TICK -> {
                    val incomingSessionId = intent.getLongExtra(
                        MonitorService.EXTRA_LOCK_SESSION_ID,
                        NO_LOCK_SESSION
                    )
                    if (
                        lockSessionId != NO_LOCK_SESSION &&
                        incomingSessionId != lockSessionId &&
                        !RuntimeLockTruthRegistry.isCurrentSession(incomingSessionId)
                    ) {
                        return
                    }
                    if (incomingSessionId != NO_LOCK_SESSION) {
                        val previousSessionId = lockSessionId
                        lockSessionId = incomingSessionId
                        if (incomingSessionId != previousSessionId && isActivityResumed) {
                            // 会话轮换经计时广播收敛后重发可见性，服务据此撤下叠加的悬浮层。
                            publishVisibility(true)
                        }
                    }
                    intent.getStringExtra(MonitorService.EXTRA_SESSION_MODE)?.let { storedMode ->
                        sessionModeState.value = MonitorSessionMode.fromStoredValue(storedMode)
                    }
                    if (intent.hasExtra(MonitorService.EXTRA_LOCK_TASK_TITLE)) {
                        taskTitleState.value =
                            intent.getStringExtra(MonitorService.EXTRA_LOCK_TASK_TITLE)
                    }
                    val state = intent.getStringExtra(MonitorService.EXTRA_STATE)
                    if (state == MonitorService.STATE_LOCK) {
                        remainingSecondsState.intValue =
                            intent.getIntExtra(MonitorService.EXTRA_REMAINING_SECONDS, 0)
                    } else {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        lockTruthRepository = LockTruthRepository(this)
        lockSessionId = intent.getLongExtra(
            MonitorService.EXTRA_LOCK_SESSION_ID,
            NO_LOCK_SESSION
        )
        overlayHandoffToken = LockActivityHandoffContract.readToken(intent)
        sessionModeState.value = intent.getStringExtra(MonitorService.EXTRA_SESSION_MODE)
            ?.let { storedMode -> MonitorSessionMode.fromStoredValue(storedMode) }
            ?: PreferenceManager(applicationContext).getMonitorSessionMode()
        taskTitleState.value = intent.getStringExtra(MonitorService.EXTRA_LOCK_TASK_TITLE)
        pendingVerifiedActionHandoff = consumeVerifiedActionHandoff(intent, lockSessionId)
        requestedKnowledgeActionState.value =
            LockKnowledgeChallengeContract.consumeRequest(intent)
        credentials = CredentialStore(this)
        lockUnlockFeaturePreferences = LockUnlockFeaturePreferences(applicationContext)
        refreshLockUnlockFeatures()
        allowlistRepository = AllowlistRepository.get(applicationContext)
        authenticationExecutor = LockAuthenticationExecutor(ContextCompat.getMainExecutor(this))
        knowledgeChallengeCoordinator =
            KnowledgeChallengeCoordinator.getInstance(applicationContext)
        aiInterventionCoordinator = try {
            AiInterventionCoordinator.getInstance(applicationContext)
        } catch (_: RuntimeException) {
            null
        }
        overlayPermissionAvailableState.value = Settings.canDrawOverlays(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        configureBlackEdgeToEdge(hideSystemBars = true)
        registerInternalReceiver()
        if (!refreshRemainingTime()) return
        startLockTruthSyncLoop()
        handleInterventionIntent(intent)

        setContent {
            ControlFreeTheme(darkTheme = true) {
                val allowlistState by allowlistRepository.state.collectAsState()
                LaunchedEffect(allowlistRepository) {
                    allowlistRepository.refresh()
                }
                key(lockSessionIdState.longValue, sessionResetTrigger.value) {
                    LockScreenContent(
                        remainingSeconds = remainingSecondsState.intValue,
                        sessionMode = sessionModeState.value,
                        taskTitle = taskTitleState.value,
                        lockSessionId = lockSessionIdState.longValue,
                        allowedApps = allowlistState.snapshot?.allowedApps.orEmpty(),
                        hasGesture = credentials.hasGesture(),
                        hasPassword = credentials.hasPassword(),
                        passwordLength = credentials.getPasswordLength(),
                        authenticationGeneration = authenticationGenerationState.intValue,
                        onPasswordAttempt = { request, password, onResult ->
                            submitCredentialVerification(
                                request = request,
                                verification = { credentials.verifyPassword(password) },
                                onResult = onResult
                            )
                        },
                        onGestureAttempt = { request, pattern, onResult ->
                            submitCredentialVerification(
                                request = request,
                                verification = { credentials.verifyGesture(pattern) },
                                onResult = onResult
                            )
                        },
                        onDirectUnlock = { request ->
                            dispatchVerifiedAction(
                                VerifiedActionHandoff(
                                    request = request,
                                    lockSessionId = lockSessionIdState.longValue
                                )
                            )
                        },
                        incomingInterventionAction = incomingInterventionAction.value,
                        incomingOpenChat = incomingOpenChat.value,
                        incomingOpenQuickNote = incomingOpenQuickNote.value,
                        onIncomingQuickNoteConsumed = { incomingOpenQuickNote.value = false },
                        onIncomingInterventionConsumed = {
                            incomingInterventionAction.value = null
                            incomingOpenChat.value = false
                        },
                        onAuthenticationSessionReset = ::invalidateAuthenticationSession,
                        isSurfaceActive = isActivityResumedState.value,
                        isAiInterventionEnabled = {
                            try {
                                aiInterventionCoordinator?.isEnabled() == true
                            } catch (_: RuntimeException) {
                                false
                            }
                        },
                        requestAiAdvice = { request ->
                            aiInterventionCoordinator?.getAdvice(request)?.message.orEmpty()
                        },
                        requestedKnowledgeAction = requestedKnowledgeActionState.value,
                        onRequestedKnowledgeActionConsumed = {
                            requestedKnowledgeActionState.value = null
                        },
                        knowledgeChallengeCoordinator = knowledgeChallengeCoordinator,
                        growthUnlockEnabled = growthUnlockEnabledState.value,
                        knowledgeChallengeEnabled = knowledgeChallengeEnabledState.value,
                        onKnowledgeChallengeVisibilityChange =
                            ::publishKnowledgeChallengeVisibility,
                        onArmSelfDiscipline = ::armSelfDiscipline,
                        onAllowedAppRequest = ::openAllowedApp,
                        isOverlayPermissionGranted = overlayPermissionAvailableState.value,
                        onRestoreOverlayPermission = ::openOverlayPermissionSettings
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        isActivityResumedState.value = true
        overlayPermissionAvailableState.value = Settings.canDrawOverlays(this)
        refreshLockUnlockFeatures()
        publishOverlaySettingsVisibility(false)
        if (::lockTruthRepository.isInitialized && refreshRemainingTime()) {
            publishVisibility(true)
            completePendingVerifiedActionHandoff()
        }
    }

    override fun onPause() {
        isActivityResumed = false
        isActivityResumedState.value = false
        super.onPause()
    }

    override fun onStop() {
        publishKnowledgeChallengeVisibility(false)
        publishVisibility(false)
        invalidateAuthenticationSession()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        setIntent(intent)
        val incomingSessionId = intent.getLongExtra(
            MonitorService.EXTRA_LOCK_SESSION_ID,
            NO_LOCK_SESSION
        )
        val sessionChanged = shouldResetLockActivityUi(lockSessionId, incomingSessionId)
        if (sessionChanged) {
            // 只有真正轮换锁定会话时才丢弃子界面；Home 导航后的同会话重入应保留
            // 暂停、跳过、白名单或闪记页面，避免用户操作被无声重置。
            sessionResetTrigger.value = sessionResetTrigger.value + 1
            incomingInterventionAction.value = null
            incomingOpenChat.value = false
            incomingOpenQuickNote.value = false
            requestedKnowledgeActionState.value = null
            pendingVerifiedActionHandoff = null
            overlayHandoffToken = null
        }
        if (incomingSessionId != NO_LOCK_SESSION) lockSessionId = incomingSessionId
        // 只有带令牌的 intent 才更新交接凭证。服务常规重拉锁屏用的 intent 不带令牌，
        // 若无条件覆盖，正在进行的悬浮层交接就会失去凭证，随后上报可见时校验不过，
        // 本界面会被服务当成意外双层直接关掉。
        LockActivityHandoffContract.readToken(intent)?.let { token ->
            overlayHandoffToken = token
        }
        consumeVerifiedActionHandoff(intent, lockSessionId)?.let { handoff ->
            pendingVerifiedActionHandoff = handoff
        }
        LockKnowledgeChallengeContract.consumeRequest(intent)?.let { action ->
            requestedKnowledgeActionState.value = action
        }
        intent.getIntExtra(MonitorService.EXTRA_REMAINING_SECONDS, -1)
            .takeIf { it >= 0 }
            ?.let { remainingSecondsState.intValue = it }
        intent.getStringExtra(MonitorService.EXTRA_SESSION_MODE)?.let { storedMode ->
            sessionModeState.value = MonitorSessionMode.fromStoredValue(storedMode)
        }
        if (intent.hasExtra(MonitorService.EXTRA_LOCK_TASK_TITLE)) {
            taskTitleState.value = intent.getStringExtra(MonitorService.EXTRA_LOCK_TASK_TITLE)
        }
        if (refreshRemainingTime() && isActivityResumed) {
            publishVisibility(true)
            completePendingVerifiedActionHandoff()
        }
        handleInterventionIntent(intent)
    }

    private fun registerInternalReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UNLOCK)
            addAction(ACTION_DISMISS_ALL_LOCK_SURFACES)
            addAction(MonitorService.ACTION_TIMER_TICK)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun openAllowedApp(packageName: String) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_OPEN_ALLOWED_APP
                    putExtra(MonitorService.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
                    putExtra(MonitorService.EXTRA_FROM_LOCK_ACTIVITY, true)
                    putExtra(
                        MonitorService.EXTRA_COMMAND_ELAPSED_MILLIS,
                        SystemClock.elapsedRealtime()
                    )
                }
            )
        } catch (_: RuntimeException) {
            Toast.makeText(this, "暂时无法打开白名单 App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun armSelfDiscipline(): Boolean =
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_ARM_SELF_DISCIPLINE
                    putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
                    putExtra(
                        MonitorService.EXTRA_COMMAND_ELAPSED_MILLIS,
                        SystemClock.elapsedRealtime()
                    )
                }
            )
            true
        } catch (_: RuntimeException) {
            Toast.makeText(this, "暂时无法记录继续自律承诺", Toast.LENGTH_SHORT).show()
            false
        }

    private fun openOverlayPermissionSettings() {
        try {
            publishOverlaySettingsVisibility(true)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: RuntimeException) {
            Toast.makeText(this, "无法打开悬浮窗设置，请在系统设置中手动开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun publishOverlaySettingsVisibility(visible: Boolean) {
        sendBroadcast(Intent(MonitorService.ACTION_OVERLAY_PERMISSION_SETTINGS_OPENED).apply {
            setPackage(packageName)
            putExtra(MonitorService.EXTRA_VISIBLE, visible)
            putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
        })
    }

    private fun dispatchVerifiedAction(handoff: VerifiedActionHandoff): Boolean =
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java).apply {
                    action = handoff.request.action.serviceAction
                    putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, handoff.lockSessionId)
                    putExtra(
                        MonitorService.EXTRA_COMMAND_ELAPSED_MILLIS,
                        SystemClock.elapsedRealtime()
                    )
                    LockActionContract.writeServiceExtras(this, handoff.request)
                }
            )
            true
        } catch (_: RuntimeException) {
            Toast.makeText(this, "操作暂时无法执行，请稍后重试", Toast.LENGTH_LONG).show()
            false
        }

    private fun handleInterventionIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_QUICK_NOTE, false)) {
            intent.removeExtra(EXTRA_OPEN_QUICK_NOTE)
            incomingOpenQuickNote.value = true
        }
        LockInterventionContract.consumeRequest(intent)?.let { request ->
            incomingInterventionAction.value = request.action
            incomingOpenChat.value = request.openChat
        }
    }

    private fun consumeVerifiedActionHandoff(
        intent: Intent,
        sessionId: Long
    ): VerifiedActionHandoff? =
        LockActionContract.consumeAuthorizedVerifiedHandoff(intent)?.let { request ->
            VerifiedActionHandoff(request, sessionId)
    }

    private fun completePendingVerifiedActionHandoff() {
        val handoff = pendingVerifiedActionHandoff ?: return

        // 先消费一次性动作，再向服务投递，避免 onResume/onNewIntent 重入造成重复执行。
        pendingVerifiedActionHandoff = null
        dispatchVerifiedAction(handoff)
    }

    private fun submitCredentialVerification(
        request: AuthorizedLockAction,
        verification: () -> VerificationResult,
        onResult: (VerificationResult) -> Unit
    ): Boolean {
        val handoff = VerifiedActionHandoff(request, lockSessionId)
        return authenticationExecutor.submit(
            task = verification,
            onResult = { executionResult ->
                val verified = executionResult.getOrElse {
                    VerificationResult(VerificationStatus.FAILURE)
                }
                val finalResult = if (verified.isSuccess && !dispatchVerifiedAction(handoff)) {
                    VerificationResult(VerificationStatus.FAILURE)
                } else {
                    verified
                }
                onResult(finalResult)
            }
        )
    }

    private fun invalidateAuthenticationSession() {
        if (::authenticationExecutor.isInitialized) authenticationExecutor.invalidate()
        authenticationGenerationState.intValue =
            if (authenticationGenerationState.intValue == Int.MAX_VALUE) {
                1
            } else {
                authenticationGenerationState.intValue + 1
            }
    }

    /**
     * 锁定真值自愈心跳：界面可见期间每秒与 LockTruthRepository 对齐一次。
     * 广播因会话轮换、进程重建等原因丢失时，这里保证倒计时恢复刷新、
     * 过期会话被采纳为当前会话（按钮随之恢复可用）、该解锁时自动关闭。
     */
    private fun startLockTruthSyncLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(LOCK_TRUTH_SYNC_INTERVAL_MILLIS)
                    if (!refreshRemainingTime()) break
                }
            }
        }
    }

    private fun refreshRemainingTime(): Boolean {
        val decision = lockTruthRepository.resolve(lockSessionId)
        if (!decision.shouldStayLocked) {
            cancelStaleLockRecoveryNotification()
            finish()
            return false
        }
        val previousSessionId = lockSessionId
        // 只采纳仲裁结果，绝不自行注册全局会话。伪造出来的会话服务并不认识：
        // 它会让交接令牌校验失败（服务转而重新盖上悬浮层并关掉本界面），
        // 也会让暂停、跳过命令因会话不符而被静默丢弃——正是"点了没反应"的来源。
        if (decision.sessionId != NO_LOCK_SESSION) lockSessionId = decision.sessionId
        if (lockSessionId != previousSessionId && isActivityResumed) {
            // 采纳新会话后立即重发可见性：服务按会话号识别命令，若继续沉默，
            // 服务会误判当前会话没有前台锁定界面而叠加悬浮层。
            publishVisibility(true)
        }
        if (decision.source != LockTruthSource.LIVE_RUNTIME) {
            // 兜底必须是自愈的，不能只是干等：主动催醒服务，让真值重新回到实时轨道。
            requestMonitorRecovery()
        }
        // 剩余时间每秒由 resolve 重新推导（实时真值、失联衰减或磁盘投影）。
        // 此处不得回写旧值：那会让服务一停摆倒计时就永久冻结。
        remainingSecondsState.intValue = decision.remainingSeconds
        return true
    }

    private fun requestMonitorRecovery() {
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (
            lastMonitorRecoveryRequestElapsedMillis != 0L &&
            nowElapsedMillis - lastMonitorRecoveryRequestElapsedMillis <
            MONITOR_RECOVERY_REQUEST_INTERVAL_MILLIS
        ) {
            return
        }
        lastMonitorRecoveryRequestElapsedMillis = nowElapsedMillis
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_RECONCILE_MONITOR_PHASE
                }
            )
        } catch (_: RuntimeException) {
            // 阶段边界闹钟与开机恢复入口仍会再次尝试拉起监督。
        }
    }

    private fun cancelStaleLockRecoveryNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .cancel(MonitorService.FALLBACK_NOTIFICATION_ID)
        } catch (_: RuntimeException) {
            // 通知已不存在或系统服务暂不可用时无需阻止锁定真值收敛。
        }
    }

    private fun publishVisibility(visible: Boolean) {
        sendBroadcast(Intent(MonitorService.ACTION_LOCK_ACTIVITY_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(MonitorService.EXTRA_VISIBLE, visible)
            putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
            putExtra(
                MonitorService.EXTRA_LOCK_ACTIVITY_INSTANCE_TOKEN,
                activityInstanceToken
            )
            putExtra(
                MonitorService.EXTRA_VISIBILITY_EVENT_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos()
            )
            overlayHandoffToken?.let { token ->
                LockActivityHandoffContract.writeToken(this, token)
            }
        })
    }

    private fun publishKnowledgeChallengeVisibility(visible: Boolean) {
        sendBroadcast(Intent(MonitorService.ACTION_KNOWLEDGE_CHALLENGE_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(MonitorService.EXTRA_VISIBLE, visible)
            putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
            putExtra(
                MonitorService.EXTRA_VISIBILITY_EVENT_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos()
            )
        })
    }

    private fun refreshLockUnlockFeatures() {
        if (!::lockUnlockFeaturePreferences.isInitialized) return
        growthUnlockEnabledState.value = lockUnlockFeaturePreferences.growthUnlockEnabled
        knowledgeChallengeEnabledState.value =
            lockUnlockFeaturePreferences.knowledgeChallengeEnabled
    }

    override fun onDestroy() {
        if (::authenticationExecutor.isInitialized) authenticationExecutor.close()
        try {
            unregisterReceiver(receiver)
        } catch (_: RuntimeException) {
            // Receiver 可能尚未注册或已被系统回收。
        }
        super.onDestroy()
    }

    private data class VerifiedActionHandoff(
        val request: AuthorizedLockAction,
        val lockSessionId: Long
    )
}

private enum class LockScreenMode {
    LOCK,
    KNOWLEDGE_CHALLENGE,
    ALLOWLIST,
    PASSWORD,
    GESTURE
}

@Composable
private fun LockScreenContent(
    remainingSeconds: Int,
    sessionMode: MonitorSessionMode,
    taskTitle: String?,
    lockSessionId: Long,
    allowedApps: List<AllowedApp>,
    hasGesture: Boolean,
    hasPassword: Boolean,
    passwordLength: Int?,
    authenticationGeneration: Int,
    onPasswordAttempt: (AuthorizedLockAction, String, (VerificationResult) -> Unit) -> Boolean,
    onGestureAttempt: (AuthorizedLockAction, List<Int>, (VerificationResult) -> Unit) -> Boolean,
    onDirectUnlock: (AuthorizedLockAction) -> Unit,
    onAuthenticationSessionReset: () -> Unit,
    isSurfaceActive: Boolean,
    isAiInterventionEnabled: () -> Boolean,
    requestAiAdvice: suspend (AiInterventionRequest) -> String,
    requestedKnowledgeAction: LockPendingAction?,
    onRequestedKnowledgeActionConsumed: () -> Unit,
    knowledgeChallengeCoordinator: KnowledgeChallengeCoordinator,
    growthUnlockEnabled: Boolean,
    knowledgeChallengeEnabled: Boolean,
    onKnowledgeChallengeVisibilityChange: (Boolean) -> Unit,
    onArmSelfDiscipline: () -> Boolean,
    onAllowedAppRequest: (String) -> Unit,
    isOverlayPermissionGranted: Boolean,
    onRestoreOverlayPermission: () -> Unit,
    incomingInterventionAction: LockPendingAction?,
    incomingOpenChat: Boolean,
    incomingOpenQuickNote: Boolean,
    onIncomingQuickNoteConsumed: () -> Unit,
    onIncomingInterventionConsumed: () -> Unit
) {
    val context = LocalContext.current
    val growthRepository = remember(context) {
        GrowthRepository.getInstance(context.applicationContext)
    }
    val growthAccount by remember(growthRepository) {
        growthRepository.observeAccount()
    }.collectAsState(initial = null)
    val currentPetProfile = remember(growthAccount?.levelProgress?.stage) {
        petStageProfile(growthAccount?.levelProgress?.stage)
    }
    val companionPrefs = remember(context) {
        com.example.controlfree.ai.AiCompanionPreferences.getInstance(context.applicationContext)
    }
    val isLockChatEnabled = remember(companionPrefs) { companionPrefs.isLockChatEnabled() }
    val petPersonality = remember(companionPrefs) { companionPrefs.getPetPersonality() }
    val chatCoordinator = remember(context) {
        com.example.controlfree.ai.AiInterventionCoordinator.getInstance(context.applicationContext)
    }
    var mode by remember { mutableStateOf(LockScreenMode.LOCK) }
    var showQuickNoteDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<AuthorizedLockAction?>(null) }
    var interventionRequestSerial by remember { mutableLongStateOf(0L) }
    var interventionState by remember { mutableStateOf<LockPetInterventionUiState?>(null) }
    var knowledgeAction by remember { mutableStateOf<LockPendingAction?>(null) }
    var knowledgeSession by remember { mutableStateOf<KnowledgeChallengeSession?>(null) }
    var knowledgeRequestSerial by remember { mutableLongStateOf(0L) }
    var knowledgeLoading by remember { mutableStateOf(false) }
    var knowledgeSubmitting by remember { mutableStateOf(false) }
    var knowledgeStatusMessage by remember { mutableStateOf<String?>(null) }
    var knowledgeRoundReview by remember { mutableStateOf<KnowledgeRoundReview?>(null) }
    var pendingKnowledgeUnlock by remember { mutableStateOf<AuthorizedLockAction?>(null) }
    val screenScope = rememberCoroutineScope()

    val isChatOpen = interventionState?.isChatPanelOpen == true
    DisposableEffect(isSurfaceActive, isChatOpen) {
        val sensorManager = if (isSurfaceActive && isChatOpen) {
            com.example.controlfree.sensor.EyeCareSensorManager(context.applicationContext) { status ->
                interventionState = interventionState?.copy(eyeDistanceStatus = status)
            }.apply { start() }
        } else {
            null
        }
        onDispose {
            sensorManager?.stop()
        }
    }

    val handleSendMessage: (String) -> Unit = { text ->
        val currentState = interventionState
        if (currentState != null) {
            val userMsg = com.example.controlfree.ai.AiChatMessage("user", text)
            val updatedHistory = currentState.chatHistory + userMsg
            interventionState = currentState.copy(
                chatHistory = updatedHistory,
                isWaitingResponse = true
            )
            screenScope.launch {
                val startTime = System.currentTimeMillis()
                val reply = try {
                    val req = com.example.controlfree.ai.AiChatRequest(
                        history = updatedHistory,
                        personality = petPersonality,
                        remainingSeconds = remainingSeconds,
                        eyeDistanceStatus = currentState.eyeDistanceStatus
                    )
                    chatCoordinator.getChatResponse(req)
                } catch (_: Exception) {
                    null
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 400L) {
                    delay(400L - elapsed)
                }
                val assistantMsg = com.example.controlfree.ai.AiChatMessage(
                    role = "assistant",
                    content = reply?.message ?: "小芽现在有点开小差呢，等会儿再聊好不好呀？"
                )

                interventionState = interventionState?.let { current ->
                    if (current.requestId == currentState.requestId) {
                        current.copy(
                            chatHistory = current.chatHistory + assistantMsg,
                            isWaitingResponse = false
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun returnToLock() {
        val abandonedBinding = knowledgeSession?.binding ?: knowledgeAction?.let { action ->
            KnowledgeChallengeBinding(lockSessionId, action.kind)
        }
        onAuthenticationSessionReset()
        interventionState = null
        pendingAction = null
        knowledgeAction = null
        knowledgeSession = null
        knowledgeLoading = false
        knowledgeSubmitting = false
        knowledgeStatusMessage = null
        knowledgeRoundReview = null
        pendingKnowledgeUnlock = null
        mode = LockScreenMode.LOCK
        if (abandonedBinding != null) {
            screenScope.launch {
                runCatching { knowledgeChallengeCoordinator.clearChallenge(abandonedBinding) }
            }
        }
    }

    fun beginAuthentication(request: AuthorizedLockAction) {
        onAuthenticationSessionReset()
        interventionState = null
        pendingAction = request
        knowledgeAction = null
        knowledgeSession = null
        knowledgeRoundReview = null
        pendingKnowledgeUnlock = null
        mode = if (hasGesture) LockScreenMode.GESTURE else LockScreenMode.PASSWORD
    }

    fun beginKnowledgeChallenge(action: LockPendingAction) {
        if (!knowledgeChallengeEnabled) return
        onAuthenticationSessionReset()
        pendingAction = null
        knowledgeAction = action
        knowledgeSession = null
        knowledgeStatusMessage = null
        knowledgeRoundReview = null
        pendingKnowledgeUnlock = null
        knowledgeSubmitting = false
        knowledgeLoading = true
        knowledgeRequestSerial = if (knowledgeRequestSerial == Long.MAX_VALUE) {
            1L
        } else {
            knowledgeRequestSerial + 1L
        }
        mode = LockScreenMode.KNOWLEDGE_CHALLENGE
    }

    LaunchedEffect(mode, lockSessionId, isSurfaceActive) {
        val challengeVisible =
            mode == LockScreenMode.KNOWLEDGE_CHALLENGE && isSurfaceActive
        onKnowledgeChallengeVisibilityChange(challengeVisible)
        if (challengeVisible) {
            while (true) {
                delay(KNOWLEDGE_CHALLENGE_VISIBILITY_HEARTBEAT_MILLIS)
                onKnowledgeChallengeVisibilityChange(true)
            }
        }
    }
    DisposableEffect(lockSessionId) {
        onDispose { onKnowledgeChallengeVisibilityChange(false) }
    }

    fun beginIntervention(action: LockPendingAction) {
        val aiAdviceRequested = try {
            shouldRequestLockPetAiAdvice(isAiInterventionEnabled())
        } catch (_: RuntimeException) {
            false
        }
        onAuthenticationSessionReset()
        interventionRequestSerial = if (interventionRequestSerial == Long.MAX_VALUE) {
            1L
        } else {
            interventionRequestSerial + 1L
        }
        interventionState = initialLockPetInterventionUiState(
            requestId = interventionRequestSerial,
            lockSessionId = lockSessionId,
            action = action,
            sessionMode = sessionMode,
            remainingSeconds = remainingSeconds,
            requestAiAdvice = aiAdviceRequested
        )
        mode = LockScreenMode.LOCK
    }

    fun switchCredentialMode(newMode: LockScreenMode) {
        if (newMode == mode || pendingAction == null) return
        onAuthenticationSessionReset()
        mode = newMode
    }

    LaunchedEffect(incomingInterventionAction, incomingOpenChat) {
        val action = incomingInterventionAction ?: return@LaunchedEffect
        onIncomingInterventionConsumed()
        beginIntervention(action)
        if (incomingOpenChat) {
            interventionState = interventionState?.copy(isChatPanelOpen = true)
        }
    }

    LaunchedEffect(incomingOpenQuickNote) {
        if (incomingOpenQuickNote) {
            onIncomingQuickNoteConsumed()
            showQuickNoteDialog = true
        }
    }

    LaunchedEffect(requestedKnowledgeAction, lockSessionId) {
        requestedKnowledgeAction?.let { action ->
            onRequestedKnowledgeActionConsumed()
            beginKnowledgeChallenge(action)
        }
    }

    LaunchedEffect(knowledgeRequestSerial, lockSessionId, isSurfaceActive) {
        if (!isSurfaceActive || knowledgeRequestSerial <= 0L) return@LaunchedEffect
        val action = knowledgeAction ?: return@LaunchedEffect
        val requestId = knowledgeRequestSerial
        val prepared = try {
            knowledgeChallengeCoordinator.start(
                KnowledgeChallengeBinding(lockSessionId, action.kind)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (
            requestId == knowledgeRequestSerial &&
            mode == LockScreenMode.KNOWLEDGE_CHALLENGE &&
            knowledgeAction == action
        ) {
            knowledgeSession = prepared
            knowledgeLoading = false
            if (prepared == null) {
                knowledgeStatusMessage = "题库暂时不可用，请返回后使用成长值解锁"
            }
        }
    }

    val activeInterventionRequestId = interventionState?.requestId
    LaunchedEffect(activeInterventionRequestId, lockSessionId, isSurfaceActive) {
        if (!isSurfaceActive) return@LaunchedEffect
        val requestSnapshot = interventionState
            ?.takeIf { state ->
                state.requestId == activeInterventionRequestId &&
                    state.lockSessionId == lockSessionId &&
                    state.isLoading
            }
            ?: return@LaunchedEffect
        val adviceMessage = try {
            requestAiAdvice(
                AiInterventionRequest(
                    actionKind = requestSnapshot.action.kind,
                    requestedPauseMinutes = requestSnapshot.action.pauseMinutes,
                    sessionMode = requestSnapshot.sessionMode,
                    remainingSeconds = requestSnapshot.remainingSeconds,
                    lockSessionId = requestSnapshot.lockSessionId
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        interventionState = interventionState?.let { current ->
            completeLockPetInterventionUiState(
                current = current,
                requestId = requestSnapshot.requestId,
                lockSessionId = requestSnapshot.lockSessionId,
                message = adviceMessage
            )
        }
    }

    BackHandler {
        if (mode != LockScreenMode.LOCK || interventionState != null) returnToLock()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BrandColors.Canvas),
        contentAlignment = Alignment.Center
    ) {
        when (mode) {
            LockScreenMode.LOCK -> LockStatusPanel(
                remainingSeconds = remainingSeconds,
                sessionMode = sessionMode,
                taskTitle = taskTitle,
                interventionState = interventionState,
                petProfile = currentPetProfile,
                onPauseClick = { beginIntervention(LockPendingAction.pause(1)) },
                onSkipClick = { beginIntervention(LockPendingAction.Skip) },
                onAllowlistClick = {
                    returnToLock()
                    mode = LockScreenMode.ALLOWLIST
                },
                onQuickNoteClick = { showQuickNoteDialog = true },
                onPauseMinutesSelected = { minutes ->
                    beginIntervention(LockPendingAction.pause(minutes))
                },
                onContinueSelfDiscipline = {
                    if (onArmSelfDiscipline()) returnToLock()
                },
                onKnowledgeChallengeRequest = if (knowledgeChallengeEnabled) {
                    ::beginKnowledgeChallenge
                } else {
                    null
                },
                onGrowthUnlockRequest = if (growthUnlockEnabled) {
                    { action ->
                        val authAction = AuthorizedLockAction(
                            action = action,
                            authorization = LockActionAuthorization.growthPoints(
                                orderId = LockActionAuthorization.generateOrderIdForAction(action)
                            )
                        )
                        val unlockPrefs = com.example.controlfree.data.LockUnlockFeaturePreferences(context.applicationContext)
                        if (unlockPrefs.requireAuthForGrowthUnlock) {
                            beginAuthentication(authAction)
                        } else {
                            onDirectUnlock(authAction)
                        }
                    }
                } else {
                    null
                },
                isOverlayPermissionGranted = isOverlayPermissionGranted,
                onRestoreOverlayPermission = onRestoreOverlayPermission,
                isLockChatEnabled = isLockChatEnabled,
                onChatPanelToggle = { open ->
                    interventionState = interventionState?.copy(isChatPanelOpen = open)
                },
                onSendMessage = handleSendMessage,
                onRelaxingActiveChanged = { active ->
                    interventionState = interventionState?.copy(eyeRelaxingActive = active)
                }
            )
            LockScreenMode.KNOWLEDGE_CHALLENGE -> KnowledgeChallengePanel(
                session = knowledgeSession,
                isLoading = knowledgeLoading,
                isSubmitting = knowledgeSubmitting,
                statusMessage = knowledgeStatusMessage,
                onSubmit = { selectedOptions ->
                    val session = knowledgeSession ?: return@KnowledgeChallengePanel
                    if (knowledgeSubmitting) return@KnowledgeChallengePanel
                    knowledgeSubmitting = true
                    screenScope.launch {
                        val result = try {
                            knowledgeChallengeCoordinator.submit(
                                session.challengeId,
                                selectedOptions
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            KnowledgeChallengeSubmissionResult.SessionNotFound
                        }
                        knowledgeSubmitting = false
                        when (result) {
                            is KnowledgeChallengeSubmissionResult.NextRound -> {
                                knowledgeSession = result.session
                                knowledgeRoundReview = result.review
                                knowledgeStatusMessage = null
                            }
                            is KnowledgeChallengeSubmissionResult.Passed -> {
                                knowledgeSession = result.session
                                knowledgeRoundReview = result.review
                                val action = knowledgeAction
                                if (action != null) {
                                    pendingKnowledgeUnlock = AuthorizedLockAction(
                                        action = action,
                                        authorization = LockActionAuthorization.knowledgeChallenge(
                                            challengeTokenId = result.pass.tokenId,
                                            orderId = LockActionAuthorization.generateOrderIdForAction(action)
                                        )
                                    )
                                } else {
                                    knowledgeSession = null
                                    knowledgeRoundReview = null
                                    knowledgeStatusMessage = "挑战动作已失效，请返回后重新进入"
                                }
                            }
                            is KnowledgeChallengeSubmissionResult.Failed -> {
                                knowledgeSession = result.session
                                knowledgeRoundReview = result.review
                                pendingKnowledgeUnlock = null
                                knowledgeStatusMessage = null
                            }
                            KnowledgeChallengeSubmissionResult.PassStorageUnavailable -> {
                                knowledgeSession = null
                                knowledgeRoundReview = null
                                pendingKnowledgeUnlock = null
                                knowledgeStatusMessage =
                                    "通关凭证无法安全保存，请返回后重试"
                            }
                            KnowledgeChallengeSubmissionResult.AlreadyFinished,
                            KnowledgeChallengeSubmissionResult.IncompleteAnswers,
                            KnowledgeChallengeSubmissionResult.SessionNotFound -> {
                                knowledgeSession = null
                                knowledgeRoundReview = null
                                pendingKnowledgeUnlock = null
                                knowledgeStatusMessage =
                                    "挑战状态已失效，请返回后重新进入"
                            }
                        }
                    }
                },
                onRetry = ::returnToLock,
                onBack = ::returnToLock,
                roundReview = knowledgeRoundReview,
                onReviewContinue = reviewContinue@ {
                    val reviewedSession = knowledgeSession ?: return@reviewContinue
                    when (reviewedSession.status) {
                        com.example.controlfree.knowledge.KnowledgeChallengeStatus.ACTIVE -> {
                            knowledgeRoundReview = null
                            knowledgeStatusMessage = null
                        }
                        com.example.controlfree.knowledge.KnowledgeChallengeStatus.PASSED -> {
                            val unlockAction = pendingKnowledgeUnlock
                            if (unlockAction == null) {
                                returnToLock()
                                return@reviewContinue
                            }
                            knowledgeRoundReview = null
                            pendingKnowledgeUnlock = null
                            val unlockPrefs =
                                com.example.controlfree.data.LockUnlockFeaturePreferences(
                                    context.applicationContext
                                )
                            if (unlockPrefs.requireAuthForKnowledgeChallenge) {
                                beginAuthentication(unlockAction)
                            } else {
                                onDirectUnlock(unlockAction)
                            }
                        }
                        com.example.controlfree.knowledge.KnowledgeChallengeStatus.FAILED -> {
                            returnToLock()
                        }
                    }
                }
            )
            LockScreenMode.ALLOWLIST -> AllowedAppsPanel(
                allowedApps = allowedApps,
                onBack = ::returnToLock,
                onAllowedAppClick = onAllowedAppRequest
            )
            LockScreenMode.PASSWORD -> pendingAction?.let { request ->
                PasswordPanel(
                    action = request.action,
                    expectedLength = passwordLength,
                    authenticationGeneration = authenticationGeneration,
                    onBack = ::returnToLock,
                    onGesture = if (hasGesture) {
                        { switchCredentialMode(LockScreenMode.GESTURE) }
                    } else {
                        null
                    },
                    onVerify = { password, result ->
                        onPasswordAttempt(request, password, result)
                    }
                )
            }
            LockScreenMode.GESTURE -> pendingAction?.let { request ->
                GesturePanel(
                    action = request.action,
                    authenticationGeneration = authenticationGeneration,
                    onBack = ::returnToLock,
                    onPassword = if (hasPassword) {
                        { switchCredentialMode(LockScreenMode.PASSWORD) }
                    } else {
                        null
                    },
                    onVerify = { pattern, result ->
                        onGestureAttempt(request, pattern, result)
                    }
                )
            }
        }
        if (showQuickNoteDialog) {
            LockQuickNoteDialog(onDismiss = { showQuickNoteDialog = false })
        }
    }
}

@Composable
private fun LockStatusPanel(
    remainingSeconds: Int,
    sessionMode: MonitorSessionMode,
    taskTitle: String?,
    interventionState: LockPetInterventionUiState?,
    petProfile: PetStageProfile,
    onPauseClick: () -> Unit,
    onSkipClick: () -> Unit,
    onAllowlistClick: () -> Unit,
    onQuickNoteClick: () -> Unit,
    onPauseMinutesSelected: (Int) -> Unit,
    onContinueSelfDiscipline: () -> Unit,
    onKnowledgeChallengeRequest: ((LockPendingAction) -> Unit)?,
    onGrowthUnlockRequest: ((LockPendingAction) -> Unit)?,
    isOverlayPermissionGranted: Boolean,
    onRestoreOverlayPermission: () -> Unit,
    isLockChatEnabled: Boolean,
    onChatPanelToggle: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onRelaxingActiveChanged: (Boolean) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                val nowMillis = System.currentTimeMillis()
                currentTimeMillis = nowMillis
                delay(MILLIS_PER_MINUTE - nowMillis % MILLIS_PER_MINUTE)
            }
        }
        val fontScale = LocalConfiguration.current.fontScale
        val screenHeightDp = maxHeight.value.toInt().coerceAtLeast(1)
        val isChatOpen = interventionState?.isChatPanelOpen == true
        val companionMaxHeight = if (isChatOpen) {
            (screenHeightDp - 180).coerceAtLeast(360).dp
        } else {
            resolveLockCompanionMaxHeightDp(
                screenHeightDp = screenHeightDp,
                fontScale = fontScale
            ).dp
        }
        val companionVisible = interventionState != null
        val summaryBottomPadding = if (companionVisible) {
            24.dp
        } else {
            (LOCK_ACTION_BUTTON_SIZE_DP + LOCK_ACTION_BOTTOM_GAP_DP + 36).dp
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .padding(bottom = summaryBottomPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LockStatusSummary(
                    remainingSeconds = remainingSeconds,
                    sessionMode = sessionMode,
                    taskTitle = taskTitle,
                    compactForCompanion = companionVisible
                )
                AnimatedVisibility(
                    visible = companionVisible,
                    enter = fadeIn(tween(if (ValueAnimator.areAnimatorsEnabled()) 180 else 0)) +
                        expandVertically(
                            animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 220 else 0),
                            expandFrom = Alignment.Top
                        ),
                    exit = fadeOut(tween(if (ValueAnimator.areAnimatorsEnabled()) 120 else 0)) +
                        shrinkVertically(
                            animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 140 else 0),
                            shrinkTowards = Alignment.Top
                        ),
                    modifier = Modifier
                        .padding(top = 16.dp)
                ) {
                    interventionState?.let { state ->
                        LockCompanionDock(
                            state = state,
                            petProfile = petProfile,
                            layoutMode = resolveLockCompanionLayoutMode(screenHeightDp, fontScale),
                            isLockChatEnabled = isLockChatEnabled,
                            onChatPanelToggle = onChatPanelToggle,
                            onSendMessage = onSendMessage,
                            onRelaxingActiveChanged = onRelaxingActiveChanged,
                            onPauseMinutesSelected = onPauseMinutesSelected,
                            onContinueSelfDiscipline = onContinueSelfDiscipline,
                            onKnowledgeChallengeRequest = onKnowledgeChallengeRequest,
                            onGrowthUnlockRequest = onGrowthUnlockRequest,
                            onAllowlistClick = onAllowlistClick
                        )
                    }
                }
                if (!isOverlayPermissionGranted) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "悬浮窗权限已关闭，当前使用备用锁屏。",
                        color = BrandColors.Warning,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onRestoreOverlayPermission) {
                        Text("恢复悬浮权限")
                    }
                }
            }
        }
        Text(
            text = formatLockDateTime(currentTimeMillis),
            color = BrandColors.TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        )
        AnimatedVisibility(
            visible = !companionVisible,
            enter = fadeIn(tween(if (ValueAnimator.areAnimatorsEnabled()) 150 else 0)),
            exit = fadeOut(tween(if (ValueAnimator.areAnimatorsEnabled()) 100 else 0)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LockBottomActionBar(
                onPauseClick = onPauseClick,
                onSkipClick = onSkipClick,
                onAllowlistClick = onAllowlistClick,
                onQuickNoteClick = onQuickNoteClick,
                interventionActionsEnabled = true,
                modifier = Modifier
            )
        }
    }
}

@Composable
private fun LockStatusSummary(
    remainingSeconds: Int,
    sessionMode: MonitorSessionMode,
    taskTitle: String?,
    compactForCompanion: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compactForCompanion) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = "锁定",
                tint = Color.Unspecified,
                modifier = Modifier.size(62.dp)
            )
            Spacer(Modifier.height(18.dp))
        }
        Text(
            lockStatusTitle(taskTitle, sessionMode),
            color = BrandColors.TextPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(if (compactForCompanion) 8.dp else 12.dp))
        Text(
            formatTime(remainingSeconds),
            color = BrandColors.TextPrimary,
            fontSize = 54.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            lockCountdownRuleText(sessionMode),
            color = BrandColors.TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LockBottomActionBar(
    onPauseClick: () -> Unit,
    onSkipClick: () -> Unit,
    onAllowlistClick: () -> Unit,
    onQuickNoteClick: () -> Unit,
    interventionActionsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(
                start = LOCK_ACTION_BUTTON_SPACING_DP.dp,
                end = LOCK_ACTION_BUTTON_SPACING_DP.dp,
                bottom = LOCK_ACTION_BOTTOM_GAP_DP.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(LOCK_ACTION_BUTTON_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LockBottomActionButton(
            iconResource = R.drawable.ic_lock_action_pause,
            accessibilityLabel = "暂停监督并选择暂停时长",
            accent = BrandColors.Primary,
            enabled = interventionActionsEnabled,
            onClick = onPauseClick
        )
        LockBottomActionButton(
            iconResource = R.drawable.ic_lock_action_skip,
            accessibilityLabel = "跳过本次监督",
            accent = BrandColors.Warning,
            enabled = interventionActionsEnabled,
            onClick = onSkipClick
        )
        LockBottomActionButton(
            iconResource = R.drawable.ic_lock_action_apps,
            accessibilityLabel = "打开白名单 APP",
            accent = BrandColors.AppAccent,
            onClick = onAllowlistClick
        )
        LockBottomActionButton(
            iconResource = R.drawable.ic_lock_action_note,
            accessibilityLabel = "闪记：快速记录想法",
            accent = BrandColors.Success,
            onClick = onQuickNoteClick
        )
    }
}

@Composable
private fun LockBottomActionButton(
    iconResource: Int,
    accessibilityLabel: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(LOCK_ACTION_BUTTON_SIZE_DP.dp)
            .background(BrandColors.SurfaceRaised, CircleShape)
            .border(1.dp, BrandColors.Outline, CircleShape)
            .semantics { contentDescription = accessibilityLabel }
    ) {
        Icon(
            painter = painterResource(iconResource),
            contentDescription = null,
            tint = accent.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(LOCK_ACTION_ICON_SIZE_DP.dp)
        )
    }
}

/** 锁定期间的闪记速记：与清单·闪记相同的输入框，保存进同一收件箱。 */
@Composable
private fun LockQuickNoteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandColors.SurfaceCard, RoundedCornerShape(24.dp))
                .border(
                    width = 1.dp,
                    color = BrandColors.Outline.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(BrandColors.Primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = BrandColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "闪记",
                        color = BrandColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "随手记录，稍后在清单 · 闪记里整理",
                        color = BrandColors.TextSecondary.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .background(
                        color = if (isFocused) BrandColors.Canvas.copy(alpha = 0.9f) else BrandColors.Canvas.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isFocused) BrandColors.Primary else BrandColors.OutlineSoft,
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(2_000) },
                    enabled = !isSaving,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = BrandColors.TextPrimary,
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    "随手记下此刻的想法...",
                                    fontSize = 13.sp,
                                    color = BrandColors.TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Text(
                    text = "${text.length}/2000",
                    fontSize = 10.sp,
                    color = if (text.length >= 1800) BrandColors.Warning else BrandColors.TextTertiary.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 10.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = BrandColors.TextSecondary)
                ) {
                    Text("取消", fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (isSaving || text.isBlank()) return@Button
                        isSaving = true
                        scope.launch {
                            val saved = runCatching {
                                TodoRepository.getInstance(context.applicationContext)
                                    .addQuickNote(content = text.trim())
                            }.isSuccess
                            isSaving = false
                            if (saved) {
                                Toast.makeText(context, "已存入闪记收件箱", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSaving && text.isNotBlank(),
                    shape = RoundedCornerShape(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandColors.Primary,
                        disabledContainerColor = BrandColors.Primary.copy(alpha = 0.4f),
                        contentColor = BrandColors.OnPrimary,
                        disabledContentColor = BrandColors.OnPrimary.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = BrandColors.OnPrimary
                            )
                        } else {
                            Text("保存", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private class BubbleShape(
    private val cornerRadius: Float,
    private val arrowHeight: Float,
    private val arrowWidth: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            val rectWidth = size.width
            val rectHeight = size.height - arrowHeight

            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = rectWidth,
                    bottom = rectHeight,
                    radiusX = cornerRadius,
                    radiusY = cornerRadius
                )
            )

            val centerX = rectWidth / 2f
            moveTo(centerX - arrowWidth / 2f, rectHeight)
            lineTo(centerX, rectHeight + arrowHeight)
            lineTo(centerX + arrowWidth / 2f, rectHeight)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun LockCompanionDock(
    state: LockPetInterventionUiState,
    petProfile: PetStageProfile,
    layoutMode: LockCompanionLayoutMode,
    isLockChatEnabled: Boolean,
    onChatPanelToggle: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onRelaxingActiveChanged: (Boolean) -> Unit,
    onPauseMinutesSelected: (Int) -> Unit,
    onContinueSelfDiscipline: () -> Unit,
    onKnowledgeChallengeRequest: ((LockPendingAction) -> Unit)?,
    onGrowthUnlockRequest: ((LockPendingAction) -> Unit)?,
    onAllowlistClick: () -> Unit
) {
    var selectedPauseMinutes by remember(state.action.kind) {
        mutableIntStateOf(state.action.pauseMinutes ?: 1)
    }
    var showCustomPause by remember(state.action.kind) { mutableStateOf(false) }
    var touchResponseIndex by remember { mutableIntStateOf(-1) }
    var touchMotionIndex by remember(petProfile.stage) { mutableIntStateOf(-1) }
    var lastPetTouchElapsed by remember { mutableStateOf<Long?>(null) }
    var touchMessage by remember { mutableStateOf<String?>(null) }
    val petRelaxOffsetX = remember { Animatable(0f) }
    val petRelaxOffsetY = remember { Animatable(0f) }
    LaunchedEffect(state.eyeRelaxingActive) {
        if (state.eyeRelaxingActive) {
            val points = listOf(
                Pair(-120f, -300f),
                Pair(120f, -300f),
                Pair(120f, -100f),
                Pair(-120f, -100f),
                Pair(0f, 0f)
            )
            points.forEach { (x, y) ->
                launch {
                    petRelaxOffsetX.animateTo(x, tween(3500, easing = LinearEasing))
                }
                petRelaxOffsetY.animateTo(y, tween(3500, easing = LinearEasing))
            }
            onRelaxingActiveChanged(false)
            onChatPanelToggle(true)
            onSendMessage("（小芽，我刚才已经认真远眺并转动眼睛放松了20秒哦~）")
        } else {
            petRelaxOffsetX.snapTo(0f)
            petRelaxOffsetY.snapTo(0f)
        }
    }
    val animationEnabled = ValueAnimator.areAnimatorsEnabled()
    val petScale = remember { Animatable(1f) }
    val petRotation = remember { Animatable(0f) }
    val petTranslationX = remember { Animatable(0f) }
    val petBurst = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    var petAnimationJob by remember { mutableStateOf<Job?>(null) }
    val idleTransition = rememberInfiniteTransition(label = "锁屏宠物舞步")
    val idleRotation = if (animationEnabled) {
        idleTransition.animateFloat(
            initialValue = -2.4f,
            targetValue = 2.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "宠物左右摇摆"
        ).value
    } else {
        0f
    }
    val idleScale = if (animationEnabled) {
        idleTransition.animateFloat(
            initialValue = 0.985f,
            targetValue = 1.015f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "宠物节奏呼吸"
        ).value
    } else {
        1f
    }

    LaunchedEffect(state.action.pauseMinutes) {
        state.action.pauseMinutes?.let { selectedPauseMinutes = it }
    }
    LaunchedEffect(state.message) { touchMessage = null }

    val resolvedAction = when (state.action.kind) {
        LockPendingActionKind.PAUSE -> LockPendingAction.pause(selectedPauseMinutes)
        LockPendingActionKind.SKIP -> LockPendingAction.Skip
    }
    val petSize = 62.dp
    val contentPadding = if (layoutMode == LockCompanionLayoutMode.COMPACT) 10.dp else 14.dp

    val density = androidx.compose.ui.platform.LocalDensity.current
    val bubbleShape = remember(density) {
        val cornerRadius = with(density) { 16.dp.toPx() }
        val arrowHeight = with(density) { 8.dp.toPx() }
        val arrowWidth = with(density) { 12.dp.toPx() }
        BubbleShape(cornerRadius, arrowHeight, arrowWidth)
    }

    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 气泡式文字说明 / 聊天面板 / 眼球操倒计时（位于最上方）
        if (state.eyeRelaxingActive) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .background(BrandColors.SurfaceRaised, bubbleShape)
                    .border(1.dp, BrandColors.OutlineSoft, bubbleShape)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "👁️ 20-20-20 护眼远眺中",
                    color = BrandColors.Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    "请将目光移向窗外（约6米远），\n同时用眼角余光跟随小芽转动眼球。",
                    color = BrandColors.TextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center
                )
                var secondsLeft by remember { mutableIntStateOf(20) }
                LaunchedEffect(Unit) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft--
                    }
                }
                Text(
                    "剩余时间: $secondsLeft 秒",
                    color = BrandColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        } else if (state.isChatPanelOpen) {
            LockPetChatPanel(
                chatHistory = state.chatHistory,
                isWaitingResponse = state.isWaitingResponse,
                onSendMessage = onSendMessage,
                onCloseChat = { onChatPanelToggle(false) },
                onEyeRelaxClick = { onRelaxingActiveChanged(true) }
            )
        } else {
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .height(130.dp)
                    .background(BrandColors.SurfaceRaised, bubbleShape)
                    .border(1.dp, BrandColors.OutlineSoft, bubbleShape)
                    .clickable(enabled = isLockChatEnabled) {
                        onChatPanelToggle(true)
                    }
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    "${petProfile.expressionMark}  ${petProfile.title}",
                    color = BrandColors.TextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                )

                Crossfade(
                    targetState = touchMessage ?: state.message,
                    animationSpec = tween(if (animationEnabled) 180 else 0),
                    label = "宠物建议切换",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 36.dp, bottom = 26.dp)
                ) { message ->
                    Text(
                        message,
                        color = BrandColors.TextPrimary,
                        fontSize = if (layoutMode == LockCompanionLayoutMode.COMPACT) 13.sp else 14.sp,
                        lineHeight = if (layoutMode == LockCompanionLayoutMode.COMPACT) 17.sp else 19.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }

                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = BrandColors.Primary,
                                strokeWidth = 1.5.dp
                            )
                            Text(
                                LOCK_PET_LOADING_TEXT,
                                color = BrandColors.TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    } else if (isLockChatEnabled) {
                        Text(
                            "点击进入多轮聊天 💬",
                            color = BrandColors.Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 2. 宠物形象（居中摆放）
        val petInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size((petSize + 14.dp).coerceAtLeast(56.dp))
                .clickable(
                    role = Role.Button,
                    interactionSource = petInteractionSource,
                    indication = null
                ) {
                    val (nextMotionIndex, motion) = nextPetTouchMotion(
                        petProfile,
                        touchMotionIndex
                    )
                    touchMotionIndex = nextMotionIndex
                    if (animationEnabled) {
                        petAnimationJob?.cancel()
                        petAnimationJob = animationScope.launch {
                            playPetTouchMotion(
                                motion = motion,
                                scale = petScale,
                                rotation = petRotation,
                                translationX = petTranslationX,
                                burst = petBurst
                            )
                        }
                    }

                    // 动作每次触摸都响应；只有气泡文案使用 450ms 节流，避免连续闪烁。
                    val now = SystemClock.elapsedRealtime()
                    val touch = resolveLockPetTouch(
                        currentResponseIndex = touchResponseIndex,
                        lastAcceptedElapsedMillis = lastPetTouchElapsed,
                        nowElapsedMillis = now,
                        responses = petProfile.touchMessages
                    )
                    if (touch.accepted) {
                        touchResponseIndex = touch.responseIndex
                        lastPetTouchElapsed = now
                        touchMessage = touch.message
                    }

                    if (state.isChatPanelOpen && !state.isWaitingResponse) {
                        // 防止连续点击宠物产生多条 AI 对话：上一条 user 消息若已是宠物交互动作则跳过
                        val lastUserMsg = state.chatHistory.lastOrNull { it.role == "user" }
                        val isConsecutiveTouch = lastUserMsg != null &&
                            lastUserMsg.content.startsWith("（") && lastUserMsg.content.endsWith("）")
                        if (!isConsecutiveTouch) {
                            val isTooClose = state.eyeDistanceStatus == com.example.controlfree.sensor.EyeDistanceStatus.TOO_CLOSE
                            val autoMsg = if (isTooClose) {
                                "（我想离近点摸摸你，你好像有点躲闪）"
                            } else {
                                val interactions = listOf(
                                    "（轻轻戳了戳小芽）",
                                    "（抚摸小芽的头，看它开心的晃脑）",
                                    "（捏了捏小芽的叶子，小芽眨了眨眼睛）",
                                    "（和小芽打招呼，摸了摸它）"
                                )
                                interactions[touchResponseIndex.coerceIn(0, interactions.size - 1)]
                            }
                            onSendMessage(autoMsg)
                        }
                    }
                }
                .semantics { contentDescription = "触摸 ${petProfile.stage.displayName}小芽" },
            contentAlignment = Alignment.Center
        ) {
            if (petProfile.appearanceEffect.haloLayers >= 1) {
                Box(
                    Modifier
                        .size(petSize + 8.dp)
                        .graphicsLayer {
                            alpha = petProfile.appearanceEffect.glowAlpha *
                                (0.8f + petBurst.value * 0.2f)
                            scaleX = 1f + petBurst.value * 0.15f
                            scaleY = scaleX
                        }
                        .background(BrandColors.Primary.copy(alpha = 0.15f), CircleShape)
                )
            }
            val isTooClose = state.eyeDistanceStatus == com.example.controlfree.sensor.EyeDistanceStatus.TOO_CLOSE
            val closeScale = if (isTooClose) 0.8f else 1f
            val closeTranslationX = if (isTooClose) (-20f).dp else 0.dp
            val closeRotation = if (isTooClose) -10f else 0f

            com.example.controlfree.ui.common.GrowingPlantCanvas(
                stage = petProfile.stage,
                showCircleBackground = false,
                modifier = Modifier
                    .size(petSize.coerceAtLeast(48.dp))
                    .offset(x = petRelaxOffsetX.value.dp, y = petRelaxOffsetY.value.dp)
                    .graphicsLayer {
                        translationX = petTranslationX.value.dp.toPx() + closeTranslationX.toPx()
                        rotationZ = idleRotation + petRotation.value + closeRotation
                        scaleX = idleScale * petScale.value * closeScale
                        scaleY = idleScale * petScale.value * closeScale
                    }
            )
        }

        // 3. 时间按钮、调整按钮（位于宠物下方）
        if (state.action.kind == LockPendingActionKind.PAUSE && !state.isChatPanelOpen && !state.eyeRelaxingActive) {
            LockPauseQuickChoices(
                selectedMinutes = selectedPauseMinutes,
                onSelect = { minutes ->
                    selectedPauseMinutes = minutes
                    showCustomPause = false
                    onPauseMinutesSelected(minutes)
                },
                onCustomClick = { showCustomPause = !showCustomPause }
            )
            if (showCustomPause) {
                LockPauseCustomAdjuster(
                    minutes = selectedPauseMinutes,
                    onMinutesChange = { selectedPauseMinutes = it },
                    onMinutesCommitted = onPauseMinutesSelected
                )
            }
        }

        // 4. 操作按钮横向排列在一行
        if (!state.isChatPanelOpen && !state.eyeRelaxingActive) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onContinueSelfDiscipline,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BrandColors.Outline),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) {
                    Text(LOCK_PET_CONTINUE_SELF_DISCIPLINE, fontSize = 11.sp, maxLines = 1)
                }
                if (onGrowthUnlockRequest != null) {
                    OutlinedButton(
                        onClick = { onGrowthUnlockRequest(resolvedAction) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BrandColors.Outline),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("成长值解锁", fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (onKnowledgeChallengeRequest != null) {
                    OutlinedButton(
                        onClick = { onKnowledgeChallengeRequest(resolvedAction) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BrandColors.Outline),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("百科挑战", fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun LockPetChatPanel(
    chatHistory: List<com.example.controlfree.ai.AiChatMessage>,
    isWaitingResponse: Boolean,
    onSendMessage: (String) -> Unit,
    onCloseChat: () -> Unit,
    onEyeRelaxClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chatHistory.size, isWaitingResponse) {
        if (chatHistory.isNotEmpty()) {
            val targetIndex = if (isWaitingResponse) chatHistory.size else chatHistory.size - 1
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .background(BrandColors.SurfaceRaised, RoundedCornerShape(16.dp))
            .border(1.dp, BrandColors.OutlineSoft, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "与小芽对话中...",
                color = BrandColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "护眼远眺 👁️",
                    color = BrandColors.Primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onEyeRelaxClick() }
                        .padding(4.dp)
                )
                Text(
                    "返回",
                    color = BrandColors.Primary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onCloseChat() }
                        .padding(4.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(chatHistory, key = { it.id }) { msg ->
                val isMe = msg.role == "user"
                val align = if (isMe) Alignment.End else Alignment.Start
                val bg = if (isMe) BrandColors.Primary else BrandColors.SurfaceCard
                val fg = if (isMe) Color.White else BrandColors.TextPrimary
                val shape = if (isMe) {
                    RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
                } else {
                    RoundedCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = align
                ) {
                    Box(
                        modifier = Modifier
                            .background(bg, shape)
                            .border(1.dp, BrandColors.OutlineSoft, shape)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = msg.content,
                            color = fg,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
            if (isWaitingResponse) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .background(BrandColors.SurfaceCard, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "小芽正在思考...",
                                color = BrandColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        val presets = listOf("我累了", "想放松一下", "帮我打气", "真的想玩")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { preset ->
                Box(
                    modifier = Modifier
                        .background(BrandColors.SurfaceCard, RoundedCornerShape(12.dp))
                        .border(1.dp, BrandColors.OutlineSoft, RoundedCornerShape(12.dp))
                        .clickable(enabled = !isWaitingResponse) {
                            onSendMessage(preset)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        preset,
                        color = BrandColors.TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("输入你想对小芽说的话...", fontSize = 11.sp) },
                singleLine = true,
                enabled = !isWaitingResponse,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandColors.Primary,
                    unfocusedBorderColor = BrandColors.OutlineSoft
                )
            )
            Text(
                "发送",
                color = if (inputText.isNotBlank() && !isWaitingResponse) BrandColors.Primary else BrandColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = inputText.isNotBlank() && !isWaitingResponse) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

private suspend fun playPetTouchMotion(
    motion: PetTouchMotion,
    scale: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    rotation: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    translationX: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    burst: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>
) {
    scale.stop()
    rotation.stop()
    translationX.stop()
    burst.stop()
    scale.snapTo(1f)
    rotation.snapTo(0f)
    translationX.snapTo(0f)
    burst.snapTo(0f)

    suspend fun pose(
        targetScale: Float = 1f,
        targetRotation: Float = 0f,
        targetTranslationX: Float = 0f,
        durationMillis: Int = 90
    ) = coroutineScope {
        launch { scale.animateTo(targetScale, tween(durationMillis)) }
        launch { rotation.animateTo(targetRotation, tween(durationMillis)) }
        launch { translationX.animateTo(targetTranslationX, tween(durationMillis)) }
    }

    coroutineScope {
        launch {
            burst.animateTo(1f, tween(130))
            burst.animateTo(0f, tween(260))
        }
        launch {
            when (motion) {
                PetTouchMotion.NOD -> {
                    pose(targetScale = 0.94f, targetRotation = -4f, durationMillis = 80)
                    pose(targetScale = 1.07f, targetRotation = 5f, durationMillis = 90)
                    pose(durationMillis = 110)
                }
                PetTouchMotion.WAVE -> {
                    pose(targetScale = 1.04f, targetRotation = -13f, durationMillis = 90)
                    pose(targetScale = 1.04f, targetRotation = 14f, durationMillis = 100)
                    pose(targetScale = 1.03f, targetRotation = -10f, durationMillis = 90)
                    pose(durationMillis = 120)
                }
                PetTouchMotion.SIDE_STEP -> {
                    pose(targetScale = 0.98f, targetTranslationX = -8f, durationMillis = 90)
                    pose(targetScale = 1.05f, targetTranslationX = 8f, durationMillis = 110)
                    pose(targetScale = 0.98f, targetTranslationX = -5f, durationMillis = 90)
                    pose(durationMillis = 120)
                }
                PetTouchMotion.SPIN -> {
                    pose(targetScale = 0.92f, targetRotation = -18f, durationMillis = 90)
                    pose(targetScale = 1.08f, targetRotation = 180f, durationMillis = 170)
                    pose(targetScale = 1f, targetRotation = 360f, durationMillis = 180)
                    rotation.snapTo(0f)
                }
                PetTouchMotion.DOUBLE_BEAT -> {
                    repeat(2) {
                        pose(targetScale = 1.12f, targetRotation = 5f, durationMillis = 75)
                        pose(targetScale = 0.94f, targetRotation = -5f, durationMillis = 75)
                    }
                    pose(durationMillis = 120)
                }
                PetTouchMotion.GUARD_POSE -> {
                    pose(targetScale = 1.12f, targetRotation = -7f, durationMillis = 120)
                    pose(targetScale = 1.12f, targetRotation = 7f, durationMillis = 120)
                    pose(targetScale = 1.06f, targetRotation = 0f, durationMillis = 140)
                    pose(durationMillis = 100)
                }
                PetTouchMotion.CELEBRATION -> {
                    pose(targetScale = 0.9f, targetRotation = -10f, durationMillis = 80)
                    pose(targetScale = 1.16f, targetRotation = 12f, durationMillis = 120)
                    pose(targetScale = 1.05f, targetRotation = -8f, durationMillis = 100)
                    pose(durationMillis = 130)
                }
                PetTouchMotion.FULL_DANCE -> {
                    listOf(-10f, 10f, -7f, 7f).forEachIndexed { index, offset ->
                        pose(
                            targetScale = if (index % 2 == 0) 0.96f else 1.08f,
                            targetRotation = offset,
                            targetTranslationX = offset,
                            durationMillis = 90
                        )
                    }
                    pose(targetScale = 1.1f, targetRotation = 180f, durationMillis = 170)
                    pose(targetRotation = 360f, durationMillis = 170)
                    rotation.snapTo(0f)
                    pose(durationMillis = 110)
                }
                PetTouchMotion.STAR_BURST -> {
                    repeat(3) { index ->
                        pose(
                            targetScale = if (index % 2 == 0) 1.16f else 0.92f,
                            targetRotation = if (index % 2 == 0) 14f else -14f,
                            durationMillis = 90
                        )
                    }
                    pose(targetScale = 1.08f, targetRotation = 360f, durationMillis = 220)
                    rotation.snapTo(0f)
                    pose(durationMillis = 120)
                }
            }
        }
    }
    scale.snapTo(1f)
    rotation.snapTo(0f)
    translationX.snapTo(0f)
    burst.snapTo(0f)
}

@Composable
private fun LockPauseQuickChoices(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    onCustomClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LOCK_PAUSE_PRESET_MINUTES.forEach { minutes ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                val selected = selectedMinutes == minutes
                val interactionSource = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (selected) {
                                BrandColors.Primary.copy(alpha = 0.2f)
                            } else {
                                BrandColors.SurfaceRaised
                            },
                            CircleShape
                        )
                        .border(
                            1.dp,
                            if (selected) BrandColors.Primary else BrandColors.Outline,
                            CircleShape
                        )
                        .clickable(
                            role = Role.Button,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(minutes) }
                        )
                        .semantics { contentDescription = "暂停 $minutes 分钟" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$minutes",
                        color = BrandColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1.25f)
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            val interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(BrandColors.SurfaceRaised, CircleShape)
                    .border(1.dp, BrandColors.Outline, CircleShape)
                    .clickable(
                        role = Role.Button,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onCustomClick
                    )
                    .semantics { contentDescription = "调整 1 到 30 分钟" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = BrandColors.Primary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockPauseCustomAdjuster(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onMinutesCommitted: (Int) -> Unit
) {
    val accentColor = BrandColors.Primary
    val inactiveTrackColor = BrandColors.OutlineSoft
    val thumbBorderColor = BrandColors.SurfaceCard
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$minutes 分",
            color = BrandColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(BrandColors.Primary.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        Spacer(Modifier.width(6.dp))
        Slider(
            value = minutes.toFloat(),
            onValueChange = { value ->
                onMinutesChange(
                    value.roundToInt().coerceIn(MIN_LOCK_PAUSE_MINUTES, MAX_LOCK_PAUSE_MINUTES)
                )
            },
            onValueChangeFinished = { onMinutesCommitted(minutes) },
            valueRange = MIN_LOCK_PAUSE_MINUTES.toFloat()..MAX_LOCK_PAUSE_MINUTES.toFloat(),
            steps = MAX_LOCK_PAUSE_MINUTES - MIN_LOCK_PAUSE_MINUTES - 1,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = BrandColors.SurfaceMuted,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(accentColor, CircleShape)
                        .border(3.dp, thumbBorderColor, CircleShape)
                )
            },
            track = { sliderState ->
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                ) {
                    val centerY = size.height / 2f
                    val trackStrokeWidth = 8.dp.toPx()
                    val range = sliderState.valueRange
                    val fraction = if (range.endInclusive > range.start) {
                        ((sliderState.value - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    drawLine(
                        color = inactiveTrackColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = accentColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width * fraction, centerY),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        )
    }
}

@Composable
private fun LockCompanionActionRow(
    primaryText: String,
    secondaryText: String,
    primaryAction: () -> Unit,
    secondaryAction: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(
            enabled = primaryEnabled,
            onClick = primaryAction,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BrandColors.Outline),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
        ) {
            Text(primaryText, fontSize = 11.sp, maxLines = 1)
        }
        OutlinedButton(
            enabled = secondaryEnabled,
            onClick = secondaryAction,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BrandColors.Outline),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
        ) {
            Text(secondaryText, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AllowedAppsPanel(
    allowedApps: List<AllowedApp>,
    onBack: () -> Unit,
    onAllowedAppClick: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(240.dp)),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "白名单 APP",
                        color = BrandColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭白名单 APP",
                            tint = Color.White
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (allowedApps.isEmpty()) {
                    Text(
                        "暂未配置白名单 APP",
                        color = BrandColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 28.dp)
                    )
                } else {
                    AllowedAppsGrid(
                        allowedApps = allowedApps,
                        onAllowedAppClick = onAllowedAppClick,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
private fun AllowedAppsGrid(
    allowedApps: List<AllowedApp>,
    onAllowedAppClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val slotCount = resolveAllowedAppGridSlotCount(allowedApps.size)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val iconSize = resolveAllowedAppIconSizeDp(
            contentWidthDp = maxWidth.value.toInt(),
            interColumnSpacingDp = 4
        ).dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until slotCount).chunked(LOCK_ALLOWED_APP_COLUMNS).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    rowSlots.forEach { appIndex ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            allowedApps.getOrNull(appIndex)?.let { app ->
                                AllowedAppButton(app, iconSize) {
                                    onAllowedAppClick(app.packageName)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllowedAppButton(
    app: AllowedApp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "打开${app.label}",
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 6.dp)
    ) {
        AndroidView(
            factory = { ImageView(it) },
            update = {
                it.setImageDrawable(app.icon)
            },
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = app.label,
            color = BrandColors.TextPrimary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PasswordPanel(
    action: LockPendingAction,
    expectedLength: Int?,
    authenticationGeneration: Int,
    onBack: () -> Unit,
    onGesture: (() -> Unit)?,
    onVerify: (String, (VerificationResult) -> Unit) -> Boolean
) {
    val context = LocalContext.current
    val responsiveLayout = rememberResponsiveLayoutSpec()
    val passwordBuffer = remember { NumericPasswordInputBuffer() }
    var password by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var verificationMessage by remember { mutableStateOf("") }

    LaunchedEffect(authenticationGeneration) {
        password = passwordBuffer.reset()
        isVerifying = false
        verificationMessage = ""
    }

    fun resetEnteredPassword() {
        password = passwordBuffer.reset()
    }

    fun verifyEnteredPassword(candidate: String = passwordBuffer.value) {
        if (isVerifying) return
        isVerifying = true
        verificationMessage = "正在验证…"
        val accepted = onVerify(candidate) { result ->
            isVerifying = false
            if (!result.isSuccess) resetEnteredPassword()
            verificationMessage = lockActionVerificationMessage(action, result)
            showVerificationToast(context, action, result)
        }
        if (!accepted) {
            isVerifying = false
            resetEnteredPassword()
            val failure = VerificationResult(VerificationStatus.FAILURE)
            verificationMessage = lockActionVerificationMessage(action, failure)
            showVerificationToast(context, action, failure)
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val keySize = when {
            maxHeight < 420.dp -> 46.dp
            maxHeight < 620.dp -> 54.dp
            else -> 64.dp
        }
        if (responsiveLayout.useTwoPane) {
            Row(
                modifier = Modifier.fillMaxHeight().widthIn(max = 920.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PasswordPanelHeader(action, expectedLength)
                    Spacer(Modifier.height(20.dp))
                    onGesture?.let {
                        TextButton(enabled = !isVerifying, onClick = it) { Text("返回手势") }
                    }
                    TextButton(enabled = !isVerifying, onClick = onBack) { Text("返回锁屏") }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PasswordPanelInput(
                        password = password,
                        onPasswordEdit = { edit ->
                            val result = passwordBuffer.apply(
                                edit = edit,
                                inputLimit = numericPasswordInputLimit(expectedLength),
                                expectedLength = expectedLength
                            )
                            password = result.value
                            result.completedSnapshot?.let(::verifyEnteredPassword)
                        },
                        expectedLength = expectedLength,
                        isVerifying = isVerifying,
                        verificationMessage = verificationMessage,
                        keySize = keySize,
                        onConfirm = { verifyEnteredPassword() }
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp)
            ) {
                PasswordPanelHeader(action, expectedLength)
                Spacer(Modifier.height(16.dp))
                PasswordPanelInput(
                    password = password,
                    onPasswordEdit = { edit ->
                        val result = passwordBuffer.apply(
                            edit = edit,
                            inputLimit = numericPasswordInputLimit(expectedLength),
                            expectedLength = expectedLength
                        )
                        password = result.value
                        result.completedSnapshot?.let(::verifyEnteredPassword)
                    },
                    expectedLength = expectedLength,
                    isVerifying = isVerifying,
                    verificationMessage = verificationMessage,
                    keySize = keySize,
                    onConfirm = { verifyEnteredPassword() }
                )
                onGesture?.let {
                    TextButton(enabled = !isVerifying, onClick = it) { Text("返回手势") }
                }
                TextButton(enabled = !isVerifying, onClick = onBack) { Text("返回锁屏") }
            }
        }
    }
}

@Composable
private fun PasswordPanelHeader(
    action: LockPendingAction,
    expectedLength: Int?
) {
    Text(
        action.title,
        color = BrandColors.TextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Text(
        if (expectedLength == null) {
            "首次升级：输完旧密码后点击键盘下方确认"
        } else {
            action.confirmationText
        },
        color = BrandColors.TextSecondary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PasswordPanelInput(
    password: String,
    onPasswordEdit: (NumericPasswordEdit) -> Unit,
    expectedLength: Int?,
    isVerifying: Boolean,
    verificationMessage: String,
    keySize: androidx.compose.ui.unit.Dp,
    onConfirm: () -> Unit
) {
    NumericPasswordPad(
        value = password,
        onEdit = onPasswordEdit,
        expectedLength = expectedLength,
        onConfirm = if (expectedLength == null) onConfirm else null,
        enabled = !isVerifying,
        keySize = keySize
    )
    Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
        if (verificationMessage.isNotBlank() && !isVerifying) {
            Text(
                verificationMessage,
                color = BrandColors.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GesturePanel(
    action: LockPendingAction,
    authenticationGeneration: Int,
    onBack: () -> Unit,
    onPassword: (() -> Unit)?,
    onVerify: (List<Int>, (VerificationResult) -> Unit) -> Boolean
) {
    val context = LocalContext.current
    val responsiveLayout = rememberResponsiveLayoutSpec()
    var isVerifying by remember { mutableStateOf(false) }
    var verificationMessage by remember { mutableStateOf("绘制已设置的手势") }
    LaunchedEffect(authenticationGeneration) {
        isVerifying = false
        verificationMessage = "绘制已设置的手势"
    }

    fun verifyPattern(view: PatternLockView, pattern: List<Int>) {
        if (isVerifying) return
        isVerifying = true
        verificationMessage = "正在验证…"
        view.isEnabled = false
        val accepted = onVerify(pattern) { result ->
            isVerifying = false
            view.clearPattern()
            verificationMessage = lockActionVerificationMessage(action, result)
            showVerificationToast(context, action, result)
        }
        if (!accepted) {
            isVerifying = false
            view.clearPattern()
            val failure = VerificationResult(VerificationStatus.FAILURE)
            verificationMessage = lockActionVerificationMessage(action, failure)
            showVerificationToast(context, action, failure)
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val gestureSize = if (responsiveLayout.useTwoPane) {
            minOf(
                340.dp,
                ((maxWidth - 32.dp) / 2),
                (maxHeight - 40.dp).coerceAtLeast(180.dp)
            )
        } else {
            minOf(
                300.dp,
                maxWidth,
                (maxHeight - 120.dp).coerceAtLeast(180.dp)
            )
        }
        if (responsiveLayout.useTwoPane) {
            Row(
                modifier = Modifier.fillMaxHeight().widthIn(max = 920.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GesturePanelHeader(action, verificationMessage)
                    Spacer(Modifier.height(20.dp))
                    onPassword?.let { switchToPassword ->
                        OutlinedButton(
                            enabled = !isVerifying,
                            onClick = switchToPassword,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("数字密码")
                        }
                    }
                    TextButton(enabled = !isVerifying, onClick = onBack) { Text("返回锁屏") }
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    GesturePatternInput(
                        authenticationGeneration = authenticationGeneration,
                        isVerifying = isVerifying,
                        modifier = Modifier.size(gestureSize),
                        onPatternComplete = ::verifyPattern
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(vertical = 12.dp)
            ) {
                GesturePanelHeader(action, verificationMessage)
                GesturePatternInput(
                    authenticationGeneration = authenticationGeneration,
                    isVerifying = isVerifying,
                    modifier = Modifier.size(gestureSize),
                    onPatternComplete = ::verifyPattern
                )
                onPassword?.let { switchToPassword ->
                    OutlinedButton(
                        enabled = !isVerifying,
                        onClick = switchToPassword,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("数字密码")
                    }
                }
                TextButton(enabled = !isVerifying, onClick = onBack) { Text("返回锁屏") }
            }
        }
    }
}

@Composable
private fun GesturePanelHeader(
    action: LockPendingAction,
    verificationMessage: String
) {
    Text(
        action.title,
        color = BrandColors.TextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Text(action.confirmationText, color = BrandColors.TextSecondary, textAlign = TextAlign.Center)
    Text(
        verificationMessage,
        color = BrandColors.TextSecondary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun GesturePatternInput(
    authenticationGeneration: Int,
    isVerifying: Boolean,
    modifier: Modifier,
    onPatternComplete: (PatternLockView, List<Int>) -> Unit
) {
    val patternNormalColor = BrandColors.TextTertiary.toArgb()
    val patternSelectedColor = BrandColors.Primary.toArgb()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        key(authenticationGeneration) {
            AndroidView(
                factory = { PatternLockView(it) },
                update = { view ->
                    view.setColors(patternNormalColor, patternSelectedColor)
                    view.isEnabled = !isVerifying
                    view.onPatternComplete = { pattern -> onPatternComplete(view, pattern) }
                },
                modifier = modifier
            )
        }
        Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            if (isVerifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = BrandColors.Primary,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

private fun showVerificationToast(
    context: Context,
    action: LockPendingAction,
    result: VerificationResult
) {
    Toast.makeText(
        context,
        lockActionVerificationMessage(action, result),
        Toast.LENGTH_SHORT
    ).show()
}

@Composable
private fun lockButtonColors() = ButtonDefaults.buttonColors(
    containerColor = BrandColors.Primary,
    contentColor = BrandColors.OnPrimary
)

private fun formatTime(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", safeSeconds / 60, safeSeconds % 60)
}

internal fun formatLockDateTime(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String = Instant.ofEpochMilli(epochMillis)
    .atZone(zoneId)
    .format(LOCK_DATE_TIME_FORMATTER)
