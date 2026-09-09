package com.example.controlfree.ui.main

import android.widget.Toast
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.controlfree.AppSupervisionService
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.app.AppSupervisionPhase
import com.example.controlfree.supervision.app.AppSupervisionRuntimeStore
import com.example.controlfree.supervision.app.AppSupervisionSnapshotReadResult
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.widget.ControlFreeWidgetType
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

private const val MAX_APP_PICKER_QUERY_LENGTH = 80
private const val MAX_APP_PLAN_EDITOR_CONTENT_HEIGHT_DP = 560
private const val MIN_APP_PLAN_EDITOR_CONTENT_HEIGHT_DP = 160
private const val APP_PLAN_EDITOR_CHROME_HEIGHT_DP = 180

@Composable
internal fun AppSupervisionPlansSection(
    state: SupervisionPlansUiState,
    viewModel: SupervisionPlansViewModel,
    now: Instant,
    runtimeReadiness: RuntimeReadiness,
    onCheckRuntimeReadiness: () -> RuntimeReadiness,
    onFixRequirement: (RuntimeRequirementKey) -> Unit,
    onRefreshReadiness: () -> Unit,
    highlightedPlanId: String? = null,
    navigationBringIntoViewRequester: BringIntoViewRequester? = null
) {
    val context = LocalContext.current
    val appPlans = state.plans.filter { it.type == SupervisionPlanType.APP }
    val appByPackage = remember(
        state.supervisableApps,
        state.appPlanTargetMetadata.appsByPackage
    ) {
        buildMap {
            state.supervisableApps.forEach { app -> put(app.packageName, app) }
            putAll(state.appPlanTargetMetadata.appsByPackage)
        }
    }
    val runtimeStore = remember { AppSupervisionRuntimeStore(context.applicationContext) }
    val runtimeSnapshot = (
        runtimeStore.read() as? AppSupervisionSnapshotReadResult.Available
        )?.snapshot
    var pendingDelete by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingEnable by remember { mutableStateOf<SupervisionPlan?>(null) }
    var pendingEnabledEditorSave by remember { mutableStateOf(false) }
    val appServiceRunning = AppSupervisionService.isRunning

    LaunchedEffect(pendingEnabledEditorSave, state.appEditorDraft) {
        if (pendingEnabledEditorSave && state.appEditorDraft == null) {
            pendingEnabledEditorSave = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SupervisionSectionHeader(
            title = "App 独立监督",
            description = "每个 App 独立累计额度，休息时不影响其他 App",
            count = appPlans.size
        )

        when {
            state.isLoading -> AppPlansLoadingCard()
            state.loadProblem != null && appPlans.isEmpty() -> Card(
                colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "计划数据当前不可用，修复或重新读取后才能管理 App 独立监督。",
                    color = BrandColors.TextSecondary,
                    modifier = Modifier.padding(18.dp)
                )
            }
            appPlans.isEmpty() -> EmptyAppPlansCard(
                onCreate = { viewModel.openNewAppPlan(ZoneId.systemDefault()) }
            )
            else -> {
                appPlans.forEach { plan ->
                    key(plan.id) {
                        val policy = plan.policy as AppRulePolicy
                        val app = appByPackage[policy.packageName]
                        val windowActive = plan.enabled &&
                            plan.schedule.isActiveAt(now, ZoneId.systemDefault())
                        val runtimeState = runtimeSnapshot?.states?.firstOrNull { runtime ->
                            runtime.planId == plan.id &&
                                runtime.planUpdatedAtEpochMillis == plan.updatedAtEpochMillis
                        }
                        val runtimeRuleActive = appServiceRunning &&
                            runtimeSnapshot?.rules?.any { runtimeRule ->
                                runtimeRule.planId == plan.id &&
                                    runtimeRule.planUpdatedAtEpochMillis ==
                                    plan.updatedAtEpochMillis &&
                                    now.toEpochMilli() < runtimeRule.occurrenceEndEpochMillis
                            } == true
                        AppPlanCard(
                            plan = plan,
                            modifier = navigationBringIntoViewRequester?.let { requester ->
                                planNavigationModifier(
                                    isTarget = highlightedPlanId == plan.id,
                                    bringIntoViewRequester = requester
                                )
                            } ?: Modifier,
                            app = app,
                            windowActive = windowActive,
                            runtimeRuleActive = runtimeRuleActive,
                            runtimePhase = runtimeState?.phase.takeIf { appServiceRunning },
                            remainingAllowanceMillis = runtimeState?.remainingAllowanceMillis,
                            remainingRestMillis = runtimeState?.restUntilEpochMillis
                                ?.minus(now.toEpochMilli())
                                ?.coerceAtLeast(0L),
                            serviceRunning = appServiceRunning,
                            runtimePrerequisitesReady = runtimeReadiness.canStart,
                            busy = plan.id in state.busyPlanIds,
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
                            onEdit = { viewModel.openAppPlan(plan) },
                            onDelete = { pendingDelete = plan }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.openNewAppPlan(ZoneId.systemDefault()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("新建 App 独立监督")
                }
            }
        }
    }

    state.appEditorDraft?.takeUnless { pendingEnabledEditorSave }?.let { draft ->
        AppPlanEditorDialog(
            draft = draft,
            state = state,
            isSaving = state.isSaving,
            onDismiss = viewModel::closeAppEditor,
            onSelectApp = viewModel::selectSupervisableApp,
            onRetryApps = viewModel::retrySupervisableApps,
            onNameChange = viewModel::updateAppName,
            exactAlarmNeedsAction = runtimeReadiness.requirements.any { requirement ->
                requirement.key == RuntimeRequirementKey.EXACT_ALARM && requirement.needsAction
            },
            onActivationChange = viewModel::updateAppActivation,
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
            onToggleDay = viewModel::toggleAppDay,
            onRangeStartChange = viewModel::updateAppRangeStart,
            onRangeEndChange = viewModel::updateAppRangeEnd,
            onAddRange = viewModel::addAppRange,
            onRemoveRange = viewModel::removeAppRange,
            onDisabledRangeStartChange = viewModel::updateAppDisabledRangeStart,
            onDisabledRangeEndChange = viewModel::updateAppDisabledRangeEnd,
            onAddDisabledRange = viewModel::addAppDisabledRange,
            onRemoveDisabledRange = viewModel::removeAppDisabledRange,
            onAllowanceChange = viewModel::updateAppAllowanceMinutes,
            onRestChange = viewModel::updateAppRestMinutes,
            onDailyUsageLimitChange = viewModel::updateAppDailyUsageLimitMinutes,
            onSave = {
                if (draft.enabled || draft.scheduledEnableAtEpochMillis != null) {
                    val currentReadiness = onCheckRuntimeReadiness()
                    if (currentReadiness.canStart) {
                        viewModel.saveAppEditor(ZoneId.systemDefault())
                    } else {
                        pendingEnabledEditorSave = true
                        onRefreshReadiness()
                    }
                } else {
                    viewModel.saveAppEditor(ZoneId.systemDefault())
                }
            }
        )
    }

    pendingDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("删除 App 监督计划？") },
            text = { Text("“${plan.name}”的独立额度、休息状态和执行时段将被删除。") },
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
            title = "启用 App 独立监督",
            description =
                "启用“${plan.name}”后，将在设定时段累计目标 App 的可用额度。请先处理缺失的关键运行权限。",
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
        state.appEditorDraft?.let { draft ->
            val isScheduledSave = !draft.enabled && draft.scheduledEnableAtEpochMillis != null
            RuntimeReadinessDialog(
                readiness = runtimeReadiness,
                title = when {
                    isScheduledSave && draft.planId == null -> "创建 App 独立监督预约"
                    isScheduledSave -> "保存 App 独立监督预约"
                    draft.planId == null -> "创建并启用 App 独立监督"
                    else -> "保存并启用 App 独立监督"
                },
                description = if (isScheduledSave) {
                    "预约“${draft.name.ifBlank { "未命名 App 监督" }}”后，任务将在所选时间自动开启，并按设定时段累计目标 App 的可用额度。请先处理缺失的关键运行权限。"
                } else {
                    "启用“${draft.name.ifBlank { "未命名 App 监督" }}”后，将在设定时段累计目标 App 的可用额度。请先处理缺失的关键运行权限。"
                },
                confirmLabel = if (isScheduledSave) "确认保存预约" else "确认保存并启用",
                dismissLabel = "返回编辑",
                onFix = onFixRequirement,
                onRefresh = onRefreshReadiness,
                onDismiss = { pendingEnabledEditorSave = false },
                onConfirm = {
                    if (runtimeReadiness.canStart) {
                        pendingEnabledEditorSave = false
                        viewModel.saveAppEditor(ZoneId.systemDefault())
                    }
                }
            )
        }
    }
}

@Composable
private fun AppPlansLoadingCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("正在读取 App 监督计划…", color = BrandColors.TextSecondary)
        }
    }
}

