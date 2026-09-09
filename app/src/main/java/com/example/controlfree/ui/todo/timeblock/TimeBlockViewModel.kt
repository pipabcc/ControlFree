package com.example.controlfree.ui.todo.timeblock

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.productivity.quicknote.LocalChineseQuickNoteParser
import com.example.controlfree.todo.TimeBlockEventEntity
import com.example.controlfree.todo.TimeBlockEventRepository
import com.example.controlfree.todo.TodoItemEntity
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.TodoRecurrenceType
import com.example.controlfree.todo.TodoRecurrenceCalculator
import com.example.controlfree.supervision.commitment.CommitmentScheduleReceiver
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimeBlockViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val clock: Clock = Clock.systemDefaultZone()
    private val todoRepository = TodoRepository.getInstance(application)
    private val eventRepository = TimeBlockEventRepository.getInstance(application)
    private val zoneId = clock.zone
    private val parser = LocalChineseQuickNoteParser()
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val scheduleMutationMutex = Mutex()

    private val todoEntities = todoRepository.observeAllTodos().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    private val eventEntities = eventRepository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    val entries: StateFlow<List<TimeBlockEntry>> = combine(
        todoEntities,
        eventEntities
    ) { todos, events ->
        buildList {
            todos.forEach { todo ->
                val entry = todo.toTimeBlockEntry()
                add(entry)
                val recurrence = TodoRecurrenceType.fromStoredValue(todo.recurrenceType)
                if (recurrence != TodoRecurrenceType.NONE && !todo.isCompleted &&
                    entry.scheduledStartEpochMillis != null && entry.scheduledEndEpochMillis != null) {
                    addAll(projectRecurringTodo(todo, zoneId))
                }
            }
            addAll(events.map(TimeBlockEventEntity::toTimeBlockEntry))
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _anchorDate = MutableStateFlow(LocalDate.now(clock))
    val anchorDate: StateFlow<LocalDate> = _anchorDate.asStateFlow()

    private val _viewMode = MutableStateFlow(
        TimeBlockViewMode.entries.firstOrNull {
            it.name == preferences.getString(KEY_VIEW_MODE, TimeBlockViewMode.TIMELINE.name)
        } ?: TimeBlockViewMode.TIMELINE
    )
    val viewMode: StateFlow<TimeBlockViewMode> = _viewMode.asStateFlow()

    private val _filter = MutableStateFlow(TimeBlockFilter())
    val filter: StateFlow<TimeBlockFilter> = _filter.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<TimeBlockSettings> = _settings.asStateFlow()

    val nowEpochMillis: StateFlow<Long> = flow {
        while (true) {
            emit(clock.millis())
            delay(NOW_TICK_MILLIS)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        clock.millis()
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun selectViewMode(mode: TimeBlockViewMode) {
        _viewMode.value = mode
        preferences.edit {
            putString(KEY_VIEW_MODE, mode.name)
        }
    }

    fun selectDate(date: LocalDate) {
        _anchorDate.value = date
    }

    fun selectMonth(yearMonth: YearMonth) {
        _anchorDate.value = yearMonth.atDay(
            _anchorDate.value.dayOfMonth.coerceAtMost(yearMonth.lengthOfMonth())
        )
    }

    fun selectToday() {
        _anchorDate.value = LocalDate.now(clock)
    }

    fun navigate(direction: Int) {
        _anchorDate.value = navigateAnchor(_anchorDate.value, _viewMode.value, direction)
    }

    fun setProjectVisible(project: String, visible: Boolean) {
        val universe = entries.value.mapTo(linkedSetOf(), TimeBlockEntry::project)
            .ifEmpty { primaryTimeBlockProjects.toCollection(linkedSetOf()) }
        val currentlyVisible = _filter.value.visibleProjects
            .takeIf(Set<String>::isNotEmpty)
            ?.toMutableSet()
            ?: universe.toMutableSet()
        if (visible) currentlyVisible += project else currentlyVisible -= project
        _filter.value = _filter.value.copy(
            visibleProjects = currentlyVisible.takeUnless { it == universe }.orEmpty()
        )
    }

    fun setHighPriorityOnly(enabled: Boolean) {
        _filter.value = _filter.value.copy(highPriorityOnly = enabled)
    }

    fun setHideCompleted(enabled: Boolean) {
        _filter.value = _filter.value.copy(hideCompleted = enabled)
    }

    fun updateSettings(value: TimeBlockSettings) {
        _settings.value = value
        preferences.edit {
            putBoolean(KEY_SHADE_WEEKENDS, value.shadeWeekends)
            putBoolean(KEY_HALF_HOUR_GRID, value.showHalfHourGrid)
            putBoolean(KEY_COMPLETION_ANIMATION, value.completionAnimation)
        }
    }

    fun parseNaturalLanguage(rawInput: String): TimeBlockNaturalLanguageSuggestion {
        val now = ZonedDateTime.now(clock)
        val parsed = parser.parse(rawInput, now)
        val start = parsed.startAtEpochMillis?.let {
            Instant.ofEpochMilli(it).atZone(zoneId)
        }
        val date = start?.toLocalDate()
            ?: parsed.dueAtEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            ?: _anchorDate.value
        val startMinute = start?.toLocalTime()?.toSecondOfDay()?.div(60)
        val duration = parsed.estimatedDurationMinutes ?: DEFAULT_EVENT_DURATION_MINUTES
        val endMinute = startMinute?.plus(duration)?.coerceAtMost(MINUTES_PER_DAY)
        val parts = buildList {
            if (date != _anchorDate.value || parsed.startAtEpochMillis != null || parsed.dueAtEpochMillis != null) {
                add(date.format(NATURAL_DATE_FORMATTER))
            }
            startMinute?.let {
                add("${formatNaturalLanguageMinute(it)}–${formatNaturalLanguageMinute(requireNotNull(endMinute))}")
            }
        }
        return TimeBlockNaturalLanguageSuggestion(
            title = parsed.title,
            date = date,
            startMinute = startMinute,
            endMinuteExclusive = endMinute,
            recognized = parts.isNotEmpty(),
            summary = parts.joinToString(" · ")
        )
    }

    fun saveDraft(draft: TimeBlockEditorDraft) = launchAction("保存失败") {
        when (draft.editorType) {
            TimeBlockEditorType.TASK -> saveTaskDraft(draft)
            TimeBlockEditorType.EVENT -> saveScheduledTaskDraft(draft)
        }
        _messages.emit("已保存到日历")
    }

    private fun getBaseTodoId(id: String): String {
        return if (id.contains("_recur_")) id.substringBefore("_recur_") else id
    }

    fun toggleCompletion(entry: TimeBlockEntry) = launchAction("更新完成状态失败") {
        val baseId = getBaseTodoId(entry.id)
        when (entry.source) {
            TimeBlockSource.TODO -> todoRepository.toggleTodoCompletion(baseId, !entry.isCompleted)
            TimeBlockSource.EVENT -> eventRepository.toggleCompletion(baseId, !entry.isCompleted)
        }
    }

    fun delete(entry: TimeBlockEntry) = launchAction("删除失败") {
        val baseId = getBaseTodoId(entry.id)
        when (entry.source) {
            TimeBlockSource.TODO -> todoRepository.deleteTodo(baseId)
            TimeBlockSource.EVENT -> eventRepository.delete(baseId)
        }
        _messages.emit("已删除")
    }

    fun deleteWithUndo(
        entry: TimeBlockEntry,
        snackbarHostState: androidx.compose.material3.SnackbarHostState
    ) {
        val baseId = getBaseTodoId(entry.id)
        viewModelScope.launch {
            try {
                when (entry.source) {
                    TimeBlockSource.TODO -> {
                        val snapshot = todoRepository.deleteTodoForUndo(baseId)
                        if (snapshot == null) {
                            snackbarHostState.showSnackbar("删除失败，日程可能已不存在")
                            return@launch
                        }
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除「${entry.title}」",
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = androidx.compose.material3.SnackbarDuration.Short
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            val restored = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                todoRepository.restoreDeletedTodo(snapshot)
                            }
                            snackbarHostState.showSnackbar(
                                if (restored) "已撤销删除" else "撤销失败"
                            )
                        }
                    }
                    TimeBlockSource.EVENT -> {
                        val deletedEvent = eventRepository.delete(entry.id)
                        if (deletedEvent == null) {
                            snackbarHostState.showSnackbar("删除失败，日程可能已不存在")
                            return@launch
                        }
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除「${entry.title}」",
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = androidx.compose.material3.SnackbarDuration.Short
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                eventRepository.save(deletedEvent)
                            }
                            snackbarHostState.showSnackbar("已撤销删除")
                        }
                    }
                }
            } catch (error: Exception) {
                snackbarHostState.showSnackbar("删除失败：${error.message}")
            }
        }
    }

    fun updateDetails(
        entry: TimeBlockEntry,
        title: String,
        project: String,
        highPriority: Boolean,
        description: String
    ) = launchAction("保存详情失败") {
        val baseId = getBaseTodoId(entry.id)
        val normalizedTitle = title.trim().ifEmpty { entry.title }
        val normalizedDesc = description.trim().takeIf { it.isNotEmpty() }
        when (entry.source) {
            TimeBlockSource.TODO -> {
                val current = todoRepository.getTodoById(baseId) ?: return@launchAction
                todoRepository.saveTodo(
                    current.copy(
                        title = normalizedTitle,
                        category = project,
                        priority = if (highPriority) 2 else 0,
                        isImportant = highPriority,
                        description = normalizedDesc,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
            TimeBlockSource.EVENT -> {
                val current = eventRepository.getById(entry.id) ?: return@launchAction
                eventRepository.save(
                    current.copy(
                        title = normalizedTitle,
                        project = project,
                        priority = if (highPriority) 2 else 0,
                        description = normalizedDesc,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
        }
    }

    fun moveToDate(entry: TimeBlockEntry, targetDate: LocalDate) = launchScheduleAction("移动失败") action@{
        val baseId = getBaseTodoId(entry.id)
        when (entry.source) {
            TimeBlockSource.EVENT -> {
                val current = eventRepository.getById(entry.id) ?: return@action
                val start = Instant.ofEpochMilli(current.startAtEpochMillis).atZone(zoneId)
                val duration = current.endAtEpochMillis - current.startAtEpochMillis
                val newStart = targetDate.atTime(start.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
                eventRepository.save(
                    current.copy(
                        startAtEpochMillis = newStart,
                        endAtEpochMillis = newStart + duration,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
            TimeBlockSource.TODO -> {
                val current = todoRepository.getTodoById(baseId) ?: return@action
                todoRepository.saveTodo(current.movedToDate(targetDate, zoneId, clock.millis()))
                requestScheduleReconciliation("calendar_todo_moved")
            }
        }
    }

    fun moveToTime(
        entry: TimeBlockEntry,
        targetDate: LocalDate,
        startMinute: Int,
        durationMinutes: Int
    ) = launchScheduleAction("排程失败") action@{
        val baseId = getBaseTodoId(entry.id)
        val snappedStart = snapMinute(startMinute).coerceAtMost(MINUTES_PER_DAY - TIME_SNAP_MINUTES)
        val safeDuration = snapMinute(durationMinutes.coerceAtLeast(TIME_SNAP_MINUTES))
            .coerceAtLeast(TIME_SNAP_MINUTES)
        val endMinute = (snappedStart + safeDuration).coerceAtMost(MINUTES_PER_DAY)
        val startAt = epochMillis(targetDate, snappedStart, zoneId)
        val endAt = epochMillis(targetDate, endMinute, zoneId)
        when (entry.source) {
            TimeBlockSource.EVENT -> {
                val current = eventRepository.getById(entry.id) ?: return@action
                eventRepository.save(
                    current.copy(
                        startAtEpochMillis = startAt,
                        endAtEpochMillis = endAt,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
            TimeBlockSource.TODO -> {
                val current = todoRepository.getTodoById(baseId) ?: return@action
                todoRepository.saveTodo(
                    current.copy(
                        scheduledStartEpochMillis = startAt,
                        scheduledEndEpochMillis = endAt,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
        }
    }

    fun moveAllDayToTime(
        entry: TimeBlockEntry,
        targetDate: LocalDate,
        dropMinute: Int
    ) = launchScheduleAction("排程失败") action@{
        if (entry.source != TimeBlockSource.TODO) return@action
        val current = todoRepository.getTodoById(getBaseTodoId(entry.id)) ?: return@action
        val window = timeBlockDropWindow(
            dropMinute = dropMinute,
            estimatedDurationMinutes = current.estimatedFocusMinutes ?: entry.estimatedFocusMinutes
        )
        val startAt = epochMillis(targetDate, window.startMinute, zoneId)
        val endAt = epochMillis(targetDate, window.endMinuteExclusive, zoneId)
        val dueAt = targetDate.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli()
        todoRepository.saveTodo(
            current.copy(
                dueDateEpochMillis = dueAt,
                scheduledStartEpochMillis = startAt,
                scheduledEndEpochMillis = endAt,
                updatedAtEpochMillis = clock.millis()
            )
        )
        requestScheduleReconciliation("calendar_all_day_scheduled")
    }

    fun resize(entry: TimeBlockEntry, endMinuteExclusive: Int) = launchScheduleAction("调整时长失败") action@{
        val baseId = getBaseTodoId(entry.id)
        when (entry.source) {
            TimeBlockSource.TODO -> {
                val current = todoRepository.getTodoById(baseId) ?: return@action
                val endAt = resizedEndAt(
                    startAt = current.scheduledStartEpochMillis ?: return@action,
                    requestedEndMinuteExclusive = endMinuteExclusive
                )
                todoRepository.saveTodo(
                    current.copy(
                        scheduledEndEpochMillis = endAt,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
            TimeBlockSource.EVENT -> {
                val current = eventRepository.getById(entry.id) ?: return@action
                val endAt = resizedEndAt(
                    startAt = current.startAtEpochMillis,
                    requestedEndMinuteExclusive = endMinuteExclusive
                )
                eventRepository.save(
                    current.copy(
                        endAtEpochMillis = endAt,
                        updatedAtEpochMillis = clock.millis()
                    )
                )
            }
        }
    }

    private fun resizedEndAt(startAt: Long, requestedEndMinuteExclusive: Int): Long {
        val start = Instant.ofEpochMilli(startAt).atZone(zoneId)
        val startMinute = start.toLocalTime().toSecondOfDay() / 60
        val normalizedEnd = timeBlockResizeEndMinute(startMinute, requestedEndMinuteExclusive)
        return epochMillis(start.toLocalDate(), normalizedEnd, zoneId)
    }

    fun shiftOneDay(entry: TimeBlockEntry) {
        val currentDate = entry.primaryDate(zoneId) ?: LocalDate.now(clock)
        moveToDate(entry, currentDate.plusDays(1))
    }

    fun scheduleInboxOnDate(todoId: String, date: LocalDate) = launchScheduleAction("安排失败") action@{
        val current = todoRepository.getTodoById(todoId) ?: return@action
        todoRepository.saveTodo(
            current.copy(
                dueDateEpochMillis = date.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli(),
                scheduledStartEpochMillis = null,
                scheduledEndEpochMillis = null,
                updatedAtEpochMillis = clock.millis()
            )
        )
        requestScheduleReconciliation("calendar_inbox_scheduled")
    }

    fun fillTodayGaps() = launchScheduleAction("填充空档失败") {
        val today = LocalDate.now(clock)
        val inbox = entries.value
            .filter { it.kind(zoneId) == TimeBlockEntryKind.INBOX_TASK && !it.isCompleted }
            .sortedWith(compareByDescending(TimeBlockEntry::isHighPriority).thenBy(TimeBlockEntry::createdAtEpochMillis))
        val gaps = freeGaps(
            date = today,
            entries = entries.value,
            zoneId = zoneId,
            nowEpochMillis = clock.millis()
        )
        val count = minOf(inbox.size, gaps.size)
        for (index in 0 until count) {
            val gap = gaps[index]
            val current = todoRepository.getTodoById(inbox[index].id) ?: continue
            val startAt = epochMillis(today, gap.startMinute, zoneId)
            val endAt = epochMillis(
                today,
                minOf(gap.startMinute + TIME_SNAP_MINUTES, gap.endMinuteExclusive),
                zoneId
            )
            todoRepository.saveTodo(
                current.copy(
                    scheduledStartEpochMillis = startAt,
                    scheduledEndEpochMillis = endAt,
                    updatedAtEpochMillis = clock.millis()
                )
            )
        }
        _messages.emit(if (count == 0) "暂无可填充的空档" else "已排入 $count 项待办")
    }

    fun moveExpiredToToday() = launchScheduleAction("顺延失败") {
        val now = clock.millis()
        val today = LocalDate.now(clock)
        val expired = todoEntities.value.filter { todo ->
            val primaryDate = (todo.scheduledStartEpochMillis ?: todo.dueDateEpochMillis)
                ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            !todo.isCompleted && primaryDate != null && primaryDate < today
        }
        expired.forEach { todo ->
            todoRepository.saveTodo(todo.movedToDate(today, zoneId, now))
        }
        if (expired.isNotEmpty()) {
            requestScheduleReconciliation("calendar_expired_moved")
        }
        _messages.emit(if (expired.isEmpty()) "没有过期待办" else "已将 ${expired.size} 项顺延到今天")
    }

    private fun requestScheduleReconciliation(reason: String) {
        val application = getApplication<Application>()
        CommitmentScheduleReceiver.requestReconciliation(application, reason)
        SupervisionScheduleReceiver.requestReconciliation(application, reason)
    }

    private suspend fun saveTaskDraft(draft: TimeBlockEditorDraft) {
        val now = clock.millis()
        val current = draft.id?.let { todoRepository.getTodoById(it) }
        val dueAt = if (draft.allDay) {
            draft.date.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            epochMillis(draft.date, draft.startMinute, zoneId)
        }
        val candidate = current?.copy(
            title = draft.title.trim().ifEmpty { "新待办" },
            description = draft.description.trim().takeIf(String::isNotEmpty),
            dueDateEpochMillis = dueAt,
            category = draft.project,
            priority = if (draft.highPriority) 2 else 0,
            isImportant = draft.highPriority,
            updatedAtEpochMillis = now
        ) ?: TodoItemEntity(
            id = draft.id ?: UUID.randomUUID().toString(),
            title = draft.title.trim().ifEmpty { "新待办" },
            description = draft.description.trim().takeIf(String::isNotEmpty),
            dueDateEpochMillis = dueAt,
            priority = if (draft.highPriority) 2 else 0,
            isCompleted = false,
            completedAtEpochMillis = null,
            category = draft.project,
            repeatRule = null,
            associatedFocusPlanId = null,
            supervisionLockEnabled = false,
            createdAtEpochMillis = now,
            estimatedFocusMinutes = DEFAULT_TASK_FOCUS_MINUTES,
            isImportant = draft.highPriority,
            updatedAtEpochMillis = now
        )
        todoRepository.saveTodo(candidate)
    }

    private suspend fun saveScheduledTaskDraft(draft: TimeBlockEditorDraft) {
        val now = clock.millis()
        val current = draft.id?.let { todoRepository.getTodoById(it) }
        val startAt = epochMillis(draft.date, draft.startMinute, zoneId)
        val endAt = epochMillis(draft.date, draft.endMinuteExclusive, zoneId)
        val durationMinutes = ((endAt - startAt) / 60_000L).toInt().coerceIn(1, 180)
        val candidate = current?.copy(
            title = draft.title.trim().ifEmpty { "新待办" },
            description = draft.description.trim().takeIf(String::isNotEmpty),
            dueDateEpochMillis = null,
            category = draft.project,
            priority = if (draft.highPriority) 2 else 0,
            scheduledStartEpochMillis = startAt,
            scheduledEndEpochMillis = endAt,
            estimatedFocusMinutes = durationMinutes,
            isImportant = draft.highPriority,
            updatedAtEpochMillis = now
        ) ?: TodoItemEntity(
            id = draft.id ?: UUID.randomUUID().toString(),
            title = draft.title.trim().ifEmpty { "新待办" },
            description = draft.description.trim().takeIf(String::isNotEmpty),
            dueDateEpochMillis = null,
            priority = if (draft.highPriority) 2 else 0,
            isCompleted = false,
            completedAtEpochMillis = null,
            category = draft.project,
            repeatRule = null,
            associatedFocusPlanId = null,
            supervisionLockEnabled = false,
            createdAtEpochMillis = now,
            scheduledStartEpochMillis = startAt,
            scheduledEndEpochMillis = endAt,
            estimatedFocusMinutes = durationMinutes,
            isImportant = draft.highPriority,
            updatedAtEpochMillis = now
        )
        todoRepository.saveTodo(candidate)
    }

    private fun launchAction(errorMessage: String, action: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _messages.emit(errorMessage)
            }
        }

    private fun launchScheduleAction(errorMessage: String, action: suspend () -> Unit) =
        launchAction(errorMessage) {
            scheduleMutationMutex.withLock { action() }
        }

    private fun readSettings() = TimeBlockSettings(
        shadeWeekends = preferences.getBoolean(KEY_SHADE_WEEKENDS, true),
        showHalfHourGrid = preferences.getBoolean(KEY_HALF_HOUR_GRID, true),
        completionAnimation = preferences.getBoolean(KEY_COMPLETION_ANIMATION, true)
    )

    companion object {
        private const val PREFERENCES_NAME = "time_block_settings"
        private const val KEY_VIEW_MODE = "schedule_view_mode"
        private const val KEY_SHADE_WEEKENDS = "shade_weekends"
        private const val KEY_HALF_HOUR_GRID = "half_hour_grid"
        private const val KEY_COMPLETION_ANIMATION = "completion_animation"
        private const val NOW_TICK_MILLIS = 30_000L
        private const val DEFAULT_EVENT_DURATION_MINUTES = 60
        private const val DEFAULT_TASK_FOCUS_MINUTES = 25
        private val NATURAL_DATE_FORMATTER = DateTimeFormatter.ofPattern("M月d日 E")
    }
}

internal fun TodoItemEntity.toTimeBlockEntry(): TimeBlockEntry {
    val hasValidSchedule = scheduledStartEpochMillis != null &&
        scheduledEndEpochMillis != null &&
        scheduledEndEpochMillis > scheduledStartEpochMillis
    return TimeBlockEntry(
        source = TimeBlockSource.TODO,
        id = id,
        title = title,
        description = description,
        project = category.ifBlank { "默认" },
        priority = maxOf(priority, if (isImportant) 2 else 0),
        isCompleted = isCompleted,
        completedAtEpochMillis = completedAtEpochMillis,
        scheduledStartEpochMillis = scheduledStartEpochMillis.takeIf { hasValidSchedule },
        scheduledEndEpochMillis = scheduledEndEpochMillis.takeIf { hasValidSchedule },
        dueAtEpochMillis = dueDateEpochMillis,
        estimatedFocusMinutes = estimatedFocusMinutes ?: 25,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis
    )
}

private fun TimeBlockEventEntity.toTimeBlockEntry() = TimeBlockEntry(
    source = TimeBlockSource.EVENT,
    id = id,
    title = title,
    description = description,
    project = project,
    priority = priority,
    isCompleted = isCompleted,
    completedAtEpochMillis = completedAtEpochMillis,
    scheduledStartEpochMillis = startAtEpochMillis,
    scheduledEndEpochMillis = endAtEpochMillis,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    estimatedFocusMinutes = ((endAtEpochMillis - startAtEpochMillis) / 60_000L)
        .toInt()
        .coerceIn(1, 180)
)

private fun TodoItemEntity.movedToDate(
    targetDate: LocalDate,
    zoneId: ZoneId,
    updatedAt: Long
): TodoItemEntity {
    val start = scheduledStartEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) }
    val end = scheduledEndEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) }
    val movedSchedule = if (start != null && end != null) {
        val duration = scheduledEndEpochMillis - scheduledStartEpochMillis
        val movedStart = targetDate.atTime(start.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
        movedStart to movedStart + duration
    } else {
        null
    }
    val due = dueDateEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) }
    return copy(
        scheduledStartEpochMillis = movedSchedule?.first,
        scheduledEndEpochMillis = movedSchedule?.second,
        dueDateEpochMillis = due?.let {
            targetDate.atTime(it.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
        },
        updatedAtEpochMillis = updatedAt
    )
}

private fun formatNaturalLanguageMinute(minute: Int): String {
    val safe = minute.coerceIn(0, MINUTES_PER_DAY)
    if (safe == MINUTES_PER_DAY) return "24:00"
    return "%02d:%02d".format(safe / 60, safe % 60)
}

private fun projectRecurringTodo(
    todo: TodoItemEntity,
    zoneId: ZoneId,
    maxDays: Int = 90
): List<TimeBlockEntry> {
    val result = mutableListOf<TimeBlockEntry>()
    val maxFutureDate = LocalDate.now(zoneId).plusDays(maxDays.toLong())
    var currentTodo = todo
    var iterations = 0
    while (iterations < 100) {
        val startMillis = currentTodo.scheduledStartEpochMillis ?: break
        val startZoned = Instant.ofEpochMilli(startMillis).atZone(zoneId)
        if (startZoned.toLocalDate().isAfter(maxFutureDate)) {
            break
        }
        val nextId = "${currentTodo.id}_recur"
        val nextTodo = TodoRecurrenceCalculator.nextOccurrence(
            todo = currentTodo,
            completedAt = Instant.ofEpochMilli(startMillis),
            zoneId = zoneId,
            nextId = nextId,
            createdAtEpochMillis = startMillis
        ) ?: break
        
        val occurrenceStart = nextTodo.scheduledStartEpochMillis ?: break
        val occurrenceDate = Instant.ofEpochMilli(occurrenceStart).atZone(zoneId).toLocalDate()
        val projectedEntry = nextTodo.copy(id = "${todo.id}_recur_$occurrenceDate").toTimeBlockEntry()
        result.add(projectedEntry)
        
        currentTodo = nextTodo
        iterations++
    }
    return result
}
