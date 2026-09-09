package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.AnniversaryDeletionSnapshot
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.anniversary.runtime.AnniversaryRuntimeCoordinator
import com.example.controlfree.todo.toJsonString
import com.example.controlfree.ui.todo.anniversary.AndroidIcuLunarCalendar
import com.example.controlfree.ui.todo.anniversary.AnniversaryCalendarType
import com.example.controlfree.ui.todo.anniversary.AnniversaryEditorDraft
import com.example.controlfree.ui.todo.anniversary.AnniversaryOccurrenceResolver
import com.example.controlfree.ui.todo.anniversary.AnniversaryRepeatRule
import com.example.controlfree.ui.todo.anniversary.AnniversaryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal sealed interface AnniversaryNotificationPermissionAction {
    data class EnableExisting(val anniversaryId: String) : AnniversaryNotificationPermissionAction

    data class SaveDraft(
        val draft: AnniversaryEditorDraft,
        val shouldRequestWidgetPin: Boolean
    ) : AnniversaryNotificationPermissionAction
}

class AnniversaryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val repository = TodoRepository.getInstance(application)
    private val runtimeCoordinator = AnniversaryRuntimeCoordinator(application)
    val lunarCalendar = AndroidIcuLunarCalendar()
    private val occurrenceResolver = AnniversaryOccurrenceResolver(lunarCalendar)
    val clock: Clock = Clock.systemDefaultZone()

    private val _isAnniversaryDataLoaded = MutableStateFlow(false)
    val isAnniversaryDataLoaded: StateFlow<Boolean> = _isAnniversaryDataLoaded.asStateFlow()

    val anniversaries: StateFlow<List<AnniversaryItemEntity>> = repository.observeAllAnniversaries()
        .onStart { _isAnniversaryDataLoaded.value = false }
        .onEach { _isAnniversaryDataLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val pendingActionStore = AnniversaryNotificationPermissionActionStore(savedStateHandle)
    private val pendingMessages = savedStateHandle.getStateFlow(
        PENDING_MESSAGES_KEY,
        arrayListOf<String>()
    )
    private val pendingWidgetPinRequests = savedStateHandle.getStateFlow(
        PENDING_WIDGET_PIN_REQUESTS_KEY,
        arrayListOf<String>()
    )

    val messages: Flow<String> = pendingMessages.mapNotNull { it.firstOrNull() }
    val widgetPinRequests: Flow<String> =
        pendingWidgetPinRequests.mapNotNull { it.firstOrNull() }

    init {
        refreshRuntime()
    }

    fun saveAnniversary(
        draft: AnniversaryEditorDraft,
        requestWidgetPinAfterSave: Boolean = false
    ) {
        viewModelScope.launch {
            val saved = runCatching {
                val now = clock.millis()
                val existing = draft.id?.let { repository.getAnniversaryById(it) }
                val anchor = occurrenceResolver.anchorInstant(draft.toSpec())
                repository.saveAnniversary(
                    AnniversaryItemEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        title = draft.title.trim(),
                        targetDateEpochMillis = anchor.toEpochMilli(),
                        isLunar = draft.calendarType == AnniversaryCalendarType.LUNAR,
                        type = draft.type.storedValue,
                        repeatRule = draft.repeatRule.storedValue,
                        isPinnedTop = draft.isPinned,
                        showOnWidget = draft.showOnWidget,
                        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                        sourceYear = draft.year,
                        sourceMonth = draft.month,
                        sourceDay = draft.day,
                        sourceHour = draft.hour,
                        sourceMinute = draft.minute,
                        sourceSecond = draft.second,
                        isLunarLeapMonth = draft.isLunarLeapMonth,
                        zoneId = draft.zoneId,
                        showOnLockScreen = draft.showOnLockScreen,
                        remindersJson = draft.reminders.toJsonString(),
                        updatedAtEpochMillis = now
                    )
                )
            }.getOrElse {
                report(it, "保存时刻失败")
                return@launch
            }
            runCatching { runtimeCoordinator.refresh() }
                .onFailure { report(it, "时刻组件暂时无法刷新") }
            if (requestWidgetPinAfterSave) enqueueWidgetPinRequest(saved.id)
        }
    }

    suspend fun deleteAnniversaryForUndo(id: String): AnniversaryDeletionSnapshot? {
        val snapshot = runCatching { repository.deleteAnniversaryForUndo(id) }.getOrElse {
            report(it, "删除时刻失败")
            return null
        } ?: return null
        removeWidgetPinRequest(id)
        refreshRuntimeAfterMutation()
        return snapshot
    }

    suspend fun restoreDeletedAnniversary(snapshot: AnniversaryDeletionSnapshot): Boolean {
        val restored = runCatching { repository.restoreDeletedAnniversary(snapshot) }.getOrElse {
            report(it, "撤销删除失败")
            return false
        }
        if (!restored) {
            notifyUser("撤销失败，时刻可能已被重新创建")
            return false
        }
        refreshRuntimeAfterMutation()
        return true
    }

    fun setPinned(id: String, pinned: Boolean) = updateRuntimeSetting {
        repository.setAnniversaryPinned(id, pinned)
    }

    fun setWidgetEnabled(id: String, enabled: Boolean, requestPinWhenEnabled: Boolean = false) =
        updateRuntimeSetting(
            onUpdated = {
                when {
                    enabled && requestPinWhenEnabled -> enqueueWidgetPinRequest(id)
                    !enabled -> removeWidgetPinRequest(id)
                }
            }
        ) {
            repository.setAnniversaryWidgetEnabled(id, enabled)
        }

    fun setLockScreenEnabled(id: String, enabled: Boolean) = updateRuntimeSetting {
        repository.setAnniversaryLockScreenEnabled(id, enabled)
    }

    internal fun stageNotificationPermissionAction(action: AnniversaryNotificationPermissionAction) {
        pendingActionStore.stage(action)
    }

    internal fun peekNotificationPermissionAction(): AnniversaryNotificationPermissionAction? =
        pendingActionStore.peek()

    internal fun consumeNotificationPermissionAction(): AnniversaryNotificationPermissionAction? =
        pendingActionStore.consume()

    internal fun resolveNotificationPermissionAction(lockScreenEnabled: Boolean): Boolean {
        val action = consumeNotificationPermissionAction() ?: return false
        when (action) {
            is AnniversaryNotificationPermissionAction.EnableExisting -> {
                if (lockScreenEnabled) setLockScreenEnabled(action.anniversaryId, true)
            }

            is AnniversaryNotificationPermissionAction.SaveDraft -> saveAnniversary(
                draft = action.draft.copy(showOnLockScreen = lockScreenEnabled),
                requestWidgetPinAfterSave = action.shouldRequestWidgetPin
            )
        }
        return true
    }

    fun consumeMessage(message: String) {
        savedStateHandle[PENDING_MESSAGES_KEY] = pendingMessages.value.consumeHead(message)
    }

    fun completeWidgetPinRequest(anniversaryId: String, requestAccepted: Boolean) {
        val remaining = pendingWidgetPinRequests.value.consumeHead(anniversaryId)
        if (remaining.size == pendingWidgetPinRequests.value.size) return
        savedStateHandle[PENDING_WIDGET_PIN_REQUESTS_KEY] = remaining
        if (!requestAccepted) {
            setWidgetEnabled(anniversaryId, false)
            notifyUser("桌面组件固定请求失败，请重试")
        }
    }

    fun notifyUser(message: String) {
        val normalized = normalizeAnniversaryMessage(message) ?: return
        savedStateHandle[PENDING_MESSAGES_KEY] = pendingMessages.value.enqueueBounded(
            value = normalized,
            maxSize = MAX_PENDING_MESSAGES,
            unique = false
        )
    }

    fun occurrenceFor(item: AnniversaryItemEntity, now: Instant = clock.instant()) =
        occurrenceResolver.resolve(
            AnniversaryEditorDraft.fromEntity(item, clock.zone).toSpec(),
            now
        )

    // 兼容旧清单页面。
    fun addAnniversary(
        title: String,
        targetDate: Long,
        isLunar: Boolean,
        type: String,
        repeatRule: String
    ) {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(targetDate).atZone(zone)
        saveAnniversary(
            AnniversaryEditorDraft(
                title = title,
                type = com.example.controlfree.ui.todo.anniversary.AnniversaryType.fromStoredValue(type),
                calendarType = if (isLunar) AnniversaryCalendarType.LUNAR else AnniversaryCalendarType.SOLAR,
                year = local.year,
                month = local.monthValue,
                day = local.dayOfMonth,
                hour = local.hour,
                minute = local.minute,
                second = local.second,
                repeatRule = com.example.controlfree.ui.todo.anniversary.AnniversaryRepeatRule.fromStoredValue(repeatRule),
                zoneId = zone.id
            )
        )
    }

    private fun updateRuntimeSetting(
        onUpdated: () -> Unit = {},
        block: suspend () -> Boolean
    ) {
        viewModelScope.launch {
            val updated = runCatching { block() }.getOrElse {
                report(it, "更新时刻设置失败")
                return@launch
            }
            if (!updated) {
                notifyUser("时刻已不存在，请刷新后重试")
                return@launch
            }
            runCatching { runtimeCoordinator.refresh() }
                .onFailure { report(it, "时刻组件暂时无法刷新") }
            onUpdated()
        }
    }

    private fun refreshRuntime() {
        viewModelScope.launch {
            refreshRuntimeAfterMutation()
        }
    }

    private suspend fun refreshRuntimeAfterMutation() {
        runCatching { runtimeCoordinator.refresh() }
            .onFailure { report(it, "时刻组件暂时无法刷新") }
    }

    private fun report(error: Throwable, fallback: String) {
        notifyUser(error.message?.takeIf(String::isNotBlank) ?: fallback)
    }

    private fun enqueueWidgetPinRequest(anniversaryId: String) {
        savedStateHandle[PENDING_WIDGET_PIN_REQUESTS_KEY] =
            pendingWidgetPinRequests.value.enqueueBounded(
                value = anniversaryId,
                maxSize = MAX_PENDING_WIDGET_REQUESTS,
                unique = true
            )
    }

    private fun removeWidgetPinRequest(anniversaryId: String) {
        savedStateHandle[PENDING_WIDGET_PIN_REQUESTS_KEY] = ArrayList(
            pendingWidgetPinRequests.value.filterNot { it == anniversaryId }
        )
    }

    private companion object {
        const val PENDING_MESSAGES_KEY = "anniversary.pending_messages"
        const val PENDING_WIDGET_PIN_REQUESTS_KEY = "anniversary.pending_widget_pin_requests"
        const val MAX_PENDING_MESSAGES = 4
        const val MAX_PENDING_WIDGET_REQUESTS = 16
    }
}