@Composable
private fun EmptyAppPlansCard(onCreate: () -> Unit) {
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
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = BrandColors.TextTertiary.copy(alpha = 0.7f)
            )
            Text(
                text = "还没有 App 独立监督",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "例如：视频 App 在 08:00–12:00 内可用 30 分钟，随后休息 10 分钟。",
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
                Text("选择 App 并创建", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AppPlanCard(
    plan: SupervisionPlan,
    modifier: Modifier = Modifier,
    app: AllowedApp?,
    windowActive: Boolean,
    runtimeRuleActive: Boolean,
    runtimePhase: AppSupervisionPhase?,
    remainingAllowanceMillis: Long?,
    remainingRestMillis: Long?,
    serviceRunning: Boolean,
    runtimePrerequisitesReady: Boolean,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val policy = plan.policy as AppRulePolicy
    val appLabel = app?.label ?: policy.packageName
    val listItemName = appPlanListItemName(plan.name, appLabel)
    val statusText = when {
        !plan.enabled && plan.scheduledEnableAtEpochMillis != null ->
            "已预约 ${formatScheduledEnableAt(plan.scheduledEnableAtEpochMillis)} 开启"
        !plan.enabled -> "已停用"
        windowActive && !runtimePrerequisitesReady -> "缺少运行权限"
        windowActive && runtimePhase == AppSupervisionPhase.REST ->
            "休息中 · 剩余 ${formatCompactDuration(remainingRestMillis ?: 0L)}"
        windowActive && runtimePhase == AppSupervisionPhase.ALLOWANCE ->
            "执行中 · 额度剩余 ${formatCompactDuration(remainingAllowanceMillis ?: 0L)}"
        windowActive && runtimeRuleActive -> "当前执行中"
        windowActive && serviceRunning -> "正在启动"
        windowActive -> "等待系统启动"
        else -> "等待生效时段"
    }
    val statusColor = when {
        windowActive && !runtimePrerequisitesReady -> BrandColors.Danger
        runtimePhase == AppSupervisionPhase.REST && windowActive -> BrandColors.Warning
        windowActive && plan.enabled -> BrandColors.Primary
        plan.enabled -> BrandColors.AppAccent
        plan.scheduledEnableAtEpochMillis != null -> BrandColors.AppAccent
        else -> BrandColors.TextTertiary
    }
    PlanActionCard(
        planId = plan.id,
        modifier = modifier,
        busy = busy,
        onEdit = onEdit,
        onDelete = onDelete,
        widgetType = ControlFreeWidgetType.SUPERVISION
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppDrawableIcon(
                    icon = app?.icon,
                    label = appLabel,
                    modifier = Modifier.padding(end = 12.dp).size(44.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        listItemName,
                        color = BrandColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (listItemName != appLabel) {
                        Text(appLabel, color = BrandColors.TextSecondary, fontSize = 12.sp)
                    }
                    Text(statusText, color = statusColor, fontSize = 12.sp)
                }
                StablePlanEnabledSwitch(
                    checked = plan.enabled,
                    onCheckedChange = onEnabledChange,
                    busy = busy
                )
            }
            HorizontalDivider(color = BrandColors.OutlineSoft)
            PlanDetailLine("执行日", formatActiveDays(plan.schedule.activeDays))
            PlanDetailLine("生效时段", formatTimeRanges(plan.schedule.ranges))
            PlanDetailLine(
                "独立循环",
                "额度 ${policy.usageAllowance.toMinutes()} 分钟 · 休息 ${policy.restDuration.toMinutes()} 分钟"
            )
            PlanDetailLine("每日上限", "${policy.dailyUsageLimit.toMinutes()} 分钟")
            PlanDetailLine(
                "禁用时段",
                if (policy.disabledRanges.isEmpty()) "未设置"
                else formatTimeRanges(policy.disabledRanges)
            )
        }
    }
}

@Composable
private fun AppPlanEditorDialog(
    draft: AppPlanEditorDraft,
    state: SupervisionPlansUiState,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSelectApp: (AllowedApp) -> Unit,
    onRetryApps: () -> Unit,
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
    onDisabledRangeStartChange: (Long, Int) -> Unit,
    onDisabledRangeEndChange: (Long, Int) -> Unit,
    onAddDisabledRange: () -> Unit,
    onRemoveDisabledRange: (Long) -> Unit,
    onAllowanceChange: (Int) -> Unit,
    onRestChange: (Int) -> Unit,
    onDailyUsageLimitChange: (Int) -> Unit,
    onSave: () -> Unit
) {
    val editorContentHeight = MAX_APP_PLAN_EDITOR_CONTENT_HEIGHT_DP.dp
    var showAppPicker by remember(draft.planId) { mutableStateOf(false) }
    var showReservationPicker by remember(draft.planId) { mutableStateOf(false) }
    var timePickerRequest by remember(draft.planId) {
        mutableStateOf<TimeRangePickerRequest?>(null)
    }
    var editingDisabledRange by remember(draft.planId) { mutableStateOf(false) }
    var appPickerQuery by remember(draft.planId) { mutableStateOf("") }
    val activeTimePicker = timePickerRequest
    val isSubpage = activeTimePicker != null || showAppPicker || showReservationPicker
    PlanEditorBottomSheet(
        title = when {
            activeTimePicker != null -> activeTimePicker.boundary.pickerTitle
            showAppPicker -> "选择要监督的 App"
            showReservationPicker -> "预约开启时间"
            draft.planId == null -> "新建 App 独立监督"
            else -> "编辑 App 独立监督"
        },
        isSaving = isSaving,
        onDismiss = onDismiss,
        onSave = onSave,
        onBack = if (isSubpage) {
            {
                timePickerRequest = null
                showAppPicker = false
                showReservationPicker = false
            }
        } else {
            null
        },
        onDeleteRequest = onDeleteRequest.takeUnless { isSubpage },
        saveLabel = "保存计划"
    ) {
        when {
            activeTimePicker != null -> {
                PlanTimeRangePickerPage(
                    request = activeTimePicker,
                    onCancel = { timePickerRequest = null },
                    onSelected = { request, minute ->
                        when (request.boundary) {
                            TimeRangeBoundary.START -> {
                                if (editingDisabledRange) {
                                    onDisabledRangeStartChange(request.rangeId, minute)
                                } else {
                                    onRangeStartChange(request.rangeId, minute)
                                }
                            }
                            TimeRangeBoundary.END -> {
                                if (editingDisabledRange) {
                                    onDisabledRangeEndChange(request.rangeId, minute)
                                } else {
                                    onRangeEndChange(request.rangeId, minute)
                                }
                            }
                        }
                        timePickerRequest = null
                        editingDisabledRange = false
                    }
                )
            }

            showAppPicker -> {
                SupervisableAppPickerContent(
                    apps = state.supervisableApps,
                    isLoading = state.isLoadingSupervisableApps,
                    loadFailed = state.supervisableAppsLoadFailed,
                    selectedPackage = draft.packageName,
                    query = appPickerQuery,
                    contentHeight = editorContentHeight,
                    onQueryChange = {
                        appPickerQuery = it.take(MAX_APP_PICKER_QUERY_LENGTH)
                    },
                    onRetry = onRetryApps,
                    onSelect = { app ->
                        onSelectApp(app)
                        showAppPicker = false
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
                        if (value.length <= MAX_APP_PLAN_NAME_LENGTH) onNameChange(value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("计划名称") },
                    supportingText = { Text("${draft.name.length}/$MAX_APP_PLAN_NAME_LENGTH") },
                    singleLine = true,
                    enabled = !isSaving
                )
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(58.dp)
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 5.dp))
                    Text(
                        if (draft.packageName.isBlank()) "选择要独立监督的 App"
                        else "${draft.packageLabel} · 更换 App",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (draft.packageName.isNotBlank()) {
                    Text(
                        draft.packageName,
                        color = BrandColors.TextTertiary,
                        fontSize = 11.sp
                    )
                }
                PlanActivationSection(
                    enabled = draft.enabled,
                    scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis,
                    exactAlarmNeedsAction = exactAlarmNeedsAction,
                    onActivationChange = onActivationChange,
                    onEditReservation = { showReservationPicker = true },
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    zoneId = draft.zoneId
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
                                selectedContainerColor = BrandColors.AppAccent.copy(alpha = 0.25f),
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
                        Text("只在几点到几点内累计额度", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onAddRange,
                        enabled = !isSaving && draft.ranges.size < MAX_APP_PLAN_RANGES
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
                            editingDisabledRange = false
                            timePickerRequest = TimeRangePickerRequest(
                                rangeId = range.id,
                                initialMinute = range.startMinute,
                                boundary = TimeRangeBoundary.START
                            )
                        },
                        onEndClick = {
                            editingDisabledRange = false
                            timePickerRequest = TimeRangePickerRequest(
                                rangeId = range.id,
                                initialMinute = range.endMinuteExclusive,
                                boundary = TimeRangeBoundary.END
                            )
                        },
                        onRemove = { onRemoveRange(range.id) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("禁用时段", fontWeight = FontWeight.Bold)
                        Text("命中后直接禁止目标 App，优先级最高", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onAddDisabledRange,
                        enabled = !isSaving &&
                            draft.disabledRanges.size < MAX_APP_DISABLED_RANGES
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加")
                    }
                }
                if (draft.disabledRanges.isEmpty()) {
                    Text(
                        "未设置禁用时段",
                        color = BrandColors.TextTertiary,
                        fontSize = 12.sp
                    )
                }
                draft.disabledRanges.forEachIndexed { index, range ->
                    TimeRangeEditorRow(
                        index = index,
                        range = range,
                        canRemove = true,
                        enabled = !isSaving,
                        onStartClick = {
                            editingDisabledRange = true
                            timePickerRequest = TimeRangePickerRequest(
                                rangeId = range.id,
                                initialMinute = range.startMinute,
                                boundary = TimeRangeBoundary.START
                            )
                        },
                        onEndClick = {
                            editingDisabledRange = true
                            timePickerRequest = TimeRangePickerRequest(
                                rangeId = range.id,
                                initialMinute = range.endMinuteExclusive,
                                boundary = TimeRangeBoundary.END
                            )
                        },
                        onRemove = { onRemoveDisabledRange(range.id) }
                    )
                }
                Text("每日累计使用", fontWeight = FontWeight.Bold)
                DurationSelector(
                    title = "每天可累计使用时长",
                    value = draft.dailyUsageLimitMinutes,
                    minValue = 1,
                    maxValue = 1_440,
                    step = 1,
                    presets = listOf(30, 60, 120, 180, 240, 480),
                    accentColor = BrandColors.Primary,
                    onValueChange = onDailyUsageLimitChange
                )
                Text("独立额度循环", fontWeight = FontWeight.Bold)
                DurationSelector(
                    title = "目标 App 可用额度",
                    value = draft.usageAllowanceMinutes,
                    minValue = 1,
                    maxValue = 240,
                    step = 1,
                    presets = listOf(5, 15, 30, 45, 60, 120),
                    accentColor = BrandColors.AppAccent,
                    onValueChange = onAllowanceChange
                )
                DurationSelector(
                    title = "额度用完后的休息时长",
                    value = draft.restMinutes,
                    minValue = 1,
                    maxValue = 120,
                    step = 1,
                    presets = listOf(1, 5, 10, 15, 30, 60),
                    accentColor = BrandColors.Warning,
                    onValueChange = onRestChange
                )
                Text(
                    "执行时段内、目标 App 在前台且亮屏时才累计每日用量；禁用时段优先于每日上限和独立额度循环。",
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SupervisableAppPickerContent(
    apps: List<AllowedApp>,
    isLoading: Boolean,
    loadFailed: Boolean,
    selectedPackage: String,
    query: String,
    contentHeight: androidx.compose.ui.unit.Dp,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSelect: (AllowedApp) -> Unit
) {
    val filtered = remember(apps, query) {
        filterSupervisableApps(apps, query)
    }

    Column(
        modifier = Modifier.fillMaxWidth().height(contentHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                    if (apps.isEmpty()) "没有可监督的普通启动器 App" else "没有匹配的 App",
                    color = BrandColors.TextSecondary,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = AllowedApp::packageName) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppDrawableIcon(
                                icon = app.icon,
                                label = app.label,
                                modifier = Modifier.padding(end = 12.dp).size(42.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    fontWeight = if (app.packageName == selectedPackage) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
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
                            if (app.packageName == selectedPackage) {
                                Text("已选", color = BrandColors.AppAccent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun resolveAppPlanEditorContentHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp - APP_PLAN_EDITOR_CHROME_HEIGHT_DP).coerceIn(
        MIN_APP_PLAN_EDITOR_CONTENT_HEIGHT_DP,
        MAX_APP_PLAN_EDITOR_CONTENT_HEIGHT_DP
    )

internal fun filterSupervisableApps(
    apps: List<AllowedApp>,
    query: String
): List<AllowedApp> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) apps else apps.filter { app ->
        app.label.contains(normalizedQuery, ignoreCase = true) ||
            app.packageName.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
private fun AppDrawableIcon(
    icon: Drawable?,
    label: String,
    modifier: Modifier = Modifier
) {
    if (icon == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = label,
                tint = BrandColors.TextTertiary,
                modifier = Modifier.size(24.dp)
            )
        }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = label
            }
        },
        update = { imageView ->
            imageView.contentDescription = label
            imageView.setImageDrawable(icon)
        }
    )
}

private fun formatCompactDuration(millis: Long): String {
    val safeMillis = millis.coerceAtLeast(0L)
    val totalMinutes = ceil(safeMillis / 60_000.0).toLong()
    return when {
        totalMinutes >= 60L -> "${totalMinutes / 60} 小时 ${totalMinutes % 60} 分"
        else -> "$totalMinutes 分钟"
    }
}
