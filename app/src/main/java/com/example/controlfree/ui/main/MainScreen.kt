package com.example.controlfree.ui.main

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.growth.GrowthRepository
import com.example.controlfree.growth.GrowthAccount
import com.example.controlfree.growth.GrowthLedgerEntry
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material.icons.filled.TaskAlt
import com.example.controlfree.ui.todo.TodoMainScreen
import com.example.controlfree.ui.todo.TodoSubTab
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.todo.search.SuperSearchScreen
import com.example.controlfree.ui.todo.search.SuperSearchSource
import com.example.controlfree.ui.todo.search.SuperSearchViewModel
import com.example.controlfree.widget.WidgetPlanDestination
import com.example.controlfree.widget.WidgetPlanNavigationRequest
import com.example.controlfree.widget.WidgetTodoNavigationRequest
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.controlfree.MonitorService
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.R
import com.example.controlfree.data.AllowlistRepository
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.diagnostics.AndroidDiagnostics
import com.example.controlfree.diagnostics.DiagnosticClearResult
import com.example.controlfree.diagnostics.SelfCheckReport
import com.example.controlfree.diagnostics.SelfCheckRunner
import com.example.controlfree.runtime.UsageAccessSettingsDestination
import com.example.controlfree.runtime.UsageAccessSettingsNavigator
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.layout.ResponsiveLayoutSpec
import com.example.controlfree.ui.layout.rememberResponsiveLayoutSpec
import com.example.controlfree.ui.statistics.StatisticsContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_RECOVERY_START_ATTEMPTS = 3
private const val ANDROID_14_API_LEVEL = 34
internal const val MAIN_BOTTOM_BAR_MIN_HEIGHT_DP = 64
internal const val MAIN_BOTTOM_BAR_CONTENT_OFFSET_DP = 4

internal fun shouldShowMainBottomBar(
    useNavigationRail: Boolean,
    isImeVisible: Boolean
): Boolean = !useNavigationRail && !isImeVisible

internal fun shouldNavigateFromSuperSearch(source: SuperSearchSource): Boolean =
    source.tab.isVisibleInChecklist

internal fun shouldRequestFullScreenIntentAccess(
    sdkInt: Int,
    canUseFullScreenIntent: Boolean
): Boolean = sdkInt >= ANDROID_14_API_LEVEL && !canUseFullScreenIntent

enum class FocusSubTab(val displayName: String) {
    QUICK("快速专注"),
    TIMED("定时专注")
}

enum class MainTab(val displayName: String) {
    MONITOR("监督"),
    FOCUS("专注"),
    TODO("清单"),
    STATISTICS("统计"),
    SETTINGS("我的")
}

enum class SettingsSubTab(val displayName: String) {
    GROWTH_CENTER("成长中心"),
    AI_PET("AI设置"),
    QUESTION_BANK("百科题库"),
    SYSTEM_PERMISSIONS("系统设置")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    initialTodoSubTab: TodoSubTab = TodoSubTab.TODO,
    onTodoSubTabSelected: (TodoSubTab) -> Unit = {},
    widgetTodoNavigationRequest: WidgetTodoNavigationRequest? = null,
    onWidgetTodoNavigationConsumed: (WidgetTodoNavigationRequest) -> Unit = {},
    widgetPlanNavigationRequest: WidgetPlanNavigationRequest? = null,
    onWidgetPlanNavigationConsumed: (WidgetPlanNavigationRequest) -> Unit = {},
    isDarkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    bgType: String = "pure",
    bgColor: Int = 0,
    bgGradient: String = "",
    bgImageIndex: Int = -1,
    onBackgroundChanged: (String, Int, String, Int) -> Unit = { _, _, _, _ -> },
    showQuickAddNoteDialog: Boolean = false,
    onShowQuickAddNoteDialogChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel(),
    plansViewModel: SupervisionPlansViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val responsiveLayout = rememberResponsiveLayoutSpec()
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val credentials = remember { CredentialStore(context.applicationContext) }
    val allowlistRepository = remember { AllowlistRepository.get(context.applicationContext) }
    val usageAccessManager = remember { UsageAccessManager(context.applicationContext) }
    val selfCheckRunner = remember { SelfCheckRunner(context.applicationContext) }
    val permissionRequestHistory = remember {
        RuntimePermissionRequestHistory(context.applicationContext)
    }
    val notificationManager = remember {
        context.applicationContext.getSystemService(NotificationManager::class.java)
    }

    val supervisionState by plansViewModel.uiState.collectAsStateWithLifecycle()
    val isSupervisionProtecting = supervisionState.plans.any { it.enabled }

    val focusTimerState by com.example.controlfree.ui.todo.timeblock.FocusTimerManager.timerState.collectAsStateWithLifecycle()
    LaunchedEffect(focusTimerState?.stableKey, focusTimerState?.paused) {
        val state = focusTimerState
        if (state != null && !state.paused && state.remainingSeconds > 0) {
            while (true) {
                delay(1000L)
                com.example.controlfree.ui.todo.timeblock.FocusTimerManager.tick()
            }
        }
    }

    var focusLockTime by remember { mutableStateOf(5) }
    var focusPlayTime by remember { mutableStateOf(1) }
    var isRunning by remember { mutableStateOf(false) }
    var activeSessionMode by remember { mutableStateOf(MonitorSessionMode.SUPERVISION) }
    var remainingSeconds by remember { mutableStateOf(0) }
    var monitorState by remember { mutableStateOf(MonitorService.STATE_USAGE) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var showAllowlistDialog by remember { mutableStateOf(false) }
    var showStartReadinessDialog by remember { mutableStateOf(false) }
    var pendingFocusPreset by remember { mutableStateOf<FocusSessionPreset?>(null) }
    var permissionCheckToken by remember { mutableIntStateOf(0) }
    var recoveryStartAttempts by remember { mutableIntStateOf(0) }
    var recoveryRetryToken by remember { mutableIntStateOf(0) }
    var recoveryConfirmationToken by remember { mutableIntStateOf(0) }
    var isRecoveryConfirmationPending by remember { mutableStateOf(false) }
    var isRecoveryUnavailable by remember { mutableStateOf(false) }
    var isSelfChecking by remember { mutableStateOf(false) }
    var selfCheckReport by remember { mutableStateOf<SelfCheckReport?>(null) }
    var showSelfCheckReport by remember { mutableStateOf(false) }
    var showClearDiagnosticsDialog by remember { mutableStateOf(false) }
    var showSuperSearch by rememberSaveable { mutableStateOf(false) }
    var showAboutPage by rememberSaveable { mutableStateOf(false) }
    var searchNavigationRevision by rememberSaveable { mutableLongStateOf(0L) }
    var pendingSearchNavigation by remember { mutableStateOf<SearchNavigationRequest?>(null) }
    val widgetSearchNavigation = widgetTodoNavigationRequest
        ?.takeIf { it.itemId != null }
        ?.let { request ->
            SearchNavigationRequest(
                revision = request.revision,
                source = when (request.subTab) {
                    TodoSubTab.TODO -> com.example.controlfree.ui.todo.search.SuperSearchSource.TODO
                    TodoSubTab.QUICK_NOTE ->
                        com.example.controlfree.ui.todo.search.SuperSearchSource.QUICK_NOTE
                    else -> return@let null
                },
                entityId = requireNotNull(request.itemId)
            )
        }
    val superSearchViewModel: SuperSearchViewModel = viewModel()
    var selectedFocusSubTab by remember { mutableStateOf(FocusSubTab.QUICK) }
    var selectedSupervisionSubTab by remember { mutableStateOf(SupervisionSubTab.TIMED) }
    val isStarting = viewModel.isStartingState.value
    val isStartPending = viewModel.isStartPendingState.value
    val focusTaskTitle = viewModel.focusTaskTitleState.value
    val isFocusRunning = isRunning && activeSessionMode == MonitorSessionMode.FOCUS
    val runtimeReadiness = remember(permissionCheckToken) {
        readRuntimeReadiness(context.applicationContext, usageAccessManager)
    }

    fun readCurrentRuntimeReadiness(): RuntimeReadiness =
        readRuntimeReadiness(context.applicationContext, usageAccessManager)

    fun clearGrantedRuntimePermissionRequestHistory() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequestHistory.clearRequested(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequestHistory.clearRequested(Manifest.permission.READ_PHONE_STATE)
        }
    }

