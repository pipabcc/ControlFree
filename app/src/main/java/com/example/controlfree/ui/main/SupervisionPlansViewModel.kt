package com.example.controlfree.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.MAX_PLAN_TRIGGER_APPS
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.PlanConflictReason
import com.example.controlfree.supervision.SupervisionPlanConflict
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.PlanWriteResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.AppAllowlistManager
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.supervision.DailyTimeRange
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SupervisionPlansLoadProblem {
    CORRUPT_DATA,
    STORAGE_FAILURE
}

internal data class SupervisionPlansUiState(
    val isLoading: Boolean = true,
    val plans: List<SupervisionPlan> = emptyList(),
    val loadProblem: SupervisionPlansLoadProblem? = null,
    val editorDraft: GlobalPlanEditorDraft? = null,
    val focusEditorDraft: FocusPlanEditorDraft? = null,
    val appEditorDraft: AppPlanEditorDraft? = null,
    val appPlanTargetMetadata: AppPlanTargetMetadata = AppPlanTargetMetadata(),
    val supervisableApps: List<AllowedApp> = emptyList(),
    val supervisableAppsLoaded: Boolean = false,
    val isLoadingSupervisableApps: Boolean = false,
    val supervisableAppsLoadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val busyPlanIds: Set<String> = emptySet()
)

internal sealed interface SupervisionPlansUiEvent {
    data class Message(val text: String) : SupervisionPlansUiEvent
}

class SupervisionPlansViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SupervisionPlansUiState())
    internal val uiState: StateFlow<SupervisionPlansUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<SupervisionPlansUiEvent>(Channel.BUFFERED)
    internal val events = eventChannel.receiveAsFlow()

    private var repository: SupervisionPlanRepository? = null
    private var applicationContext: Context? = null
    private var appAllowlistManager: AppAllowlistManager? = null
    private var observationJob: Job? = null
    private var appPlanTargetMetadataJob: Job? = null
    private var loadingAppPlanTargetPackages: Set<String> = emptySet()
    private var nextRangeId = 10L

    fun initialize(context: Context) {
        if (repository == null) {
            applicationContext = context.applicationContext
            repository = SupervisionPlanRepository.getInstance(requireNotNull(applicationContext))
            appAllowlistManager = AppAllowlistManager(requireNotNull(applicationContext))
            requestScheduleReconciliation("plans_screen_initialized")
        }
        if (observationJob?.isActive != true) observePlans()
    }

    fun retryLoading() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadProblem = null)
        observationJob?.cancel()
        observePlans()
    }

    fun reconcileSchedules(reason: String = "app_resumed") {
        requestScheduleReconciliation(reason)
    }

    fun openNewGlobalPlan(deviceZoneId: ZoneId = ZoneId.systemDefault()) {
        _uiState.value = _uiState.value.copy(
            appEditorDraft = null,
            focusEditorDraft = null,
            editorDraft = GlobalPlanEditorDraft(
                planId = null,
                expectedUpdatedAtEpochMillis = null,
                createdAtEpochMillis = null,
                name = "日常监督",
                enabled = true,
                activeDays = DayOfWeek.entries.toSet(),
                ranges = listOf(TimeRangeDraft(nextRangeId++, 0, 24 * 60)),
                usageMinutes = 30,
                lockMinutes = 5,
                zoneId = deviceZoneId,
                zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
            )
        )
    }

    fun openGlobalPlan(plan: SupervisionPlan) {
        if (plan.type != SupervisionPlanType.GLOBAL || plan.policy !is GlobalCyclePolicy) {
            emitMessage("当前版本暂不支持编辑此类计划")
            return
        }
        val draft = GlobalPlanEditorMapper.fromPlan(plan)
        if (draft.triggerAppPackageNames.isNotEmpty()) ensureSupervisableAppsLoaded()
        nextRangeId = maxOf(nextRangeId, (draft.ranges.maxOfOrNull { it.id } ?: 0L) + 1L)
        _uiState.value = _uiState.value.copy(
            editorDraft = draft,
            focusEditorDraft = null,
            appEditorDraft = null
        )
    }

    fun closeEditor() {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(editorDraft = null)
    }

    fun openNewFocusPlan(deviceZoneId: ZoneId = ZoneId.systemDefault()) {
        _uiState.value = _uiState.value.copy(
            editorDraft = null,
            appEditorDraft = null,
            focusEditorDraft = FocusPlanEditorDraft(
                planId = null,
                expectedUpdatedAtEpochMillis = null,
                createdAtEpochMillis = null,
                name = "日常专注",
                enabled = true,
                activeDays = DayOfWeek.entries.toSet(),
                ranges = listOf(TimeRangeDraft(nextRangeId++, 0, 24 * 60)),
                lockMinutes = 5,
                playMinutes = 1,
                zoneId = deviceZoneId,
                zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
            )
        )
    }

    fun openFocusPlan(plan: SupervisionPlan) {
        if (plan.type != SupervisionPlanType.FOCUS || plan.policy !is FocusCyclePolicy) {
            emitMessage("当前计划不是专注任务")
            return
        }
        val draft = FocusPlanEditorMapper.fromPlan(plan)
        nextRangeId = maxOf(nextRangeId, (draft.ranges.maxOfOrNull { it.id } ?: 0L) + 1L)
        _uiState.value = _uiState.value.copy(
            focusEditorDraft = draft,
            editorDraft = null,
            appEditorDraft = null
        )
    }

    fun closeFocusEditor() {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(focusEditorDraft = null)
    }

    fun openNewAppPlan(deviceZoneId: ZoneId = ZoneId.systemDefault()) {
        ensureSupervisableAppsLoaded()
        _uiState.value = _uiState.value.copy(
            editorDraft = null,
            focusEditorDraft = null,
            appEditorDraft = AppPlanEditorDraft(
                planId = null,
                expectedUpdatedAtEpochMillis = null,
                createdAtEpochMillis = null,
                name = LEGACY_DEFAULT_APP_PLAN_NAME,
                enabled = true,
                activeDays = DayOfWeek.entries.toSet(),
                ranges = listOf(TimeRangeDraft(nextRangeId++, 0, 24 * 60)),
                disabledRanges = emptyList(),
                packageName = "",
                packageLabel = "",
                usageAllowanceMinutes = 30,
                restMinutes = 10,
                dailyUsageLimitMinutes = 120,
                zoneId = deviceZoneId,
                zoneMode = ScheduleZoneMode.FOLLOW_DEVICE
            )
        )
    }

    fun openAppPlan(plan: SupervisionPlan) {
        if (plan.type != SupervisionPlanType.APP || plan.policy !is com.example.controlfree.supervision.AppRulePolicy) {
            emitMessage("当前计划不是 App 独立监督")
            return
        }
        ensureSupervisableAppsLoaded()
        val packageName = plan.policy.packageName
        val label = _uiState.value.supervisableApps
            .firstOrNull { it.packageName == packageName }
            ?.label
            ?: packageName
        val draft = AppPlanEditorMapper.fromPlan(plan, label)
        nextRangeId = maxOf(
            nextRangeId,
            (draft.ranges.plus(draft.disabledRanges).maxOfOrNull { it.id } ?: 0L) + 1L
        )
        _uiState.value = _uiState.value.copy(
            appEditorDraft = draft,
            editorDraft = null,
            focusEditorDraft = null
        )
    }

    fun closeAppEditor() {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(appEditorDraft = null)
    }

    fun retrySupervisableApps() {
        _uiState.value = _uiState.value.copy(supervisableAppsLoaded = false)
        ensureSupervisableAppsLoaded(force = true)
    }

    fun selectSupervisableApp(app: AllowedApp) = updateAppDraft {
        copy(
            name = appPlanNameAfterAppSelection(
                currentName = name,
                currentPackageLabel = packageLabel,
                selectedAppLabel = app.label
            ),
            packageName = app.packageName,
            packageLabel = app.label
        )
    }

    fun updateAppName(name: String) = updateAppDraft { copy(name = name) }

    fun updateAppEnabled(enabled: Boolean) = updateAppDraft {
        copy(
            enabled = enabled,
            scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis.takeUnless { enabled }
        )
    }

    fun updateAppActivation(enabled: Boolean, scheduledEnableAtEpochMillis: Long?) =
        updateAppDraft {
            copy(
                enabled = enabled,
                scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis.takeUnless { enabled }
            )
        }

    fun toggleAppDay(day: DayOfWeek) = updateAppDraft {
        copy(
            activeDays = activeDays.toMutableSet().apply {
                if (!add(day)) remove(day)
            }.toSet()
        )
    }

    fun updateAppAllowanceMinutes(minutes: Int) = updateAppDraft {
        copy(usageAllowanceMinutes = minutes)
    }

    fun updateAppRestMinutes(minutes: Int) = updateAppDraft { copy(restMinutes = minutes) }

    fun updateAppDailyUsageLimitMinutes(minutes: Int) = updateAppDraft {
        copy(dailyUsageLimitMinutes = minutes)
    }

    fun updateAppRangeStart(rangeId: Long, minute: Int) = updateAppDraft {
        copy(ranges = ranges.map { range ->
            if (range.id == rangeId) range.copy(startMinute = minute) else range
        })
    }

    fun updateAppRangeEnd(rangeId: Long, minuteExclusive: Int) = updateAppDraft {
        copy(ranges = ranges.map { range ->
            if (range.id == rangeId) range.copy(endMinuteExclusive = minuteExclusive) else range
        })
    }

    fun addAppRange() {
        val draft = _uiState.value.appEditorDraft ?: return
        val suggestion = TimeRangeSuggestion.next(draft.ranges, nextRangeId)
        if (suggestion == null) {
            emitMessage("已达到时间段上限，或当天没有完整的一小时空档")
            return
        }
        nextRangeId++
        updateAppDraft { copy(ranges = ranges + suggestion) }
    }

    fun removeAppRange(rangeId: Long) = updateAppDraft {
        if (ranges.size <= 1) {
            emitMessage("每个计划至少保留一个时间段")
            this
        } else {
            copy(ranges = ranges.filterNot { it.id == rangeId })
        }
    }

    fun updateAppDisabledRangeStart(rangeId: Long, minute: Int) = updateAppDraft {
        copy(disabledRanges = disabledRanges.map { range ->
            if (range.id == rangeId) range.copy(startMinute = minute) else range
        })
    }

    fun updateAppDisabledRangeEnd(rangeId: Long, minuteExclusive: Int) = updateAppDraft {
        copy(disabledRanges = disabledRanges.map { range ->
            if (range.id == rangeId) range.copy(endMinuteExclusive = minuteExclusive) else range
        })
    }

    fun addAppDisabledRange() {
        val draft = _uiState.value.appEditorDraft ?: return
        val suggestion = TimeRangeSuggestion.next(draft.disabledRanges, nextRangeId)
        if (suggestion == null || draft.disabledRanges.size >= MAX_APP_DISABLED_RANGES) {
            emitMessage("已达到禁用时段上限，或当天没有完整的一小时空档")
            return
        }
        nextRangeId++
        updateAppDraft { copy(disabledRanges = disabledRanges + suggestion) }
    }

    fun removeAppDisabledRange(rangeId: Long) = updateAppDraft {
        copy(disabledRanges = disabledRanges.filterNot { it.id == rangeId })
    }

    fun saveAppEditor(deviceZoneId: ZoneId = ZoneId.systemDefault()) {
        val repository = repository ?: run {
            emitMessage("监督计划存储尚未就绪")
            return
        }
        val draft = _uiState.value.appEditorDraft ?: return
        if (_uiState.value.isSaving) return
        if (draft.enabled || draft.scheduledEnableAtEpochMillis != null) {
            val current = _uiState.value
            when {
                current.isLoadingSupervisableApps -> {
                    emitMessage("正在确认 App 列表，请稍候再保存")
                    return
                }
                !current.supervisableAppsLoaded -> {
                    ensureSupervisableAppsLoaded()
                    emitMessage("请先成功读取可监督的 App 列表")
                    return
                }
                current.supervisableApps.none { app -> app.packageName == draft.packageName } -> {
                    emitMessage("所选 App 已卸载，或属于电话、短信及系统保护应用")
                    return
                }
            }
        }
        val buildResult = AppPlanEditorMapper.build(
            draft = draft,
            newPlanId = UUID.randomUUID().toString(),
            nowEpochMillis = System.currentTimeMillis(),
            deviceZoneId = deviceZoneId
        )
        if (buildResult is AppPlanBuildResult.Invalid) {
            emitMessage(buildResult.reason)
            return
        }
        buildResult as AppPlanBuildResult.Success
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            val result = repository.saveWithReservation(
                plan = buildResult.plan,
                expectedUpdatedAtEpochMillis = buildResult.expectedUpdatedAtEpochMillis,
                scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis
            )
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                appEditorDraft = if (result is PlanWriteResult.Success) null
                else _uiState.value.appEditorDraft
            )
            emitWriteResult(
                result,
                if (draft.planId == null) "App 监督计划已创建" else "App 监督计划已保存"
            )
        }
    }

    fun updateFocusName(name: String) = updateFocusDraft { copy(name = name) }

    fun updateFocusEnabled(enabled: Boolean) = updateFocusDraft {
        copy(
            enabled = enabled,
            scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis.takeUnless { enabled }
        )
    }

    fun updateFocusActivation(enabled: Boolean, scheduledEnableAtEpochMillis: Long?) =
        updateFocusDraft {
            copy(
                enabled = enabled,
                scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis.takeUnless { enabled }
            )
        }

    fun toggleFocusDay(day: DayOfWeek) = updateFocusDraft {
        copy(
            activeDays = activeDays.toMutableSet().apply {
                if (!add(day)) remove(day)
            }.toSet()
        )
    }

    fun updateFocusLockMinutes(minutes: Int) = updateFocusDraft { copy(lockMinutes = minutes) }

    fun updateFocusPlayMinutes(minutes: Int) = updateFocusDraft { copy(playMinutes = minutes) }

    fun updateFocusRangeStart(rangeId: Long, minute: Int) = updateFocusDraft {
        copy(ranges = ranges.map { range ->
            if (range.id == rangeId) range.copy(startMinute = minute) else range
        })
    }

    fun updateFocusRangeEnd(rangeId: Long, minuteExclusive: Int) = updateFocusDraft {
        copy(ranges = ranges.map { range ->
            if (range.id == rangeId) range.copy(endMinuteExclusive = minuteExclusive) else range
        })
    }

    fun addFocusRange() {
        val draft = _uiState.value.focusEditorDraft ?: return
        val suggestion = TimeRangeSuggestion.next(draft.ranges, nextRangeId)
        if (suggestion == null) {
            emitMessage("已达到时间段上限，或当天没有完整的一小时空档")
            return
        }
        nextRangeId++
        updateFocusDraft { copy(ranges = ranges + suggestion) }
    }

    fun removeFocusRange(rangeId: Long) = updateFocusDraft {
        if (ranges.size <= 1) {
            emitMessage("每个专注任务至少保留一个时间段")
            this
        } else {
            copy(ranges = ranges.filterNot { it.id == rangeId })
        }
    }

    fun saveFocusEditor(deviceZoneId: ZoneId = ZoneId.systemDefault()) {
        val repository = repository ?: run {
            emitMessage("专注任务存储尚未就绪")
            return
        }
        val draft = _uiState.value.focusEditorDraft ?: return
        if (_uiState.value.isSaving) return
        val buildResult = FocusPlanEditorMapper.build(
            draft = draft,
            newPlanId = UUID.randomUUID().toString(),
            nowEpochMillis = System.currentTimeMillis(),
            deviceZoneId = deviceZoneId
        )
        if (buildResult is FocusPlanBuildResult.Invalid) {
            emitMessage(buildResult.reason)
            return
        }
        buildResult as FocusPlanBuildResult.Success
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            val scheduledEnableAtEpochMillis = if (draft.oneTimeFocusWindow == null) {
                draft.scheduledEnableAtEpochMillis
            } else {
                buildResult.plan.scheduledEnableAtEpochMillis
            }
            val result = repository.saveWithReservation(
                plan = buildResult.plan,
                expectedUpdatedAtEpochMillis = buildResult.expectedUpdatedAtEpochMillis,
                scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis
            )
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                focusEditorDraft = if (result is PlanWriteResult.Success) null
                else _uiState.value.focusEditorDraft
            )
            emitWriteResult(
                result,
                if (draft.planId == null) "专注任务已创建" else "专注任务已保存"
            )
        }
    }

    fun updateName(name: String) = updateDraft { copy(name = name) }

    fun updateEnabled(enabled: Boolean) = updateDraft {
        copy(
            enabled = enabled,
            scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis.takeUnless { enabled }
        )
    }

    fun updateActivation(enabled: Boolean, scheduledEnableAtEpochMillis: Long?) = updateDraft {
        copy(
            enabled = enabled,
            scheduledEnableAtEpochMillis = scheduledEnableAtEpochMillis.takeUnless { enabled },
            triggerAppPackageNames = emptySet()
        )
    }

    fun prepareGlobalAppTriggerSelection() {
        ensureSupervisableAppsLoaded()
    }

    fun toggleGlobalTriggerApp(app: AllowedApp) = updateDraft {
        val updated = triggerAppPackageNames.toMutableSet()
        val wasAppTrigger = updated.isNotEmpty()
        if (app.packageName in updated && updated.size == 1) {
            emitMessage("App 触发至少保留一个 App；如需取消，请改选其他启动方式")
            return@updateDraft this
        }
        if (!updated.add(app.packageName)) {
            updated.remove(app.packageName)
        } else if (updated.size > MAX_PLAN_TRIGGER_APPS) {
            emitMessage("每个任务最多添加 $MAX_PLAN_TRIGGER_APPS 个触发 App")
            return@updateDraft this
        }
        copy(
            enabled = enabled.takeIf { wasAppTrigger } ?: false,
            scheduledEnableAtEpochMillis = null,
            triggerAppPackageNames = updated
        )
    }

    fun toggleDay(day: DayOfWeek) = updateDraft {
        copy(
            activeDays = activeDays.toMutableSet().apply {
                if (!add(day)) remove(day)
            }.toSet()
        )
    }

    fun updateUsageMinutes(minutes: Int) = updateDraft { copy(usageMinutes = minutes) }

    fun updateLockMinutes(minutes: Int) = updateDraft { copy(lockMinutes = minutes) }

    fun updateRangeStart(rangeId: Long, minute: Int) = updateDraft {
        copy(ranges = ranges.map { range ->
            if (range.id == rangeId) range.copy(startMinute = minute) else range
        })
    }

    fun updateRangeEnd(rangeId: Long, minuteExclusive: Int) = updateDraft {
        copy(ranges = ranges.map { range ->
            if (range.id == rangeId) range.copy(endMinuteExclusive = minuteExclusive) else range
        })
    }

    fun addRange() {
        val draft = _uiState.value.editorDraft ?: return
        val suggestion = TimeRangeSuggestion.next(draft.ranges, nextRangeId)
        if (suggestion == null) {
            emitMessage("已达到时间段上限，或当天没有完整的一小时空档")
            return
        }
        nextRangeId++
        updateDraft { copy(ranges = ranges + suggestion) }
    }

    fun removeRange(rangeId: Long) = updateDraft {
        if (ranges.size <= 1) {
            emitMessage("每个计划至少保留一个时间段")
            this
        } else {
            copy(ranges = ranges.filterNot { it.id == rangeId })
        }
    }

    fun saveEditor(deviceZoneId: ZoneId = ZoneId.systemDefault()) {
        val repository = repository ?: run {
            emitMessage("监督计划存储尚未就绪")
            return
        }
        val draft = _uiState.value.editorDraft ?: return
        if (_uiState.value.isSaving) return
        if (draft.triggerAppPackageNames.isNotEmpty()) {
            val current = _uiState.value
            when {
                current.isLoadingSupervisableApps -> {
                    emitMessage("正在确认 App 列表，请稍候再保存")
                    return
                }
                !current.supervisableAppsLoaded -> {
                    ensureSupervisableAppsLoaded()
                    emitMessage("请先成功读取可选 App 列表")
                    return
                }
                !current.supervisableApps.mapTo(hashSetOf(), AllowedApp::packageName)
                    .containsAll(draft.triggerAppPackageNames) -> {
                    emitMessage("部分触发 App 已卸载，或属于系统保护应用")
                    return
                }
            }
        }
        val buildResult = GlobalPlanEditorMapper.build(
            draft = draft,
            newPlanId = UUID.randomUUID().toString(),
            nowEpochMillis = System.currentTimeMillis(),
            deviceZoneId = deviceZoneId
        )
        if (buildResult is GlobalPlanBuildResult.Invalid) {
            emitMessage(buildResult.reason)
            return
        }
        buildResult as GlobalPlanBuildResult.Success
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            val result = repository.saveWithReservation(
                plan = buildResult.plan,
                expectedUpdatedAtEpochMillis = buildResult.expectedUpdatedAtEpochMillis,
                scheduledEnableAtEpochMillis = draft.scheduledEnableAtEpochMillis
            )
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                editorDraft = if (result is PlanWriteResult.Success) null
                else _uiState.value.editorDraft
            )
            emitWriteResult(result, if (draft.planId == null) "监督计划已创建" else "监督计划已保存")
        }
    }

    fun setEnabled(plan: SupervisionPlan, enabled: Boolean) {
        val repository = repository ?: return
        if (plan.id in _uiState.value.busyPlanIds) return
        setPlanBusy(plan.id, true)
        viewModelScope.launch {
            val result = repository.setEnabled(
                planId = plan.id,
                enabled = enabled,
                expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
                updatedAtEpochMillis = nextVersionAfter(plan.updatedAtEpochMillis)
            )
            setPlanBusy(plan.id, false)
            emitWriteResult(result, if (enabled) "计划已启用" else "计划已停用")
        }
    }

    fun scheduleEnable(plan: SupervisionPlan, enableAtEpochMillis: Long) {
        val repository = repository ?: run {
            emitMessage("任务存储尚未就绪")
            return
        }
        if (plan.id in _uiState.value.busyPlanIds) return
        setPlanBusy(plan.id, true)
        viewModelScope.launch {
            val nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
            val result = repository.scheduleEnable(
                planId = plan.id,
                enableAtEpochMillis = enableAtEpochMillis,
                expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
                nowEpochMillis = nowEpochMillis
            )
            setPlanBusy(plan.id, false)
            emitWriteResult(result, "预约开启时间已保存")
        }
    }

    fun cancelScheduledEnable(plan: SupervisionPlan) {
        val repository = repository ?: run {
            emitMessage("任务存储尚未就绪")
            return
        }
        if (plan.id in _uiState.value.busyPlanIds) return
        setPlanBusy(plan.id, true)
        viewModelScope.launch {
            val result = repository.cancelScheduledEnable(
                planId = plan.id,
                expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
                nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
            )
            setPlanBusy(plan.id, false)
            val successMessage = if (
                result is PlanWriteResult.Success && result.affectedPlanIds.isEmpty()
            ) {
                "预约状态已变化，无需重复取消"
            } else {
                "预约已取消"
            }
            emitWriteResult(result, successMessage)
        }
    }

    fun delete(plan: SupervisionPlan) {
        val repository = repository ?: return
        if (plan.id in _uiState.value.busyPlanIds) return
        setPlanBusy(plan.id, true)
        viewModelScope.launch {
            val result = repository.delete(plan.id, plan.updatedAtEpochMillis)
            setPlanBusy(plan.id, false)
            if (result is PlanWriteResult.Success) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    editorDraft = current.editorDraft?.takeUnless { it.planId == plan.id },
                    focusEditorDraft = current.focusEditorDraft?.takeUnless { it.planId == plan.id },
                    appEditorDraft = current.appEditorDraft?.takeUnless { it.planId == plan.id }
                )
            }
            emitWriteResult(
                result,
                if (plan.type == SupervisionPlanType.FOCUS) "专注任务已删除" else "监督计划已删除"
            )
        }
    }

    private fun observePlans() {
        val repository = repository ?: return
        observationJob = viewModelScope.launch {
            repository.observePlans().collect { result ->
                when (result) {
                    is PlanLoadResult.Success -> {
                        val hasGlobalPlans = result.plans.any { it.type == SupervisionPlanType.GLOBAL }
                        val prefs = PreferenceManager(requireNotNull(applicationContext))
                        if (!prefs.isDefaultPlansInitialized() && !hasGlobalPlans) {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val morningPlan = SupervisionPlan(
                                        id = UUID.randomUUID().toString(),
                                        name = "晨间监督",
                                        type = SupervisionPlanType.GLOBAL,
                                        enabled = false,
                                        schedule = WeeklySchedule(
                                            zoneId = ZoneId.systemDefault(),
                                            activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                                            ranges = listOf(DailyTimeRange(9 * 60, 12 * 60))
                                        ),
                                        policy = GlobalCyclePolicy(usageDuration = java.time.Duration.ofMinutes(25), lockDuration = java.time.Duration.ofMinutes(15)),
                                        createdAtEpochMillis = System.currentTimeMillis(),
                                        updatedAtEpochMillis = System.currentTimeMillis()
                                    )
                                    val allDayPlan = SupervisionPlan(
                                        id = UUID.randomUUID().toString(),
                                        name = "全天防沉迷",
                                        type = SupervisionPlanType.GLOBAL,
                                        enabled = false,
                                        schedule = WeeklySchedule(
                                            zoneId = ZoneId.systemDefault(),
                                            activeDays = DayOfWeek.values().toSet(),
                                            ranges = listOf(DailyTimeRange(6 * 60, 23 * 60 + 56))
                                        ),
                                        policy = GlobalCyclePolicy(usageDuration = java.time.Duration.ofMinutes(8), lockDuration = java.time.Duration.ofMinutes(10)),
                                        createdAtEpochMillis = System.currentTimeMillis(),
                                        updatedAtEpochMillis = System.currentTimeMillis()
                                    )
                                    repository.save(morningPlan, null)
                                    repository.save(allDayPlan, null)
                                    // 保存成功后才置位，失败时下次发射会自动重试。
                                    prefs.setDefaultPlansInitialized(true)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }

                        val hasFocusPlans = result.plans.any { it.type == SupervisionPlanType.FOCUS }
                        if (!prefs.isDefaultFocusPlansInitialized() && !hasFocusPlans) {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val nightFocusPlan = SupervisionPlan(
                                        id = UUID.randomUUID().toString(),
                                        name = "拒绝熬夜",
                                        type = SupervisionPlanType.FOCUS,
                                        enabled = false,
                                        schedule = WeeklySchedule(
                                            zoneId = ZoneId.systemDefault(),
                                            activeDays = DayOfWeek.values().toSet(),
                                            ranges = listOf(DailyTimeRange(23 * 60, 6 * 60))
                                        ),
                                        policy = FocusCyclePolicy(lockDuration = java.time.Duration.ofMinutes(180), playDuration = java.time.Duration.ofMinutes(1)),
                                        createdAtEpochMillis = System.currentTimeMillis(),
                                        updatedAtEpochMillis = System.currentTimeMillis()
                                    )
                                    repository.save(nightFocusPlan, null)
                                    // 保存成功后才置位，失败时下次发射会自动重试。
                                    prefs.setDefaultFocusPlansInitialized(true)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }

                        val targetPackages = appPlanTargetPackages(result.plans)
                        val current = _uiState.value
                        val knownTargetApps = current.supervisableApps.filter { app ->
                            app.packageName in targetPackages
                        }
                        val targetMetadata = current.appPlanTargetMetadata
                            .retainTargets(targetPackages)
                            .resolve(
                                requestedPackages = knownTargetApps
                                    .mapTo(linkedSetOf(), AllowedApp::packageName),
                                resolvedApps = knownTargetApps
                            )
                        _uiState.value = current.copy(
                            isLoading = false,
                            plans = result.plans,
                            loadProblem = null,
                            appPlanTargetMetadata = targetMetadata
                        )
                        ensureAppPlanTargetMetadataLoaded(targetPackages)
                    }
                    is PlanLoadResult.CorruptData -> {
                        clearAppPlanTargetMetadata()
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            plans = emptyList(),
                            loadProblem = SupervisionPlansLoadProblem.CORRUPT_DATA,
                            appPlanTargetMetadata = AppPlanTargetMetadata()
                        )
                    }
                    PlanLoadResult.StorageFailure -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            loadProblem = SupervisionPlansLoadProblem.STORAGE_FAILURE
                        )
                    }
                }
            }
        }
    }

    private fun ensureAppPlanTargetMetadataLoaded(targetPackages: Set<String>) {
        val manager = appAllowlistManager ?: return
        val unresolvedPackages = _uiState.value.appPlanTargetMetadata
            .unresolvedTargets(targetPackages)
        if (unresolvedPackages.isEmpty()) {
            appPlanTargetMetadataJob?.cancel()
            appPlanTargetMetadataJob = null
            loadingAppPlanTargetPackages = emptySet()
            return
        }
        if (
            appPlanTargetMetadataJob?.isActive == true &&
            loadingAppPlanTargetPackages == unresolvedPackages
        ) {
            return
        }

        appPlanTargetMetadataJob?.cancel()
        loadingAppPlanTargetPackages = unresolvedPackages
        appPlanTargetMetadataJob = viewModelScope.launch {
            val resolvedApps = withContext(Dispatchers.IO) {
                unresolvedPackages.mapNotNull(manager::getAppMetadata)
            }
            val activePackages = appPlanTargetPackages(_uiState.value.plans)
            val stillRelevantPackages = unresolvedPackages
                .filterTo(linkedSetOf(), activePackages::contains)
            _uiState.value = _uiState.value.copy(
                appPlanTargetMetadata = _uiState.value.appPlanTargetMetadata.resolve(
                    requestedPackages = stillRelevantPackages,
                    resolvedApps = resolvedApps
                )
            )
            if (loadingAppPlanTargetPackages == unresolvedPackages) {
                loadingAppPlanTargetPackages = emptySet()
                appPlanTargetMetadataJob = null
            }
        }
    }

    private fun clearAppPlanTargetMetadata() {
        appPlanTargetMetadataJob?.cancel()
        appPlanTargetMetadataJob = null
        loadingAppPlanTargetPackages = emptySet()
    }

    private fun updateDraft(transform: GlobalPlanEditorDraft.() -> GlobalPlanEditorDraft) {
        val current = _uiState.value.editorDraft ?: return
        _uiState.value = _uiState.value.copy(editorDraft = current.transform())
    }

    private fun updateFocusDraft(transform: FocusPlanEditorDraft.() -> FocusPlanEditorDraft) {
        val current = _uiState.value.focusEditorDraft ?: return
        _uiState.value = _uiState.value.copy(focusEditorDraft = current.transform())
    }

    private fun updateAppDraft(transform: AppPlanEditorDraft.() -> AppPlanEditorDraft) {
        val current = _uiState.value.appEditorDraft ?: return
        _uiState.value = _uiState.value.copy(appEditorDraft = current.transform())
    }

    private fun ensureSupervisableAppsLoaded(force: Boolean = false) {
        val manager = appAllowlistManager ?: return
        val current = _uiState.value
        if (!force && (current.supervisableAppsLoaded || current.isLoadingSupervisableApps)) return
        _uiState.value = current.copy(
            isLoadingSupervisableApps = true,
            supervisableAppsLoadFailed = false
        )
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                try {
                    manager.getSupervisableApps()
                } catch (_: RuntimeException) {
                    null
                }
            }
            val latest = _uiState.value
            val activeTargetPackages = appPlanTargetPackages(latest.plans)
            val knownTargetApps = apps.orEmpty().filter { app ->
                app.packageName in activeTargetPackages
            }
            _uiState.value = latest.copy(
                supervisableApps = apps.orEmpty(),
                supervisableAppsLoaded = apps != null,
                isLoadingSupervisableApps = false,
                supervisableAppsLoadFailed = apps == null,
                appPlanTargetMetadata = latest.appPlanTargetMetadata.resolve(
                    requestedPackages = knownTargetApps
                        .mapTo(linkedSetOf(), AllowedApp::packageName),
                    resolvedApps = knownTargetApps
                )
            )
        }
    }

    private fun setPlanBusy(planId: String, busy: Boolean) {
        _uiState.value = _uiState.value.copy(
            busyPlanIds = _uiState.value.busyPlanIds.toMutableSet().apply {
                if (busy) add(planId) else remove(planId)
            }.toSet()
        )
    }

    private fun emitWriteResult(result: PlanWriteResult, successMessage: String) {
        if (result is PlanWriteResult.Success) {
            requestScheduleReconciliation("plan_changed")
        }
        val message = when (result) {
            is PlanWriteResult.Success -> if (result.autoDisabledPlanIds.isEmpty()) {
                successMessage
            } else {
                "$successMessage，已自动停用 ${result.autoDisabledPlanIds.size} 个冲突任务"
            }
            is PlanWriteResult.Conflicts -> planConflictMessage(result.conflicts)
            is PlanWriteResult.StaleData -> "计划已在其他操作中更新，请重新打开后再试"
            is PlanWriteResult.NonIncreasingVersion -> "计划版本无效，请重新打开后再试"
            is PlanWriteResult.CorruptData -> "监督计划数据异常，请先运行设置页自检"
            is PlanWriteResult.DuplicateIds -> "检测到重复的计划编号"
            is PlanWriteResult.NotFound -> "计划已不存在，列表将自动刷新"
            is PlanWriteResult.InvalidInput -> result.reason
            PlanWriteResult.StorageFailure -> "保存失败，请检查存储空间后重试"
        }
        emitMessage(message)
    }

    private fun emitMessage(message: String) {
        eventChannel.trySend(SupervisionPlansUiEvent.Message(message))
    }

    private fun nextVersionAfter(version: Long): Long = when {
        version == Long.MAX_VALUE -> version
        else -> maxOf(System.currentTimeMillis(), version + 1L)
    }

    private fun requestScheduleReconciliation(reason: String) {
        applicationContext?.let { context ->
            try {
                SupervisionScheduleReceiver.requestReconciliation(context, reason)
            } catch (_: RuntimeException) {
                // 下次进入页面、系统时间变化或已保存闹钟仍会再次校准。
            }
        }
    }
}

internal fun planConflictMessage(conflicts: List<SupervisionPlanConflict>): String {
    val reasons = conflicts.mapTo(mutableSetOf(), SupervisionPlanConflict::reason)
    return when {
        PlanConflictReason.OVERLAPPING_APP_RULE in reasons ->
            "同一个 App 的已启用执行时段不能重叠"
        PlanConflictReason.DIFFERENT_TIME_ZONES in reasons ->
            "计划使用了不同固定时区，无法安全判断时段冲突，请统一时区设置"
        PlanConflictReason.DUPLICATE_PLAN_ID in reasons -> "检测到重复的计划编号"
        else -> "该时段与已启用的全局监督或专注任务冲突"
    }
}