internal fun normalizeAnniversaryMessage(message: String): String? =
    message.trim().take(ANNIVERSARY_MESSAGE_MAX_LENGTH).takeIf(String::isNotEmpty)

private const val ANNIVERSARY_MESSAGE_MAX_LENGTH = 400

internal fun List<String>.enqueueBounded(
    value: String,
    maxSize: Int,
    unique: Boolean
): ArrayList<String> {
    require(maxSize > 0) { "maxSize must be positive" }
    if (unique && value in this) return ArrayList(this)
    return ArrayList((this + value).takeLast(maxSize))
}

internal fun List<String>.consumeHead(expected: String): ArrayList<String> =
    if (firstOrNull() == expected) ArrayList(drop(1)) else ArrayList(this)

internal class AnniversaryNotificationPermissionActionStore(
    private val savedStateHandle: SavedStateHandle
) {
    fun stage(action: AnniversaryNotificationPermissionAction) {
        clear()
        when (action) {
            is AnniversaryNotificationPermissionAction.EnableExisting -> {
                savedStateHandle[ANNIVERSARY_ID_KEY] = action.anniversaryId
                savedStateHandle[ACTION_KIND_KEY] = ENABLE_EXISTING_KIND
            }

            is AnniversaryNotificationPermissionAction.SaveDraft -> {
                writeDraft(action.draft)
                savedStateHandle[SHOULD_REQUEST_WIDGET_PIN_KEY] = action.shouldRequestWidgetPin
                savedStateHandle[ACTION_KIND_KEY] = SAVE_DRAFT_KIND
            }
        }
    }

    fun peek(): AnniversaryNotificationPermissionAction? {
        val action = when (savedStateHandle.get<String>(ACTION_KIND_KEY)) {
            ENABLE_EXISTING_KIND -> savedStateHandle.get<String>(ANNIVERSARY_ID_KEY)
                ?.takeIf(String::isNotBlank)
                ?.let(AnniversaryNotificationPermissionAction::EnableExisting)

            SAVE_DRAFT_KIND -> restoreDraft()?.let { draft ->
                val shouldRequestWidgetPin =
                    savedStateHandle.get<Boolean>(SHOULD_REQUEST_WIDGET_PIN_KEY)
                        ?: return@let null
                AnniversaryNotificationPermissionAction.SaveDraft(
                    draft = draft,
                    shouldRequestWidgetPin = shouldRequestWidgetPin
                )
            }

            else -> null
        }
        if (action == null) clear()
        return action
    }

    fun consume(): AnniversaryNotificationPermissionAction? = peek().also { clear() }

    private fun writeDraft(draft: AnniversaryEditorDraft) {
        draft.id?.let { savedStateHandle[DRAFT_ID_KEY] = it }
        savedStateHandle[DRAFT_TITLE_KEY] = draft.title
        savedStateHandle[DRAFT_TYPE_KEY] = draft.type.name
        savedStateHandle[DRAFT_CALENDAR_TYPE_KEY] = draft.calendarType.name
        savedStateHandle[DRAFT_YEAR_KEY] = draft.year
        savedStateHandle[DRAFT_MONTH_KEY] = draft.month
        savedStateHandle[DRAFT_DAY_KEY] = draft.day
        savedStateHandle[DRAFT_HOUR_KEY] = draft.hour
        savedStateHandle[DRAFT_MINUTE_KEY] = draft.minute
        savedStateHandle[DRAFT_SECOND_KEY] = draft.second
        savedStateHandle[DRAFT_IS_LUNAR_LEAP_MONTH_KEY] = draft.isLunarLeapMonth
        savedStateHandle[DRAFT_REPEAT_RULE_KEY] = draft.repeatRule.name
        savedStateHandle[DRAFT_ZONE_ID_KEY] = draft.zoneId
        savedStateHandle[DRAFT_IS_PINNED_KEY] = draft.isPinned
        savedStateHandle[DRAFT_SHOW_ON_WIDGET_KEY] = draft.showOnWidget
        savedStateHandle[DRAFT_SHOW_ON_LOCK_SCREEN_KEY] = draft.showOnLockScreen
    }

    private fun restoreDraft(): AnniversaryEditorDraft? {
        val type = savedStateHandle.get<String>(DRAFT_TYPE_KEY)
            ?.let { stored -> AnniversaryType.entries.firstOrNull { it.name == stored } }
            ?: return null
        val calendarType = savedStateHandle.get<String>(DRAFT_CALENDAR_TYPE_KEY)
            ?.let { stored -> AnniversaryCalendarType.entries.firstOrNull { it.name == stored } }
            ?: return null
        val repeatRule = savedStateHandle.get<String>(DRAFT_REPEAT_RULE_KEY)
            ?.let { stored -> AnniversaryRepeatRule.entries.firstOrNull { it.name == stored } }
            ?: return null
        val draft = AnniversaryEditorDraft(
            id = savedStateHandle[DRAFT_ID_KEY],
            title = savedStateHandle.get<String>(DRAFT_TITLE_KEY) ?: return null,
            type = type,
            calendarType = calendarType,
            year = savedStateHandle.get<Int>(DRAFT_YEAR_KEY) ?: return null,
            month = savedStateHandle.get<Int>(DRAFT_MONTH_KEY) ?: return null,
            day = savedStateHandle.get<Int>(DRAFT_DAY_KEY) ?: return null,
            hour = savedStateHandle.get<Int>(DRAFT_HOUR_KEY) ?: return null,
            minute = savedStateHandle.get<Int>(DRAFT_MINUTE_KEY) ?: return null,
            second = savedStateHandle.get<Int>(DRAFT_SECOND_KEY) ?: return null,
            isLunarLeapMonth = savedStateHandle.get<Boolean>(DRAFT_IS_LUNAR_LEAP_MONTH_KEY)
                ?: return null,
            repeatRule = repeatRule,
            zoneId = savedStateHandle.get<String>(DRAFT_ZONE_ID_KEY) ?: return null,
            isPinned = savedStateHandle.get<Boolean>(DRAFT_IS_PINNED_KEY) ?: return null,
            showOnWidget = savedStateHandle.get<Boolean>(DRAFT_SHOW_ON_WIDGET_KEY) ?: return null,
            showOnLockScreen = savedStateHandle.get<Boolean>(DRAFT_SHOW_ON_LOCK_SCREEN_KEY)
                ?: return null
        )
        return draft.takeIf(AnniversaryEditorDraft::isValid)
    }

    private fun clear() {
        ALL_KEYS.forEach { savedStateHandle.remove<Any>(it) }
    }

    private companion object {
        const val ACTION_KIND_KEY = "anniversary.pending_notification.kind"
        const val ANNIVERSARY_ID_KEY = "anniversary.pending_notification.id"
        const val SHOULD_REQUEST_WIDGET_PIN_KEY = "anniversary.pending_notification.request_widget"
        const val DRAFT_ID_KEY = "anniversary.pending_notification.draft.id"
        const val DRAFT_TITLE_KEY = "anniversary.pending_notification.draft.title"
        const val DRAFT_TYPE_KEY = "anniversary.pending_notification.draft.type"
        const val DRAFT_CALENDAR_TYPE_KEY = "anniversary.pending_notification.draft.calendar_type"
        const val DRAFT_YEAR_KEY = "anniversary.pending_notification.draft.year"
        const val DRAFT_MONTH_KEY = "anniversary.pending_notification.draft.month"
        const val DRAFT_DAY_KEY = "anniversary.pending_notification.draft.day"
        const val DRAFT_HOUR_KEY = "anniversary.pending_notification.draft.hour"
        const val DRAFT_MINUTE_KEY = "anniversary.pending_notification.draft.minute"
        const val DRAFT_SECOND_KEY = "anniversary.pending_notification.draft.second"
        const val DRAFT_IS_LUNAR_LEAP_MONTH_KEY = "anniversary.pending_notification.draft.leap_month"
        const val DRAFT_REPEAT_RULE_KEY = "anniversary.pending_notification.draft.repeat_rule"
        const val DRAFT_ZONE_ID_KEY = "anniversary.pending_notification.draft.zone_id"
        const val DRAFT_IS_PINNED_KEY = "anniversary.pending_notification.draft.pinned"
        const val DRAFT_SHOW_ON_WIDGET_KEY = "anniversary.pending_notification.draft.widget"
        const val DRAFT_SHOW_ON_LOCK_SCREEN_KEY = "anniversary.pending_notification.draft.lock_screen"
        const val ENABLE_EXISTING_KIND = "enable_existing"
        const val SAVE_DRAFT_KIND = "save_draft"

        val ALL_KEYS = listOf(
            ACTION_KIND_KEY,
            ANNIVERSARY_ID_KEY,
            SHOULD_REQUEST_WIDGET_PIN_KEY,
            DRAFT_ID_KEY,
            DRAFT_TITLE_KEY,
            DRAFT_TYPE_KEY,
            DRAFT_CALENDAR_TYPE_KEY,
            DRAFT_YEAR_KEY,
            DRAFT_MONTH_KEY,
            DRAFT_DAY_KEY,
            DRAFT_HOUR_KEY,
            DRAFT_MINUTE_KEY,
            DRAFT_SECOND_KEY,
            DRAFT_IS_LUNAR_LEAP_MONTH_KEY,
            DRAFT_REPEAT_RULE_KEY,
            DRAFT_ZONE_ID_KEY,
            DRAFT_IS_PINNED_KEY,
            DRAFT_SHOW_ON_WIDGET_KEY,
            DRAFT_SHOW_ON_LOCK_SCREEN_KEY
        )
    }
}
