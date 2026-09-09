package com.example.controlfree.ui.main

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.MonitorService
import androidx.core.net.toUri
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.runtime.ScheduledOwnerReadResult
import com.example.controlfree.supervision.runtime.SupervisionAlarmScheduler
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.widget.ControlFreeWidgetType
import com.example.controlfree.widget.WidgetPlanNavigationRequest
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path

@Composable
internal fun FocusPlansSection(
    viewModel: SupervisionPlansViewModel,
    runtimeReadiness: RuntimeReadiness,
    onCheckRuntimeReadiness: () -> RuntimeReadiness,
    onFixRequirement: (RuntimeRequirementKey) -> Unit,
    onRefreshReadiness: () -> Unit,
    navigationRequest: WidgetPlanNavigationRequest? = null,
    onNavigationConsumed: (WidgetPlanNavigationRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingEnable by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingCancelReservation by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingEnabledEditorSave by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val navigationBringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(viewModel, context.applicationContext) {
        viewModel.initialize(context.applicationContext)
    }
    LaunchedEffect(pendingEnabledEditorSave, state.focusEditorDraft) {
        if (pendingEnabledEditorSave && state.focusEditorDraft == null) {
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
        while (true) {
            delay(1_000L)
            now = Instant.now()
        }
    }

    val focusPlans = state.plans.filter { plan -> plan.type == SupervisionPlanType.FOCUS }
    LaunchedEffect(navigationRequest?.revision, state.isLoading, state.plans) {
        val request = navigationRequest ?: return@LaunchedEffect
        if (state.isLoading) return@LaunchedEffect
        val target = focusPlans.firstOrNull { plan -> plan.id == request.planId }
        if (target == null) {
            Toast.makeText(context, "该组件绑定的专注任务已不存在", Toast.LENGTH_SHORT).show()
            onNavigationConsumed(request)
            return@LaunchedEffect
        }
        navigationBringIntoViewRequester.bringIntoView()
        delay(900L)
        onNavigationConsumed(request)
    }
    val runtimePreferences = remember { PreferenceManager(context.applicationContext) }
    val runtimeOwner = (
        runtimePreferences.inspectScheduledMonitorOwner() as? ScheduledOwnerReadResult.Available
        )?.owner
    val monitorActive = runtimePreferences.isMonitorActive()
    val monitorServiceRunning = MonitorService.isRunning
    val activeSessionMode = runtimePreferences.getMonitorSessionMode()
    val hasEnabledFocusPlan = focusPlans.any { plan ->
        plan.enabled || plan.scheduledEnableAtEpochMillis != null
    }
    val exactAlarmRefreshBucket = now.epochSecond / 30L
    val exactAlarmReady = remember(focusPlans, exactAlarmRefreshBucket) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "定时专注任务",
                    color = BrandColors.TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "在指定时间范围内自动执行锁定与玩机循环",
                    color = BrandColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
            AssistChip(onClick = {}, enabled = false, label = { Text("${focusPlans.size} 个") })
        }

        if (hasEnabledFocusPlan && !exactAlarmReady) {
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
            state.loadProblem != null && focusPlans.isEmpty() -> PlansErrorCard(
                problem = state.loadProblem,
                onRetry = viewModel::retryLoading
            )
            focusPlans.isEmpty() -> EmptyFocusPlansCard(
                onCreate = { viewModel.openNewFocusPlan(ZoneId.systemDefault()) }
            )
            else -> {
                focusPlans.forEach { plan ->
                    key(plan.id) {
                        val scheduledWindowActive =
                            plan.enabled && (
                                plan.oneTimeFocusWindow?.contains(now.toEpochMilli())
                                    ?: plan.schedule.isActiveAt(now, ZoneId.systemDefault())
                                )
                        val runtimeActive = monitorActive && monitorServiceRunning &&
                            activeSessionMode == MonitorSessionMode.FOCUS &&
                            runtimeOwner?.planId == plan.id &&
                            runtimeOwner.planUpdatedAtEpochMillis == plan.updatedAtEpochMillis
                        FocusPlanCard(
                            plan = plan,
                            modifier = planNavigationModifier(
                                isTarget = navigationRequest?.planId == plan.id,
                                bringIntoViewRequester = navigationBringIntoViewRequester
                            ),
                            nowEpochMillis = now.toEpochMilli(),
                            scheduledWindowActive = scheduledWindowActive,
                            runtimeActive = runtimeActive,
                            blockedByOtherTask =
                                scheduledWindowActive && monitorActive && !runtimeActive,
                            monitorServiceRunning = monitorServiceRunning,
                            runtimePrerequisitesReady = runtimeReadiness.canStart,
                            busy = plan.id in state.busyPlanIds,
                            onEnabledChange = { enabled ->
                                val oneTimeWindow = plan.oneTimeFocusWindow
                                if (oneTimeWindow != null && !enabled &&
                                    plan.scheduledEnableAtEpochMillis != null
                                ) {
                                    pendingCancelReservation = plan
                                } else if (enabled) {
                                    val currentReadiness = onCheckRuntimeReadiness()
                                    if (currentReadiness.canStart) {
                                        if (oneTimeWindow != null &&
                                            oneTimeWindow.startEpochMillis > now.toEpochMilli()
                                        ) {
                                            viewModel.scheduleEnable(
                                                plan,
                                                oneTimeWindow.startEpochMillis
                                            )
                                        } else {
                                            viewModel.setEnabled(plan, true)
                                        }
                                    } else {
                                        pendingEnable = plan
                                        onRefreshReadiness()
                                    }
                                } else {
                                    viewModel.setEnabled(plan, false)
                                }
                            },
                            onEdit = { viewModel.openFocusPlan(plan) },
                            onDelete = { pendingDelete = plan }
                        )
                    }
                }
                if (state.loadProblem != null) {
                    PlansErrorCard(state.loadProblem, viewModel::retryLoading)
                }
                OutlinedButton(
                    onClick = { viewModel.openNewFocusPlan(ZoneId.systemDefault()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建专注任务")
                }
            }
        }
    }

    state.focusEditorDraft?.takeUnless { pendingEnabledEditorSave }?.let { draft ->
        FocusPlanEditorDialog(
            draft = draft,
            isSaving = state.isSaving,
            onDismiss = viewModel::closeFocusEditor,
            onNameChange = viewModel::updateFocusName,
            exactAlarmNeedsAction = runtimeReadiness.requirements.any { requirement ->
                requirement.key == RuntimeRequirementKey.EXACT_ALARM && requirement.needsAction
            },
            onActivationChange = viewModel::updateFocusActivation,
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
            onToggleDay = viewModel::toggleFocusDay,
            onRangeStartChange = viewModel::updateFocusRangeStart,
            onRangeEndChange = viewModel::updateFocusRangeEnd,
            onAddRange = viewModel::addFocusRange,
            onRemoveRange = viewModel::removeFocusRange,
            onLockChange = viewModel::updateFocusLockMinutes,
            onPlayChange = viewModel::updateFocusPlayMinutes,
            onSave = {
                if (draft.enabled || draft.scheduledEnableAtEpochMillis != null) {
                    val currentReadiness = onCheckRuntimeReadiness()
                    if (currentReadiness.canStart) {
                        viewModel.saveFocusEditor(ZoneId.systemDefault())
                    } else {
                        pendingEnabledEditorSave = true
                        onRefreshReadiness()
                    }
                } else {
                    viewModel.saveFocusEditor(ZoneId.systemDefault())
                }
            }
        )
    }

    pendingDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("删除专注任务？") },
            text = {
                Text(
                    "“${plan.name}”及其执行时间范围将被永久删除；若有关联待办，" +
                        "其排程时间和预计专注时长也会清除。"
                )
            },
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

    pendingCancelReservation?.let { selectedPlan ->
        val currentPlan = state.plans.firstOrNull { it.id == selectedPlan.id } ?: selectedPlan
        AlertDialog(
            onDismissRequest = { pendingCancelReservation = null },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("取消一次性专注？") },
            text = {
                Text(
                    "取消“${currentPlan.name}”后将解除专注锁预约，并清除关联待办的排程时间。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCancelReservation = null
                        val latestPlan = state.plans.firstOrNull { it.id == selectedPlan.id }
                        if (
                            latestPlan == null || latestPlan.enabled ||
                            latestPlan.scheduledEnableAtEpochMillis == null
                        ) {
                            Toast.makeText(
                                context,
                                "预约状态已变化，请确认当前计划状态",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            viewModel.cancelScheduledEnable(latestPlan)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Danger)
                ) { Text("取消预约") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancelReservation = null }) { Text("保留") }
            }
        )
    }


    pendingEnable?.let { plan ->
        RuntimeReadinessDialog(
            readiness = runtimeReadiness,
            title = "启用定时专注任务",
            description =
                "启用“${plan.name}”后，将在设定时段自动进入锁定与玩机循环。请先确认关键运行权限。",
            confirmLabel = "确认启用",
            dismissLabel = "暂不启用",
            onFix = onFixRequirement,
            onRefresh = onRefreshReadiness,
            onDismiss = { pendingEnable = null },
            onConfirm = {
                if (runtimeReadiness.canStart) {
                    pendingEnable = null
                    val oneTimeStart = plan.oneTimeFocusWindow?.startEpochMillis
                    if (oneTimeStart != null && oneTimeStart > now.toEpochMilli()) {
                        viewModel.scheduleEnable(plan, oneTimeStart)
                    } else {
                        viewModel.setEnabled(plan, true)
                    }
                }
            }
        )
    }

    if (pendingEnabledEditorSave) {
        state.focusEditorDraft?.let { draft ->
            val isScheduledSave = !draft.enabled && draft.scheduledEnableAtEpochMillis != null
            RuntimeReadinessDialog(
                readiness = runtimeReadiness,
                title = when {
                    isScheduledSave && draft.planId == null -> "创建定时专注预约"
                    isScheduledSave -> "保存定时专注预约"
                    draft.planId == null -> "创建并启用定时专注任务"
                    else -> "保存并启用定时专注任务"
                },
                description = if (isScheduledSave) {
                    "预约“${draft.name.ifBlank { "未命名专注任务" }}”后，任务将在所选时间自动开启，并按设定时段进入锁定与玩机循环。请先确认关键运行权限。"
                } else {
                    "启用“${draft.name.ifBlank { "未命名专注任务" }}”后，将在设定时段自动进入锁定与玩机循环。请先确认关键运行权限。"
                },
                confirmLabel = if (isScheduledSave) "确认保存预约" else "确认保存并启用",
                dismissLabel = "返回编辑",
                onFix = onFixRequirement,
                onRefresh = onRefreshReadiness,
                onDismiss = { pendingEnabledEditorSave = false },
                onConfirm = {
                    if (runtimeReadiness.canStart) {
                        pendingEnabledEditorSave = false
                        viewModel.saveFocusEditor(ZoneId.systemDefault())
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyFocusPlansCard(onCreate: () -> Unit) {
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
                text = "还没有定时专注任务",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "例如：每天 08:00–12:00 自动执行锁定 15 分钟、可用 3 分钟的循环。",
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
                Text("新建专注任务", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FocusPlanCard(
    plan: SupervisionPlan,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long,
    scheduledWindowActive: Boolean,
    runtimeActive: Boolean,
    blockedByOtherTask: Boolean,
    monitorServiceRunning: Boolean,
    runtimePrerequisitesReady: Boolean,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val policy = plan.policy as FocusCyclePolicy
    val oneTimeWindow = plan.oneTimeFocusWindow
    val isOneTime = oneTimeWindow != null
    val isOneTimeExpired = oneTimeWindow?.endEpochMillis?.let { it <= nowEpochMillis } == true
    val isOneTimeTerminal = isOneTime && !plan.enabled &&
        plan.scheduledEnableAtEpochMillis == null
    val statusText = when {
        isOneTimeExpired -> "已结束"
        isOneTimeTerminal -> "已取消，请从待办重新安排"
        !plan.enabled && plan.scheduledEnableAtEpochMillis != null ->
            "已预约 ${formatScheduledEnableAt(plan.scheduledEnableAtEpochMillis)} 开启"
        !plan.enabled -> "已停用"
        scheduledWindowActive && !runtimePrerequisitesReady -> "缺少运行权限"
        runtimeActive -> "执行中"
        blockedByOtherTask -> "等待当前任务结束"
        scheduledWindowActive && monitorServiceRunning -> "正在启动"
        scheduledWindowActive -> "等待系统启动"
        else -> "等待生效时段"
    }
    val statusColor = when {
        isOneTimeExpired -> BrandColors.TextTertiary
        scheduledWindowActive && !runtimePrerequisitesReady -> BrandColors.Danger
        runtimeActive -> BrandColors.Primary
        plan.enabled -> BrandColors.Warning
        plan.scheduledEnableAtEpochMillis != null -> BrandColors.AppAccent
        else -> BrandColors.TextTertiary
    }
    val isOff = !plan.enabled

    PlanActionCard(
        planId = plan.id,
        modifier = modifier.graphicsLayer(alpha = if (isOff) 0.92f else 1f),
        busy = busy,
        onEdit = onEdit,
        onDelete = onDelete,
        showEdit = !isOneTime,
        widgetType = ControlFreeWidgetType.FOCUS
    ) {
        Column(
            modifier = Modifier
                .background(if (isOff) Color(0xFFF9FAF9) else Color.White)
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            // 头部：图标 + 名称 + 状态点 + 开关
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
                            imageVector = if (runtimeActive) Icons.Default.PlayArrow else Icons.Default.Schedule,
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
                                val infiniteTransition = rememberInfiniteTransition(label = "focusPulseDot")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "focusPulseDot"
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
                    checked = plan.enabled || plan.scheduledEnableAtEpochMillis != null,
                    onCheckedChange = onEnabledChange,
                    busy = busy,
                    enabled = !isOneTimeExpired && !isOneTimeTerminal
                )
            }
            // 执行日 & 生效时段
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (oneTimeWindow != null) {
                    Column {
                        Text(
                            text = "模式",
                            color = BrandColors.TextTertiary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "一次性",
                            color = BrandColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "起止时间",
                            color = BrandColors.TextTertiary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = formatOneTimeFocusWindow(oneTimeWindow, ZoneId.systemDefault()),
                            color = BrandColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                } else {
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
            }
            // 专注循环比例条
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
                        text = "专注循环",
                        color = BrandColors.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "锁定 → 可用 循环",
                        color = BrandColors.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                val lockWeight = policy.lockDuration.toMinutes().toFloat()
                val playWeight = policy.playDuration.toMinutes().toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEEF1EF))
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
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
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(playWeight)
                                .background(
                                    if (plan.enabled) Brush.horizontalGradient(
                                        listOf(BrandColors.Primary, Color(0xFF16B07E))
                                    ) else SolidColor(Color(0xFFC2CDC7))
                                )
                        )
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
                            text = "${policy.playDuration.toMinutes()} 分钟",
                            color = BrandColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

internal fun formatOneTimeFocusWindow(
    window: OneTimeFocusWindow,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss", locale)
    val start = Instant.ofEpochMilli(window.startEpochMillis).atZone(zoneId).format(formatter)
    val end = Instant.ofEpochMilli(window.endEpochMillis).atZone(zoneId).format(formatter)
    return "$start - $end"
}

@Composable
private fun FocusPlanEditorDialog(
    draft: FocusPlanEditorDraft,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    exactAlarmNeedsAction: Boolean,
    onActivationChange: (Boolean, Long?) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onDeleteRequest: (() -> Unit)?,
    onToggleDay: (DayOfWeek) -> Unit,
    onRangeStartChange: (Long, Int) -> Unit,
    onRangeEndChange: (Long, Int) -> Unit,
    onAddRange: () -> Unit,
    onRemoveRange: (Long) -> Unit,
    onLockChange: (Int) -> Unit,
    onPlayChange: (Int) -> Unit,
    onSave: () -> Unit
) {
    val oneTimeWindow = draft.oneTimeFocusWindow
    var timePickerRequest by remember(draft.planId) {
        mutableStateOf<TimeRangePickerRequest?>(null)
    }
    var showReservationPicker by remember(draft.planId) { mutableStateOf(false) }
    val activeTimePicker = timePickerRequest
    val isSubpage = activeTimePicker != null || showReservationPicker
    PlanEditorBottomSheet(
        title = when {
            activeTimePicker != null -> activeTimePicker.boundary.pickerTitle
            showReservationPicker -> "预约开启时间"
            draft.planId == null -> "新建专注任务"
            else -> "编辑专注任务"
        },
        isSaving = isSaving,
        onDismiss = onDismiss,
        onSave = onSave,
        onBack = if (isSubpage) {
            {
                timePickerRequest = null
                showReservationPicker = false
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
                        if (value.length <= MAX_FOCUS_PLAN_NAME_LENGTH) onNameChange(value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务名称") },
                    supportingText = {
                        Text("${draft.name.length}/$MAX_FOCUS_PLAN_NAME_LENGTH")
                    },
                    singleLine = true,
                    enabled = !isSaving
                )
                if (oneTimeWindow == null) {
                    PlanActivationSection(
                        enabled = draft.enabled,
                        scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
                        exactAlarmNeedsAction = exactAlarmNeedsAction,
                        onActivationChange = onActivationChange,
                        onEditReservation = { showReservationPicker = true },
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        zoneId = draft.zoneId
                    )
                }
                if (oneTimeWindow != null) {
                    Text("一次性时间", fontWeight = FontWeight.Bold)
                    PlanDetailLine(
                        "起止时间",
                        formatOneTimeFocusWindow(oneTimeWindow, ZoneId.systemDefault())
                    )
                } else {
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
                            Text("时段开始时锁定，结束时停止任务并解锁", fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = onAddRange,
                            enabled = !isSaving && draft.ranges.size < MAX_FOCUS_PLAN_RANGES
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
                }
                Text("专注循环", fontWeight = FontWeight.Bold)
                DurationSelector(
                    title = "锁定时长",
                    value = draft.lockMinutes,
                    minValue = 1,
                    maxValue = 180,
                    step = 1,
                    presets = listOf(5, 10, 30, 45, 60, 90),
                    accentColor = BrandColors.Danger,
                    onValueChange = onLockChange
                )
                DurationSelector(
                    title = "可用时长",
                    value = draft.playMinutes,
                    minValue = 1,
                    maxValue = 60,
                    step = 1,
                    presets = listOf(1, 5, 10, 15, 30, 60),
                    accentColor = BrandColors.Primary,
                    onValueChange = onPlayChange
                )
                Text(
                    "任务每次进入生效时段都会从完整锁定阶段开始。",
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