    fun requestFocusPresetConfirmation(preset: FocusSessionPreset) {
        if (
            !areFocusPresetsEnabled(
                isAnyMonitorRunning = isRunning,
                isStarting = viewModel.isStartingState.value
            )
        ) {
            return
        }
        focusLockTime = preset.lockMinutes
        focusPlayTime = preset.playMinutes
        pendingFocusPreset = preset
        permissionCheckToken++
        showStartReadinessDialog = true
    }

    fun startFocusWithPermissionCheck() {
        val currentReadiness = readCurrentRuntimeReadiness()
        if (!currentReadiness.canStart) {
            pendingFocusPreset = null
            permissionCheckToken++
            showStartReadinessDialog = true
            return
        }

        val normalizedLock = normalizeDuration(focusLockTime, 1, 180, 1)
        val normalizedPlay = normalizeDuration(focusPlayTime, 1, 60, 1)
        showStartReadinessDialog = false
        if (!viewModel.requestFocusStart(normalizedLock, normalizedPlay)) {
            Toast.makeText(
                context,
                "已有监督或专注任务正在启动或运行",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun runSelfCheck() {
        if (isSelfChecking) return
        isSelfChecking = true
        coroutineScope.launch {
            try {
                selfCheckReport = selfCheckRunner.run()
                showSelfCheckReport = true
            } catch (_: TimeoutCancellationException) {
                Toast.makeText(context, "自检超时，请检查系统服务后重试", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                Toast.makeText(context, "自检未能完成，请稍后重试", Toast.LENGTH_LONG).show()
            } finally {
                isSelfChecking = false
            }
        }
    }

    fun openApplicationSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        } catch (_: RuntimeException) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: RuntimeException) {
                Toast.makeText(
                    context,
                    "无法打开系统设置，请手动进入“应用和服务”",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(plansViewModel, context.applicationContext) {
        // 计划页即使尚未打开，App 前台生命周期也必须能够校准定时监督。
        plansViewModel.initialize(context.applicationContext)
    }

    BackHandler(enabled = selectedTab != MainTab.MONITOR) {
        onTabSelected(MainTab.MONITOR)
    }

    fun ensureActiveMonitorService(force: Boolean = false): Boolean {
        if (MonitorService.isRunning && !force) return true
        if (recoveryStartAttempts >= MAX_RECOVERY_START_ATTEMPTS) {
            isRecoveryUnavailable = true
            return false
        }
        recoveryStartAttempts++
        return try {
            // 不携带 START 动作，只恢复持久化阶段，绝不重置监督周期。
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            isRecoveryConfirmationPending = true
            recoveryConfirmationToken++
            true
        } catch (_: RuntimeException) {
            isRecoveryConfirmationPending = false
            if (recoveryStartAttempts < MAX_RECOVERY_START_ATTEMPTS) {
                recoveryRetryToken++
            } else {
                isRecoveryUnavailable = true
            }
            Toast.makeText(
                context,
                "监督服务恢复失败，请检查系统后台与自启动限制",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    LaunchedEffect(Unit) {
        clearGrantedRuntimePermissionRequestHistory()
        viewModel.initPreferences(context)
        focusLockTime = normalizeDuration(viewModel.focusLockTimeState.value, 1, 180, 1)
        focusPlayTime = normalizeDuration(viewModel.focusPlayTimeState.value, 1, 60, 1)
        isRunning = viewModel.isRunningState.value
        activeSessionMode = viewModel.activeSessionModeState.value
        remainingSeconds = viewModel.remainingSecondsState.value
        monitorState = viewModel.monitorStateState.value
        if (viewModel.isRunningState.value && !MonitorService.isRunning) {
            ensureActiveMonitorService()
        }
    }
    LaunchedEffect(viewModel.focusLockTimeState.value) {
        focusLockTime = normalizeDuration(viewModel.focusLockTimeState.value, 1, 180, 1)
    }
    LaunchedEffect(viewModel.focusPlayTimeState.value) {
        focusPlayTime = normalizeDuration(viewModel.focusPlayTimeState.value, 1, 60, 1)
    }
    LaunchedEffect(viewModel.isRunningState.value) { isRunning = viewModel.isRunningState.value }
    LaunchedEffect(viewModel.activeSessionModeState.value) {
        activeSessionMode = viewModel.activeSessionModeState.value
    }
    LaunchedEffect(viewModel.remainingSecondsState.value) {
        remainingSeconds = viewModel.remainingSecondsState.value
    }
    LaunchedEffect(viewModel.monitorStateState.value) {
        monitorState = viewModel.monitorStateState.value
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    MonitorService.ACTION_TIMER_TICK -> {
                        remainingSeconds = intent.getIntExtra(MonitorService.EXTRA_REMAINING_SECONDS, 0)
                        monitorState = intent.getStringExtra(MonitorService.EXTRA_STATE)
                            ?: MonitorService.STATE_USAGE
                        activeSessionMode = MonitorSessionMode.fromStoredValue(
                            intent.getStringExtra(MonitorService.EXTRA_SESSION_MODE)
                        )
                        if (activeSessionMode == MonitorSessionMode.FOCUS) {
                            intent.getStringExtra(MonitorService.EXTRA_LOCK_TASK_TITLE)
                                ?.let(viewModel::updateFocusTaskTitle)
                        }
                        if (!viewModel.isStartingState.value) {
                            isRunning = intent.getBooleanExtra(MonitorService.EXTRA_IS_RUNNING, true)
                            viewModel.updateMonitorTick(
                                monitorState,
                                remainingSeconds,
                                activeSessionMode
                            )
                        }
                    }
                    MonitorService.ACTION_MONITOR_STARTED -> {
                        val attemptId = intent.getLongExtra(
                            MonitorService.EXTRA_START_ATTEMPT_ID,
                            MonitorService.NO_START_ATTEMPT
                        )
                        val startedState = intent.getStringExtra(MonitorService.EXTRA_STATE)
                            ?: MonitorService.STATE_USAGE
                        val startedRemaining = intent.getIntExtra(
                            MonitorService.EXTRA_REMAINING_SECONDS,
                            0
                        )
                        val startedSessionMode = MonitorSessionMode.fromStoredValue(
                            intent.getStringExtra(MonitorService.EXTRA_SESSION_MODE)
                        )
                        if (viewModel.confirmMonitorStarted(
                                attemptId,
                                startedState,
                                startedRemaining,
                                startedSessionMode
                            )
                        ) {
                            recoveryStartAttempts = 0
                            isRecoveryConfirmationPending = false
                            isRecoveryUnavailable = false
                            monitorState = startedState
                            remainingSeconds = startedRemaining
                            activeSessionMode = startedSessionMode
                            isRunning = true
                            Toast.makeText(
                                context,
                                if (startedSessionMode == MonitorSessionMode.FOCUS) {
                                    "专注任务已启动"
                                } else {
                                    "玩机监督已启动"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    MonitorService.ACTION_MONITOR_STOPPED -> {
                        recoveryStartAttempts = 0
                        isRecoveryConfirmationPending = false
                        isRecoveryUnavailable = false
                        remainingSeconds = 0
                        monitorState = MonitorService.STATE_USAGE
                        activeSessionMode = MonitorSessionMode.SUPERVISION
                        isRunning = false
                        isStopping = false
                        showCancelDialog = false
                        viewModel.confirmMonitorStopped()
                    }
                    MonitorService.ACTION_MONITOR_START_FAILED -> {
                        val attemptId = intent.getLongExtra(
                            MonitorService.EXTRA_START_ATTEMPT_ID,
                            MonitorService.NO_START_ATTEMPT
                        )
                        if (!viewModel.acceptMonitorStartFailure(attemptId)) return
                        isRecoveryConfirmationPending = false
                        viewModel.updateRunningState()
                        isRunning = viewModel.isRunningState.value
                        remainingSeconds = viewModel.remainingSecondsState.value
                        monitorState = viewModel.monitorStateState.value
                        Toast.makeText(
                            context,
                            intent.getStringExtra(MonitorService.EXTRA_ERROR_MESSAGE)
                                ?: "监督计时启动失败，请重试",
                            Toast.LENGTH_LONG
                        ).show()
                        if (viewModel.isRunningState.value && !MonitorService.isRunning) {
                            if (recoveryStartAttempts < MAX_RECOVERY_START_ATTEMPTS) {
                                recoveryRetryToken++
                            } else {
                                isRecoveryUnavailable = true
                            }
                        }
                    }
                    MonitorService.ACTION_MONITOR_ERROR -> {
                        isStopping = false
                        viewModel.updateRunningState()
                        Toast.makeText(
                            context,
                            intent.getStringExtra(MonitorService.EXTRA_ERROR_MESSAGE)
                                ?: "监督状态保存失败",
                            Toast.LENGTH_LONG
                        ).show()
                        if (viewModel.isRunningState.value && !MonitorService.isRunning) {
                            recoveryStartAttempts = 0
                            isRecoveryConfirmationPending = false
                            isRecoveryUnavailable = false
                            ensureActiveMonitorService()
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(MonitorService.ACTION_TIMER_TICK)
                addAction(MonitorService.ACTION_MONITOR_STARTED)
                addAction(MonitorService.ACTION_MONITOR_START_FAILED)
                addAction(MonitorService.ACTION_MONITOR_STOPPED)
                addAction(MonitorService.ACTION_MONITOR_ERROR)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: RuntimeException) {
                // Receiver 可能已被系统回收。
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel, plansViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                clearGrantedRuntimePermissionRequestHistory()
                permissionCheckToken++
                plansViewModel.reconcileSchedules("app_resumed")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "仍需悬浮窗权限才能锁屏", Toast.LENGTH_LONG).show()
        }
        permissionCheckToken++
    }
    val usagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (usageAccessManager.hasUsageAccess()) {
            Toast.makeText(context, "使用情况访问权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "仍需使用情况访问权限才能启用白名单回锁", Toast.LENGTH_LONG).show()
        }
        permissionCheckToken++
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionRequestHistory.clearRequested(Manifest.permission.POST_NOTIFICATIONS)
            Toast.makeText(context, "通知权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "需要通知权限才能可靠运行监督服务", Toast.LENGTH_LONG).show()
        }
        permissionCheckToken++
    }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionRequestHistory.clearRequested(Manifest.permission.READ_PHONE_STATE)
            Toast.makeText(context, "电话状态权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未授权电话状态，无法保证来电期间自动解除遮挡", Toast.LENGTH_LONG).show()
        }
        permissionCheckToken++
    }
    val fullScreenIntentPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val canUseFullScreenIntent = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            notificationManager?.canUseFullScreenIntent() == true
        } else {
            true
        }
        if (shouldRequestFullScreenIntentAccess(Build.VERSION.SDK_INT, canUseFullScreenIntent)) {
            Toast.makeText(
                context,
                "建议开启全屏通知，增强悬浮锁层失效时的备用保护",
                Toast.LENGTH_LONG
            ).show()
        }
        permissionCheckToken++
    }
    val exactAlarmPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.getSystemService(AlarmManager::class.java)
                    ?.canScheduleExactAlarms() == true
            } catch (_: RuntimeException) {
                false
            }
        } else {
            true
        }
        Toast.makeText(
            context,
            if (exactAlarmGranted) {
                "精确定时权限已开启"
            } else {
                "未开启精确定时权限，定时任务可能被系统延后执行"
            },
            Toast.LENGTH_LONG
        ).show()
        permissionCheckToken++
    }

    fun requestRuntimePermissionOrOpenSettings(
        permission: String,
        requestPermission: () -> Unit
    ) {
        val activity = context.findActivity()
        val shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
        } == true
        val shouldOpenSettings = shouldOpenApplicationPermissionSettings(
            wasRequested = permissionRequestHistory.wasRequested(permission),
            shouldShowRationale = shouldShowRationale
        )
        if (shouldOpenSettings) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } else {
            permissionRequestHistory.markRequested(permission)
            requestPermission()
        }
    }

    val openRuntimeRequirement: (RuntimeRequirementKey) -> Unit = { key ->
        try {
            when (key) {
                RuntimeRequirementKey.USAGE_ACCESS -> {
                    val destination = UsageAccessSettingsNavigator.open(context.packageName) {
                        usagePermissionLauncher.launch(it)
                    }
                    if (destination == UsageAccessSettingsDestination.UNAVAILABLE) {
                        Toast.makeText(
                            context,
                            "无法打开使用情况访问设置，请在系统设置中手动处理",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                RuntimeRequirementKey.OVERLAY ->
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )

                RuntimeRequirementKey.PHONE_STATE -> requestRuntimePermissionOrOpenSettings(
                    Manifest.permission.READ_PHONE_STATE
                ) {
                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }

                RuntimeRequirementKey.NOTIFICATION_PERMISSION ->
                    requestRuntimePermissionOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }

                RuntimeRequirementKey.NOTIFICATIONS_ENABLED ->
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    })

                RuntimeRequirementKey.SERVICE_CHANNEL,
                RuntimeRequirementKey.LOCK_RECOVERY_CHANNEL -> {
                    val channelId = requireNotNull(notificationChannelIdForRequirement(key))
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                            }
                        )
                    } catch (_: RuntimeException) {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }
                }

                RuntimeRequirementKey.FULL_SCREEN_INTENT ->
                    fullScreenIntentPermissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )

