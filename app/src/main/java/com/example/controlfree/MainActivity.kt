package com.example.controlfree

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.theme.AppBackground
import com.example.controlfree.theme.ControlFreeTheme
import com.example.controlfree.theme.shouldUseDarkSystemBarAppearance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.controlfree.data.MonitorRecoveryGuard
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.growth.ProductivityRewardOutboxConsumer
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.supervision.app.AppSupervisionRuntimeStore
import com.example.controlfree.todo.habit.runtime.HabitReminderReceiver
import com.example.controlfree.ui.main.AuthenticationGate
import com.example.controlfree.ui.main.MainTab
import com.example.controlfree.ui.todo.TodoSubTab
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import android.widget.Toast
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.widget.WidgetNavigationContract
import com.example.controlfree.widget.WidgetPlanDestination
import com.example.controlfree.widget.WidgetPlanNavigationRequest
import com.example.controlfree.widget.WidgetPlanNavigationRoute
import com.example.controlfree.widget.WidgetPlanNavigationSnapshot
import com.example.controlfree.widget.WidgetTodoNavigationRequest
import com.example.controlfree.widget.WidgetTodoNavigationRoute
import com.example.controlfree.widget.WidgetTodoNavigationSnapshot

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import java.util.Locale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement

class MainActivity : ComponentActivity() {
  private lateinit var credentials: CredentialStore
  private lateinit var preferences: PreferenceManager
  private lateinit var recoveryGuard: MonitorRecoveryGuard
  private lateinit var appSupervisionRuntimeStore: AppSupervisionRuntimeStore
  private var isSessionAuthenticated by mutableStateOf(false)
  private var isAuthenticationRequired by mutableStateOf(false)
  private var selectedTab by mutableStateOf(MainTab.MONITOR)
  private var selectedTodoSubTab by mutableStateOf(TodoSubTab.TODO)
  private var shortcutNavigationRequest by mutableStateOf<AppShortcutNavigationRequest?>(null)
  private var widgetTodoNavigationRequest by mutableStateOf<WidgetTodoNavigationRequest?>(null)
  private var widgetPlanNavigationRequest by mutableStateOf<WidgetPlanNavigationRequest?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.controlfree.ui.todo.timeblock.FocusTimerManager.init(applicationContext)
    MonitorNotificationChannels.ensureCreated(applicationContext)
    ProductivityRewardOutboxConsumer.getInstance(applicationContext).startForProcess()
    HabitReminderReceiver.requestReconciliation(applicationContext, "app_created")
    credentials = CredentialStore(applicationContext)
    preferences = PreferenceManager(applicationContext)
    recoveryGuard = MonitorRecoveryGuard(applicationContext)
    appSupervisionRuntimeStore = AppSupervisionRuntimeStore(applicationContext)
    val initialDarkTheme = preferences.isDarkThemeEnabled()
    val initialBackgroundType = preferences.getBackgroundType()
    val initialBackgroundColor = preferences.getBackgroundColor()
    val initialBackgroundGradient = preferences.getBackgroundGradient()
    val initialBackgroundImageIndex = preferences.getBackgroundImageIndex()
    val requestedTodoSubTab = TodoSubTab.fromNavigationValue(
      intent?.getStringExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET)
    )
    val destinationValue = intent?.getStringExtra(AppShortcutRoute.EXTRA_DESTINATION)
    val restoredTodoRequest = WidgetTodoNavigationRoute.restore(
      savedInstanceState?.widgetTodoNavigationSnapshot()
    )
    val restoredPlanRequest = WidgetPlanNavigationRoute.restore(
      savedInstanceState?.widgetPlanNavigationSnapshot()
    )
    val incomingTodoRequest = WidgetTodoNavigationRoute.nextRequest(
      previous = restoredTodoRequest,
      subTabValue = intent?.getStringExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET),
      createTargetValue = intent?.getStringExtra(WidgetNavigationContract.EXTRA_CREATE_TARGET),
      itemIdValue = intent?.getStringExtra(WidgetNavigationContract.EXTRA_ITEM_ID)
    )
    val incomingPlanRequest = WidgetPlanNavigationRoute.nextRequest(
      previous = restoredPlanRequest,
      destinationValue = destinationValue,
      planIdValue = intent?.getStringExtra(WidgetNavigationContract.EXTRA_PLAN_ID)
    )
    shortcutNavigationRequest = AppShortcutRoute.nextRequest(
      previous = null,
      destinationValue = destinationValue
    )
    when {
      incomingPlanRequest != null -> widgetPlanNavigationRequest = incomingPlanRequest
      incomingTodoRequest != null -> widgetTodoNavigationRequest = incomingTodoRequest
      shortcutNavigationRequest != null -> Unit
      restoredPlanRequest != null -> widgetPlanNavigationRequest = restoredPlanRequest
      else -> widgetTodoNavigationRequest = restoredTodoRequest
    }
    intent?.removeExtra(AppShortcutRoute.EXTRA_DESTINATION)
    intent?.removeExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET)
    intent?.removeExtra(WidgetNavigationContract.EXTRA_CREATE_TARGET)
    intent?.removeExtra(WidgetNavigationContract.EXTRA_ITEM_ID)
    intent?.removeExtra(WidgetNavigationContract.EXTRA_PLAN_ID)
    selectedTab = widgetPlanNavigationRequest?.destination?.toMainTab()
      ?: widgetTodoNavigationRequest?.let { MainTab.TODO }
      ?: shortcutNavigationRequest?.destination?.toMainTab()
      ?: savedInstanceState
        ?.getString(STATE_SELECTED_TAB)
        ?.let(::mainTabForStoredValue)
      ?: MainTab.MONITOR
    selectedTodoSubTab = widgetTodoNavigationRequest?.subTab
      ?: requestedTodoSubTab
      ?.takeIf { selectedTab == MainTab.TODO }
      ?: savedInstanceState
        ?.getString(STATE_SELECTED_TODO_SUB_TAB)
        ?.let(TodoSubTab::fromNavigationValue)
      ?: TodoSubTab.TODO
    refreshAuthenticationRequirement()

    configureThemedEdgeToEdge(
      darkTheme = shouldUseDarkSystemBarAppearance(
        darkTheme = initialDarkTheme,
        bgType = initialBackgroundType,
        bgColor = initialBackgroundColor,
        bgGradient = initialBackgroundGradient,
        bgImageIndex = initialBackgroundImageIndex
      )
    )
    setContent {
      var isDarkTheme by rememberSaveable { mutableStateOf(initialDarkTheme) }
      val coroutineScope = rememberCoroutineScope()
      val context = androidx.compose.ui.platform.LocalContext.current
      var bgType by remember { mutableStateOf(initialBackgroundType) }
      var bgColor by remember { mutableStateOf(initialBackgroundColor) }
      var bgGradient by remember { mutableStateOf(initialBackgroundGradient) }
      var bgImageIndex by remember { mutableStateOf(initialBackgroundImageIndex) }
      var showQuickAddNoteDialog by remember { mutableStateOf(false) }
      val quickNoteViewModel: com.example.controlfree.ui.todo.viewmodel.QuickNoteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

      ControlFreeTheme(
        darkTheme = isDarkTheme,
        bgType = bgType,
        bgColor = bgColor,
        bgGradient = bgGradient,
        bgImageIndex = bgImageIndex
      ) {
        AppBackground(
          bgType = bgType,
          bgColor = bgColor,
          bgGradient = bgGradient,
          bgImageIndex = bgImageIndex,
          modifier = Modifier.fillMaxSize()
        ) {
          // 主内容始终保持在组合中：验证门以整屏覆盖层呈现，这样跳转系统相机等
          // 外部界面返回后，即使需要重新验证，ActivityResult（如拍照结果）也不会
          // 因宿主组合被替换而丢失。
          key(shortcutNavigationRequest?.revision ?: 0L) {
            val canConsumeWidgetNavigation =
              !isAuthenticationRequired || isSessionAuthenticated
            MainNavigation(
              selectedTab = selectedTab,
              onTabSelected = { selectedTab = it },
              initialTodoSubTab = selectedTodoSubTab,
              onTodoSubTabSelected = { selectedTodoSubTab = it },
              widgetTodoNavigationRequest = widgetTodoNavigationRequest
                ?.takeIf { canConsumeWidgetNavigation },
              onWidgetTodoNavigationConsumed = { consumed ->
                widgetTodoNavigationRequest = WidgetTodoNavigationRoute.consume(
                  widgetTodoNavigationRequest,
                  consumed
                )
              },
              widgetPlanNavigationRequest = widgetPlanNavigationRequest
                ?.takeIf { canConsumeWidgetNavigation },
              onWidgetPlanNavigationConsumed = { consumed ->
                widgetPlanNavigationRequest = WidgetPlanNavigationRoute.consume(
                  widgetPlanNavigationRequest,
                  consumed
                )
              },
              isDarkTheme = isDarkTheme,
              onDarkThemeChange = { enabled ->
                applyPersistedThemeChange(
                  darkTheme = enabled,
                  persistTheme = preferences::setDarkThemeEnabled,
                  applyWindowAppearance = { darkTheme ->
                    applyThemedWindowAppearance(
                      darkTheme = shouldUseDarkSystemBarAppearance(
                        darkTheme = darkTheme,
                        bgType = bgType,
                        bgColor = bgColor,
                        bgGradient = bgGradient,
                        bgImageIndex = bgImageIndex
                      )
                    )
                  },
                  updateComposeTheme = { isDarkTheme = it }
                )
              },
              bgType = bgType,
              bgColor = bgColor,
              bgGradient = bgGradient,
              bgImageIndex = bgImageIndex,
              onBackgroundChanged = { type, col, grad, imgIdx ->
                preferences.setBackgroundType(type)
                preferences.setBackgroundColor(col)
                preferences.setBackgroundGradient(grad)
                preferences.setBackgroundImageIndex(imgIdx)
                applyThemedWindowAppearance(
                  darkTheme = shouldUseDarkSystemBarAppearance(
                    darkTheme = isDarkTheme,
                    bgType = type,
                    bgColor = col,
                    bgGradient = grad,
                    bgImageIndex = imgIdx
                  )
                )
                bgType = type
                bgColor = col
                bgGradient = grad
                bgImageIndex = imgIdx
              },
              showQuickAddNoteDialog = showQuickAddNoteDialog,
              onShowQuickAddNoteDialogChange = { showQuickAddNoteDialog = it }
            )
          }

          if (showQuickAddNoteDialog) {
              var noteText by remember { mutableStateOf("") }
              val isAnalyzing by quickNoteViewModel.isAnalyzingText.collectAsState()
              val pendingConfirm by quickNoteViewModel.pendingAssistantConfirmation.collectAsState()
              val analysisError by quickNoteViewModel.analysisError.collectAsState()

              val focusRequester = remember { FocusRequester() }
              LaunchedEffect(showQuickAddNoteDialog) {
                  if (showQuickAddNoteDialog) {
                      delay(200)
                      focusRequester.requestFocus()
                  }
              }

              Dialog(
                  onDismissRequest = {
                      if (!isAnalyzing) {
                          showQuickAddNoteDialog = false
                          quickNoteViewModel.cancelAssistantConversion()
                      }
                  }
              ) {
                  Card(
                      shape = RoundedCornerShape(24.dp),
                      colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                      modifier = Modifier
                          .fillMaxWidth()
                          .padding(16.dp)
                  ) {
                      Column(
                          modifier = Modifier
                              .padding(16.dp)
                              .verticalScroll(rememberScrollState())
                              .fillMaxWidth()
                      ) {
                          TextField(
                              value = noteText,
                              onValueChange = { noteText = it },
                              placeholder = { Text("用一句话轻松创建待办、日程、习惯、账本、时刻与想法...", color = BrandColors.TextTertiary) },
                              modifier = Modifier
                                  .fillMaxWidth()
                                  .focusRequester(focusRequester),
                              enabled = !isAnalyzing && pendingConfirm == null,
                              colors = TextFieldDefaults.colors(
                                  focusedContainerColor = Color.Transparent,
                                  unfocusedContainerColor = Color.Transparent,
                                  disabledContainerColor = Color.Transparent,
                                  focusedIndicatorColor = Color.Transparent,
                                  unfocusedIndicatorColor = Color.Transparent,
                                  disabledIndicatorColor = Color.Transparent
                              ),
                              textStyle = TextStyle(fontSize = 16.sp, color = BrandColors.TextPrimary)
                          )

                          if (!isAnalyzing && pendingConfirm == null) {
                              Row(
                                  modifier = Modifier.fillMaxWidth(),
                                  verticalAlignment = Alignment.CenterVertically
                              ) {
                                  Spacer(Modifier.weight(1f))
                                  Box(
                                      modifier = Modifier
                                          .size(40.dp)
                                          .clip(CircleShape)
                                          .background(if (noteText.isNotBlank()) BrandColors.Primary else BrandColors.OutlineSoft)
                                          .clickable(
                                              enabled = noteText.isNotBlank(),
                                              onClick = {
                                                  quickNoteViewModel.analyzeDirectText(noteText)
                                              }
                                          ),
                                      contentAlignment = Alignment.Center
                                  ) {
                                       Text(
                                           text = "AI",
                                           color = Color.White,
                                           fontSize = 14.sp,
                                           fontWeight = FontWeight.Bold
                                       )
                                  }
                              }
                          }

                          if (isAnalyzing) {
                              Spacer(Modifier.height(16.dp))
                              Row(
                                  verticalAlignment = Alignment.CenterVertically,
                                  horizontalArrangement = Arrangement.Center,
                                  modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                              ) {
                                  CircularProgressIndicator(
                                      modifier = Modifier.size(20.dp),
                                      color = BrandColors.Primary,
                                      strokeWidth = 2.dp
                                  )
                                  Spacer(Modifier.width(12.dp))
                                  // 计时文本自带状态，避免每秒重组成个弹窗
                                  AnalyzingTimerText()
                              }
                          }

                          if (!isAnalyzing && analysisError != null) {
                              analysisError?.let { err ->
                                  Spacer(Modifier.height(16.dp))
                                  Box(
                                      Modifier
                                          .fillMaxWidth()
                                          .height(1.dp)
                                          .background(BrandColors.OutlineSoft)
                                  )
                                  Spacer(Modifier.height(12.dp))

                                  Text(
                                      text = "AI 助理分析提示",
                                      fontWeight = FontWeight.Bold,
                                      color = BrandColors.TextPrimary,
                                      fontSize = 16.sp
                                  )
                                  Spacer(Modifier.height(8.dp))

                                  Row(
                                      modifier = Modifier
                                          .fillMaxWidth()
                                          .background(BrandColors.Warning.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                          .padding(12.dp),
                                      verticalAlignment = Alignment.CenterVertically
                                  ) {
                                      Icon(
                                          imageVector = Icons.Default.Warning,
                                          contentDescription = null,
                                          tint = BrandColors.Warning,
                                          modifier = Modifier.size(20.dp)
                                      )
                                      Spacer(Modifier.width(10.dp))
                                      Text(
                                          text = err,
                                          color = BrandColors.TextPrimary,
                                          fontSize = 13.sp,
                                          lineHeight = 18.sp,
                                          modifier = Modifier.weight(1f)
                                      )
                                  }

                                  Spacer(Modifier.height(16.dp))

                                  Row(
                                      modifier = Modifier.fillMaxWidth(),
                                      horizontalArrangement = Arrangement.End,
                                      verticalAlignment = Alignment.CenterVertically
                                  ) {
                                      TextButton(
                                          onClick = {
                                              quickNoteViewModel.cancelAssistantConversion()
                                          }
                                      ) {
                                          Text("重试", fontWeight = FontWeight.Bold, color = BrandColors.Primary)
                                      }
                                  }
                              }
                          }

                          pendingConfirm?.let { confirmation ->
                              Spacer(Modifier.height(16.dp))
                              Box(
                                  Modifier
                                      .fillMaxWidth()
                                      .height(1.dp)
                                      .background(BrandColors.OutlineSoft)
                              )
                              Spacer(Modifier.height(12.dp))

                              Text(
                                  text = "确认 AI 助理操作",
                                  fontWeight = FontWeight.Bold,
                                  color = BrandColors.TextPrimary,
                                  fontSize = 16.sp
                              )
                              Spacer(Modifier.height(8.dp))

                              val analysis = confirmation.analysis

                              Column(
                                  verticalArrangement = Arrangement.spacedBy(10.dp),
                                  modifier = Modifier.fillMaxWidth()
                              ) {
                                  AssistantPreviewSection(
                                      "账目",
                                      analysis.ledgerEntries.map { entry ->
                                          val sign = if (
                                              entry.direction == com.example.controlfree.todo.LedgerDirection.EXPENSE
                                          ) "-" else "+"
                                          "${entry.title} · $sign${String.format(Locale.CHINA, "%.2f", entry.amountFen / 100.0)} 元"
                                      }
                                  )
                                  AssistantPreviewSection(
                                      "待办",
                                      analysis.todoItems.map { todo ->
                                          val timing = buildList {
                                              todo.scheduledStartEpochMillis?.let { startAt ->
                                                  add("开始 ${formatTimestamp(startAt)}")
                                                  todo.durationMinutes?.let { duration ->
                                                      runCatching {
                                                          Math.addExact(
                                                              startAt,
                                                              Math.multiplyExact(duration.toLong(), 60_000L)
                                                          )
                                                      }.getOrNull()?.let { endAt ->
                                                          add("预计结束 ${formatTimestamp(endAt)}")
                                                      }
                                                  }
                                              }
                                              if (todo.scheduledStartEpochMillis == null) {
                                                  todo.durationMinutes?.let { add("预计用时 $it 分钟") }
                                              }
                                              todo.dueAtEpochMillis?.let { add("截止 ${formatTimestamp(it)}") }
                                              if (todo.reminderMinutesBefore.isNotEmpty()) {
                                                  add(
                                                      todo.reminderMinutesBefore.joinToString(
                                                          prefix = "提前 ",
                                                          postfix = " 分钟提醒"
                                                      )
                                                  )
                                              }
                                          }
                                          listOf(todo.title, timing.joinToString(" · "))
                                              .filter(String::isNotBlank)
                                              .joinToString("\n")
                                      }
                                  )
                                  AssistantPreviewSection(
                                      "习惯",
                                      analysis.habits.map { it.name }
                                  )
                                  AssistantPreviewSection(
                                      "专注",
                                      analysis.focusSessions.map { "${it.title} · ${it.durationMinutes} 分钟" }
                                  )
                                  AssistantPreviewSection(
                                      "时刻",
                                      analysis.anniversaries.map {
                                          "${it.title} · ${formatTimestamp(it.targetAtEpochMillis)}"
                                      }
                                  )
                                  AssistantPreviewSection("AI 建议", analysis.advice, BrandColors.Primary)
                                  AssistantPreviewSection("注意", analysis.warnings, BrandColors.Warning)
                              }

                              Spacer(Modifier.height(16.dp))

                              Row(
                                  modifier = Modifier.fillMaxWidth(),
                                  horizontalArrangement = Arrangement.End,
                                  verticalAlignment = Alignment.CenterVertically
                              ) {
                                  TextButton(
                                      onClick = {
                                          quickNoteViewModel.cancelAssistantConversion()
                                      }
                                  ) {
                                      Text("取消", color = BrandColors.TextSecondary)
                                  }
                                  Spacer(Modifier.width(8.dp))
                                  TextButton(
                                      onClick = {
                                          quickNoteViewModel.confirmAssistantConversion(confirmation)
                                          showQuickAddNoteDialog = false
                                      }
                                  ) {
                                      Text("确认创建", fontWeight = FontWeight.Bold, color = BrandColors.Primary)
                                  }
                              }
                          }
                      }
                  }
              }
          }
          if (isAuthenticationRequired && !isSessionAuthenticated) {
            // Surface 会阻断触摸事件穿透到其后的内容。
            Surface(modifier = Modifier.fillMaxSize(), color = BrandColors.OverlaySurface) {
              AuthenticationGate(
                credentials = credentials,
                onUnlocked = { isSessionAuthenticated = true }
              )
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val destinationValue = intent.getStringExtra(AppShortcutRoute.EXTRA_DESTINATION)
    val requestedTodoSubTab = TodoSubTab.fromNavigationValue(
      intent.getStringExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET)
    )
    val nextWidgetRequest = WidgetTodoNavigationRoute.nextRequest(
      previous = widgetTodoNavigationRequest,
      subTabValue = intent.getStringExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET),
      createTargetValue = intent.getStringExtra(WidgetNavigationContract.EXTRA_CREATE_TARGET),
      itemIdValue = intent.getStringExtra(WidgetNavigationContract.EXTRA_ITEM_ID)
    )
    val nextPlanRequest = WidgetPlanNavigationRoute.nextRequest(
      previous = widgetPlanNavigationRequest,
      destinationValue = destinationValue,
      planIdValue = intent.getStringExtra(WidgetNavigationContract.EXTRA_PLAN_ID)
    )
    intent.removeExtra(AppShortcutRoute.EXTRA_DESTINATION)
    intent.removeExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET)
    intent.removeExtra(WidgetNavigationContract.EXTRA_CREATE_TARGET)
    intent.removeExtra(WidgetNavigationContract.EXTRA_ITEM_ID)
    intent.removeExtra(WidgetNavigationContract.EXTRA_PLAN_ID)
    val nextRequest = AppShortcutRoute.nextRequest(
      previous = shortcutNavigationRequest,
      destinationValue = destinationValue
    )
    if (nextRequest == null && nextWidgetRequest == null && nextPlanRequest == null) return
    if (nextRequest != null) {
      shortcutNavigationRequest = nextRequest
      selectedTab = nextRequest.destination.toMainTab()
    }
    when {
      nextPlanRequest != null -> {
        widgetPlanNavigationRequest = nextPlanRequest
        widgetTodoNavigationRequest = null
        selectedTab = nextPlanRequest.destination.toMainTab()
      }
      nextWidgetRequest != null -> {
        widgetTodoNavigationRequest = nextWidgetRequest
        widgetPlanNavigationRequest = null
        selectedTab = MainTab.TODO
        selectedTodoSubTab = nextWidgetRequest.subTab
      }
      nextRequest != null -> {
        widgetTodoNavigationRequest = null
        widgetPlanNavigationRequest = null
        if (selectedTab == MainTab.TODO) {
          selectedTodoSubTab = requestedTodoSubTab ?: TodoSubTab.TODO
        }
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(STATE_SELECTED_TAB, selectedTab.name)
    outState.putString(STATE_SELECTED_TODO_SUB_TAB, selectedTodoSubTab.name)
    widgetTodoNavigationRequest?.let { request ->
      outState.putWidgetTodoNavigationSnapshot(WidgetTodoNavigationRoute.snapshot(request))
    }
    widgetPlanNavigationRequest?.let { request ->
      outState.putWidgetPlanNavigationSnapshot(WidgetPlanNavigationRoute.snapshot(request))
    }
    super.onSaveInstanceState(outState)
  }

  override fun onStart() {
    refreshAuthenticationRequirement()
    // 外部跳转预期只豁免一次返回；进入前台后立即失效。
    ExternalResultExpectation.clear()
    super.onStart()
  }

  override fun onStop() {
    super.onStop()
    // 生命周期观察者先使后台认证任务失效，再清除本次前台会话。
    // 应用内主动跳转相机/相册等外部界面属于同一次使用会话，短窗口内不清除，
    // 否则拍照返回会被应用锁拦截。
    if (!ExternalResultExpectation.isActive()) {
      isSessionAuthenticated = false
    }
  }

  private fun refreshAuthenticationRequirement() {
    isAuthenticationRequired = shouldRequireAppAuthentication(
      isMonitorActive = preferences.isMonitorActive(),
      isRecoveryLockRequired = recoveryGuard.requiresLock(),
      isMonitorServiceRunning = MonitorService.isRunning,
      isAppSupervisionActive = AppSupervisionService.isRunning ||
        appSupervisionRuntimeStore.requiresAppAuthentication(System.currentTimeMillis()),
      hasCredential = credentials.hasAnyCredential()
    )
  }

  private fun Bundle.widgetTodoNavigationSnapshot(): WidgetTodoNavigationSnapshot? {
    if (!containsKey(STATE_WIDGET_TODO_REVISION)) return null
    val subTabValue = getString(STATE_WIDGET_TODO_SUB_TAB) ?: return null
    return WidgetTodoNavigationSnapshot(
      revision = getLong(STATE_WIDGET_TODO_REVISION),
      subTabValue = subTabValue,
      createTargetValue = getString(STATE_WIDGET_TODO_CREATE_TARGET),
      itemIdValue = getString(STATE_WIDGET_TODO_ITEM_ID)
    )
  }

  private fun Bundle.putWidgetTodoNavigationSnapshot(snapshot: WidgetTodoNavigationSnapshot) {
    putLong(STATE_WIDGET_TODO_REVISION, snapshot.revision)
    putString(STATE_WIDGET_TODO_SUB_TAB, snapshot.subTabValue)
    putString(STATE_WIDGET_TODO_CREATE_TARGET, snapshot.createTargetValue)
    putString(STATE_WIDGET_TODO_ITEM_ID, snapshot.itemIdValue)
  }

  private fun Bundle.widgetPlanNavigationSnapshot(): WidgetPlanNavigationSnapshot? {
    if (!containsKey(STATE_WIDGET_PLAN_REVISION)) return null
    val destinationValue = getString(STATE_WIDGET_PLAN_DESTINATION) ?: return null
    val planIdValue = getString(STATE_WIDGET_PLAN_ID) ?: return null
    return WidgetPlanNavigationSnapshot(
      revision = getLong(STATE_WIDGET_PLAN_REVISION),
      destinationValue = destinationValue,
      planIdValue = planIdValue
    )
  }

  private fun Bundle.putWidgetPlanNavigationSnapshot(snapshot: WidgetPlanNavigationSnapshot) {
    putLong(STATE_WIDGET_PLAN_REVISION, snapshot.revision)
    putString(STATE_WIDGET_PLAN_DESTINATION, snapshot.destinationValue)
    putString(STATE_WIDGET_PLAN_ID, snapshot.planIdValue)
  }

  private companion object {
    const val STATE_SELECTED_TAB = "main_selected_tab"
    const val STATE_SELECTED_TODO_SUB_TAB = "main_selected_todo_sub_tab"
    const val STATE_WIDGET_TODO_REVISION = "main_widget_todo_revision"
    const val STATE_WIDGET_TODO_SUB_TAB = "main_widget_todo_sub_tab"
    const val STATE_WIDGET_TODO_CREATE_TARGET = "main_widget_todo_create_target"
    const val STATE_WIDGET_TODO_ITEM_ID = "main_widget_todo_item_id"
    const val STATE_WIDGET_PLAN_REVISION = "main_widget_plan_revision"
    const val STATE_WIDGET_PLAN_DESTINATION = "main_widget_plan_destination"
    const val STATE_WIDGET_PLAN_ID = "main_widget_plan_id"
  }
}

private fun AppShortcutDestination.toMainTab(): MainTab = when (this) {
  AppShortcutDestination.FOCUS -> MainTab.FOCUS
  AppShortcutDestination.MONITOR -> MainTab.MONITOR
  AppShortcutDestination.TODO -> MainTab.TODO
  AppShortcutDestination.STATISTICS -> MainTab.STATISTICS
  AppShortcutDestination.SETTINGS -> MainTab.SETTINGS
}

private fun WidgetPlanDestination.toMainTab(): MainTab = when (this) {
  WidgetPlanDestination.MONITOR -> MainTab.MONITOR
  WidgetPlanDestination.FOCUS -> MainTab.FOCUS
}

private fun mainTabForStoredValue(value: String): MainTab? =
  MainTab.entries.firstOrNull { tab -> tab.name == value }

internal fun shouldRequireAppAuthentication(
  isMonitorActive: Boolean,
  isRecoveryLockRequired: Boolean,
  isMonitorServiceRunning: Boolean,
  isAppSupervisionActive: Boolean = false,
  hasCredential: Boolean = true
): Boolean =
  hasCredential && (
    isMonitorActive || isRecoveryLockRequired || isMonitorServiceRunning || isAppSupervisionActive
  )

@Composable
private fun AssistantPreviewSection(
    title: String,
    items: List<String>,
    accent: Color = BrandColors.TextPrimary
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "$title (${items.size})",
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        items.forEach { item ->
            Text(
                "• $item",
                color = BrandColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}.getOrDefault("")

/** AI 分析中的秒级计时；状态自持，把每秒重组隔离在这个小文本里。 */
@Composable
private fun AnalyzingTimerText() {
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsedSeconds++
        }
    }
    Text(
        text = "AI 助理分析中... ${elapsedSeconds}s",
        color = BrandColors.Primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
}
