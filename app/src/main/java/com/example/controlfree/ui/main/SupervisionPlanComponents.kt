package com.example.controlfree.ui.main

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TabRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.example.controlfree.MonitorService
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.runtime.SupervisionAlarmScheduler
import com.example.controlfree.supervision.runtime.ScheduledOwnerReadResult
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.widget.ControlFreeWidgetType
import com.example.controlfree.widget.WidgetPlanNavigationRequest
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.controlfree.data.UsageStatsRepository
import com.example.controlfree.supervision.persistence.SupervisionHistoryRepository
import com.example.controlfree.supervision.history.SupervisionSessionKind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import com.example.controlfree.supervision.history.SupervisionSessionEndReason

internal enum class SupervisionSubTab(val displayName: String) {
    TIMED("定时监督"),
    APP("App监督")
}

@Composable
internal fun SupervisionPlansSection(
    selectedSubTab: SupervisionSubTab,
    viewModel: SupervisionPlansViewModel,
    runtimeReadiness: RuntimeReadiness,
    onCheckRuntimeReadiness: () -> RuntimeReadiness,
    onFixRequirement: (RuntimeRequirementKey) -> Unit,
    onRefreshReadiness: () -> Unit,
    navigationRequest: WidgetPlanNavigationRequest? = null,
    onNavigationConsumed: (WidgetPlanNavigationRequest) -> Unit = {},
    remainingSeconds: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingEnable by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingEnabledEditorSave by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val navigationBringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(viewModel, context.applicationContext) {
        viewModel.initialize(context.applicationContext)
    }
    LaunchedEffect(pendingEnabledEditorSave, state.editorDraft) {
        if (pendingEnabledEditorSave && state.editorDraft == null) {
            pendingEnabledEditorSave = false
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SupervisionPlansUiEvent.Message -> Toast.makeText(
                    context,
                    event.text,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        // 本区块的时钟只用于分钟级语义（时间窗判断、统计刷新桶），
        // 30 秒 tick 足够；秒级倒计时由服务推送的 remainingSeconds 单独驱动。
        while (true) {
            delay(30_000L)
            now = Instant.now()
        }
    }

    val historyRepository = remember { SupervisionHistoryRepository.getInstance(context.applicationContext) }
    val usageStatsRepository = remember { UsageStatsRepository(context.applicationContext) }
    var todayTotalMillis by remember { mutableStateOf(0L) }
    var todayFocusMinutes by remember { mutableStateOf(0L) }
    var completedSupervisionsCount by remember { mutableStateOf(0) }
    val statsRefreshKey = remember(now) { now.epochSecond / 10L }
    LaunchedEffect(state.plans, statsRefreshKey) {
        withContext(Dispatchers.IO) {
            try {
                val overview = usageStatsRepository.loadOverview()
                todayTotalMillis = overview.todayTotalMillis
                
                val zoneId = ZoneId.systemDefault()
                val todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val nowEpoch = System.currentTimeMillis()
                val allRecords = historyRepository.getAll()
                val todayRecords = allRecords.filter { record ->
                    val recordEnd = record.endedAtEpochMillis ?: nowEpoch
                    record.startedAtEpochMillis < nowEpoch && recordEnd > todayStart
                }
                val todayFocusMillis = todayRecords
                    .filter { it.kind == SupervisionSessionKind.MANUAL_FOCUS || it.kind == SupervisionSessionKind.SCHEDULED_FOCUS }
                    .sumOf { record ->
                        val start = maxOf(record.startedAtEpochMillis, todayStart)
                        val end = minOf(record.endedAtEpochMillis ?: nowEpoch, nowEpoch)
                        (end - start).coerceAtLeast(0L)
                    }
                todayFocusMinutes = todayFocusMillis / 60000L

                val sevenDaysAgoStart = LocalDate.now(zoneId).minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
                completedSupervisionsCount = allRecords.count { record ->
                    record.startedAtEpochMillis >= sevenDaysAgoStart && record.endReason == SupervisionSessionEndReason.COMPLETED
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val globalPlans = state.plans.filter { it.type == SupervisionPlanType.GLOBAL }
    LaunchedEffect(navigationRequest?.revision, state.isLoading, state.plans) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (state.isLoading) return@LaunchedEffect
        val target = state.plans.firstOrNull { plan ->
            plan.id == request.planId && plan.type != SupervisionPlanType.FOCUS
        }
        if (target == null) {
            Toast.makeText(context, "该组件绑定的监督任务已不存在", Toast.LENGTH_SHORT).show()
            onNavigationConsumed(request)
            return@LaunchedEffect
        }
        navigationBringIntoViewRequester.bringIntoView()
        delay(900L)
        onNavigationConsumed(request)
    }
    val runtimePreferences = remember { PreferenceManager(context.applicationContext) }
    val monitorServiceRunning = MonitorService.isRunning
    // 运行时归属/活跃状态只在服务启停或计划变更时变化；
    // remember 避免每次重组都重复读取 SharedPreferences。
    val runtimeOwner = remember(monitorServiceRunning, state.plans) {
        (
            runtimePreferences.inspectScheduledMonitorOwner() as? ScheduledOwnerReadResult.Available
            )?.owner
    }
    val monitorActive = remember(monitorServiceRunning, state.plans) {
        runtimePreferences.isMonitorActive()
    }
    val hasEnabledTimedPlan = state.plans.any(SupervisionPlan::enabled)
    val exactAlarmRefreshBucket = now.epochSecond / 30L
    val exactAlarmReady = remember(globalPlans, exactAlarmRefreshBucket) {
        try {
            SupervisionAlarmScheduler(context.applicationContext).canScheduleExact()
        } catch (_: RuntimeException) {
            false
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectedSubTab == SupervisionSubTab.TIMED) {
            SupervisionOverviewCard(
                todayTotalMillis = todayTotalMillis,
                todayFocusMinutes = todayFocusMinutes,
                timedPlansCount = globalPlans.size,
                appPlansCount = state.plans.filter { it.type == SupervisionPlanType.APP }.size,
                completedCount = completedSupervisionsCount
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))

            SupervisionSectionHeader(
                title = "定时监督任务",
                description = "在指定的几点到几点执行对应监督任务",
                count = globalPlans.size
            )

            if (hasEnabledTimedPlan && !exactAlarmReady) {
                ExactAlarmRecommendationCard(
                    onOpenSettings = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = "package:${context.packageName}".toUri()
                                    }
                                )
                            }
                        } catch (_: RuntimeException) {
                            Toast.makeText(
                                context,
                                "无法打开精确定时设置，请在系统特殊权限中手动开启",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }

            when {
                state.isLoading -> PlansLoadingCard()
                state.loadProblem != null && globalPlans.isEmpty() -> PlansErrorCard(
                    problem = state.loadProblem,
                    onRetry = viewModel::retryLoading
                )
                globalPlans.isEmpty() -> EmptyPlansCard(
                    onCreate = { viewModel.openNewGlobalPlan(ZoneId.systemDefault()) }
                )
                else -> {
                    globalPlans.forEach { plan ->
                        key(plan.id) {
                            val scheduledWindowActive =
                                plan.enabled && plan.schedule.isActiveAt(now, ZoneId.systemDefault())
                            val runtimeActive = monitorActive && monitorServiceRunning &&
                                runtimeOwner?.planId == plan.id &&
                                runtimeOwner.planUpdatedAtEpochMillis == plan.updatedAtEpochMillis
                            GlobalPlanCard(
                                plan = plan,
                                modifier = planNavigationModifier(
                                    isTarget = navigationRequest?.planId == plan.id,
                                    bringIntoViewRequester = navigationBringIntoViewRequester
                                ),
                                scheduledWindowActive = scheduledWindowActive,
                                runtimeActive = runtimeActive,
                                blockedByManualMonitor =
                                    scheduledWindowActive && monitorActive && runtimeOwner == null,
                                monitorServiceRunning = monitorServiceRunning,
                                runtimePrerequisitesReady = runtimeReadiness.canStart,
                                busy = plan.id in state.busyPlanIds,
                                remainingSeconds = remainingSeconds,
                                onEnabledChange = { enabled ->
                                    if (enabled) {
                                        val currentReadiness = onCheckRuntimeReadiness()
                                        if (currentReadiness.canStart) {
                                            viewModel.setEnabled(plan, true)
                                        } else {
                                            pendingEnable = plan
                                            onRefreshReadiness()
                                        }
                                    } else {
                                        viewModel.setEnabled(plan, false)
                                    }
                                },
                                onEdit = { viewModel.openGlobalPlan(plan) },
                                onDelete = { pendingDelete = plan }
                            )
                        }
                    }
                    if (state.loadProblem != null) {
                        PlansErrorCard(state.loadProblem, viewModel::retryLoading)
                    }
                    OutlinedButton(
                        onClick = { viewModel.openNewGlobalPlan(ZoneId.systemDefault()) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("新建定时监督任务")
                    }
                }
            }
        } else {
            AppSupervisionPlansSection(
                state = state,
                viewModel = viewModel,
                now = now,
                runtimeReadiness = runtimeReadiness.forAppSupervision(),
                onCheckRuntimeReadiness = {
                    onCheckRuntimeReadiness().forAppSupervision()
                },
                onFixRequirement = onFixRequirement,
                onRefreshReadiness = onRefreshReadiness,
                highlightedPlanId = navigationRequest?.planId,
                navigationBringIntoViewRequester = navigationBringIntoViewRequester
            )
        }
    }

    state.editorDraft?.takeUnless { pendingEnabledEditorSave }?.let { draft ->
        GlobalPlanEditorDialog(
            draft = draft,
            isSaving = state.isSaving,
            onDismiss = viewModel::closeEditor,
            onNameChange = viewModel::updateName,
            exactAlarmNeedsAction = runtimeReadiness.requirements.any { requirement ->
                requirement.key == RuntimeRequirementKey.EXACT_ALARM && requirement.needsAction
            },
            onActivationChange = viewModel::updateActivation,
            state = state,
            onPrepareAppTriggerSelection = viewModel::prepareGlobalAppTriggerSelection,
            onToggleTriggerApp = viewModel::toggleGlobalTriggerApp,
            onRetryApps = viewModel::retrySupervisableApps,
            onOpenExactAlarmSettings = {
                onFixRequirement(RuntimeRequirementKey.EXACT_ALARM)
            },
            onDeleteRequest = draft.planId?.let { planId ->
                {
                    state.plans.firstOrNull { it.id == planId }?.let { plan ->
                        pendingDelete = plan
                    }
                }
            },
            onToggleDay = viewModel::toggleDay,
            onRangeStartChange = viewModel::updateRangeStart,
            onRangeEndChange = viewModel::updateRangeEnd,
            onAddRange = viewModel::addRange,
            onRemoveRange = viewModel::removeRange,
            onUsageChange = viewModel::updateUsageMinutes,
            onLockChange = viewModel::updateLockMinutes,
            onSave = {
                if (
                    draft.enabled ||
                    draft.scheduledEnableAtEpochMillis != null ||
                    draft.triggerAppPackageNames.isNotEmpty()
                ) {
                    val currentReadiness = onCheckRuntimeReadiness()
                    if (currentReadiness.canStart) {
                        viewModel.saveEditor(ZoneId.systemDefault())
                    } else {
                        pendingEnabledEditorSave = true
                        onRefreshReadiness()
                    }
                } else {
                    viewModel.saveEditor(ZoneId.systemDefault())
                }
            }
        )
    }

    pendingDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("删除监督计划？") },
            text = { Text("“${plan.name}”及其时间范围将被永久删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(plan)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Danger)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }


    pendingEnable?.let { plan ->
        RuntimeReadinessDialog(
            readiness = runtimeReadiness,
            title = "启用定时监督任务",
            description =
                "启用“${plan.name}”后，将在设定时段自动执行玩机与锁定循环。请先处理缺失的关键运行权限。",
            confirmLabel = "确认启用",
            dismissLabel = "暂不启用",
            onFix = onFixRequirement,
            onRefresh = onRefreshReadiness,
            onDismiss = { pendingEnable = null },
            onConfirm = {
                if (runtimeReadiness.canStart) {
                    pendingEnable = null
                    viewModel.setEnabled(plan, true)
                }
            }
        )
    }

    if (pendingEnabledEditorSave) {
        state.editorDraft?.let { draft ->
            val isScheduledSave = !draft.enabled && draft.scheduledEnableAtEpochMillis != null
            val isAppTriggeredSave = draft.triggerAppPackageNames.isNotEmpty()
            RuntimeReadinessDialog(
                readiness = runtimeReadiness,
                title = when {
                    isAppTriggeredSave && draft.planId == null -> "创建 App 触发监督任务"
                    isAppTriggeredSave -> "保存 App 触发监督任务"
                    isScheduledSave && draft.planId == null -> "创建定时监督预约"
                    isScheduledSave -> "保存定时监督预约"
                    draft.planId == null -> "创建并启用定时监督任务"
                    else -> "保存并启用定时监督任务"
                },
                description = when {
                    isAppTriggeredSave ->
                        "保存后将在设定时段内监测已选 App，任务停用时再次打开任一目标 App 即自动开启监督。请先处理缺失的关键运行权限。"
                    isScheduledSave ->
                        "预约“${draft.name.ifBlank { "未命名监督任务" }}”后，任务将在所选时间自动开启，并按设定时段执行监督。请先处理缺失的关键运行权限。"
                    else ->
                        "启用“${draft.name.ifBlank { "未命名监督任务" }}”后，将在设定时段自动执行监督。请先处理缺失的关键运行权限。"
                },
                confirmLabel = when {
                    isAppTriggeredSave -> "确认保存 App 触发"
                    isScheduledSave -> "确认保存预约"
                    else -> "确认保存并启用"
                },
                dismissLabel = "返回编辑",
                onFix = onFixRequirement,
                onRefresh = onRefreshReadiness,
                onDismiss = { pendingEnabledEditorSave = false },
                onConfirm = {
                    if (runtimeReadiness.canStart) {
                        pendingEnabledEditorSave = false
                        viewModel.saveEditor(ZoneId.systemDefault())
                    }
                }
            )
        }
    }
}

@Composable
internal fun ExactAlarmRecommendationCard(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.WarningContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("建议开启精确定时", color = BrandColors.Warning, fontWeight = FontWeight.Bold)
            Text(
                "未开启时，Android 可能延后任务开始时间；任务运行后仍会按结束边界自动停止。",
                color = BrandColors.TextSecondary,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = onOpenSettings) { Text("打开精确定时设置") }
        }
    }
}

@Composable
internal fun PlansLoadingCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text("正在读取监督计划…", color = BrandColors.TextSecondary)
        }
    }
}