                RuntimeRequirementKey.EXACT_ALARM -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        exactAlarmPermissionLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                }

                RuntimeRequirementKey.BACKGROUND_POPUP -> {
                    com.example.controlfree.diagnostics.BackgroundPopupSettingsNavigator.openBackgroundPopupSettings(context)
                }

                RuntimeRequirementKey.BATTERY_UNRESTRICTED -> {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "请在系统“忽略电池优化”设置中将本应用设为不限制", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (_: RuntimeException) {
            Toast.makeText(context, "无法打开对应设置，请在系统设置中手动处理", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isStartPending, permissionCheckToken, viewModel.isInitializedState.value) {
        if (!isStartPending || !viewModel.isInitializedState.value) return@LaunchedEffect
        try {
            val currentReadiness = readRuntimeReadiness(context.applicationContext, usageAccessManager)
            if (!currentReadiness.canStart) {
                viewModel.cancelMonitorStart()
                showStartReadinessDialog = true
                permissionCheckToken++
                return@LaunchedEffect
            }
            val pendingStart = viewModel.consumePendingStart() ?: return@LaunchedEffect
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).apply {
                    action = when (pendingStart.sessionMode) {
                        MonitorSessionMode.SUPERVISION -> MonitorService.ACTION_START_MONITOR
                        MonitorSessionMode.FOCUS -> MonitorService.ACTION_START_FOCUS
                    }
                    putExtra(MonitorService.EXTRA_START_ATTEMPT_ID, pendingStart.attemptId)
                    pendingStart.focusTaskTitle?.let {
                        putExtra(MonitorService.EXTRA_FOCUS_TASK_TITLE, it)
                    }
                    pendingStart.sourceTodoId?.let {
                        putExtra(MonitorService.EXTRA_FOCUS_SOURCE_TODO_ID, it)
                    }
                }
            )
        } catch (_: RuntimeException) {
            viewModel.cancelMonitorStart()
            Toast.makeText(
                context,
                "无法打开授权页面或启动服务，请在系统设置中手动授权后重试",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(isStarting, isStartPending) {
        if (!isStarting || isStartPending) return@LaunchedEffect
        delay(10_000L)
        if (viewModel.isStartingState.value && !viewModel.isStartPendingState.value) {
            viewModel.updateRunningState()
            if (MonitorService.isRunning) {
                val attemptId = viewModel.startAttemptIdState.value
                    ?: MonitorService.NO_START_ATTEMPT
                val sessionMode = viewModel.pendingSessionModeState.value
                    ?: viewModel.activeSessionModeState.value
                val fallbackRemainingSeconds = when (sessionMode) {
                    MonitorSessionMode.SUPERVISION -> viewModel.usageTimeState.value * 60
                    MonitorSessionMode.FOCUS -> viewModel.focusLockTimeState.value * 60
                }
                viewModel.confirmMonitorStarted(
                    attemptId,
                    viewModel.monitorStateState.value,
                    viewModel.remainingSecondsState.value.takeIf { it > 0 }
                        ?: fallbackRemainingSeconds,
                    sessionMode
                )
            } else {
                viewModel.cancelMonitorStart()
                Toast.makeText(context, "监督服务启动超时，请检查后台运行限制后重试", Toast.LENGTH_LONG).show()
                if (viewModel.isRunningState.value) ensureActiveMonitorService()
            }
        }
    }

    LaunchedEffect(isStartPending) {
        if (!isStartPending) return@LaunchedEffect
        delay(120_000L)
        if (viewModel.isStartPendingState.value) {
            viewModel.cancelMonitorStart()
            Toast.makeText(context, "授权流程已超时，请确认权限后重新点击启动", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(recoveryRetryToken) {
        if (recoveryRetryToken <= 0) return@LaunchedEffect
        delay((recoveryStartAttempts.coerceAtLeast(1) * 2_000L).coerceAtMost(6_000L))
        viewModel.updateRunningState()
        if (viewModel.isRunningState.value && !MonitorService.isRunning) {
            ensureActiveMonitorService()
        }
    }

    LaunchedEffect(recoveryConfirmationToken) {
        if (recoveryConfirmationToken <= 0) return@LaunchedEffect
        delay(12_000L)
        if (isRecoveryConfirmationPending) {
            isRecoveryConfirmationPending = false
            viewModel.updateRunningState()
            if (viewModel.isRunningState.value) {
                ensureActiveMonitorService(force = true)
            }
        }
    }

    if (!viewModel.isInitializedState.value) {
        Box(
            Modifier.fillMaxSize().background(BrandColors.PageBackground),
            contentAlignment = Alignment.Center
        ) {
            if (selectedTab == MainTab.STATISTICS) {
                CircularProgressIndicator(color = BrandColors.Primary)
            }
        }
    } else {
        Scaffold(
            containerColor = BrandColors.PageBackground,
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandColors.BackgroundChrome)
                ) {
                    TopAppBar(
                            title = {
                                val titleIconSize = with(LocalDensity.current) {
                                    MaterialTheme.typography.titleLarge.fontSize.toDp()
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_brand_mark),
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(titleIconSize)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val pageTitle = if (selectedTab == MainTab.MONITOR) {
                                        "监督"
                                    } else {
                                        selectedTab.displayName
                                    }
                                    Text(
                                        text = pageTitle,
                                        fontWeight = FontWeight.Bold,
                                        color = BrandColors.BackgroundTextPrimary
                                    )
                                }
                            },
                            actions = {
                                if (selectedTab == MainTab.TODO) {
                                    IconButton(onClick = { showSuperSearch = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Search,
                                            contentDescription = "超级搜索",
                                            tint = BrandColors.BackgroundTextPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    IconButton(onClick = { onShowQuickAddNoteDialogChange(true) }) {
                                        Text(
                                            text = "AI",
                                            color = BrandColors.BackgroundTextPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            style = androidx.compose.ui.text.TextStyle(letterSpacing = 1.sp)
                                        )
                                    }
                                } else if (selectedTab == MainTab.MONITOR) {
                                    Box(modifier = Modifier.padding(end = 14.dp)) {
                                        ProtectingStatusBadge(isProtecting = isSupervisionProtecting)
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                                navigationIconContentColor = BrandColors.BackgroundTextPrimary,
                                titleContentColor = BrandColors.BackgroundTextPrimary,
                                actionIconContentColor = BrandColors.BackgroundTextPrimary
                            )
                    )
                }
            },
            bottomBar = {
                // 输入法出现时底栏会被键盘覆盖；若仍参与 Scaffold 测量，
                // 页面会额外保留整块底栏高度，使底部输入框离键盘过远。
                if (
                    shouldShowMainBottomBar(
                        useNavigationRail = responsiveLayout.useNavigationRail,
                        isImeVisible = isImeVisible
                    )
                ) {
                    MainBottomBar(selectedTab = selectedTab, onTabSelected = onTabSelected)
                }
            },
            modifier = modifier.background(BrandColors.PageBackground)
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(BrandColors.PageBackground)
            ) {
                if (responsiveLayout.useNavigationRail) {
                    MainNavigationRail(selectedTab, onTabSelected)
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (selectedTab) {
                        MainTab.FOCUS -> Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(
                                selectedTabIndex = selectedFocusSubTab.ordinal,
                                containerColor = BrandColors.BackgroundChrome,
                                contentColor = BrandColors.BackgroundAccent,
                                indicator = {},
                                divider = {}
                            ) {
                                FocusSubTab.entries.forEachIndexed { index, tab ->
                                    val isSelected = selectedFocusSubTab.ordinal == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                selectedFocusSubTab = tab
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val isImageBg = BrandColors.BackgroundType == "image"
                                        val capsuleColor = if (isImageBg) {
                                            if (BrandColors.UsesDarkForeground) {
                                                if (isSelected) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.48f)
                                            } else {
                                                if (isSelected) Color.Black.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.38f)
                                            }
                                        } else {
                                            if (isSelected) {
                                                BrandColors.Primary.copy(alpha = 0.12f)
                                            } else {
                                                Color.Transparent
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    color = capsuleColor,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(horizontal = 14.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                tab.displayName,
                                                fontSize = if (isSelected) 15.sp else 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                softWrap = false,
                                                color = if (isSelected) {
                                                    BrandColors.BackgroundAccent
                                                } else {
                                                    BrandColors.BackgroundTextSecondary
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            ScrollableTabContent(
                                contentPadding = PaddingValues(bottom = 80.dp),
                                responsiveLayout = responsiveLayout,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (selectedFocusSubTab == FocusSubTab.QUICK) {
                                    if (responsiveLayout.useTwoPane) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                FocusSessionOverview(
                                                    isFocusRunning = isFocusRunning,
                                                    taskTitle = focusTaskTitle,
                                                    lockMinutes = focusLockTime,
                                                    playMinutes = focusPlayTime,
                                                    arePresetsEnabled = areFocusPresetsEnabled(
                                                        isAnyMonitorRunning = isRunning,
                                                        isStarting = isStarting
                                                    ),
                                                    onTaskTitleChange = viewModel::updateFocusTaskTitle,
                                                    onApplyPreset = ::requestFocusPresetConfirmation
                                                )
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                FocusControlPanel(
                                                    isAnyMonitorRunning = isRunning,
                                                    isFocusRunning = isFocusRunning,
                                                    monitorState = monitorState,
                                                    remainingSeconds = remainingSeconds,
                                                    isStopping = isStopping,
                                                    isRecoveryUnavailable = isRecoveryUnavailable,
                                                    focusLockTime = focusLockTime,
                                                    focusPlayTime = focusPlayTime,
                                                    isStarting = isStarting,
                                                    onRetryRecovery = {
                                                        recoveryStartAttempts = 0
                                                        isRecoveryUnavailable = false
                                                        ensureActiveMonitorService(force = true)
                                                    },
                                                    onCancelClick = { showCancelDialog = true },
                                                    onFocusLockTimeChange = { focusLockTime = it },
                                                    onFocusPlayTimeChange = { focusPlayTime = it },
                                                    onStartClick = ::startFocusWithPermissionCheck
                                                )
                                            }
                                        }
                                    } else {
                                        FocusSessionOverview(
                                            isFocusRunning = isFocusRunning,
                                            taskTitle = focusTaskTitle,
                                            lockMinutes = focusLockTime,
                                            playMinutes = focusPlayTime,
                                            arePresetsEnabled = areFocusPresetsEnabled(
                                                isAnyMonitorRunning = isRunning,
                                                isStarting = isStarting
                                            ),
                                            onTaskTitleChange = viewModel::updateFocusTaskTitle,
                                            onApplyPreset = ::requestFocusPresetConfirmation
                                        )
                                        FocusControlPanel(
                                            isAnyMonitorRunning = isRunning,
                                            isFocusRunning = isFocusRunning,
                                            monitorState = monitorState,
                                            remainingSeconds = remainingSeconds,
                                            isStopping = isStopping,
                                            isRecoveryUnavailable = isRecoveryUnavailable,
                                            focusLockTime = focusLockTime,
                                            focusPlayTime = focusPlayTime,
                                            isStarting = isStarting,
                                            onRetryRecovery = {
                                                recoveryStartAttempts = 0
                                                isRecoveryUnavailable = false
                                                ensureActiveMonitorService(force = true)
                                            },
                                            onCancelClick = { showCancelDialog = true },
                                            onFocusLockTimeChange = { focusLockTime = it },
                                            onFocusPlayTimeChange = { focusPlayTime = it },
                                            onStartClick = ::startFocusWithPermissionCheck
                                        )
                                    }
                                } else {
                                    FocusPlansSection(
                                        viewModel = plansViewModel,
                                        runtimeReadiness = runtimeReadiness,
                                        onCheckRuntimeReadiness = ::readCurrentRuntimeReadiness,
                                        onFixRequirement = { requirement ->
                                            openRuntimeRequirement(requirement)
                                        },
                                        onRefreshReadiness = { permissionCheckToken++ },
                                        navigationRequest = widgetPlanNavigationRequest?.takeIf {
                                            it.destination == WidgetPlanDestination.FOCUS
                                        },
                                        onNavigationConsumed = onWidgetPlanNavigationConsumed
                                    )
                                }
                            }
                        }

                        MainTab.MONITOR -> Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(
                                selectedTabIndex = selectedSupervisionSubTab.ordinal,
                                containerColor = BrandColors.BackgroundChrome,
                                contentColor = BrandColors.BackgroundAccent,
                                indicator = {},
                                divider = {}
                            ) {
                                SupervisionSubTab.entries.forEachIndexed { index, tab ->
                                    val isSelected = selectedSupervisionSubTab.ordinal == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                selectedSupervisionSubTab = tab
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val isImageBg = BrandColors.BackgroundType == "image"
                                        val capsuleColor = if (isImageBg) {
                                            if (BrandColors.UsesDarkForeground) {
                                                if (isSelected) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.48f)
                                            } else {
                                                if (isSelected) Color.Black.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.38f)
                                            }
                                        } else {
                                            if (isSelected) {
                                                BrandColors.Primary.copy(alpha = 0.12f)
                                            } else {
                                                Color.Transparent
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    color = capsuleColor,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(horizontal = 14.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                tab.displayName,
                                                fontSize = if (isSelected) 15.sp else 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                softWrap = false,
                                                color = if (isSelected) {
                                                    BrandColors.BackgroundAccent
                                                } else {
                                                    BrandColors.BackgroundTextSecondary
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            ScrollableTabContent(
                                contentPadding = PaddingValues(bottom = 80.dp),
                                responsiveLayout = responsiveLayout,
                                modifier = Modifier.weight(1f)
                            ) {
                                SupervisionPlansSection(
                                    selectedSubTab = selectedSupervisionSubTab,
                                    viewModel = plansViewModel,
                                    runtimeReadiness = runtimeReadiness,
                                    onCheckRuntimeReadiness = ::readCurrentRuntimeReadiness,
                                    onFixRequirement = openRuntimeRequirement,
                                    onRefreshReadiness = { permissionCheckToken++ },
                                    navigationRequest = widgetPlanNavigationRequest?.takeIf {
                                        it.destination == WidgetPlanDestination.MONITOR
                                    },
                                    onNavigationConsumed = onWidgetPlanNavigationConsumed,
                                    remainingSeconds = remainingSeconds
                                )
                            }
                        }

                        MainTab.STATISTICS -> StatisticsContent()

                        MainTab.TODO -> TodoMainScreen(
                            initialSubTab = initialTodoSubTab,
                            onSubTabSelected = onTodoSubTabSelected,
                            searchNavigationRequest = widgetSearchNavigation
                                ?: pendingSearchNavigation,
                            onSearchNavigationConsumed = { request ->
                                val widgetRequest = widgetTodoNavigationRequest
                                if (
                                    widgetSearchNavigation?.revision == request.revision &&
                                    widgetRequest.itemId == request.entityId
                                ) {
                                    onWidgetTodoNavigationConsumed(widgetRequest)
                                } else if (pendingSearchNavigation?.revision == request.revision) {
                                    pendingSearchNavigation = null
                                }
                            },
                            widgetNavigationRequest = widgetTodoNavigationRequest,
                            onWidgetNavigationConsumed = onWidgetTodoNavigationConsumed,
                            modifier = Modifier.fillMaxSize()
                        )

                        MainTab.SETTINGS -> {
                            var selectedSubTab by rememberSaveable { mutableStateOf(SettingsSubTab.GROWTH_CENTER) }
                            val pagerState = rememberPagerState(
                                initialPage = selectedSubTab.ordinal,
                                pageCount = { SettingsSubTab.entries.size }
                            )
                            LaunchedEffect(pagerState.currentPage) {
                                selectedSubTab = SettingsSubTab.entries[pagerState.currentPage]
                            }
                            val context = LocalContext.current
                            val repository = remember(context.applicationContext) {
                                GrowthRepository.getInstance(context.applicationContext)
                            }
                            val account by produceState<GrowthAccount?>(initialValue = null, repository) {
                                try {
                                    value = repository.getAccount()
                                    repository.observeAccount().collectLatest { value = it }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    value = null
                                }
                            }
                            val recentLedger by produceState<List<GrowthLedgerEntry>>(
                                initialValue = emptyList(),
                                repository
                            ) {
                                try {
                                    repository.getAccount()
                                    repository.observeRecentLedger(limit = 100).collectLatest { value = it }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    value = emptyList()
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(BrandColors.PageBackground)
                            ) {
                                TabRow(
                                    selectedTabIndex = pagerState.currentPage,
                                    containerColor = BrandColors.BackgroundChrome,
                                    contentColor = BrandColors.BackgroundAccent,
                                    indicator = {},
                                    divider = {}
                                ) {
                                    SettingsSubTab.entries.forEach { subTab ->
                                        val isSelected = pagerState.currentPage == subTab.ordinal
                                        androidx.compose.foundation.layout.Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clickable(
                                                    interactionSource = remember {
                                                        androidx.compose.foundation.interaction.MutableInteractionSource()
                                                    },
                                                    indication = null,
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            pagerState.scrollToPage(subTab.ordinal)
                                                        }
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val isImageBg = BrandColors.BackgroundType == "image"
                                            val capsuleColor = if (isImageBg) {
                                                if (BrandColors.UsesDarkForeground) {
                                                    if (isSelected) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.48f)
                                                } else {
                                                    if (isSelected) Color.Black.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.38f)
                                                }
                                            } else {
                                                if (isSelected) {
                                                    BrandColors.Primary.copy(alpha = 0.12f)
                                                } else {
                                                     Color.Transparent
                                                }
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = capsuleColor,
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = subTab.displayName,
                                                    fontSize = if (isSelected) 15.sp else 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    color = if (isSelected) {
                                                        BrandColors.BackgroundAccent
                                                    } else {
                                                        BrandColors.BackgroundTextSecondary
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    val currentSubTab = SettingsSubTab.entries[page]
                                    ScrollableTabContent(
                                    contentPadding = PaddingValues(0.dp),
                                    responsiveLayout = responsiveLayout
                                ) {
                                    if (responsiveLayout.useTwoPane) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            when (currentSubTab) {
                                                SettingsSubTab.GROWTH_CENTER -> {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        GrowthLevelCard(account = account)
                                                        GrowthPointsCard(account = account)
                                                        GrowthUnlockSettingsCard()
                                                    }
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        GrowthLedgerCard(recentLedger = recentLedger)
                                                        GrowthRulesCard(account = account, currentStage = account?.levelProgress?.stage)
                                                    }
                                                }
                                                SettingsSubTab.AI_PET -> {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        AiCompanionSettingsCard()
                                                        AiApiKeySettingsCard()
                                                    }
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                                SettingsSubTab.QUESTION_BANK -> {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        ChallengeUnlockSettingsCard()
                                                    }
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        QuestionBankSettingsCard()
                                                    }
                                                }
                                                SettingsSubTab.SYSTEM_PERMISSIONS -> {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        AuthenticationSettingsCard(credentials = credentials)
                                                        RuntimeReadinessCard(
                                                            readiness = runtimeReadiness,
                                                            onFix = openRuntimeRequirement,
                                                            onRefresh = { permissionCheckToken++ }
                                                        )
                                                    }
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        FeatureNavigationCard(
                                                            title = "锁屏白名单",
                                                            description = "电话和短信始终允许，也可添加其他 App",
                                                            icon = {
                                                                Icon(
                                                                    androidx.compose.ui.res.painterResource(id = com.example.controlfree.R.drawable.ic_allowlist),
                                                                    null,
                                                                    tint = BrandColors.Secondary
                                                                )
                                                            },
                                                            onClick = { showAllowlistDialog = true }
                                                        )
                                                        KeepAliveGuideCard(
                                                            context,
                                                            onOpenApplicationSettings = ::openApplicationSettings
                                                        )
                                                        CacheManagementCard()
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        when (currentSubTab) {
                                            SettingsSubTab.GROWTH_CENTER -> {
                                                GrowthLevelCard(account = account)
                                                GrowthPointsCard(account = account)
                                                GrowthUnlockSettingsCard()
                                                GrowthLedgerCard(recentLedger = recentLedger)
                                                GrowthRulesCard(account = account, currentStage = account?.levelProgress?.stage)
                                            }
                                            SettingsSubTab.AI_PET -> {
                                                AiCompanionSettingsCard()
                                                AiApiKeySettingsCard()
                                            }
                                            SettingsSubTab.QUESTION_BANK -> {
                                                ChallengeUnlockSettingsCard()
                                                QuestionBankSettingsCard()
                                            }
                                            SettingsSubTab.SYSTEM_PERMISSIONS -> {
                                                BackgroundSettingsCard(
                                                    isDarkTheme = isDarkTheme,
                                                    bgType = bgType,
                                                    bgColor = bgColor,
                                                    bgGradient = bgGradient,
                                                    bgImageIndex = bgImageIndex,
                                                    onDarkThemeChange = onDarkThemeChange,
                                                    onBackgroundChanged = onBackgroundChanged
                                                )
                                                AuthenticationSettingsCard(credentials = credentials)
                                                FeatureNavigationCard(
                                                    title = "锁屏白名单",
                                                    description = "电话和短信始终允许，也可添加其他 App",
                                                    icon = {
                                                        Icon(
                                                            androidx.compose.ui.res.painterResource(id = com.example.controlfree.R.drawable.ic_allowlist),
                                                            null,
                                                            tint = BrandColors.Secondary
                                                        )
                                                    },
                                                    onClick = { showAllowlistDialog = true }
                                                )
                                                RuntimeReadinessCard(
                                                    readiness = runtimeReadiness,
                                                    onFix = openRuntimeRequirement,
                                                    onRefresh = { permissionCheckToken++ }
                                                )
                                                DiagnosticsCard(
                                                    isChecking = isSelfChecking,
                                                    lastReport = selfCheckReport,
                                                    onRunSelfCheck = ::runSelfCheck,
                                                    onClearDiagnostics = { showClearDiagnosticsDialog = true }
                                                )
                                                KeepAliveGuideCard(
                                                    context,
                                                    onOpenApplicationSettings = ::openApplicationSettings
                                                )
                                            }
                                        }
                                    }
                                    if (currentSubTab == SettingsSubTab.SYSTEM_PERMISSIONS) {
                                        AppInformationCard(
                                            onOpenAbout = { showAboutPage = true }
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }

                    // 全局专注药丸悬浮层，浮动在所有页面最上方底栏之上
                    focusTimerState?.let { timer ->
                        val scope = rememberCoroutineScope()
                        val context = LocalContext.current
                        com.example.controlfree.ui.todo.timeblock.FocusTimerPill(
                            state = timer,
                            onPauseToggle = { com.example.controlfree.ui.todo.timeblock.FocusTimerManager.togglePause() },
                            onComplete = {
                                com.example.controlfree.ui.todo.timeblock.FocusTimerManager.completeFocus(context, scope)
                            },
                            onClose = { com.example.controlfree.ui.todo.timeblock.FocusTimerManager.skipOrClose() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAboutPage) {
        AboutAppDialog(
            bgType = bgType,
            bgColor = bgColor,
            bgGradient = bgGradient,
            bgImageIndex = bgImageIndex,
            onBack = { showAboutPage = false }
        )
    }

    if (showSuperSearch) {
        Dialog(
            onDismissRequest = { showSuperSearch = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            SuperSearchScreen(
                viewModel = superSearchViewModel,
                onBack = { showSuperSearch = false },
                onResultClick = { result ->
                    if (!shouldNavigateFromSuperSearch(result.source)) return@SuperSearchScreen
                    searchNavigationRevision = if (searchNavigationRevision == Long.MAX_VALUE) {
                        1L
                    } else {
                        searchNavigationRevision + 1L
                    }
                    pendingSearchNavigation = SearchNavigationRequest(
                        revision = searchNavigationRevision,
                        source = result.source,
                        entityId = result.entityId,
                        targetDate = result.targetDate
                    )
                    showSuperSearch = false
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { if (!isStopping) showCancelDialog = false },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("结束本次专注任务？", fontWeight = FontWeight.Bold) },
            text = { Text("当前倒计时将停止，锁定界面会立即解除。") },
            confirmButton = {
                Button(
                    enabled = !isStopping,
                    onClick = {
                        if (isStopping) return@Button
                        isStopping = true
                        context.startService(Intent(context, MonitorService::class.java).apply {
                            action = MonitorService.ACTION_STOP_MONITOR
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Danger)
                ) {
                    Text(
                        if (isStopping) "正在结束…" else "结束专注任务",
                        color = BrandColors.OnPrimary
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !isStopping, onClick = { showCancelDialog = false }) {
                    Text("继续专注")
                }
            }
        )
    }

    if (showStartReadinessDialog) {
        val startConfiguration = resolveFocusStartConfiguration(
            pendingPreset = pendingFocusPreset,
            configuredLockMinutes = focusLockTime,
            configuredPlayMinutes = focusPlayTime
        )
        FocusStartReadinessDialog(
            readiness = runtimeReadiness,
            lockMinutes = startConfiguration.lockMinutes,
            playMinutes = startConfiguration.playMinutes,
            onFix = openRuntimeRequirement,
            onRefresh = { permissionCheckToken++ },
            onDismiss = {
                pendingFocusPreset = null
                showStartReadinessDialog = false
            },
            onStart = {
                val currentReadiness = readCurrentRuntimeReadiness()
                val currentConfiguration = resolveFocusStartConfiguration(
                    pendingPreset = pendingFocusPreset,
                    configuredLockMinutes = focusLockTime,
                    configuredPlayMinutes = focusPlayTime
                )
                if (currentReadiness.canStart &&
                    viewModel.requestFocusStart(
                        currentConfiguration.lockMinutes,
                        currentConfiguration.playMinutes
                    )
                ) {
                    focusLockTime = currentConfiguration.lockMinutes
                    focusPlayTime = currentConfiguration.playMinutes
                    pendingFocusPreset = null
                    showStartReadinessDialog = false
                } else if (!currentReadiness.canStart) {
                    permissionCheckToken++
                    Toast.makeText(context, "请先处理所有关键运行条件", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "已有监督或专注任务正在启动或运行", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showAllowlistDialog) {
        AllowlistDialog(
            repository = allowlistRepository,
            onDismiss = { showAllowlistDialog = false },
            onSaved = {
                showAllowlistDialog = false
                Toast.makeText(context, "白名单已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSelfCheckReport) {
        selfCheckReport?.let { report ->
            SelfCheckReportDialog(
                report = report,
                onDismiss = { showSelfCheckReport = false },
                onOpenBackgroundSettings = ::openApplicationSettings,
                onOpenBatterySettings = {
                    openRuntimeRequirement(RuntimeRequirementKey.BATTERY_UNRESTRICTED)
                }
            )
        }
    }

    if (showClearDiagnosticsDialog) {
        ClearDiagnosticsConfirmationDialog(
            onDismiss = { showClearDiagnosticsDialog = false },
            onConfirm = {
                showClearDiagnosticsDialog = false
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            AndroidDiagnostics.clear(context.applicationContext)
                                .get(5L, TimeUnit.SECONDS)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw CancellationException("diagnostic clear interrupted").also {
                                it.initCause(interrupted)
                            }
                        } catch (_: Exception) {
                            DiagnosticClearResult.STORAGE_FAILURE
                        }
                    }
                    Toast.makeText(
                        context,
                        if (result == DiagnosticClearResult.CLEARED) {
                            "本地诊断已清空"
                        } else {
                            "清空失败，请稍后重试"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    ProductivityCelebrationHost()
}

@Composable
private fun MainBottomBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    Surface(
        color = BrandColors.BackgroundChrome,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(color = BrandColors.BackgroundDivider, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(min = MAIN_BOTTOM_BAR_MIN_HEIGHT_DP.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
            MainTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val color = if (selected) {
                    BrandColors.BackgroundAccent
                } else {
                    BrandColors.BackgroundTextTertiary
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MAIN_BOTTOM_BAR_MIN_HEIGHT_DP.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { onTabSelected(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.offset(y = MAIN_BOTTOM_BAR_CONTENT_OFFSET_DP.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalContentColor provides color
                        ) {
                            MainTabIcon(tab)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.displayName,
                            color = color,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun MainNavigationRail(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    val itemColors = NavigationRailItemDefaults.colors(
        selectedIconColor = BrandColors.BackgroundAccent,
        selectedTextColor = BrandColors.BackgroundAccent,
        indicatorColor = BrandColors.BackgroundAccentContainer,
        unselectedIconColor = BrandColors.BackgroundTextTertiary,
        unselectedTextColor = BrandColors.BackgroundTextTertiary
    )
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(88.dp),
        containerColor = BrandColors.BackgroundChrome
    ) {
        Spacer(Modifier.weight(1f))
        MainTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { MainTabIcon(tab) },
                label = { Text(tab.displayName) },
                alwaysShowLabel = true,
                colors = itemColors
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MainTabIcon(tab: MainTab) {
    MainNavigationIcon(tab)
}

@Composable
private fun ScrollableTabContent(
    contentPadding: PaddingValues,
    responsiveLayout: ResponsiveLayoutSpec,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BrandColors.PageBackground)
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = responsiveLayout.preferredContentMaxWidthDp.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = responsiveLayout.pageHorizontalPaddingDp.dp,
                    end = responsiveLayout.pageHorizontalPaddingDp.dp,
                    top = 18.dp,
                    bottom = 18.dp + contentPadding.calculateBottomPadding()
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun FocusControlPanel(
    isAnyMonitorRunning: Boolean,
    isFocusRunning: Boolean,
    monitorState: String,
    remainingSeconds: Int,
    isStopping: Boolean,
    isRecoveryUnavailable: Boolean,
    focusLockTime: Int,
    focusPlayTime: Int,
    isStarting: Boolean,
    onRetryRecovery: () -> Unit,
    onCancelClick: () -> Unit,
    onFocusLockTimeChange: (Int) -> Unit,
    onFocusPlayTimeChange: (Int) -> Unit,
    onStartClick: () -> Unit
) {
    if (isFocusRunning) {
        RunningStatusCard(
            state = monitorState,
            remainingSeconds = remainingSeconds,
            isStopping = isStopping,
            isRecoveryUnavailable = isRecoveryUnavailable,
            onRetryRecovery = onRetryRecovery,
            onCancelClick = onCancelClick
        )
    } else if (isAnyMonitorRunning) {
        OtherSupervisionRunningCard()
    } else {
        ConfigurationCard(
            focusLockTime = focusLockTime,
            focusPlayTime = focusPlayTime,
            isStarting = isStarting,
            onFocusLockTimeChange = onFocusLockTimeChange,
            onFocusPlayTimeChange = onFocusPlayTimeChange,
            onStartClick = onStartClick
        )
    }
}

@Composable
private fun OtherSupervisionRunningCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "其他监督任务正在运行",
                color = BrandColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "设备锁定通道一次只能运行一个任务，请先在监督页结束当前任务。",
                color = BrandColors.TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ConfigurationCard(
    focusLockTime: Int,
    focusPlayTime: Int,
    isStarting: Boolean,
    onFocusLockTimeChange: (Int) -> Unit,
    onFocusPlayTimeChange: (Int) -> Unit,
    onStartClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DurationSelector(
            title = "锁定时长",
            value = focusLockTime,
            minValue = 1,
            maxValue = 180,
            step = 1,
            presets = listOf(5, 10, 30, 45, 60, 90),
            accentColor = BrandColors.Danger,
            onValueChange = onFocusLockTimeChange
        )
        DurationSelector(
            title = "可用时长",
            value = focusPlayTime,
            minValue = 1,
            maxValue = 60,
            step = 1,
            presets = listOf(1, 5, 10, 15, 30, 60),
            accentColor = BrandColors.Primary,
            onValueChange = onFocusPlayTimeChange
        )
        Text(
            "点击开始后立即锁定，再按玩机与锁定阶段持续循环",
            color = BrandColors.TextSecondary,
            fontSize = 13.sp
        )
        Button(
            onClick = onStartClick,
            enabled = !isStarting,
            colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (isStarting) {
                CircularProgressIndicator(
                    modifier = Modifier.width(22.dp).height(22.dp),
                    color = BrandColors.OnPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在授权或启动…",
                    color = BrandColors.OnPrimary,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = BrandColors.OnPrimary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "开始专注任务",
                    color = BrandColors.OnPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun RunningStatusCard(
    state: String,
    remainingSeconds: Int,
    isStopping: Boolean,
    isRecoveryUnavailable: Boolean,
    onRetryRecovery: () -> Unit,
    onCancelClick: () -> Unit
) {
    val isLocking = state == MonitorService.STATE_LOCK
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                if (isLocking) "锁定状态进行中" else "玩机阶段进行中",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                if (isLocking) "本轮锁定倒计时" else "本轮可玩机倒计时",
                color = BrandColors.TextSecondary
            )
            Text(
                formatTime(remainingSeconds),
                color = if (isLocking) BrandColors.Danger else BrandColors.Primary,
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold
            )
            if (isLocking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = BrandColors.Danger)
                    Spacer(Modifier.width(7.dp))
                    Text("屏幕已被强制锁定", color = BrandColors.Danger, fontSize = 13.sp)
                }
            }
            if (isRecoveryUnavailable) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "监督服务暂未响应，请检查后台活动设置后重试恢复。",
                    color = BrandColors.Warning,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRetryRecovery,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("重试恢复服务") }
            }
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = onCancelClick,
                enabled = !isStopping,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandColors.Danger),
                border = BorderStroke(1.dp, BrandColors.Danger),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (isStopping) "正在结束…" else "终止专注任务", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FeatureNavigationCard(actionIcon: @Composable (() -> Unit)? = null,
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text(description, color = BrandColors.TextSecondary, fontSize = 12.sp)
            }
            if (actionIcon != null) {
                Spacer(Modifier.width(8.dp))
                actionIcon()
            }
        }
    }
}

@Composable
private fun KeepAliveGuideCard(
    context: Context,
    onOpenApplicationSettings: () -> Unit
) {
}


private fun formatTime(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", safeSeconds / 60, safeSeconds % 60)
}

@Composable
private fun BackgroundSettingsCard(
    isDarkTheme: Boolean,
    bgType: String,
    bgColor: Int,
    bgGradient: String,
    bgImageIndex: Int,
    onDarkThemeChange: (Boolean) -> Unit,
    onBackgroundChanged: (String, Int, String, Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        onClick = { showDialog = true },
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.example.controlfree.R.drawable.ic_custom_background),
                contentDescription = null,
                tint = BrandColors.Primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("自定义背景", color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
                val statusText = when (bgType) {
                    "pure" -> if (bgColor == 0) "系统默认" else "自定义纯色"
                    "gradient" -> "双色渐变"
                    "image" -> when (bgImageIndex) {
                        0 -> "图片：绿意"
                        1 -> "图片：童趣"
                        2 -> "图片：清植"
                        3 -> "图片：彩愿"
                        else -> "图片背景"
                    }
                    else -> "系统默认"
                }
                Text(statusText, color = BrandColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }

    if (showDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val initialTab = when (bgType) {
            "pure" -> 0
            "gradient" -> 1
            "image" -> 2
            else -> 0
        }
        var selectedTab by remember { mutableStateOf(initialTab) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("自定义背景设置", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("纯色", "渐变", "图片").forEachIndexed { index, label ->
                            OutlinedButton(
                                onClick = { selectedTab = index },
                                modifier = Modifier.weight(1f),
                                border = if (selectedTab == index) BorderStroke(2.dp, BrandColors.Primary) else BorderStroke(1.dp, BrandColors.OutlineSoft),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (selectedTab == index) BrandColors.Primary else BrandColors.TextSecondary
                                )
                            ) {
                                Text(label, fontSize = 12.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    when (selectedTab) {
                        0 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("选择高级莫兰迪纯色：", fontSize = 11.sp, color = BrandColors.TextSecondary)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val colors = listOf(
                                        "默认" to 0,
                                        "柔粉" to 0xFFE5C8C8.toInt(),
                                        "雅绿" to 0xFFC7D3C6.toInt(),
                                        "静蓝" to 0xFFC6D5E5.toInt(),
                                        "丁香" to 0xFFDACAE5.toInt()
                                    )
                                    colors.forEach { (name, colVal) ->
                                        val isSelected = bgType == "pure" && bgColor == colVal
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.weight(1f).clickable {
                                                if (colVal != 0 && isDarkTheme) {
                                                    android.widget.Toast.makeText(context, "自定义背景仅支持亮色模式，请先切换为亮色", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    onBackgroundChanged("pure", colVal, "", -1)
                                                }
                                            }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(
                                                        color = if (colVal == 0) Color.LightGray else Color(colVal),
                                                        shape = CircleShape
                                                    )
                                                    .border(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) BrandColors.Primary else BrandColors.OutlineSoft,
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = if (colVal == 0 || colVal == 0xFFC7D3C6.toInt() || colVal == 0xFFC6D5E5.toInt() || colVal == 0xFFDACAE5.toInt() || colVal == 0xFFE5C8C8.toInt()) BrandColors.Primary else Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(name, fontSize = 10.sp, color = BrandColors.TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("选择莫兰迪低饱和渐变：", fontSize = 11.sp, color = BrandColors.TextSecondary)
                                val gradients = listOf(
                                    "薄雾玫瑰" to "#E8C5C8,#C6D5E5",
                                    "极地晨曦" to "#C7D3C6,#DACAE5",
                                    "冰川幽蓝" to "#A9C0D3,#C7D9D4",
                                    "暖沙微光" to "#E0D4C3,#E5C8C8"
                                )
                                gradients.chunked(2).forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        pair.forEach { (name, gradVal) ->
                                            val isSelected = bgType == "gradient" && bgGradient == gradVal
                                            OutlinedButton(
                                                onClick = {
                                                    if (isDarkTheme) {
                                                        android.widget.Toast.makeText(context, "自定义背景仅支持亮色模式，请先切换为亮色", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        onBackgroundChanged("gradient", 0, gradVal, -1)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                border = if (isSelected) BorderStroke(2.dp, BrandColors.Primary) else BorderStroke(1.dp, BrandColors.OutlineSoft)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = BrandColors.Primary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                    }
                                                    Text(name, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("选择图片背景：", fontSize = 11.sp, color = BrandColors.TextSecondary)
                                val images = listOf(
                                    "图片：绿意" to 0,
                                    "图片：童趣" to 1,
                                    "图片：清植" to 2,
                                    "图片：彩愿" to 3
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    images.forEach { (name, imgIdx) ->
                                        val isSelected = bgType == "image" && bgImageIndex == imgIdx
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.weight(1f).clickable {
                                                if (isDarkTheme) {
                                                    android.widget.Toast.makeText(context, "自定义背景仅支持亮色模式，请先切换为亮色", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    onBackgroundChanged("image", 0, "", imgIdx)
                                                }
                                            }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .background(
                                                        color = BrandColors.SurfaceRaised,
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .border(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) BrandColors.Primary else BrandColors.OutlineSoft,
                                                        shape = RoundedCornerShape(8.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val imageRes = when (imgIdx) {
                                                    0 -> R.drawable.bg_aurora_thumb
                                                    1 -> R.drawable.bg_sunset_thumb
                                                    2 -> R.drawable.bg_starry_thumb
                                                    3 -> R.drawable.bg_sand_thumb
                                                    else -> null
                                                }
                                                if (imageRes != null) {
                                                    Image(
                                                        painter = painterResource(id = imageRes),
                                                        contentDescription = null,
                                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(name.substringAfter("："), fontSize = 10.sp, color = BrandColors.TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isDefaultBg = (bgType == "pure" && bgColor == 0)
                    TextButton(
                        onClick = {
                            if (isDefaultBg) {
                                onDarkThemeChange(!isDarkTheme)
                            } else {
                                android.widget.Toast.makeText(context, "暗色模式仅在默认背景下起作用，请先切换回默认背景", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(
                            text = if (isDarkTheme) "亮色模式" else "暗色模式",
                            fontWeight = FontWeight.Bold,
                            color = if (isDefaultBg) BrandColors.Primary else BrandColors.TextTertiary
                        )
                    }
                    Button(onClick = { showDialog = false }) {
                        Text("确定")
                    }
                }
            }
        )
    }
}
