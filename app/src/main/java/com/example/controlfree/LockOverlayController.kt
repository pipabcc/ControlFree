package com.example.controlfree

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.example.controlfree.ai.AiInterventionCoordinator
import com.example.controlfree.ai.AiInterventionRequest
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.LockUnlockFeaturePreferences
import com.example.controlfree.growth.GrowthRepository
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.security.PatternLockView
import com.example.controlfree.security.VerificationResult
import com.example.controlfree.security.VerificationStatus
import com.example.controlfree.theme.BrandColorInts
import java.util.Locale
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

enum class OverlayShowResult {
    SHOWN,
    DEFERRED,
    PERMISSION_REVOKED,
    WINDOW_ERROR
}

internal fun resolveOverlayShowPrecondition(
    hasOverlayPermission: Boolean,
    hasWindowManager: Boolean
): OverlayShowResult? = when {
    !hasOverlayPermission -> OverlayShowResult.PERMISSION_REVOKED
    !hasWindowManager -> OverlayShowResult.WINDOW_ERROR
    else -> null
}

internal fun resolveAllowedAppRowHeightDp(fontScale: Float): Int = when {
    fontScale >= 1.8f -> 108
    fontScale >= 1.3f -> 96
    else -> 84
}

// 系统窗口在 Activity/权限界面交接期可能连续拒绝移除；给足重试窗口（8 次 × 250ms），
// 避免僵尸悬浮窗盖在新界面上冻结显示旧倒计时。
internal const val OVERLAY_REMOVAL_MAX_ATTEMPTS = 8

internal fun shouldRetryOverlayRemoval(
    isAttachedToWindow: Boolean,
    completedAttempts: Int,
    maximumAttempts: Int = OVERLAY_REMOVAL_MAX_ATTEMPTS
): Boolean =
    isAttachedToWindow && completedAttempts >= 0 && completedAttempts < maximumAttempts

internal fun shouldResetOverlayRemovalAttempts(
    isSameView: Boolean,
    isRetryScheduled: Boolean,
    isAttachedToWindow: Boolean,
    completedAttempts: Int,
    maximumAttempts: Int = OVERLAY_REMOVAL_MAX_ATTEMPTS
): Boolean =
    !isSameView ||
        (isAttachedToWindow &&
            !isRetryScheduled &&
            !shouldRetryOverlayRemoval(
                isAttachedToWindow = true,
                completedAttempts = completedAttempts,
                maximumAttempts = maximumAttempts
            ))

/**
 * 悬浮锁层的真值自愈决策。锁定已经结束的窗口必须自拆——服务实例销毁、可见性
 * 广播乱序或窗口移除竞态都可能让"该消失的窗口"失去唯一的移除者，残留窗口会以
 * 冻结的倒计时挡住整个系统，且所有按钮因会话失效而失灵。仍在锁定时则采纳仲裁
 * 给出的当前会话并刷新倒计时，让携带旧会话的窗口重新变得可操作。
 */
internal sealed interface OverlayTruthDirective {
    data object Dismiss : OverlayTruthDirective

    data class Continue(
        val sessionId: Long,
        val remainingSeconds: Int
    ) : OverlayTruthDirective
}

internal fun resolveOverlayTruthDirective(
    decision: LockTruthDecision,
    attachedSessionId: Long
): OverlayTruthDirective = if (!decision.shouldStayLocked) {
    OverlayTruthDirective.Dismiss
} else {
    OverlayTruthDirective.Continue(
        sessionId = decision.sessionId.takeIf { it != NO_LOCK_SESSION } ?: attachedSessionId,
        remainingSeconds = decision.remainingSeconds
    )
}

/**
 * 锁定阶段的系统悬浮层。锁定内容和认证内容互斥，数字密码不依赖系统输入法。
 */