@Composable
internal fun PlansErrorCard(
    problem: SupervisionPlansLoadProblem?,
    onRetry: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.DangerContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (problem == SupervisionPlansLoadProblem.CORRUPT_DATA) {
                    "监督计划数据异常，为防止误执行已停止读取"
                } else {
                    "暂时无法读取监督计划"
                },
                color = BrandColors.Danger
            )
            OutlinedButton(onClick = onRetry) { Text("重新读取") }
        }
    }
}

@Composable
private fun EmptyPlansCard(onCreate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BrandColors.SurfaceCard.copy(alpha = 0.76f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = BrandColors.TextTertiary.copy(alpha = 0.7f)
            )
            Text(
                text = "还没有定时监督任务",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "例如：每天 08:00–12:00 执行 30 分钟可用、5 分钟锁定的监督循环。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = BrandColors.TextTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(2.dp))
            Button(
                onClick = onCreate,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandColors.Primary.copy(alpha = 0.12f),
                    contentColor = BrandColors.Primary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text("新建监督任务", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun ProtectingStatusBadge(isProtecting: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "protectingPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "protectingPulse"
    )

    val bg = if (isProtecting) Color(0xFFE2F3EC) else Color(0xFFEEF1EF)
    val dotColor = if (isProtecting) Color(0xFF16B07E) else Color(0xFF8A988F)
    val textColor = if (isProtecting) Color(0xFF0C2C20) else Color(0xFF5D6F66)
    val text = if (isProtecting) "守护中" else "未开启"

    Row(
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer(alpha = if (isProtecting) alpha else 1.0f)
                .background(dotColor, shape = CircleShape)
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SupervisionOverviewCard(
    todayTotalMillis: Long,
    todayFocusMinutes: Long,
    timedPlansCount: Int,
    appPlansCount: Int,
    completedCount: Int
) {
    val primaryColor = BrandColors.Primary
    val primaryContainerColor = BrandColors.PrimaryContainer
    val textPrimaryColor = BrandColors.TextPrimary
    val textSecondaryColor = BrandColors.TextSecondary
    val surfaceCardColor = BrandColors.SurfaceCard

    val hours = todayTotalMillis / 3600000L
    val minutes = (todayTotalMillis % 3600000L) / 60000L
    val ratio = (todayTotalMillis.toFloat() / 86400000f).coerceIn(0f, 1f)
    val percentage = (ratio * 100f).toInt()
    
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color(0x180F5132),
                spotColor = Color(0x140F5132)
            )
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        surfaceCardColor,
                        surfaceCardColor,
                        primaryContainerColor.copy(alpha = 0.46f)
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .border(BorderStroke(1.dp, Color(0x0D000000)), shape = RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "今日用机时长",
                        color = textSecondaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "$hours",
                            color = textPrimaryColor,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "h",
                            color = textSecondaryColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$minutes",
                            color = textPrimaryColor,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "m",
                            color = textSecondaryColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "已完成 $completedCount 次监督",
                            color = textSecondaryColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.08f),
                            radius = size.minDimension / 2 - 8.dp.toPx(),
                            style = Stroke(width = 8.dp.toPx())
                        )
                        drawArc(
                            brush = Brush.linearGradient(
                                colors = listOf(primaryColor, Color(0xFF16B07E))
                            ),
                            startAngle = -90f,
                            sweepAngle = 360f * ratio,
                            useCenter = false,
                            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$percentage%",
                            color = textPrimaryColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "已使用",
                            color = textSecondaryColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                val statModifier = Modifier
                    .weight(1f)
                    .background(primaryColor.copy(alpha = 0.04f), shape = RoundedCornerShape(13.dp))
                    .border(BorderStroke(1.dp, Color(0x0A000000)), shape = RoundedCornerShape(13.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp)
                
                Column(modifier = statModifier) {
                    Text(
                        text = "$timedPlansCount",
                        color = textPrimaryColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "定时任务",
                        color = textSecondaryColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                Column(modifier = statModifier) {
                    Text(
                        text = "$appPlansCount",
                        color = textPrimaryColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "App监督",
                        color = textSecondaryColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                Column(modifier = statModifier) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$todayFocusMinutes",
                            color = textPrimaryColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "m",
                            color = textSecondaryColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 1.dp, start = 1.dp)
                        )
                    }
                    Text(
                        text = "专注时长",
                        color = textSecondaryColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalPlanCard(
    plan: SupervisionPlan,
    modifier: Modifier = Modifier,
    scheduledWindowActive: Boolean,
    runtimeActive: Boolean,
    blockedByManualMonitor: Boolean,
    monitorServiceRunning: Boolean,
    runtimePrerequisitesReady: Boolean,
    busy: Boolean,
    remainingSeconds: Int = 0,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val policy = plan.policy as GlobalCyclePolicy
    val statusText = when {
        !plan.enabled && plan.scheduledEnableAtEpochMillis != null ->
            "已预约 ${formatScheduledEnableAt(plan.scheduledEnableAtEpochMillis)} 开启"
        !plan.enabled && plan.triggerAppPackageNames.isNotEmpty() ->
            "等待打开触发 App"
        !plan.enabled -> "已停用"
        scheduledWindowActive && !runtimePrerequisitesReady -> "缺少运行权限"
        runtimeActive -> "运行中"
        blockedByManualMonitor -> "等待手动监督结束"
        scheduledWindowActive && monitorServiceRunning -> "正在启动"
        scheduledWindowActive -> "等待系统启动"
        else -> "等待生效时段"
    }
    val statusColor = when {
        scheduledWindowActive && !runtimePrerequisitesReady -> BrandColors.Danger
        runtimeActive -> BrandColors.Primary
        plan.enabled -> BrandColors.Warning
        plan.scheduledEnableAtEpochMillis != null -> BrandColors.AppAccent
        plan.triggerAppPackageNames.isNotEmpty() -> BrandColors.AppAccent
        else -> BrandColors.TextTertiary
    }
    val isOff = !plan.enabled

    PlanActionCard(
        planId = plan.id,
        modifier = modifier.graphicsLayer(alpha = if (isOff) 0.92f else 1f),
        busy = busy,
        onEdit = onEdit,
        onDelete = onDelete,
        widgetType = ControlFreeWidgetType.SUPERVISION
    ) {
        Column(
            modifier = Modifier
                .background(if (isOff) Color(0xFFF9FAF9) else Color.White)
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val iconBg = if (runtimeActive) BrandColors.PrimaryContainer else Color(0xFFEEF1EF)
                    val iconColor = if (runtimeActive) BrandColors.Primary else Color(0xFF8A988F)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (runtimeActive) Icons.Default.PlayArrow else Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = plan.name,
                            color = BrandColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            val dotColor = if (runtimeActive) BrandColors.Primary else if (plan.enabled) BrandColors.Warning else Color(0xFFB9C4BD)
                            if (runtimeActive) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulseDot")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulseDot"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .graphicsLayer(alpha = alpha)
                                        .background(dotColor, shape = CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(dotColor, shape = CircleShape)
                                )
                            }
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                StablePlanEnabledSwitch(
                    checked = plan.enabled,
                    onCheckedChange = onEnabledChange,
                    busy = busy
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column {
                    Text(
                        text = "执行日",
                        color = BrandColors.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatActiveDays(plan.schedule.activeDays),
                        color = BrandColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Column {
                    Text(
                        text = "生效时段",
                        color = BrandColors.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatTimeRanges(plan.schedule.ranges),
                        color = BrandColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "监督循环",
                        color = BrandColors.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "可用 → 锁定 循环",
                        color = BrandColors.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                val useWeight = policy.usageDuration.toMinutes().toFloat()
                val lockWeight = policy.lockDuration.toMinutes().toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEEF1EF))
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(useWeight)
                                .background(
                                    if (plan.enabled) Brush.horizontalGradient(
                                        listOf(BrandColors.Primary, Color(0xFF16B07E))
                                    ) else SolidColor(Color(0xFFC2CDC7))
                                )
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(lockWeight)
                        ) {
                            val w = size.width
                            val h = size.height
                            val baseColor = if (plan.enabled) Color(0xFFD9822B) else Color(0xFFD8C2A8)
                            val stripeColor = if (plan.enabled) Color(0xFFE89A4A) else Color(0xFFE0D0BE)
                            drawRect(color = baseColor)
                            
                            val stripeWidth = 6.dp.toPx()
                            val gap = 10.dp.toPx()
                            var x = -h
                            while (x < w) {
                                val path = Path().apply {
                                    moveTo(x, 0f)
                                    lineTo(x + stripeWidth, 0f)
                                    lineTo(x + stripeWidth + h, h)
                                    lineTo(x + h, h)
                                    close()
                                }
                                drawPath(path, color = stripeColor)
                                x += stripeWidth + gap
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (plan.enabled) BrandColors.Primary else Color(0xFFC2CDC7))
                        )
                        Text(
                            text = "可用 ",
                            color = BrandColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${policy.usageDuration.toMinutes()} 分钟",
                            color = BrandColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (plan.enabled) Color(0xFFD9822B) else Color(0xFFD8C2A8))
                        )
                        Text(
                            text = "锁定 ",
                            color = BrandColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${policy.lockDuration.toMinutes()} 分钟",
                            color = BrandColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (runtimeActive && remainingSeconds > 0) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulseNow")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseNow"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandColors.PrimaryContainer)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .graphicsLayer(scaleX = scale, scaleY = scale)
                                .background(BrandColors.Primary, shape = CircleShape)
                        )
                        Text(
                            text = "本周期可用剩余",
                            color = BrandColors.Primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    val formatted = remember(remainingSeconds) {
                        val min = remainingSeconds / 60
                        val sec = remainingSeconds % 60
                        String.format("%02d:%02d", min, sec)
                    }
                    Text(
                        text = formatted,
                        color = BrandColors.Primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TriggerAppPickerContent(
    apps: List<AllowedApp>,
    isLoading: Boolean,
    loadFailed: Boolean,
    selectedPackages: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onToggle: (AllowedApp) -> Unit
) {
    val filtered = remember(apps, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) apps else apps.filter { app ->
            app.label.contains(normalized, ignoreCase = true) ||
                app.packageName.contains(normalized, ignoreCase = true)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().height(480.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "已选 ${selectedPackages.size} 个。打开任意一个都会触发；任务已开启时会忽略。",
            color = BrandColors.TextSecondary,
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("搜索名称或包名") },
            singleLine = true
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                loadFailed -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("读取 App 列表失败")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onRetry) { Text("重新读取") }
                }
                filtered.isEmpty() -> Text(
                    if (apps.isEmpty()) "没有可选的普通 App" else "没有匹配的 App",
                    color = BrandColors.TextSecondary,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = AllowedApp::packageName) { app ->
                        val selected = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app) }
                                .padding(horizontal = 4.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TriggerAppIcon(
                                icon = app.icon,
                                label = app.label,
                                modifier = Modifier.padding(end = 12.dp).size(40.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    app.packageName,
                                    color = BrandColors.TextTertiary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                if (selected) "已选" else "选择",
                                color = if (selected) BrandColors.AppAccent else BrandColors.TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerAppIcon(
    icon: Drawable?,
    label: String,
    modifier: Modifier = Modifier
) {
    if (icon == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Apps, contentDescription = label, tint = BrandColors.TextTertiary)
        }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = label
            }
        },
        update = { view ->
            view.contentDescription = label
            view.setImageDrawable(icon)
        }
    )
}

@Composable
internal fun planNavigationModifier(
    isTarget: Boolean,
    bringIntoViewRequester: BringIntoViewRequester
): Modifier = if (isTarget) {
    Modifier
        .bringIntoViewRequester(bringIntoViewRequester)
        .border(2.dp, BrandColors.Primary, RoundedCornerShape(20.dp))
} else {
    Modifier
}

@Composable
internal fun PlanDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = BrandColors.TextTertiary, fontSize = 13.sp)
        Text(
            value,
            color = BrandColors.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(start = 14.dp)
        )
    }
}

@Composable
private fun GlobalPlanEditorDialog(
    draft: GlobalPlanEditorDraft,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    exactAlarmNeedsAction: Boolean,
    onActivationChange: (Boolean, Long?) -> Unit,
    state: SupervisionPlansUiState,
    onPrepareAppTriggerSelection: () -> Unit,
    onToggleTriggerApp: (AllowedApp) -> Unit,
    onRetryApps: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onDeleteRequest: (() -> Unit)?,
    onToggleDay: (DayOfWeek) -> Unit,
    onRangeStartChange: (Long, Int) -> Unit,
    onRangeEndChange: (Long, Int) -> Unit,
    onAddRange: () -> Unit,
    onRemoveRange: (Long) -> Unit,
    onUsageChange: (Int) -> Unit,
    onLockChange: (Int) -> Unit,
    onSave: () -> Unit
) {
    var timePickerRequest by remember(draft.planId) {
        mutableStateOf<TimeRangePickerRequest?>(null)
    }
    var showReservationPicker by remember(draft.planId) { mutableStateOf(false) }
    var showTriggerAppPicker by remember(draft.planId) { mutableStateOf(false) }
    var triggerAppQuery by remember(draft.planId) { mutableStateOf("") }
    val activeTimePicker = timePickerRequest
    val isSubpage = activeTimePicker != null || showReservationPicker || showTriggerAppPicker
    PlanEditorBottomSheet(
        title = when {
            activeTimePicker != null -> activeTimePicker.boundary.pickerTitle
            showReservationPicker -> "预约开启时间"
            showTriggerAppPicker -> "选择触发 App"
            draft.planId == null -> "新建定时监督任务"
            else -> "编辑定时监督任务"
        },
        isSaving = isSaving,
        onDismiss = onDismiss,
        onSave = onSave,
        onBack = if (isSubpage) {
            {
                timePickerRequest = null
                showReservationPicker = false
                showTriggerAppPicker = false
            }
        } else {
            null
        },
        onDeleteRequest = onDeleteRequest.takeUnless { isSubpage },
        saveLabel = "保存任务"
    ) {
        when {
            activeTimePicker != null -> {
                PlanTimeRangePickerPage(
                    request = activeTimePicker,
                    onCancel = { timePickerRequest = null },
                    onSelected = { request, minute ->
                        when (request.boundary) {
                            TimeRangeBoundary.START -> onRangeStartChange(request.rangeId, minute)
                            TimeRangeBoundary.END -> onRangeEndChange(request.rangeId, minute)
                        }
                        timePickerRequest = null
                    }
                )
            }

            showReservationPicker -> {
                val initialEpochMillis = draft.scheduledEnableAtEpochMillis
                    ?: defaultScheduledEnableDateTime(
                        System.currentTimeMillis(),
                        draft.zoneId
                    ).atZone(draft.zoneId).toInstant().toEpochMilli()
                PlanReservationPickerPage(
                    initialEpochMillis = initialEpochMillis,
                    zoneId = draft.zoneId,
                    onCancel = { showReservationPicker = false },
                    onSelected = { selectedEpochMillis ->
                        onActivationChange(false, selectedEpochMillis)
                        showReservationPicker = false
                    }
                )
            }

            showTriggerAppPicker -> {
                TriggerAppPickerContent(
                    apps = state.supervisableApps,
                    isLoading = state.isLoadingSupervisableApps,
                    loadFailed = state.supervisableAppsLoadFailed,
                    selectedPackages = draft.triggerAppPackageNames,
                    query = triggerAppQuery,
                    onQueryChange = { triggerAppQuery = it.take(80) },
                    onRetry = onRetryApps,
                    onToggle = onToggleTriggerApp
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value ->
                        if (value.length <= MAX_GLOBAL_PLAN_NAME_LENGTH) onNameChange(value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务名称") },
                    supportingText = { Text("${draft.name.length}/$MAX_GLOBAL_PLAN_NAME_LENGTH") },
                    singleLine = true,
                    enabled = !isSaving
                )
                PlanActivationSection(
                    enabled = draft.enabled,
                    scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
                    exactAlarmNeedsAction = exactAlarmNeedsAction,
                    onActivationChange = onActivationChange,
                    onEditReservation = { showReservationPicker = true },
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    zoneId = draft.zoneId,
                    triggerAppPackageNames = draft.triggerAppPackageNames,
                    allowAppTrigger = true,
                    onEditTriggerApps = {
                        onPrepareAppTriggerSelection()
                        showTriggerAppPicker = true
                    }
                )
                Text("每周执行日", fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in draft.activeDays,
                            onClick = { onToggleDay(day) },
                            enabled = !isSaving,
                            label = { Text(shortDayName(day)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandColors.Primary.copy(alpha = 0.25f),
                                selectedLabelColor = BrandColors.TextPrimary
                            )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("执行时间范围", fontWeight = FontWeight.Bold)
                        Text("仅在这些时段内运行监督任务", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onAddRange,
                        enabled = !isSaving && draft.ranges.size < MAX_GLOBAL_PLAN_RANGES
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加")
                    }
                }
                draft.ranges.forEachIndexed { index, range ->
                    TimeRangeEditorRow(
                        index = index,
                        range = range,
                        canRemove = draft.ranges.size > 1,
                        enabled = !isSaving,
                        onStartClick = {
                            timePickerRequest = TimeRangePickerRequest(
                                rangeId = range.id,
                                initialMinute = range.startMinute,
                                boundary = TimeRangeBoundary.START
                            )
                        },
                        onEndClick = {
                            timePickerRequest = TimeRangePickerRequest(
                                rangeId = range.id,
                                initialMinute = range.endMinuteExclusive,
                                boundary = TimeRangeBoundary.END
                            )
                        },
                        onRemove = { onRemoveRange(range.id) }
                    )
                }
                Text(
                    "监督循环",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                DurationSelector(
                    title = "可用时长",
                    value = draft.usageMinutes,
                    minValue = 1,
                    maxValue = 180,
                    step = 1,
                    presets = listOf(5, 10, 30, 45, 60, 90),
                    accentColor = BrandColors.Primary,
                    onValueChange = onUsageChange
                )
                DurationSelector(
                    title = "锁定时长",
                    value = draft.lockMinutes,
                    minValue = 1,
                    maxValue = 60,
                    step = 1,
                    presets = listOf(1, 5, 10, 15, 30, 60),
                    accentColor = BrandColors.Danger,
                    onValueChange = onLockChange
                )
                Text(
                    "示例：08:00–12:00 表示任务只在早上 8 点至中午 12 点执行。",
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun TimeRangeEditorRow(
    index: Int,
    range: TimeRangeDraft,
    canRemove: Boolean,
    enabled: Boolean,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceRaised),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("时段 ${index + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = enabled && canRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "删除时段 ${index + 1}")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onStartClick,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(formatMinute(range.startMinute)) }
                Text("至")
                OutlinedButton(
                    onClick = onEndClick,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(formatMinute(range.endMinuteExclusive)) }
            }
            if (range.endMinuteExclusive <= range.startMinute) {
                Text("结束时间位于次日", color = BrandColors.Warning, fontSize = 12.sp)
            }
        }
    }
}

internal fun formatActiveDays(days: Set<DayOfWeek>): String = when (days) {
    DayOfWeek.entries.toSet() -> "每天"
    setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    ) -> "工作日"
    setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "周末"
    else -> DayOfWeek.entries.filter(days::contains).joinToString("、", transform = ::shortDayName)
}

internal fun formatTimeRanges(ranges: List<com.example.controlfree.supervision.DailyTimeRange>): String =
    ranges.joinToString("；") { range ->
        val endPrefix = if (range.endMinuteExclusive <= range.startMinute) "次日 " else ""
        "${formatMinute(range.startMinute)}–$endPrefix${formatMinute(range.endMinuteExclusive)}"
    }

internal fun formatMinute(minute: Int): String {
    require(minute in 0..24 * 60)
    if (minute == 24 * 60) return "24:00"
    return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
}

internal fun shortDayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}