class LockOverlayController internal constructor(
    private val context: Context,
    private val hasGesture: () -> Boolean,
    private val hasPassword: () -> Boolean,
    private val passwordLength: () -> Int?,
    private val onAllowedAppRequest: (String) -> Unit,
    private val onLockActivityRequest: (OverlayLockActivityRequest) -> Boolean,
    private val onContinueSelfDiscipline: (() -> Boolean)? = null,
    private val onGrowthUnlockRequest: ((LockPendingAction) -> Unit)? = null,
    private val onAttachmentChanged: (Boolean) -> Unit = {}
) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var rootView: View? = null
    private var countdownView: TextView? = null
    private var countdownRuleView: TextView? = null
    private var lockStatusTitleView: TextView? = null
    private var currentTaskTitle: String? = null
    private var currentSessionMode = MonitorSessionMode.SUPERVISION
    private var allowedAppsCard: LinearLayout? = null
    private var allowedAppsGrid: LinearLayout? = null
    private var allowedAppsEmptyView: TextView? = null
    private var attachedConfigurationSignature: Int? = null
    private var attachedLockSessionId: Long = NO_LOCK_SESSION
    private var authenticationExecutor: LockAuthenticationExecutor? = null
    private val aiInterventionCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AiInterventionCoordinator.getInstance(context.applicationContext)
    }
    private val interventionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var interventionJob: Job? = null
    private var activeInterventionRequestId: Long? = null
    private var petInterventionUiState: OverlayPetInterventionState? = null
    private var petIdleAnimator: Animator? = null
    private var petTouchAnimator: Animator? = null
    private var currentPetProfile = petStageProfile(stage = null)
    private var interventionRequestSerial = 0L
    private var currentRemainingSeconds = 0
    private val removalHandler = Handler(Looper.getMainLooper())
    private var pendingRemovalView: View? = null
    private var removalAttemptCount = 0
    private var isRemovalRetryScheduled = false
    private var lastReportedAttachmentState = false
    private val pendingRemovalCallbacks = mutableListOf<() -> Unit>()
    private val removalRetryRunnable = Runnable {
        isRemovalRetryScheduled = false
        pendingRemovalView?.let(::attemptOverlayRemoval)
    }
    private val credentials by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CredentialStore(context)
    }
    private val lockTruthRepository by lazy(LazyThreadSafetyMode.NONE) {
        LockTruthRepository(context)
    }
    private var isTruthSyncScheduled = false
    private val truthSyncRunnable = Runnable {
        isTruthSyncScheduled = false
        syncWithLockTruth()
        scheduleLockTruthSync()
    }

    init {
        interventionScope.launch {
            GrowthRepository.getInstance(context.applicationContext)
                .observeAccount()
                .collect { account ->
                    currentPetProfile = petStageProfile(account.levelProgress.stage)
                    petInterventionUiState?.applyPetProfile(currentPetProfile)
                }
        }
    }

    fun show(
        remainingSeconds: Int,
        allowedApps: List<AllowedApp>,
        sessionMode: MonitorSessionMode,
        taskTitle: String?
    ): OverlayShowResult {
        // 方案B：LockActivity 为唯一锁屏界面，悬浮窗不再作为锁屏载体
        return OverlayShowResult.SHOWN
        currentRemainingSeconds = remainingSeconds.coerceAtLeast(0)
        resolveOverlayShowPrecondition(
            hasOverlayPermission = Settings.canDrawOverlays(context),
            hasWindowManager = windowManager != null
        )?.let { failure ->
            hide()
            return failure
        }
        val manager = requireNotNull(windowManager)
        val configurationSignature = currentConfigurationSignature()
        val currentSessionId = RuntimeLockTruthRegistry.currentSessionId()

        val attachedRoot = rootView
        if (attachedRoot?.isAttachedToWindow == true) {
            reportAttachmentState(true)
            if (
                attachedConfigurationSignature == configurationSignature &&
                attachedLockSessionId == currentSessionId
            ) {
                cancelPendingOverlayRemoval(attachedRoot)
                updateCountdown(remainingSeconds)
                updateCountdownRule(sessionMode)
                updateLockStatusTitle(taskTitle, sessionMode)
                scheduleLockTruthSync()
                return OverlayShowResult.SHOWN
            }
            // 旋转、窗口尺寸、大字体或锁定会话变化时重建，取消旧认证并重新测量。
            hide()
            if (attachedRoot.isAttachedToWindow) {
                // 移除失败时保留仍可操作的旧窗口，禁止再叠加第二个全屏窗口。
                cancelPendingOverlayRemoval(attachedRoot)
                attachedConfigurationSignature = configurationSignature
                attachedLockSessionId = currentSessionId
                updateCountdown(remainingSeconds)
                updateCountdownRule(sessionMode)
                updateLockStatusTitle(taskTitle, sessionMode)
                scheduleLockTruthSync()
                return OverlayShowResult.SHOWN
            }
        }
        if (rootView?.isAttachedToWindow == false) clearViewReferences()

        val overlay = createOverlayView(remainingSeconds, allowedApps, sessionMode, taskTitle)
        return try {
            manager.addView(overlay, createLayoutParams())
            rootView = overlay
            attachedConfigurationSignature = configurationSignature
            attachedLockSessionId = currentSessionId
            reportAttachmentState(true)
            overlay.requestFocus()
            scheduleLockTruthSync()
            OverlayShowResult.SHOWN
        } catch (_: RuntimeException) {
            if (overlay.isAttachedToWindow) {
                rootView = overlay
                attachedConfigurationSignature = configurationSignature
                attachedLockSessionId = currentSessionId
                reportAttachmentState(true)
                requestOverlayRemoval(overlay)
            } else {
                clearViewReferences()
            }
            OverlayShowResult.WINDOW_ERROR
        }
    }

    fun updateCountdown(remainingSeconds: Int) {
        currentRemainingSeconds = remainingSeconds.coerceAtLeast(0)
        countdownView?.text = formatTime(currentRemainingSeconds)
    }

    /**
     * 仅返回 WindowManager 已实际挂载的状态。调用 hide() 只代表发起移除，
     * 在系统确认 View 脱离前这里仍返回 true，服务不得据此启动第二层锁屏。
     */
    fun isAttached(): Boolean =
        rootView?.isAttachedToWindow == true ||
            pendingRemovalView?.isAttachedToWindow == true

    private fun updateCountdownRule(sessionMode: MonitorSessionMode) {
        countdownRuleView?.text = lockCountdownRuleText(sessionMode)
    }

    private fun updateLockStatusTitle(taskTitle: String?, sessionMode: MonitorSessionMode) {
        currentTaskTitle = resolveLockTaskTitle(taskTitle, sessionMode)
        currentSessionMode = sessionMode
        lockStatusTitleView?.text = lockStatusTitle(currentTaskTitle, sessionMode)
    }

    fun updateAllowedApps(apps: List<AllowedApp>) {
        val grid = allowedAppsGrid ?: return
        populateAllowedAppsGrid(grid, apps)
        grid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        allowedAppsEmptyView?.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        updateAllowedAppsPanelHeight(apps.size)
    }

    fun hide(onRemoved: (() -> Unit)? = null) {
        cancelPetInterventionRequest()
        onRemoved?.let(pendingRemovalCallbacks::add)
        val view = rootView
        if (view == null) {
            completeRemovalCallbacks()
            return
        }
        if (!view.isAttachedToWindow) {
            clearViewReferences()
            return
        }
        requestOverlayRemoval(view)
    }

    /** Service 生命周期结束后永久释放数据库订阅与动画任务；该实例不能再次 show。 */
    fun release() {
        hide()
        interventionScope.cancel()
    }

    private fun requestOverlayRemoval(view: View) {
        if (
            shouldResetOverlayRemovalAttempts(
                isSameView = pendingRemovalView === view,
                isRetryScheduled = isRemovalRetryScheduled,
                isAttachedToWindow = view.isAttachedToWindow,
                completedAttempts = removalAttemptCount
            )
        ) {
            pendingRemovalView = view
            removalAttemptCount = 0
            isRemovalRetryScheduled = false
            removalHandler.removeCallbacks(removalRetryRunnable)
        }
        if (!isRemovalRetryScheduled) attemptOverlayRemoval(view)
        // 移除期间保持真值心跳：系统持续拒绝移除时，窗口仍能按真值刷新或
        // 在锁定恢复后被服务复用，而不是留在过期状态。
        scheduleLockTruthSync()
    }

    private fun cancelPendingOverlayRemoval(view: View) {
        if (pendingRemovalView !== view) return
        removalHandler.removeCallbacks(removalRetryRunnable)
        pendingRemovalView = null
        removalAttemptCount = 0
        isRemovalRetryScheduled = false
    }

    private fun attemptOverlayRemoval(view: View) {
        // rootView 可能已指向新窗口，但旧窗口仍待移除；此时继续按 pendingRemovalView
        // 清理旧窗口，只是完成后不能清空新窗口的引用。
        val isCurrentRoot = rootView === view
        if (!isCurrentRoot && pendingRemovalView !== view) return
        if (!view.isAttachedToWindow) {
            if (isCurrentRoot) clearViewReferences() else cancelPendingOverlayRemoval(view)
            return
        }
        if (!shouldRetryOverlayRemoval(true, removalAttemptCount)) {
            // 连续快速重试仍失败时不能永久放弃：残留窗口会以冻结的旧倒计时
            // 盖住一切界面并拦截触摸。进入冷却期后重置计数继续尝试，
            // 直到系统接受移除或窗口自然脱离。
            removalAttemptCount = 0
            isRemovalRetryScheduled = true
            removalHandler.postDelayed(removalRetryRunnable, OVERLAY_REMOVAL_COOLDOWN_MILLIS)
            return
        }

        removalAttemptCount++
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: RuntimeException) {
            // 窗口服务可能正处于 Activity/权限界面交接，稍后在主线程有限重试。
        }
        if (!view.isAttachedToWindow) {
            if (isCurrentRoot) clearViewReferences() else cancelPendingOverlayRemoval(view)
        } else if (shouldRetryOverlayRemoval(true, removalAttemptCount)) {
            isRemovalRetryScheduled = true
            removalHandler.postDelayed(removalRetryRunnable, OVERLAY_REMOVAL_RETRY_DELAY_MILLIS)
        } else {
            isRemovalRetryScheduled = true
            removalHandler.postDelayed(removalRetryRunnable, OVERLAY_REMOVAL_COOLDOWN_MILLIS)
        }
    }

    private fun clearViewReferences() {
        cancelPetInterventionRequest()
        petIdleAnimator?.cancel()
        petIdleAnimator = null
        petTouchAnimator?.cancel()
        petTouchAnimator = null
        removalHandler.removeCallbacks(removalRetryRunnable)
        removalHandler.removeCallbacks(truthSyncRunnable)
        isTruthSyncScheduled = false
        pendingRemovalView = null
        removalAttemptCount = 0
        isRemovalRetryScheduled = false
        authenticationExecutor?.close()
        authenticationExecutor = null
        rootView = null
        countdownView = null
        countdownRuleView = null
        lockStatusTitleView = null
        currentTaskTitle = null
        currentSessionMode = MonitorSessionMode.SUPERVISION
        currentRemainingSeconds = 0
        petInterventionUiState = null
        allowedAppsCard = null
        allowedAppsGrid = null
        allowedAppsEmptyView = null
        attachedConfigurationSignature = null
        attachedLockSessionId = NO_LOCK_SESSION
        reportAttachmentState(false)
        completeRemovalCallbacks()
    }

    private fun reportAttachmentState(attached: Boolean) {
        if (lastReportedAttachmentState == attached) return
        lastReportedAttachmentState = attached
        onAttachmentChanged(attached)
    }

    /**
     * 锁定真值自愈心跳：与锁定 Activity 的心跳同源。窗口挂载期间每秒与
     * [LockTruthRepository] 对齐一次——锁定确已结束（暂停、跳过、解锁或阶段
     * 推进）时窗口一秒内自拆，不再依赖所属 Service 实例恰好调用 hide()；
     * 会话轮换时采纳新会话并刷新倒计时，避免残留窗口冻结在旧倒计时上、
     * 按钮全部因会话不符而失灵。
     */
    private fun syncWithLockTruth() {
        val attachedView = rootView ?: pendingRemovalView
        if (attachedView?.isAttachedToWindow != true) return
        val directive = try {
            resolveOverlayTruthDirective(
                decision = lockTruthRepository.resolve(attachedLockSessionId),
                attachedSessionId = attachedLockSessionId
            )
        } catch (_: RuntimeException) {
            // 真值仲裁暂时不可用时保持现状，下一次心跳继续收敛。
            return
        }
        when (directive) {
            OverlayTruthDirective.Dismiss -> hide()
            is OverlayTruthDirective.Continue -> {
                attachedLockSessionId = directive.sessionId
                updateCountdown(directive.remainingSeconds)
            }
        }
    }

    private fun scheduleLockTruthSync() {
        if (isTruthSyncScheduled) return
        if (rootView == null && pendingRemovalView == null) return
        isTruthSyncScheduled = true
        removalHandler.postDelayed(truthSyncRunnable, LOCK_TRUTH_SYNC_INTERVAL_MILLIS)
    }

    private fun completeRemovalCallbacks() {
        if (pendingRemovalCallbacks.isEmpty()) return
        val callbacks = pendingRemovalCallbacks.toList()
        pendingRemovalCallbacks.clear()
        callbacks.forEach { callback ->
            try {
                callback()
            } catch (_: RuntimeException) {
                // Window 已成功移除；上层交接失败由其自身恢复入口处理。
            }
        }
    }

    private fun cancelPetInterventionRequest() {
        activeInterventionRequestId = null
        interventionJob?.cancel()
        interventionJob = null
        petInterventionUiState?.uiState
            ?.takeIf(LockPetInterventionUiState::isLoading)
            ?.let { current ->
                petInterventionUiState?.render(
                    completeLockPetInterventionUiState(
                        current = current,
                        requestId = current.requestId,
                        lockSessionId = current.lockSessionId,
                        message = null
                    )
                )
            }
    }

    private fun currentConfigurationSignature(): Int = context.resources.configuration.run {
        var result = orientation
        result = 31 * result + screenWidthDp
        result = 31 * result + screenHeightDp
        result = 31 * result + fontScale.toBits()
        result
    }

    private fun createOverlayView(
        remainingSeconds: Int,
        allowedApps: List<AllowedApp>,
        sessionMode: MonitorSessionMode,
        taskTitle: String?
    ): View {
        val unlockFeatures = LockUnlockFeaturePreferences(context)
        val growthUnlockEnabled = unlockFeatures.growthUnlockEnabled
        val knowledgeChallengeEnabled = unlockFeatures.knowledgeChallengeEnabled
        val executor = getAuthenticationExecutor()
        var pendingAction: OverlayVerifiedAction? = null
        val root = FrameLayout(context).apply {
            setBackgroundColor(BrandColorInts.Canvas)
            isFocusable = true
            isFocusableInTouchMode = true
            systemUiVisibility = IMMERSIVE_FLAGS
            setOnSystemUiVisibilityChangeListener {
                if (systemUiVisibility != IMMERSIVE_FLAGS) {
                    systemUiVisibility = IMMERSIVE_FLAGS
                }
            }
        }

        val lockPanel = createLockPanel(remainingSeconds, allowedApps, sessionMode, taskTitle)
        val allowedAppsPanel = createAllowedAppsPanel(allowedApps)
        val passwordPanel = createPasswordPanel(executor) { pendingAction }
        val gesturePanel = createGesturePanel(executor) { pendingAction }
        val lockState = requireNotNull(lockPanel.tag as? OverlayLockPanelState)
        val petState = lockState.petState
        petInterventionUiState = petState
        var visiblePanel: View = lockPanel

        fun showOnly(
            panel: View,
            preservePendingAction: Boolean = false,
            preserveIntervention: Boolean = false
        ) {
            executor.invalidate()
            (passwordPanel.tag as? OverlayPasswordState)?.cancelVerification()
            (gesturePanel.tag as? OverlayGestureState)?.cancelVerification()
            if (!preserveIntervention) {
                cancelPetInterventionRequest()
                lockState.hideCompanion()
            }
            if (!preservePendingAction) pendingAction = null
            lockPanel.visibility = if (panel === lockPanel) View.VISIBLE else View.GONE
            allowedAppsPanel.visibility =
                if (panel === allowedAppsPanel) View.VISIBLE else View.GONE
            passwordPanel.visibility = if (panel === passwordPanel) View.VISIBLE else View.GONE
            gesturePanel.visibility = if (panel === gesturePanel) View.VISIBLE else View.GONE
            visiblePanel = panel
        }

        fun beginAuthentication(action: LockPendingAction) {
            pendingAction = OverlayVerifiedAction(
                request = AuthorizedLockAction(
                    action,
                    LockActionAuthorization.growthPoints()
                ),
                lockSessionId = currentActionSessionId()
            )
            (passwordPanel.tag as? OverlayPasswordState)?.setAction(action)
            (gesturePanel.tag as? OverlayGestureState)?.setAction(action)
            showOnly(
                panel = if (hasGesture()) gesturePanel else passwordPanel,
                preservePendingAction = true
            )
        }

        fun beginPetIntervention(action: LockPendingAction) {
            val aiEnabled = try {
                aiInterventionCoordinator.isEnabled()
            } catch (_: RuntimeException) {
                false
            }
            val requestAiAdvice = shouldRequestLockPetAiAdvice(aiEnabled)
            val sessionId = currentActionSessionId()
            interventionRequestSerial = if (interventionRequestSerial == Long.MAX_VALUE) {
                1L
            } else {
                interventionRequestSerial + 1L
            }
            pendingAction = OverlayVerifiedAction(
                request = AuthorizedLockAction(
                    action,
                    LockActionAuthorization.growthPoints()
                ),
                lockSessionId = sessionId
            )
            val initialState = initialLockPetInterventionUiState(
                requestId = interventionRequestSerial,
                lockSessionId = sessionId,
                action = action,
                sessionMode = currentSessionMode,
                remainingSeconds = currentRemainingSeconds,
                requestAiAdvice = requestAiAdvice
            )
            cancelPetInterventionRequest()
            petState.render(initialState)
            lockState.showCompanion()
            showOnly(
                lockPanel,
                preservePendingAction = true,
                preserveIntervention = true
            )
            if (!requestAiAdvice) return
            activeInterventionRequestId = initialState.requestId
            interventionJob = interventionScope.launch {
                val adviceMessage = try {
                    aiInterventionCoordinator.getAdvice(
                        AiInterventionRequest(
                            actionKind = action.kind,
                            requestedPauseMinutes = action.pauseMinutes,
                            sessionMode = initialState.sessionMode,
                            remainingSeconds = initialState.remainingSeconds,
                            lockSessionId = sessionId
                        )
                    ).message
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (
                    rootView !== root ||
                    !root.isAttachedToWindow ||
                    activeInterventionRequestId != initialState.requestId
                ) {
                    return@launch
                }
                // 会话已轮换或结束时不采纳云端文案，但必须结束加载态并回退到
                // 本地建议；直接丢弃会让残留窗口永远停在"正在更新建议…"。
                val acceptedMessage = adviceMessage?.takeIf {
                    RuntimeLockTruthRegistry.isCurrentSession(sessionId)
                }
                val currentState = petState.uiState ?: return@launch
                val completed = completeLockPetInterventionUiState(
                    current = currentState,
                    requestId = initialState.requestId,
                    lockSessionId = sessionId,
                    message = acceptedMessage
                )
                if (completed.requestId == initialState.requestId) petState.render(completed)
            }
        }

        lockPanel.findViewWithTag<View>(TAG_PAUSE_BUTTON).setOnClickListener {
            // 悬浮层自带介入卡、暂停时长选择与密码/手势验证面板，直接就地展开。
            // 交给 LockActivity 承接会有两个致命问题：系统一旦拦截后台启动就完全没有
            // 反应（而白名单是本地面板切换，所以只有它"能用"）；即便启动成功，
            // Activity 起来之前的空窗期还会露出桌面。
            beginPetIntervention(LockPendingAction.pause(DEFAULT_LOCK_PAUSE_MINUTES))
        }
        lockPanel.findViewWithTag<View>(TAG_SKIP_BUTTON).setOnClickListener {
            beginPetIntervention(LockPendingAction.Skip)
        }
        lockPanel.findViewWithTag<View>(TAG_ALLOWED_APPS_BUTTON).setOnClickListener {
            showOnly(allowedAppsPanel)
        }
        lockPanel.findViewWithTag<View>(TAG_QUICK_NOTE_BUTTON).setOnClickListener {
            // 悬浮层是纯 View 窗口，输入法交互交给 LockActivity 的闪记弹窗完成；
            // 用户主动点击属于前台交互，可以从悬浮层直接拉起未导出的锁定 Activity。
            if (!onLockActivityRequest(OverlayLockActivityRequest.QuickNote)) {
                Toast.makeText(context, "暂时无法打开闪记", Toast.LENGTH_SHORT).show()
            }
        }
        allowedAppsPanel.findViewWithTag<View>(TAG_BACK_BUTTON).setOnClickListener {
            showOnly(lockPanel)
        }
        petState.onPauseMinutesSelected = { minutes ->
            beginPetIntervention(LockPendingAction.pause(minutes))
        }
        petState.onContinueSelfDiscipline = {
            val accepted = onContinueSelfDiscipline?.invoke() == true
            if (accepted) {
                showOnly(lockPanel)
            } else {
                Toast.makeText(context, "暂时无法记录继续自律承诺", Toast.LENGTH_SHORT).show()
            }
            accepted
        }
        petState.onKnowledgeChallenge = if (knowledgeChallengeEnabled) {
            { action ->
                if (
                    !onLockActivityRequest(
                        OverlayLockActivityRequest.KnowledgeChallenge(action)
                    )
                ) {
                    Toast.makeText(context, "暂时无法打开百科挑战", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            null
        }
        petState.onGrowthUnlock = if (growthUnlockEnabled) {
            onGrowthUnlockRequest ?: { action -> beginAuthentication(action) }
        } else {
            null
        }
        petState.renderActionAvailability()
        petState.onActionChanged = { action ->
            pendingAction = OverlayVerifiedAction(
                request = AuthorizedLockAction(
                    action,
                    LockActionAuthorization.growthPoints()
                ),
                lockSessionId = currentActionSessionId()
            )
        }
        passwordPanel.findViewWithTag<Button>(TAG_BACK_BUTTON).setOnClickListener {
            showOnly(lockPanel)
        }
        passwordPanel.findViewWithTag<Button>(TAG_GESTURE_BUTTON)?.setOnClickListener {
            showOnly(gesturePanel, preservePendingAction = true)
        }
        gesturePanel.findViewWithTag<Button>(TAG_BACK_BUTTON).setOnClickListener {
            showOnly(lockPanel)
        }
        gesturePanel.findViewWithTag<Button>(TAG_PASSWORD_BUTTON)?.setOnClickListener {
            showOnly(passwordPanel, preservePendingAction = true)
        }

        root.addView(lockPanel, matchFrame())
        root.addView(allowedAppsPanel, matchFrame())
        root.addView(passwordPanel, matchFrame())
        root.addView(gesturePanel, matchFrame())
        showOnly(lockPanel)

        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) {
                false
            } else {
                if (visiblePanel !== lockPanel || lockState.isCompanionVisible()) {
                    showOnly(lockPanel)
                }
                true
            }
        }
        return root
    }

    private fun createLockPanel(
        remainingSeconds: Int,
        @Suppress("UNUSED_PARAMETER") allowedApps: List<AllowedApp>,
        sessionMode: MonitorSessionMode,
        taskTitle: String?
    ): View {
        val actionButtonSize = dp(LOCK_ACTION_BUTTON_SIZE_DP)
        val root = FrameLayout(context).apply {
            setBackgroundColor(BrandColorInts.Canvas)
        }
        val summary = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        val brandMarkView = ImageView(context).apply {
            setImageResource(R.drawable.ic_brand_mark)
        }
        summary.addView(
            brandMarkView,
            linearParams(dp(62), dp(62)).apply { bottomMargin = dp(18) }
        )

        val titleView = createTextView(
            lockStatusTitle(taskTitle, sessionMode),
            21f,
            BrandColorInts.TextPrimary,
            Gravity.CENTER
        ).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        lockStatusTitleView = titleView
        currentTaskTitle = resolveLockTaskTitle(taskTitle, sessionMode)
        currentSessionMode = sessionMode
        summary.addView(
            titleView,
            matchWidthWrapHeight().apply { bottomMargin = dp(12) }
        )
        countdownView = createTextView(
            formatTime(remainingSeconds),
            54f,
            BrandColorInts.TextPrimary,
            Gravity.CENTER
        ).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        summary.addView(countdownView, matchWidthWrapHeight())
        val ruleView = createTextView(
            lockCountdownRuleText(sessionMode),
            12f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        countdownRuleView = ruleView
        summary.addView(
            ruleView,
            matchWidthWrapHeight()
        )

        val summaryHost = FrameLayout(context).apply {
            addView(
                summary,
                centeredOverlayContentParams(maxWidthDp = 560).apply {
                    gravity = Gravity.CENTER
                }
            )
        }
        val summaryScroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                summaryHost,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val summaryScrollParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            bottomMargin = actionButtonSize + dp(LOCK_ACTION_BOTTOM_GAP_DP)
        }
        root.addView(summaryScroll, summaryScrollParams)

        val companionDock = createInlinePetCompanion()
        val petState = requireNotNull(companionDock.tag as? OverlayPetInterventionState)
        val companionDockParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
        }
        companionDock.visibility = View.GONE
        summary.addView(companionDock, companionDockParams)

        val actionButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val actions = listOf(
            Triple("暂停监督", R.drawable.ic_lock_action_pause, TAG_PAUSE_BUTTON),
            Triple("跳过本次监督", R.drawable.ic_lock_action_skip, TAG_SKIP_BUTTON),
            Triple("打开白名单 APP", R.drawable.ic_lock_action_apps, TAG_ALLOWED_APPS_BUTTON),
            Triple("闪记：快速记录想法", R.drawable.ic_lock_action_note, TAG_QUICK_NOTE_BUTTON)
        )
        val interventionActionButtons = mutableListOf<View>()
        actions.forEachIndexed { index, (description, icon, buttonTag) ->
            val actionButton = createOverlayActionButton(description, icon, buttonTag)
            if (buttonTag == TAG_PAUSE_BUTTON || buttonTag == TAG_SKIP_BUTTON) {
                interventionActionButtons += actionButton
            }
            actionButtons.addView(
                actionButton,
                linearParams(actionButtonSize, actionButtonSize).apply {
                    if (index > 0) marginStart = dp(LOCK_ACTION_BUTTON_SPACING_DP)
                }
            )
        }
        val actionButtonsParams = centeredOverlayContentParams(maxWidthDp = 480).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(LOCK_ACTION_BOTTOM_GAP_DP)
        }
        root.addView(actionButtons, actionButtonsParams)
        val lockPanelState = OverlayLockPanelState(
            companionDock = companionDock,
            petState = petState,
            summaryScroll = summaryScroll,
            summaryScrollParams = summaryScrollParams,
            actionButtonSize = actionButtonSize,
            brandMarkView = brandMarkView,
            interventionActionButtons = interventionActionButtons,
            actionButtons = actionButtons
        )
        root.tag = lockPanelState
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navigationBarBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val bottomGap = dp(LOCK_ACTION_BOTTOM_GAP_DP) + navigationBarBottom
            if (actionButtonsParams.bottomMargin != bottomGap) {
                actionButtonsParams.bottomMargin = bottomGap
                actionButtons.layoutParams = actionButtonsParams
                lockPanelState.updateNavigationBottomGap(bottomGap)
            }
            insets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
        return root
    }

    private fun createOverlayActionButton(
        description: String,
        iconResource: Int,
        buttonTag: String
    ): View {
        val accent = when (buttonTag) {
            TAG_PAUSE_BUTTON -> BrandColorInts.Primary
            TAG_SKIP_BUTTON -> BrandColorInts.Warning
            TAG_QUICK_NOTE_BUTTON -> BrandColorInts.Success
            else -> BrandColorInts.AppAccent
        }
        val iconPadding = dp(
            (LOCK_ACTION_BUTTON_SIZE_DP - LOCK_ACTION_ICON_SIZE_DP) / 2
        )
        return ImageButton(context).apply {
            tag = buttonTag
            isClickable = true
            isFocusable = true
            contentDescription = description
            tooltipText = description
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            minimumWidth = 0
            minimumHeight = 0
            background = createCircularRippleBackground(DARK_BUTTON, accent)
        }
    }

    private fun createInlinePetCompanion(): View {
        val state = OverlayPetInterventionState()
        val compact = resolveLockCompanionLayoutMode(
            screenHeightDp = context.resources.configuration.screenHeightDp.takeIf { it > 0 } ?: 640,
            fontScale = context.resources.configuration.fontScale
        ) == LockCompanionLayoutMode.COMPACT
        val contentPaddingDp = if (compact) 10 else 14
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(contentPaddingDp),
                dp(contentPaddingDp),
                dp(contentPaddingDp),
                dp(contentPaddingDp)
            )
            background = null
        }
        state.petView = ImageButton(context).apply {
            setImageDrawable(GrowingPlantDrawable(currentPetProfile.stage, showCircleBackground = false).apply { start() })
            contentDescription = "触摸 AI 小芽"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            background = createBorderlessCircularRippleBackground(BrandColorInts.Primary)
            setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                val (nextMotionIndex, motion) = nextPetTouchMotion(
                    currentPetProfile,
                    state.touchMotionIndex
                )
                state.touchMotionIndex = nextMotionIndex
                playPetTouchMotion(state.petView, motion)
                val touch = resolveLockPetTouch(
                    currentResponseIndex = state.touchResponseIndex,
                    lastAcceptedElapsedMillis = state.lastPetTouchElapsedMillis,
                    nowElapsedMillis = now,
                    responses = currentPetProfile.touchMessages
                )
                if (!touch.accepted) return@setOnClickListener
                state.touchResponseIndex = touch.responseIndex
                state.lastPetTouchElapsedMillis = now
                touch.message?.let(state::renderTransientMessage)
            }
        }

        val bubble = BubbleLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            cornerRadius = dp(16).toFloat()
            arrowHeight = dp(8).toFloat()
            arrowWidth = dp(12).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12) + dp(8))
        }
        state.titleView = createTextView(
            currentPetProfile.title,
            12f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        bubble.addView(state.titleView, matchWidthWrapHeight())
        state.messageView = createTextView(
            "",
            if (compact) 13f else 14f,
            BrandColorInts.TextPrimary,
            Gravity.CENTER
        ).apply {
            maxLines = if (compact) 2 else 3
            setLineSpacing(0f, 1.18f)
        }
        bubble.addView(state.messageView, matchWidthWrapHeight())

        val companionPrefs = com.example.controlfree.ai.AiCompanionPreferences.getInstance(context)
        if (companionPrefs.isLockChatEnabled()) {
            val hint = createTextView(
                "点击开启多轮聊天 💬",
                11f,
                BrandColorInts.Primary,
                Gravity.CENTER
            ).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            }
            state.chatHintView = hint
            bubble.addView(hint, matchWidthWrapHeight())
            bubble.isClickable = true
            bubble.setOnClickListener {
                val currentAction = state.uiState?.action ?: LockPendingAction.pause(1)
                if (
                    !onLockActivityRequest(
                        OverlayLockActivityRequest.Intervention(
                            action = currentAction,
                            openChat = true
                        )
                    )
                ) {
                    Toast.makeText(context, "暂时无法打开聊天", Toast.LENGTH_SHORT).show()
                }
            }
        }

        state.loadingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                ProgressBar(context).apply {
                    isIndeterminate = true
                    contentDescription = "正在更新建议"
                },
                linearParams(dp(14), dp(14)).apply { marginEnd = dp(6) }
            )
            addView(
                createTextView(LOCK_PET_LOADING_TEXT, 11f, MUTED_WHITE, Gravity.CENTER),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        bubble.addView(state.loadingRow, matchWidth(dp(24)))

        state.pauseQuickChoices = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        LOCK_PAUSE_PRESET_MINUTES.forEach { minutes ->
            val button = createPausePresetButton(minutes.toString()).apply {
                contentDescription = "暂停 $minutes 分钟"
            }
            state.pausePresetButtons[minutes] = button
            button.setOnClickListener {
                state.setSelectedPauseMinutes(minutes, notify = true)
                state.setCustomPauseVisible(false)
            }
            state.pauseQuickChoices.addView(
                circularCompanionButtonSlot(button),
                LinearLayout.LayoutParams(0, dp(48), 1f)
            )
        }
        state.customPauseButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            imageTintList = ColorStateList.valueOf(BrandColorInts.TextPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = createCircularRippleBackground(
                BrandColorInts.Canvas,
                BrandColorInts.TextPrimary
            )
            contentDescription = "调整 1 到 30 分钟"
            setOnClickListener { state.setCustomPauseVisible(!state.isCustomPauseVisible) }
        }
        state.pauseQuickChoices.addView(
            circularCompanionButtonSlot(state.customPauseButton),
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        state.customPauseArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        state.selectedMinutesView = createTextView(
            "1 分",
            12f,
            BrandColorInts.TextPrimary,
            Gravity.START
        )
        state.customPauseArea.addView(
            state.selectedMinutesView,
            LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        state.pauseSeekBar = SeekBar(context).apply {
            max = MAX_LOCK_PAUSE_MINUTES - MIN_LOCK_PAUSE_MINUTES
            progress = 1 - MIN_LOCK_PAUSE_MINUTES
            splitTrack = false
            progressDrawable = createModernPauseProgressDrawable()
            thumb = createModernPauseThumbDrawable()
            contentDescription = "暂停时长，当前 1 分钟"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        state.setSelectedPauseMinutes(
                            MIN_LOCK_PAUSE_MINUTES + progress,
                            notify = false
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    state.onPauseMinutesSelected(state.selectedPauseMinutes)
                }
            })
        }
        state.customPauseArea.addView(
            state.pauseSeekBar,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        state.continueSelfDisciplineButton = createCompactCompanionButton(
            LOCK_PET_CONTINUE_SELF_DISCIPLINE
        ).apply { setOnClickListener { state.onContinueSelfDiscipline() } }
        state.proceedButton = createCompactCompanionButton("成长值解锁").apply {
            setOnClickListener { state.onGrowthUnlock?.invoke(state.resolvedAction()) }
        }
        state.knowledgeChallengeButton = createCompactCompanionButton("百科挑战").apply {
            setOnClickListener { state.onKnowledgeChallenge?.invoke(state.resolvedAction()) }
        }
        buttonRow.addView(state.continueSelfDisciplineButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        buttonRow.addView(state.proceedButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(6)
        })
        buttonRow.addView(state.knowledgeChallengeButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(6)
        })

        val bubbleScroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                bubble,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        card.addView(
            bubbleScroll,
            LinearLayout.LayoutParams(dp(280), dp(130)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
        )
        card.addView(
            state.petView,
            linearParams(
                dp(if (compact) 74 else 92),
                dp(if (compact) 74 else 92)
            ).apply { bottomMargin = dp(10) }
        )
        card.addView(
            state.pauseQuickChoices,
            matchWidthWrapHeight().apply { bottomMargin = dp(10) }
        )
        card.addView(
            state.customPauseArea,
            matchWidthWrapHeight().apply { bottomMargin = dp(10) }
        )
        card.addView(
            buttonRow,
            matchWidthWrapHeight().apply { topMargin = dp(8) }
        )
        state.applyPetProfile(currentPetProfile)
        state.petView.doOnAttach { startPetIdleDance(it) }
        state.renderActionAvailability()
        return card.apply { tag = state }
    }

    private fun createCompactCompanionButton(text: String): Button =
        createOutlinedButton(text).apply {
            minWidth = 0
            minHeight = dp(48)
            textSize = 11f
            setPadding(dp(3), 0, dp(3), 0)
            maxLines = 1
        }

    private fun createPausePresetButton(text: String): Button = Button(context).apply {
        this.text = text
        textSize = 12f
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setTextColor(BrandColorInts.TextPrimary)
        setPadding(0, 0, 0, 0)
        background = createCircularRippleBackground(
            BrandColorInts.Canvas,
            BrandColorInts.TextPrimary
        )
    }

    private fun circularCompanionButtonSlot(button: View): FrameLayout = FrameLayout(context).apply {
        addView(
            button,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
        )
    }

    private fun createModernPauseProgressDrawable(): LayerDrawable {
        val inactive = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(BrandColorInts.OutlineSoft)
            setSize(1, dp(8))
        }
        val active = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(BrandColorInts.Primary)
            setSize(1, dp(8))
        }
        return LayerDrawable(
            arrayOf(inactive, ClipDrawable(active, Gravity.START, ClipDrawable.HORIZONTAL))
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun createModernPauseThumbDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(BrandColorInts.Primary)
        setStroke(dp(3), BrandColorInts.SurfaceCard)
        setSize(dp(24), dp(24))
    }

    private fun createCompanionButtonRow(first: Button, second: Button) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(first, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(3)
            })
            addView(second, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(3)
            })
        }

    private fun startPetIdleDance(petView: View) {
        petIdleAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetPetTransform(petView)
            return
        }
        petView.pivotX = petView.width / 2f
        petView.pivotY = petView.height.toFloat()
        petIdleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3_200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                val wave = sin(phase * Math.PI * 4.0).toFloat()
                petView.translationX = wave * dp(3)
                petView.translationY = 0f
                petView.rotation = wave * 5f
                val breathing = 1f + sin(phase * Math.PI * 2.0).toFloat() * 0.025f
                petView.scaleX = breathing
                petView.scaleY = breathing
            }
            start()
        }
    }

    private fun playPetTouchMotion(petView: View, motion: PetTouchMotion) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetPetTransform(petView)
            return
        }
        petIdleAnimator?.cancel()
        petTouchAnimator?.cancel()
        val translation = when (motion) {
            PetTouchMotion.SIDE_STEP -> floatArrayOf(0f, -dp(9).toFloat(), dp(9).toFloat(), 0f)
            PetTouchMotion.FULL_DANCE,
            PetTouchMotion.STAR_BURST -> floatArrayOf(0f, -dp(7).toFloat(), dp(7).toFloat(), 0f)
            else -> floatArrayOf(0f, -dp(2).toFloat(), dp(2).toFloat(), 0f)
        }
        val rotation = when (motion) {
            PetTouchMotion.NOD -> floatArrayOf(0f, -7f, 7f, 0f)
            PetTouchMotion.WAVE -> floatArrayOf(0f, -15f, 13f, -10f, 0f)
            PetTouchMotion.SPIN,
            PetTouchMotion.STAR_BURST -> floatArrayOf(0f, 360f)
            PetTouchMotion.GUARD_POSE -> floatArrayOf(0f, -9f, 0f)
            PetTouchMotion.CELEBRATION,
            PetTouchMotion.FULL_DANCE -> floatArrayOf(0f, -14f, 14f, -8f, 8f, 0f)
            PetTouchMotion.DOUBLE_BEAT,
            PetTouchMotion.SIDE_STEP -> floatArrayOf(0f, -6f, 6f, 0f)
        }
        val scale = when (motion) {
            PetTouchMotion.DOUBLE_BEAT,
            PetTouchMotion.CELEBRATION,
            PetTouchMotion.STAR_BURST -> floatArrayOf(1f, 1.14f, 0.94f, 1.1f, 1f)
            else -> floatArrayOf(1f, 0.95f, 1.08f, 1f)
        }
        val duration = when (motion) {
            PetTouchMotion.SPIN,
            PetTouchMotion.FULL_DANCE,
            PetTouchMotion.STAR_BURST -> 650L
            else -> 460L
        }
        petTouchAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(petView, View.TRANSLATION_X, *translation),
                ObjectAnimator.ofFloat(petView, View.ROTATION, *rotation),
                ObjectAnimator.ofFloat(petView, View.SCALE_X, *scale),
                ObjectAnimator.ofFloat(petView, View.SCALE_Y, *scale)
            )
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetPetTransform(petView)
                    if (petView.isAttachedToWindow) startPetIdleDance(petView)
                }

                override fun onAnimationCancel(animation: Animator) {
                    resetPetTransform(petView)
                }
            })
            start()
        }
    }

    private fun resetPetTransform(petView: View) {
        petView.translationX = 0f
        petView.translationY = 0f
        petView.rotation = 0f
        petView.scaleX = 1f
        petView.scaleY = 1f
    }

    private fun crossfadeOverlayMessage(messageView: TextView, message: String) {
        messageView.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !messageView.isShown) {
            messageView.alpha = 1f
            messageView.text = message
            return
        }
        messageView.animate()
            .alpha(0f)
            .setDuration(90L)
            .withEndAction {
                messageView.text = message
                messageView.animate().alpha(1f).setDuration(120L).start()
            }
            .start()
    }

    private fun createAllowedAppsPanel(apps: List<AllowedApp>): View {
        val configuration = context.resources.configuration
        val screenWidthDp = configuration.screenWidthDp.takeIf { it > 0 } ?: 360
        val screenHeightDp = configuration.screenHeightDp.takeIf { it > 0 } ?: 640
        val panelWidthDp = resolveAllowedAppsPanelWidthDp(screenWidthDp)
        val panelHeightDp = resolveAllowedAppsPanelHeightDp(
            appCount = apps.size,
            screenHeightDp = screenHeightDp,
            rowHeightDp = resolveAllowedAppRowHeightDp(configuration.fontScale)
        )
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(BrandColorInts.SurfaceCard)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            createTextView("白名单 APP", 20f, BrandColorInts.TextPrimary, Gravity.START).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            ImageButton(context).apply {
                tag = TAG_BACK_BUTTON
                setImageResource(R.drawable.ic_lock_close)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                imageTintMode = android.graphics.PorterDuff.Mode.SRC_IN
                contentDescription = "关闭白名单 APP"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = createBorderlessCircularRippleBackground(Color.WHITE)
            },
            linearParams(dp(48), dp(48))
        )
        card.addView(header, matchWidthWrapHeight().apply { bottomMargin = dp(8) })

        val contentArea = FrameLayout(context)
        val appsGrid = createAllowedAppsGrid(apps)
        val appsScroll = ScrollView(context).apply {
            addView(
                appsGrid,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        appsGrid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        val emptyView = createTextView(
            "暂未配置白名单 APP",
            14f,
            MUTED_WHITE,
            Gravity.CENTER
        ).apply { visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE }
        contentArea.addView(appsScroll, matchFrame())
        contentArea.addView(emptyView, matchFrame())
        card.addView(
            contentArea,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        allowedAppsGrid = appsGrid
        allowedAppsEmptyView = emptyView
        allowedAppsCard = card

        return FrameLayout(context).apply {
            setBackgroundColor(BrandColorInts.Canvas)
            addView(
                card,
                FrameLayout.LayoutParams(
                    dp(panelWidthDp),
                    dp(panelHeightDp),
                    Gravity.CENTER
                )
            )
        }
    }

    private fun createAllowedAppsGrid(apps: List<AllowedApp>): LinearLayout {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        populateAllowedAppsGrid(container, apps)
        return container
    }

    private fun populateAllowedAppsGrid(container: LinearLayout, apps: List<AllowedApp>) {
        container.removeAllViews()
        val configuration = context.resources.configuration
        val rowHeight = dp(resolveAllowedAppRowHeightDp(configuration.fontScale))
        val panelWidthDp = resolveAllowedAppsPanelWidthDp(
            configuration.screenWidthDp.takeIf { it > 0 } ?: 360
        )
        val contentWidthDp = (panelWidthDp - 40).coerceAtLeast(1)
        val iconSize = dp(
            resolveAllowedAppIconSizeDp(
                contentWidthDp = contentWidthDp,
                interColumnSpacingDp = 4
            )
        )
        val appRows = apps.chunked(LOCK_ALLOWED_APP_COLUMNS)
        appRows.forEachIndexed { rowIndex, rowApps ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            repeat(LOCK_ALLOWED_APP_COLUMNS) { index ->
                val app = rowApps.getOrNull(index)
                val item = if (app == null) {
                    View(context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                } else {
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        isFocusable = true
                        contentDescription = "打开${app.label}"
                        setPadding(dp(2), dp(6), dp(2), dp(6))
                        minimumHeight = rowHeight
                        setOnClickListener { onAllowedAppRequest(app.packageName) }

                        addView(ImageView(context).apply {
                            setImageDrawable(app.icon)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, linearParams(iconSize, iconSize))
                        addView(
                            createTextView(
                                app.label,
                                11f,
                                BrandColorInts.TextPrimary,
                                Gravity.CENTER
                            ).apply {
                                isSingleLine = false
                                ellipsize = null
                            },
                            matchWidthWrapHeight().apply { topMargin = dp(5) }
                        )
                    }
                }
                row.addView(
                    item,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
            }
            container.addView(
                row,
                matchWidthWrapHeight().apply {
                    if (rowIndex < appRows.lastIndex) bottomMargin = dp(8)
                }
            )
        }
    }

    private fun updateAllowedAppsPanelHeight(appCount: Int) {
        val card = allowedAppsCard ?: return
        val configuration = context.resources.configuration
        val screenHeightDp = configuration.screenHeightDp.takeIf { it > 0 } ?: 640
        val targetHeight = dp(
            resolveAllowedAppsPanelHeightDp(
                appCount = appCount,
                screenHeightDp = screenHeightDp,
                rowHeightDp = resolveAllowedAppRowHeightDp(configuration.fontScale)
            )
        )
        val params = card.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.height == targetHeight) return
        params.height = targetHeight
        card.layoutParams = params
    }

    private fun createPasswordPanel(
        executor: LockAuthenticationExecutor,
        actionProvider: () -> OverlayVerifiedAction?
    ): View {
        val panel = centeredPanel()
        val state = OverlayPasswordState(passwordLength())
        val dialKeySizeDp = resolveDialKeySizeDp()
        val dialRowHeightDp = dialKeySizeDp + 6
        panel.tag = state
        state.actionTitle = createTextView(
            "验证操作",
            24f,
            BrandColorInts.TextPrimary,
            Gravity.CENTER
        ).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        panel.addView(
            state.actionTitle,
            matchWidthWrapHeight().apply { bottomMargin = dp(8) }
        )
        state.actionDescription = createTextView(
            if (state.expectedLength == null) {
                "首次升级：输完旧密码后点击键盘下方确认"
            } else {
                "验证通过后执行所选操作"
            },
            13f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        panel.addView(
            state.actionDescription,
            matchWidthWrapHeight().apply { bottomMargin = dp(12) }
        )

        state.indicator = createTextView(
            "○  ○  ○  ○",
            22f,
            BrandColorInts.TextPrimary,
            Gravity.CENTER
        ).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panel.addView(state.indicator, matchWidthWrapHeight().apply { bottomMargin = dp(14) })
        state.inputStatus = createTextView(
            "",
            13f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        panel.addView(state.inputStatus, matchWidthWrapHeight().apply { bottomMargin = dp(8) })
        state.verificationStatus = createTextView(
            "",
            13f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        panel.addView(
            state.verificationStatus,
            matchWidthWrapHeight().apply { bottomMargin = dp(10) }
        )

        fun submitPassword() {
            if (state.isVerifying || state.digits.length < CredentialStore.MIN_PASSWORD_LENGTH) return
            val verifiedAction = actionProvider()
            if (verifiedAction == null) {
                val failure = VerificationResult(VerificationStatus.FAILURE)
                state.setVerificationMessage(lockVerificationMessage(failure))
                showVerificationResult(null, failure)
                return
            }
            val action = verifiedAction.request.action
            val enteredPassword = state.digits.toString()
            state.setVerifying(true)
            val accepted = executor.submit(
                task = { credentials.verifyPassword(enteredPassword) },
                onResult = { executionResult ->
                    state.setVerifying(false)
                    val verified = executionResult.getOrElse {
                        VerificationResult(VerificationStatus.FAILURE)
                    }
                    val result = completeVerification(verified, verifiedAction)
                    if (!result.isSuccess) state.clear()
                    state.setVerificationMessage(lockActionVerificationMessage(action, result))
                    showVerificationResult(action, result)
                }
            )
            if (!accepted) {
                state.setVerifying(false)
                state.clear()
                val failure = VerificationResult(VerificationStatus.FAILURE)
                state.setVerificationMessage(lockActionVerificationMessage(action, failure))
                showVerificationResult(action, failure)
            }
        }

        fun appendDigit(digit: Char) {
            if (state.isVerifying) return
            val expectedLength = state.expectedLength
            val inputLimit = expectedLength?.coerceIn(
                CredentialStore.MIN_PASSWORD_LENGTH,
                CredentialStore.MAX_PASSWORD_LENGTH
            ) ?: CredentialStore.MAX_PASSWORD_LENGTH
            if (state.digits.length >= inputLimit) return
            state.setVerificationMessage("")
            state.digits.append(digit)
            state.render()
            if (expectedLength != null && state.digits.length == expectedLength) submitPassword()
        }

        val dialPad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        listOf("123", "456", "789").forEach { rowDigits ->
            dialPad.addView(createDialRow(rowDigits.map { digit ->
                createDialButton(digit.toString(), "数字 $digit") { appendDigit(digit) }
                    .also(state.inputButtons::add)
            }, dialKeySizeDp), matchWidth(dp(dialRowHeightDp)).apply { bottomMargin = dp(8) })
        }

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val clearButton = createDialIconButton(
            iconResource = android.R.drawable.ic_menu_delete,
            description = "清空输入",
            keySizeDp = dialKeySizeDp
        ) { if (!state.isVerifying) state.clear() }
        state.clearButton = clearButton
        bottomRow.addView(clearButton, dialKeyParams(dialKeySizeDp))
        bottomRow.addView(
            createDialButton("0", "数字 0") { appendDigit('0') }
                .also(state.inputButtons::add),
            dialKeyParams(dialKeySizeDp).apply { marginStart = dp(12) }
        )
        val deleteButton = createDialButton("⌫", "删除一位") {
            if (!state.isVerifying && state.digits.isNotEmpty()) {
                state.digits.deleteCharAt(state.digits.lastIndex)
                state.render()
            }
        }
        state.deleteButton = deleteButton
        bottomRow.addView(
            deleteButton,
            dialKeyParams(dialKeySizeDp).apply { marginStart = dp(12) }
        )
        dialPad.addView(bottomRow, matchWidth(dp(dialRowHeightDp)))
        panel.addView(dialPad, matchWidthWrapHeight().apply { bottomMargin = dp(10) })

        if (state.expectedLength == null) {
            val confirmButton = createButton(
                "确认输入",
                BrandColorInts.Primary,
                BrandColorInts.OnPrimary
            ).apply {
                contentDescription = "确认输入"
                setOnClickListener { submitPassword() }
            }
            state.confirmButton = confirmButton
            panel.addView(
                confirmButton,
                matchWidthWrapHeight().apply { bottomMargin = dp(10) }
            )
        }

        if (hasGesture()) {
            val gestureButton = createButton(
                "返回手势",
                DARK_BUTTON,
                BrandColorInts.TextPrimary
            ).apply { tag = TAG_GESTURE_BUTTON }
            state.alternativeButton = gestureButton
            panel.addView(
                gestureButton,
                matchWidthWrapHeight().apply { bottomMargin = dp(8) }
            )
        }

        val backButton = createButton("取消操作", DARK_BUTTON, BrandColorInts.TextPrimary).apply {
            tag = TAG_BACK_BUTTON
        }
        state.backButton = backButton
        panel.addView(
            backButton,
            matchWidthWrapHeight()
        )
        state.render()
        return wrapCentered(panel).apply { tag = state }
    }

    private fun createGesturePanel(
        executor: LockAuthenticationExecutor,
        actionProvider: () -> OverlayVerifiedAction?
    ): View {
        val panel = centeredPanel()
        val configuration = context.resources.configuration
        val panelWidthDp = minOf(
            configuration.screenWidthDp.takeIf { it > 0 } ?: 360,
            MAX_AUTH_PANEL_WIDTH_DP
        )
        val availableWidth = (panelWidthDp - AUTH_PANEL_HORIZONTAL_PADDING_DP).coerceAtLeast(1)
        val gestureSize = minOf(MAX_GESTURE_SIZE_DP, availableWidth)
        val actionTitle = createTextView(
            "验证操作",
            24f,
            BrandColorInts.TextPrimary,
            Gravity.CENTER
        ).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        panel.addView(actionTitle, matchWidthWrapHeight().apply { bottomMargin = dp(8) })
        val actionDescription = createTextView(
            "验证通过后执行所选操作",
            13f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        panel.addView(
            actionDescription,
            matchWidthWrapHeight().apply { bottomMargin = dp(12) }
        )
        val patternView = PatternLockView(context).apply {
            contentDescription = "九宫格手势密码"
        }
        val state = OverlayGestureState(patternView)
        state.actionTitle = actionTitle
        state.actionDescription = actionDescription
        state.verificationStatus = createTextView(
            "绘制已设置的手势",
            13f,
            MUTED_WHITE,
            Gravity.CENTER
        )
        panel.addView(
            state.verificationStatus,
            matchWidthWrapHeight().apply { bottomMargin = dp(10) }
        )
        patternView.onPatternComplete = patternComplete@ { pattern ->
            if (state.isVerifying) return@patternComplete
            val verifiedAction = actionProvider()
            if (verifiedAction == null) {
                val failure = VerificationResult(VerificationStatus.FAILURE)
                state.setVerificationMessage(lockVerificationMessage(failure))
                showVerificationResult(null, failure)
                state.patternView.clearPattern()
                return@patternComplete
            }
            val action = verifiedAction.request.action
            state.setVerifying(true)
            val accepted = executor.submit(
                task = { credentials.verifyGesture(pattern) },
                onResult = { executionResult ->
                    state.setVerifying(false)
                    state.patternView.clearPattern()
                    val verified = executionResult.getOrElse {
                        VerificationResult(VerificationStatus.FAILURE)
                    }
                    val result = completeVerification(verified, verifiedAction)
                    state.setVerificationMessage(lockActionVerificationMessage(action, result))
                    showVerificationResult(action, result)
                }
            )
            if (!accepted) {
                state.setVerifying(false)
                state.patternView.clearPattern()
                val failure = VerificationResult(VerificationStatus.FAILURE)
                state.setVerificationMessage(lockActionVerificationMessage(action, failure))
                showVerificationResult(action, failure)
            }
        }
        panel.addView(
            patternView,
            linearParams(dp(gestureSize), dp(gestureSize)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        )
        if (hasPassword()) {
            val passwordButton = createButton(
                "数字密码",
                DARK_BUTTON,
                BrandColorInts.TextPrimary
            ).apply { tag = TAG_PASSWORD_BUTTON }
            state.alternativeButton = passwordButton
            panel.addView(
                passwordButton,
                matchWidthWrapHeight().apply { bottomMargin = dp(8) }
            )
        }
        val backButton = createButton("取消操作", DARK_BUTTON, BrandColorInts.TextPrimary).apply {
            tag = TAG_BACK_BUTTON
        }
        state.backButton = backButton
        panel.addView(
            backButton,
            matchWidthWrapHeight()
        )
        return wrapCentered(panel).apply { tag = state }
    }

    private fun centeredPanel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
    }

    private fun wrapCentered(
        content: View,
        scrollable: Boolean = true,
        maxWidthDp: Int = 720
    ): View {
        val childParams = centeredOverlayContentParams(maxWidthDp)
        return if (scrollable) {
            val centeredHost = FrameLayout(context).apply {
                setBackgroundColor(BrandColorInts.Canvas)
                addView(content, childParams)
            }
            ScrollView(context).apply {
                setBackgroundColor(BrandColorInts.Canvas)
                isFillViewport = true
                addView(
                    centeredHost,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        } else {
            FrameLayout(context).apply {
                setBackgroundColor(BrandColorInts.Canvas)
                addView(content, childParams)
            }
        }
    }

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showVerificationResult(
        action: LockPendingAction?,
        result: VerificationResult
    ) {
        val message = action?.let { lockActionVerificationMessage(it, result) }
            ?: lockVerificationMessage(result)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun completeVerification(
        result: VerificationResult,
        verifiedAction: OverlayVerifiedAction
    ): VerificationResult {
        if (!result.isSuccess) return result
        return if (requestAction(verifiedAction)) {
            result
        } else {
            VerificationResult(VerificationStatus.FAILURE)
        }
    }

    private fun requestAction(verifiedAction: OverlayVerifiedAction): Boolean {
        val request = verifiedAction.request
        var lockSessionId = verifiedAction.lockSessionId
        if (!RuntimeLockTruthRegistry.isCurrentSession(lockSessionId)) {
            // 残留窗口常携带轮换前的旧会话。直接判失败会把正确的凭据验证改写成
            // "验证失败，请重试"；先向锁定真值仲裁：锁定确已结束就自拆窗口并按
            // 成功收尾（没有锁可解不是用户输错密码），仍在锁定则采纳当前会话。
            val directive = try {
                resolveOverlayTruthDirective(
                    decision = lockTruthRepository.resolve(lockSessionId),
                    attachedSessionId = lockSessionId
                )
            } catch (_: RuntimeException) {
                null
            } ?: return false
            when (directive) {
                OverlayTruthDirective.Dismiss -> {
                    hide()
                    return true
                }
                is OverlayTruthDirective.Continue -> {
                    if (directive.sessionId == NO_LOCK_SESSION) return false
                    lockSessionId = directive.sessionId
                    attachedLockSessionId = directive.sessionId
                }
            }
        }
        if (
            MonitorService.isRunning &&
            requestActionFromRunningService(lockSessionId, request)
        ) {
            return true
        }
        // 服务未运行或普通投递失败时，用前台服务命令唤醒监督并携带原动作；
        // 全屏悬浮窗正被用户操作，满足后台启动前台服务的豁免条件。
        if (requestActionViaForegroundService(lockSessionId, request)) return true
        return handOffVerifiedActionToActivity(request)
    }

    private fun requestActionViaForegroundService(
        lockSessionId: Long,
        request: AuthorizedLockAction
    ): Boolean = try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MonitorService::class.java).apply {
                this.action = request.action.serviceAction
                putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
                putExtra(
                    MonitorService.EXTRA_COMMAND_ELAPSED_MILLIS,
                    SystemClock.elapsedRealtime()
                )
                LockActionContract.writeServiceExtras(this, request)
            }
        )
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun requestActionFromRunningService(
        lockSessionId: Long,
        request: AuthorizedLockAction
    ): Boolean = try {
        // 服务已经处于前台时只投递命令，不触发新的前台服务启动限制。
        context.startService(Intent(context, MonitorService::class.java).apply {
            this.action = request.action.serviceAction
            putExtra(MonitorService.EXTRA_LOCK_SESSION_ID, lockSessionId)
            putExtra(
                MonitorService.EXTRA_COMMAND_ELAPSED_MILLIS,
                SystemClock.elapsedRealtime()
            )
            LockActionContract.writeServiceExtras(this, request)
        })
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun handOffVerifiedActionToActivity(
        request: AuthorizedLockAction
    ): Boolean {
        // 陈旧悬浮层不能从后台可靠拉起前台服务。用户刚刚主动完成认证，先把未导出的
        // LockActivity 带到前台，再由其一次性投递原动作，符合 Android 后台启动限制。
        return onLockActivityRequest(OverlayLockActivityRequest.VerifiedAction(request))
    }

    private fun getAuthenticationExecutor(): LockAuthenticationExecutor =
        authenticationExecutor ?: LockAuthenticationExecutor(
            ContextCompat.getMainExecutor(context)
        ).also { authenticationExecutor = it }

    /** 优先取进程内实时会话；注册表为空（服务失联）时沿用窗口已采纳的会话。 */
    private fun currentActionSessionId(): Long =
        RuntimeLockTruthRegistry.currentSessionId()
            .takeIf { it != NO_LOCK_SESSION }
            ?: attachedLockSessionId

    private fun createTextView(text: String, size: Float, color: Int, gravity: Int) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            this.gravity = gravity
        }

    private fun createButton(text: String, backgroundColor: Int, textColor: Int) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(textColor)
        backgroundTintList = null
        background = createRoundedRippleBackground(backgroundColor, textColor)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(48)
    }

    private fun createOutlinedButton(text: String) = createButton(
        text,
        BrandColorInts.Canvas,
        BrandColorInts.TextPrimary
    ).apply {
        minHeight = dp(40)
        background = createRoundedRippleBackground(
            backgroundColor = BrandColorInts.Canvas,
            rippleBaseColor = BrandColorInts.TextPrimary,
            strokeColor = BrandColorInts.Outline
        )
    }

    private fun createRoundedRippleBackground(
        backgroundColor: Int,
        rippleBaseColor: Int,
        strokeColor: Int? = null
    ) =
        RippleDrawable(
            ColorStateList.valueOf((rippleBaseColor and 0x00FFFFFF) or 0x26000000),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(backgroundColor)
                strokeColor?.let { setStroke(dp(1), it) }
            },
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
        )

    private fun createCircularRippleBackground(
        backgroundColor: Int,
        rippleBaseColor: Int
    ) = RippleDrawable(
        ColorStateList.valueOf((rippleBaseColor and 0x00FFFFFF) or 0x26000000),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundColor)
            setStroke(dp(1), BrandColorInts.Outline)
        },
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
    )

    private fun createBorderlessCircularRippleBackground(rippleBaseColor: Int) = RippleDrawable(
        ColorStateList.valueOf((rippleBaseColor and 0x00FFFFFF) or 0x26000000),
        null,
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    )

    // 宠物形象专用：保留发光圆盘作为成长阶段反馈，但不画轮廓描边（用户反馈圆圈线突兀）。
    private fun createStrokelessGlowRippleBackground(
        backgroundColor: Int,
        rippleBaseColor: Int
    ) = RippleDrawable(
        ColorStateList.valueOf((rippleBaseColor and 0x00FFFFFF) or 0x26000000),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundColor)
        },
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    )

    private fun createDialRow(
        buttons: List<Button>,
        keySizeDp: Int
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        buttons.forEachIndexed { index, button ->
            addView(
                button,
                dialKeyParams(keySizeDp).apply { if (index > 0) marginStart = dp(12) }
            )
        }
    }

    /**
     * 拨号键按下即录入：快速连点、双指交替或外层 ScrollView 抢占手势时不会漏掉按键。
     * 触摸事件在此消费；无障碍服务仍通过 performClick 走 OnClickListener 路径。
     */
    private fun bindDialKeyPressListener(key: View, onPress: () -> Unit) {
        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    view.isPressed = true
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onPress()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            true
        }
    }

    private fun createDialButton(
        text: String,
        description: String,
        onClick: () -> Unit
    ) = Button(context).apply {
        this.text = text
        contentDescription = description
        isAllCaps = false
        textSize = if (text.length == 1 && text[0].isDigit()) 23f else 20f
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(
            10,
            if (text.length == 1 && text[0].isDigit()) 23 else 20,
            1,
            TypedValue.COMPLEX_UNIT_SP
        )
        setTextColor(BrandColorInts.TextPrimary)
        backgroundTintList = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(DARK_BUTTON)
        }
        setOnClickListener { onClick() }
        bindDialKeyPressListener(this, onClick)
    }

    private fun createDialIconButton(
        iconResource: Int,
        description: String,
        keySizeDp: Int,
        onClick: () -> Unit
    ) = ImageButton(context).apply {
        setImageResource(iconResource)
        imageTintList = ColorStateList.valueOf(BrandColorInts.TextPrimary)
        contentDescription = description
        val iconPaddingDp = (keySizeDp / 4).coerceIn(8, 18)
        setPadding(dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(DARK_BUTTON)
        }
        setOnClickListener { onClick() }
        bindDialKeyPressListener(this, onClick)
    }

    private fun dialKeyParams(keySizeDp: Int) =
        LinearLayout.LayoutParams(dp(keySizeDp), dp(keySizeDp))

    private fun resolveDialKeySizeDp(): Int {
        val configuration = context.resources.configuration
        val panelWidthDp = minOf(
            configuration.screenWidthDp.takeIf { it > 0 } ?: 360,
            MAX_AUTH_PANEL_WIDTH_DP
        )
        val availableWidthDp = (panelWidthDp - AUTH_PANEL_HORIZONTAL_PADDING_DP).coerceAtLeast(1)
        val widthBoundDp = ((availableWidthDp - DIAL_PAD_TOTAL_GAP_DP).coerceAtLeast(3)) / 3
        val desiredSizeDp = (
            64f + (configuration.fontScale.coerceAtLeast(1f) - 1f) * 20f
            ).toInt().coerceAtMost(96)
        return minOf(desiredSizeDp, widthBoundDp).coerceAtLeast(1)
    }

    private fun matchFrame() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun linearParams(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun matchWidth(height: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    private fun centeredOverlayContentParams(maxWidthDp: Int = 720): FrameLayout.LayoutParams {
        val screenWidthDp = context.resources.configuration.screenWidthDp
        val width = if (screenWidthDp > maxWidthDp) {
            dp(maxWidthDp)
        } else {
            FrameLayout.LayoutParams.MATCH_PARENT
        }
        return FrameLayout.LayoutParams(
            width,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun formatTime(seconds: Int) =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private inner class OverlayLockPanelState(
        private val companionDock: View,
        val petState: OverlayPetInterventionState,
        private val summaryScroll: ScrollView,
        private val summaryScrollParams: FrameLayout.LayoutParams,
        private val actionButtonSize: Int,
        private val brandMarkView: View,
        private val interventionActionButtons: List<View>,
        private val actionButtons: View
    ) {
        private var navigationBottomGap = dp(LOCK_ACTION_BOTTOM_GAP_DP)
        private var companionShown = false

        fun isCompanionVisible(): Boolean = companionShown

        fun showCompanion() {
            companionDock.animate().cancel()
            actionButtons.animate().cancel()
            companionShown = true
            setInterventionActionsEnabled(false)
            brandMarkView.visibility = View.GONE
            companionDock.visibility = View.VISIBLE
            
            // 隐藏底栏按钮
            if (!ValueAnimator.areAnimatorsEnabled()) {
                actionButtons.visibility = View.GONE
                actionButtons.alpha = 1f
                companionDock.alpha = 1f
                companionDock.translationY = 0f
            } else {
                actionButtons.animate()
                    .alpha(0f)
                    .setDuration(120L)
                    .withEndAction { actionButtons.visibility = View.GONE }
                    .start()
                companionDock.alpha = 0f
                companionDock.translationY = dp(16).toFloat()
                companionDock.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .start()
            }
            
            updateReservedSpace()
        }

        fun hideCompanion() {
            if (!companionShown) {
                setInterventionActionsEnabled(true)
                updateReservedSpace()
                return
            }
            companionShown = false
            setInterventionActionsEnabled(true)
            brandMarkView.visibility = View.VISIBLE
            companionDock.animate().cancel()
            actionButtons.animate().cancel()
            
            // 恢复底栏按钮
            actionButtons.visibility = View.VISIBLE
            if (!ValueAnimator.areAnimatorsEnabled()) {
                companionDock.visibility = View.GONE
                companionDock.alpha = 1f
                companionDock.translationY = 0f
                actionButtons.alpha = 1f
            } else {
                actionButtons.alpha = 0f
                actionButtons.animate()
                    .alpha(1f)
                    .setDuration(150L)
                    .start()
                companionDock.animate()
                    .alpha(0f)
                    .translationY(dp(10).toFloat())
                    .setDuration(120L)
                    .withEndAction {
                        companionDock.visibility = View.GONE
                        companionDock.alpha = 1f
                        companionDock.translationY = 0f
                    }
                    .start()
            }
            
            updateReservedSpace()
        }

        fun updateNavigationBottomGap(bottomGap: Int) {
            navigationBottomGap = bottomGap
            updateReservedSpace()
        }

        private fun updateReservedSpace() {
            summaryScrollParams.bottomMargin = if (isCompanionVisible()) {
                navigationBottomGap
            } else {
                actionButtonSize + navigationBottomGap
            }
            summaryScroll.layoutParams = summaryScrollParams
        }

        private fun setInterventionActionsEnabled(enabled: Boolean) {
            interventionActionButtons.forEach { button ->
                button.isEnabled = enabled
                button.alpha = if (enabled) 1f else 0.38f
            }
        }
    }

    private inner class OverlayPetInterventionState {
        lateinit var petView: ImageButton
        lateinit var titleView: TextView
        lateinit var messageView: TextView
        var chatHintView: TextView? = null
        lateinit var loadingRow: View
        lateinit var continueSelfDisciplineButton: Button
        lateinit var proceedButton: Button
        lateinit var knowledgeChallengeButton: Button
        lateinit var pauseQuickChoices: LinearLayout
        lateinit var customPauseArea: LinearLayout
        lateinit var customPauseButton: View
        lateinit var selectedMinutesView: TextView
        lateinit var pauseSeekBar: SeekBar
        val pausePresetButtons = linkedMapOf<Int, Button>()
        var selectedPauseMinutes = 5
            private set
        var isCustomPauseVisible = false
            private set
        var touchResponseIndex = -1
        var touchMotionIndex = -1
        var lastPetTouchElapsedMillis: Long? = null
        var onPauseMinutesSelected: (Int) -> Unit = {}
        var onContinueSelfDiscipline: () -> Boolean = { false }
        var onKnowledgeChallenge: ((LockPendingAction) -> Unit)? = null
        var onGrowthUnlock: ((LockPendingAction) -> Unit)? = null
        var onActionChanged: (LockPendingAction) -> Unit = {}
        var uiState: LockPetInterventionUiState? = null
            private set

        fun applyPetProfile(profile: PetStageProfile) {
            touchMotionIndex = -1
            touchResponseIndex = -1
            if (::titleView.isInitialized) {
                titleView.text = "${profile.expressionMark}  ${profile.title}"
            }
            if (::petView.isInitialized) {
                petView.contentDescription = "触摸${profile.stage.displayName}阶段小芽"
                val glowAlpha = (profile.appearanceEffect.glowAlpha * 255f)
                    .toInt()
                    .coerceIn(0, 255)
                val glowColor = (BrandColorInts.Primary and 0x00FFFFFF) or
                    (glowAlpha shl 24)
                petView.background = createStrokelessGlowRippleBackground(
                    glowColor,
                    BrandColorInts.Primary
                )
                petView.elevation = dp(profile.appearanceEffect.haloLayers * 3).toFloat()
            }
        }

        fun render(state: LockPetInterventionUiState) {
            uiState = state
            if (::messageView.isInitialized) crossfadeOverlayMessage(messageView, state.message)
            if (::loadingRow.isInitialized) {
                loadingRow.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            }
            chatHintView?.let { hint ->
                hint.visibility = if (state.isLoading) View.GONE else View.VISIBLE
            }
            if (state.action.kind == LockPendingActionKind.PAUSE) {
                setSelectedPauseMinutes(requireNotNull(state.action.pauseMinutes), notify = false)
            }
            if (::pauseQuickChoices.isInitialized) {
                pauseQuickChoices.visibility = if (state.action.kind == LockPendingActionKind.PAUSE) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
            if (state.action.kind != LockPendingActionKind.PAUSE) setCustomPauseVisible(false)
            if (::proceedButton.isInitialized) {
                proceedButton.text = "成长值解锁"
                proceedButton.contentDescription = "使用成长值并继续验证"
            }
            onActionChanged(resolvedAction())
        }

        fun renderTransientMessage(message: String) {
            if (::messageView.isInitialized) crossfadeOverlayMessage(messageView, message)
        }

        fun resolvedAction(): LockPendingAction = when (uiState?.action?.kind) {
            LockPendingActionKind.PAUSE -> LockPendingAction.pause(selectedPauseMinutes)
            else -> LockPendingAction.Skip
        }

        fun setSelectedPauseMinutes(minutes: Int, notify: Boolean) {
            selectedPauseMinutes = minutes.coerceIn(
                MIN_LOCK_PAUSE_MINUTES,
                MAX_LOCK_PAUSE_MINUTES
            )
            if (::selectedMinutesView.isInitialized) {
                selectedMinutesView.text = "$selectedPauseMinutes 分"
            }
            if (::pauseSeekBar.isInitialized) {
                val progress = selectedPauseMinutes - MIN_LOCK_PAUSE_MINUTES
                if (pauseSeekBar.progress != progress) pauseSeekBar.progress = progress
                pauseSeekBar.contentDescription = "暂停时长，当前 $selectedPauseMinutes 分钟"
            }
            pausePresetButtons.forEach { (preset, button) ->
                val selected = preset == selectedPauseMinutes
                button.alpha = if (selected) 1f else 0.72f
                button.background = createCircularRippleBackground(
                    backgroundColor = if (selected) {
                        (BrandColorInts.Primary and 0x00FFFFFF) or 0x2E000000
                    } else {
                        BrandColorInts.Canvas
                    },
                    rippleBaseColor = BrandColorInts.TextPrimary
                )
            }
            if (::proceedButton.isInitialized) {
                proceedButton.text = "成长值解锁"
                proceedButton.contentDescription = "使用成长值并继续验证"
            }
            onActionChanged(resolvedAction())
            if (notify) onPauseMinutesSelected(selectedPauseMinutes)
        }

        fun setCustomPauseVisible(visible: Boolean) {
            isCustomPauseVisible = visible
            if (::customPauseArea.isInitialized) {
                customPauseArea.visibility = if (visible) View.VISIBLE else View.GONE
            }
            if (::customPauseButton.isInitialized) {
                customPauseButton.contentDescription = if (visible) {
                    "收起 1 到 30 分钟调整"
                } else {
                    "展开 1 到 30 分钟调整"
                }
            }
        }

        fun renderActionAvailability() {
            if (::proceedButton.isInitialized) {
                proceedButton.isEnabled = onGrowthUnlock != null
                proceedButton.visibility = if (proceedButton.isEnabled) View.VISIBLE else View.GONE
            }
            if (::knowledgeChallengeButton.isInitialized) {
                knowledgeChallengeButton.isEnabled = onKnowledgeChallenge != null
                knowledgeChallengeButton.visibility =
                    if (knowledgeChallengeButton.isEnabled) View.VISIBLE else View.GONE
            }
        }
    }

    private data class OverlayVerifiedAction(
        val request: AuthorizedLockAction,
        val lockSessionId: Long
    )

    private class OverlayPasswordState(val expectedLength: Int?) {
        val digits = StringBuilder()
        val inputButtons = mutableListOf<View>()
        lateinit var actionTitle: TextView
        lateinit var actionDescription: TextView
        lateinit var indicator: TextView
        lateinit var inputStatus: TextView
        lateinit var verificationStatus: TextView
        var confirmButton: Button? = null
        var clearButton: View? = null
        var deleteButton: View? = null
        var alternativeButton: View? = null
        var backButton: View? = null
        var isVerifying: Boolean = false
            private set

        fun setAction(action: LockPendingAction) {
            if (::actionTitle.isInitialized) actionTitle.text = action.title
            if (::actionDescription.isInitialized && expectedLength != null) {
                actionDescription.text = action.confirmationText
            }
        }

        fun clear() {
            digits.clear()
            if (::verificationStatus.isInitialized) verificationStatus.text = ""
            render()
        }

        fun setVerificationMessage(message: String) {
            if (::verificationStatus.isInitialized) verificationStatus.text = message
        }

        fun setVerifying(verifying: Boolean) {
            isVerifying = verifying
            if (verifying) setVerificationMessage("正在验证…")
            render()
        }

        fun cancelVerification() {
            isVerifying = false
            clear()
        }

        fun render() {
            if (::indicator.isInitialized) {
                indicator.text = if (digits.isEmpty()) {
                    "○  ○  ○  ○"
                } else {
                    List(digits.length) { "●" }.joinToString("  ")
                }
            }
            if (::inputStatus.isInitialized) {
                inputStatus.text = expectedLength?.let { "已输入 ${digits.length}/$it 位" }
                    ?: "已输入 ${digits.length} 位"
            }
            confirmButton?.let { button ->
                val canConfirm = !isVerifying && digits.length >= CredentialStore.MIN_PASSWORD_LENGTH
                button.isEnabled = canConfirm
                button.alpha = if (canConfirm) 1f else 0.35f
            }
            val hasInput = digits.isNotEmpty()
            listOf(clearButton, deleteButton).forEach { button ->
                val enabled = !isVerifying && hasInput
                button?.isEnabled = enabled
                button?.alpha = if (enabled) 1f else 0.35f
            }
            inputButtons.forEach { button ->
                button.isEnabled = !isVerifying
                button.alpha = if (isVerifying) 0.35f else 1f
            }
            listOf(alternativeButton, backButton).forEach { button ->
                button?.isEnabled = !isVerifying
                button?.alpha = if (isVerifying) 0.35f else 1f
            }
        }
    }

    private class OverlayGestureState(val patternView: PatternLockView) {
        lateinit var actionTitle: TextView
        lateinit var actionDescription: TextView
        lateinit var verificationStatus: TextView
        var alternativeButton: View? = null
        var backButton: View? = null
        var isVerifying: Boolean = false
            private set

        fun setAction(action: LockPendingAction) {
            if (::actionTitle.isInitialized) actionTitle.text = action.title
            if (::actionDescription.isInitialized) {
                actionDescription.text = action.confirmationText
            }
        }

        fun setVerifying(verifying: Boolean) {
            isVerifying = verifying
            if (verifying) setVerificationMessage("正在验证…")
            patternView.isEnabled = !verifying
            listOf(alternativeButton, backButton).forEach { button ->
                button?.isEnabled = !verifying
                button?.alpha = if (verifying) 0.35f else 1f
            }
        }

        fun setVerificationMessage(message: String) {
            if (::verificationStatus.isInitialized) verificationStatus.text = message
        }

        fun cancelVerification() {
            setVerifying(false)
            patternView.clearPattern()
            setVerificationMessage("绘制已设置的手势")
        }
    }

    companion object {
        private const val TAG_PASSWORD_BUTTON = "password_button"
        private const val TAG_GESTURE_BUTTON = "gesture_button"
        private const val TAG_BACK_BUTTON = "back_button"
        private const val TAG_PAUSE_BUTTON = "pause_button"
        private const val TAG_SKIP_BUTTON = "skip_button"
        private const val TAG_ALLOWED_APPS_BUTTON = "allowed_apps_button"
        private const val TAG_QUICK_NOTE_BUTTON = "quick_note_button"

        private const val MAX_AUTH_PANEL_WIDTH_DP = 720
        private const val AUTH_PANEL_HORIZONTAL_PADDING_DP = 56
        private const val MAX_GESTURE_SIZE_DP = 280
        private const val DIAL_PAD_TOTAL_GAP_DP = 24
        private const val OVERLAY_REMOVAL_RETRY_DELAY_MILLIS = 250L
        private const val OVERLAY_REMOVAL_COOLDOWN_MILLIS = 4_000L
        private const val LOCK_TRUTH_SYNC_INTERVAL_MILLIS = 1_000L

        private val DARK_BUTTON = BrandColorInts.SurfaceRaised
        private val MUTED_WHITE = BrandColorInts.TextSecondary
        private const val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

private class BubbleLayout(context: Context) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BrandColorInts.SurfaceRaised
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BrandColorInts.OutlineSoft
    }
    private val path = Path()

    var cornerRadius = 0f
    var arrowHeight = 0f
    var arrowWidth = 0f

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokePaint.strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            resources.displayMetrics
        )
        val strokeHalf = strokePaint.strokeWidth / 2f
        val rectWidth = width.toFloat() - strokeHalf
        val rectHeight = height.toFloat() - arrowHeight - strokeHalf

        path.reset()
        // 绘制圆角矩形
        path.addRoundRect(
            strokeHalf, strokeHalf, rectWidth, rectHeight,
            cornerRadius, cornerRadius,
            Path.Direction.CW
        )

        // 绘制三角指示箭头指向底部中间
        val centerX = width.toFloat() / 2f
        path.moveTo(centerX - arrowWidth / 2f, rectHeight)
        path.lineTo(centerX, rectHeight + arrowHeight)
        path.lineTo(centerX + arrowWidth / 2f, rectHeight)
        path.close()

        canvas.drawPath(path, paint)
        canvas.drawPath(path, strokePaint)
    }
}
