package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.ai.AiAdviceSource
import com.example.controlfree.ai.ProductivityAiCoordinator
import com.example.controlfree.productivity.calendar.CalendarDaySummary
import com.example.controlfree.productivity.calendar.CalendarDateRange
import com.example.controlfree.productivity.calendar.CalendarEventType
import com.example.controlfree.productivity.calendar.CalendarRangeStatistics
import com.example.controlfree.productivity.calendar.LedgerCalendarEventDetails
import com.example.controlfree.productivity.calendar.ProductivityCalendarEvent
import com.example.controlfree.productivity.calendar.SupervisionCalendarEventDetails
import com.example.controlfree.productivity.calendar.UnifiedCalendarAggregator
import com.example.controlfree.productivity.schedule.BusyInterval
import com.example.controlfree.productivity.schedule.FocusHistorySample
import com.example.controlfree.productivity.schedule.HabitHistorySample
import com.example.controlfree.productivity.schedule.LocalScheduleRecommender
import com.example.controlfree.productivity.schedule.SupervisionBusyIntervalFactory
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.supervision.persistence.OneTimeFocusScheduleResult
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.runtime.SupervisionAlarmPrecision
import com.example.controlfree.supervision.runtime.SupervisionAlarmScheduler
import com.example.controlfree.supervision.runtime.SupervisionReconciliationResult
import com.example.controlfree.supervision.runtime.SupervisionScheduleCoordinator
import com.example.controlfree.supervision.persistence.SupervisionSessionEntity
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.AnniversaryOccurrenceCalculator
import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.HabitScheduleCalculator
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.todo.TodoItemEntity
import com.example.controlfree.todo.TodoRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UnifiedCalendarUiState(
    val month: YearMonth,
    val selectedDate: LocalDate,
    val days: Map<LocalDate, CalendarDaySummary>,
    val nowEpochMillis: Long,
    val today: LocalDate,
    val visibleRange: CalendarDateRange
) {
    val selectedDay: CalendarDaySummary?
        get() = days[selectedDate]

    fun statistics(range: CalendarDateRange): CalendarRangeStatistics =
        UnifiedCalendarAggregator.statistics(days, range)
}

data class SmartScheduleSuggestionUi(
    val todoId: String,
    val expectedTodoUpdatedAtEpochMillis: Long,
    val todoTitle: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val reason: String,
    val isAiEnhanced: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedCalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TodoRepository.getInstance(application)
    private val supervisionPlanRepository = SupervisionPlanRepository.getInstance(application)
    private val historyDao = ControlFreeDatabase.getInstance(application).supervisionHistoryDao()
    private val aiCoordinator = ProductivityAiCoordinator.getInstance(application)
    val clock: Clock = Clock.systemDefaultZone()
    private val zoneId: ZoneId = clock.zone

    private val _selectedMonth = MutableStateFlow(YearMonth.now(clock))
    val selectedMonth = _selectedMonth.asStateFlow()
    private val _selectedDate = MutableStateFlow(LocalDate.now(clock))
    val selectedDate = _selectedDate.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val nowTicker = flow {
        while (true) {
            emit(clock.millis())
            delay(CALENDAR_TICK_MILLIS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.millis())
    private val calendarRange = nowTicker
        .map { epochMillis ->
            val today = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
            calendarDataRange(today)
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            calendarDataRange(LocalDate.now(clock))
        )

    val todos: StateFlow<List<TodoItemEntity>> = repository.observeAllTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val habits = repository.observeAllHabits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val habitRecords = calendarRange
        .flatMapLatest { range ->
            repository.observeHabitRecords(
                startDate = range.startDate.toString(),
                endDate = range.endDateInclusive.toString()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val anniversaries: StateFlow<List<AnniversaryItemEntity>> = repository.observeAllAnniversaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val focusHistory: StateFlow<List<SupervisionSessionEntity>> = calendarRange
        .flatMapLatest { range ->
            historyDao.observeOverlapping(
                startEpochMillis = range.startEpochMillis(zoneId),
                endExclusiveEpochMillis = range.endExclusiveEpochMillis(zoneId)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val ledgerEntries: StateFlow<List<LedgerEntryEntity>> = calendarRange
        .flatMapLatest { range ->
            repository.observeLedgerEntriesBetween(
                startEpochMillis = range.startEpochMillis(zoneId),
                endExclusiveEpochMillis = range.endExclusiveEpochMillis(zoneId)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val recommendationDate = nowTicker
        .map { epochMillis -> Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate() }
        .distinctUntilChanged()
    private val recommendationFocusHistory: StateFlow<List<SupervisionSessionEntity>> =
        recommendationDate
            .flatMapLatest { today ->
                historyDao.observeOverlapping(
                    startEpochMillis = today.minusDays(RECOMMENDATION_HISTORY_DAYS)
                        .atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    endExclusiveEpochMillis = today.plusDays(1)
                        .atStartOfDay(zoneId).toInstant().toEpochMilli()
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val recommendationHabitRecords = recommendationDate
        .flatMapLatest { today ->
            repository.observeHabitRecords(
                startDate = today.minusDays(RECOMMENDATION_HISTORY_DAYS).toString(),
                endDate = today.toString()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val recommendationPlans = supervisionPlanRepository.observePlans()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PlanLoadResult.StorageFailure
        )

    private val calendarSources = combine(
        combine(
            todos,
            habits,
            habitRecords,
            anniversaries,
            focusHistory
        ) { todoItems, habitItems, records, anniversaryItems, sessions ->
            CalendarSources(todoItems, habitItems, records, anniversaryItems, sessions, emptyList())
        },
        ledgerEntries
    ) { sources, entries ->
        sources.copy(ledgerEntries = entries)
    }

    val uiState: StateFlow<UnifiedCalendarUiState> = combine(
        calendarSources,
        _selectedMonth,
        _selectedDate,
        nowTicker,
        calendarRange
    ) { sources, month, selectedDate, nowEpochMillis, visibleRange ->
        val events = UnifiedCalendarEventFactory.create(
            range = visibleRange,
            todos = sources.todos,
            habits = sources.habits,
            habitRecords = sources.habitRecords,
            anniversaries = sources.anniversaries,
            sessions = sources.sessions,
            ledgerEntries = sources.ledgerEntries,
            zoneId = zoneId
        )
        UnifiedCalendarUiState(
            month = month,
            selectedDate = selectedDate.coerceTo(month),
            days = UnifiedCalendarAggregator.summarize(
                range = visibleRange,
                events = events,
                zoneId = zoneId,
                activeSessionEndEpochMillis = nowEpochMillis
            ),
            nowEpochMillis = nowEpochMillis,
            today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate(),
            visibleRange = visibleRange
        )
    }
        // 事件工厂与汇总要遍历约 38 天 × 全部实体；每个 tick 都会执行，
        // 移到 Default 调度器避免占用主线程。
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UnifiedCalendarUiState(
            month = _selectedMonth.value,
            selectedDate = _selectedDate.value,
            days = emptyMap(),
            nowEpochMillis = clock.millis(),
            today = LocalDate.now(clock),
            visibleRange = calendarDataRange(LocalDate.now(clock))
        )
    )

    private val ignoredSuggestionTodoIds = MutableStateFlow<Set<String>>(emptySet())

    @Suppress("UNCHECKED_CAST")
    private val rawScheduleSuggestion: StateFlow<SmartScheduleSuggestionUi?> = combine(
        todos,
        habits,
        recommendationHabitRecords,
        recommendationFocusHistory,
        recommendationPlans,
        ignoredSuggestionTodoIds
    ) { array ->
        val todoItems = array[0] as List<TodoItemEntity>
        val habitItems = array[1] as List<HabitItemEntity>
        val records = array[2] as List<HabitRecordEntity>
        val sessions = array[3] as List<SupervisionSessionEntity>
        val planLoadResult = array[4] as PlanLoadResult
        val ignoredIds = array[5] as Set<String>
        ScheduleSources(todoItems, habitItems, records, sessions, planLoadResult, ignoredIds)
    }
        .mapLatest { sources -> buildScheduleSuggestion(sources) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scheduleSuggestion: StateFlow<SmartScheduleSuggestionUi?> = combine(
        rawScheduleSuggestion,
        nowTicker
    ) { suggestion, nowEpochMillis ->
        activeScheduleSuggestion(suggestion, nowEpochMillis)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissScheduleSuggestion(todoId: String) {
        ignoredSuggestionTodoIds.value = ignoredSuggestionTodoIds.value + todoId
    }

    fun selectDate(date: LocalDate) {
        _selectedMonth.value = YearMonth.from(date)
        _selectedDate.value = date
    }

    fun selectDate(date: String) {
        runCatching { LocalDate.parse(date) }
            .onSuccess { parsedDate -> selectDate(parsedDate) }
            .onFailure { _messages.tryEmit("日期无效") }
    }

    fun previousMonth() = moveMonth(-1)

    fun nextMonth() = moveMonth(1)

    fun selectToday() = selectDate(LocalDate.now(clock))

    fun applyScheduleSuggestion(suggestion: SmartScheduleSuggestionUi) {
        val nowEpochMillis = clock.millis()
        if (activeScheduleSuggestion(suggestion, nowEpochMillis) == null) {
            _messages.tryEmit("推荐时段已过期，请等待刷新后重试")
            return
        }
        viewModelScope.launch {
            when (
                val result = supervisionPlanRepository.scheduleOneTimeFocusForTodo(
                    todoId = suggestion.todoId,
                    expectedTodoUpdatedAtEpochMillis =
                        suggestion.expectedTodoUpdatedAtEpochMillis,
                    startEpochMillis = suggestion.startEpochMillis,
                    endEpochMillis = suggestion.endEpochMillis,
                    nowEpochMillis = nowEpochMillis
                )
                ) {
                is OneTimeFocusScheduleResult.Success -> {
                    val reconciliation = try {
                        SupervisionScheduleCoordinator(
                            context = getApplication(),
                            clock = clock
                        ).reconcile()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        runCatching {
                            SupervisionAlarmScheduler(getApplication()).scheduleRetry()
                        }
                        null
                    }
                    _messages.tryEmit(
                        when {
                            reconciliation is SupervisionReconciliationResult.Completed &&
                                reconciliation.alarmPrecision == SupervisionAlarmPrecision.INEXACT ->
                                "已安排一次性专注锁（系统省电模式下可能稍有延迟）"
                            reconciliation !is SupervisionReconciliationResult.Completed ->
                                "专注计划已保存，系统将自动重试调度"
                            result.wasCreated -> "已安排一次性专注锁"
                            else -> "专注计划已存在，未重复创建"
                        }
                    )
                    selectDate(
                        Instant.ofEpochMilli(suggestion.startEpochMillis)
                            .atZone(zoneId)
                            .toLocalDate()
                    )
                }
                is OneTimeFocusScheduleResult.Conflicts -> {
                    _messages.tryEmit(oneTimeFocusScheduleConflictMessage())
                }
                is OneTimeFocusScheduleResult.InvalidInput -> _messages.tryEmit(result.reason)
                is OneTimeFocusScheduleResult.TodoNotFound -> _messages.tryEmit("待办已被删除")
                is OneTimeFocusScheduleResult.StaleTodo ->
                    _messages.tryEmit("待办已发生变化，请使用最新建议")
                is OneTimeFocusScheduleResult.CorruptData -> _messages.tryEmit("监督计划数据损坏，请先修复")
                OneTimeFocusScheduleResult.StorageFailure -> _messages.tryEmit("安排失败，请稍后重试")
            }
        }
    }

    private fun moveMonth(offset: Long) {
        val month = _selectedMonth.value.plusMonths(offset)
        _selectedMonth.value = month
        _selectedDate.value = _selectedDate.value.coerceTo(month)
    }

    private suspend fun buildScheduleSuggestion(sources: ScheduleSources): SmartScheduleSuggestionUi? {
        val supervisionPlans = (sources.planLoadResult as? PlanLoadResult.Success)?.plans
            ?: return null
        val todoItems = sources.todos
        val todo = todoItems.asSequence()
            .filterNot(TodoItemEntity::isCompleted)
            .filter { it.id !in sources.ignoredIds }
            .filter { it.scheduledStartEpochMillis == null || it.scheduledEndEpochMillis == null }
            .sortedWith(
                compareByDescending<TodoItemEntity> { it.isImportant || it.priority >= 2 }
                    .thenBy { it.dueDateEpochMillis ?: Long.MAX_VALUE }
                    .thenByDescending(TodoItemEntity::priority)
            )
            .firstOrNull() ?: return null
        val now = clock.millis()
        val deadline = todo.dueDateEpochMillis?.takeIf { it > now }
        val horizonEnd = Instant.ofEpochMilli(now)
            .atZone(zoneId)
            .plusDays(DEFAULT_RECOMMENDATION_DAYS)
            .toInstant()
            .toEpochMilli()
        val searchEnd = minOf(deadline ?: horizonEnd, horizonEnd)
        val focusSessions = sources.sessions.filter(SupervisionSessionEntity::isFocusSession)
        val candidates = withContext(Dispatchers.Default) {
            val supervisionBusyIntervals = SupervisionBusyIntervalFactory.create(
                plans = supervisionPlans,
                startEpochMillis = now,
                endExclusiveEpochMillis = searchEnd,
                deviceZoneId = zoneId
            )
            LocalScheduleRecommender.recommend(
                nowEpochMillis = now,
                deadlineEpochMillis = deadline,
                durationMinutes = todo.estimatedFocusMinutes?.coerceIn(1, 480) ?: 25,
                focusHistory = focusSessions.mapNotNull { session ->
                    val end = session.endedAtEpochMillis ?: return@mapNotNull null
                    FocusHistorySample(
                        startedAtEpochMillis = session.startedAtEpochMillis,
                        endedAtEpochMillis = end,
                        completed = session.endReason == "completed"
                    )
                },
                habitHistory = HabitHistorySampleFactory.create(
                    habits = sources.habits,
                    records = sources.habitRecords
                ),
                busyIntervals = todoItems.mapNotNull { item ->
                    val start = item.scheduledStartEpochMillis ?: return@mapNotNull null
                    val end = item.scheduledEndEpochMillis ?: return@mapNotNull null
                    if (end <= now || end <= start) null else BusyInterval(start, end)
                } + supervisionBusyIntervals,
                zoneId = zoneId
            )
        }
        val suggestion = aiCoordinator.recommendSchedule(candidates) ?: return null
        return SmartScheduleSuggestionUi(
            todoId = todo.id,
            expectedTodoUpdatedAtEpochMillis = todo.updatedAtEpochMillis,
            todoTitle = todo.title,
            startEpochMillis = suggestion.candidate.startEpochMillis,
            endEpochMillis = suggestion.candidate.endEpochMillis,
            reason = explainScheduleSuggestion(suggestion.candidate.reason, suggestion.reason),
            isAiEnhanced = suggestion.source == AiAdviceSource.DEEPSEEK
        )
    }

    private fun explainScheduleSuggestion(baseReason: String, aiReason: String): String {
        val normalizedBase = baseReason.trim()
        val normalizedAi = aiReason.trim()
        if (normalizedBase.isEmpty()) return normalizedAi
        if (normalizedAi.isEmpty() || normalizedAi == normalizedBase) return normalizedBase
        return "$normalizedBase；$normalizedAi"
    }

    private data class CalendarSources(
        val todos: List<TodoItemEntity>,
        val habits: List<HabitItemEntity>,
        val habitRecords: List<HabitRecordEntity>,
        val anniversaries: List<AnniversaryItemEntity>,
        val sessions: List<SupervisionSessionEntity>,
        val ledgerEntries: List<LedgerEntryEntity>
    )

    private data class ScheduleSources(
        val todos: List<TodoItemEntity>,
        val habits: List<HabitItemEntity>,
        val habitRecords: List<HabitRecordEntity>,
        val sessions: List<SupervisionSessionEntity>,
        val planLoadResult: PlanLoadResult,
        val ignoredIds: Set<String>
    )

}

internal fun calendarDataRange(today: LocalDate): CalendarDateRange = CalendarDateRange(
    startDate = today.minusDays(CALENDAR_HISTORY_BUFFER_DAYS),
    endDateInclusive = today.plusDays(DEFAULT_RECOMMENDATION_DAYS)
)

internal fun oneTimeFocusScheduleConflictMessage(): String =
    "该推荐时段已被监督或专注锁占用，请等待刷新后重试"

internal fun activeScheduleSuggestion(
    suggestion: SmartScheduleSuggestionUi?,
    nowEpochMillis: Long
): SmartScheduleSuggestionUi? = suggestion?.takeIf { it.startEpochMillis > nowEpochMillis }

internal object HabitHistorySampleFactory {
    fun create(
        habits: Collection<HabitItemEntity>,
        records: Collection<HabitRecordEntity>
    ): List<HabitHistorySample> {
        val habitsById = habits.associateBy(HabitItemEntity::id)
        return records.mapNotNull { record ->
            val habit = habitsById[record.habitId] ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(record.completedDate) }
                .getOrNull() ?: return@mapNotNull null
            HabitHistorySample(
                checkedInAtEpochMillis = record.updatedAtEpochMillis
                    .takeIf { it >= record.createdAtEpochMillis }
                    ?: record.createdAtEpochMillis,
                targetReached = record.completionCount >= habit.targetCountPerDay.coerceAtLeast(1),
                wasScheduled = HabitScheduleCalculator.isScheduled(habit, date)
            )
        }
    }
}

internal object UnifiedCalendarEventFactory {
    fun create(
        month: YearMonth,
        todos: Collection<TodoItemEntity>,
        habits: Collection<HabitItemEntity>,
        habitRecords: Collection<HabitRecordEntity>,
        anniversaries: Collection<AnniversaryItemEntity>,
        sessions: Collection<SupervisionSessionEntity>,
        zoneId: ZoneId,
        ledgerEntries: Collection<LedgerEntryEntity> = emptyList()
    ): List<ProductivityCalendarEvent> = create(
        range = CalendarDateRange.month(month),
        todos = todos,
        habits = habits,
        habitRecords = habitRecords,
        anniversaries = anniversaries,
        sessions = sessions,
        ledgerEntries = ledgerEntries,
        zoneId = zoneId
    )

    fun create(
        range: CalendarDateRange,
        todos: Collection<TodoItemEntity>,
        habits: Collection<HabitItemEntity>,
        habitRecords: Collection<HabitRecordEntity>,
        anniversaries: Collection<AnniversaryItemEntity>,
        sessions: Collection<SupervisionSessionEntity>,
        ledgerEntries: Collection<LedgerEntryEntity>,
        zoneId: ZoneId
    ): List<ProductivityCalendarEvent> = buildList {
        todos.forEach { todo ->
            val plannedAt = todo.scheduledStartEpochMillis ?: todo.dueDateEpochMillis
            if (plannedAt != null) {
                add(
                    ProductivityCalendarEvent(
                        id = "todo:planned:${todo.id}",
                        title = todo.title,
                        type = CalendarEventType.PLANNED_TODO,
                        startEpochMillis = plannedAt,
                        endEpochMillis = todo.scheduledEndEpochMillis?.takeIf { it >= plannedAt }
                    )
                )
            }
            todo.completedAtEpochMillis?.takeIf { todo.isCompleted }?.let { completedAt ->
                add(
                    ProductivityCalendarEvent(
                        id = "todo:completed:${todo.id}",
                        title = todo.title,
                        type = CalendarEventType.COMPLETED_TODO,
                        startEpochMillis = completedAt
                    )
                )
            }
        }

        val recordsByHabitAndDate = habitRecords.associateBy { it.habitId to it.completedDate }
        habits.filterNot(HabitItemEntity::isArchived).forEach { habit ->
            HabitScheduleCalculator.scheduledDates(
                habit,
                range.startDate,
                range.endDateInclusive
            ).forEach { date ->
                val at = date.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
                add(
                    ProductivityCalendarEvent(
                        id = "habit:due:${habit.id}:$date",
                        title = habit.name,
                        type = CalendarEventType.HABIT_DUE,
                        startEpochMillis = at
                    )
                )
                val record = recordsByHabitAndDate[habit.id to date.toString()]
                if ((record?.completionCount ?: 0) >= habit.targetCountPerDay.coerceAtLeast(1)) {
                    add(
                        ProductivityCalendarEvent(
                            id = "habit:completed:${habit.id}:$date",
                            title = habit.name,
                            type = CalendarEventType.HABIT_COMPLETED,
                            startEpochMillis = date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
                        )
                    )
                }
            }
        }

        val rangeStart = range.startDate.atStartOfDay(zoneId).toInstant()
        val rangeEnd = range.endDateInclusive.plusDays(1).atStartOfDay(zoneId).toInstant()
        anniversaries.forEach { anniversary ->
            AnniversaryOccurrenceCalculator.occurrencesBetween(anniversary, rangeStart, rangeEnd)
                .forEach { occurrence ->
                    add(
                        ProductivityCalendarEvent(
                            id = "anniversary:${occurrence.key}",
                            title = anniversary.title,
                            type = CalendarEventType.ANNIVERSARY,
                            startEpochMillis = occurrence.occursAtEpochMillis
                        )
                    )
                }
        }

        sessions.mapNotNull(SupervisionSessionEntity::toCalendarEventOrNull).forEach(::add)
        ledgerEntries.mapNotNull(LedgerEntryEntity::toCalendarEventOrNull).forEach(::add)
    }
}

private fun SupervisionSessionEntity.isFocusSession(): Boolean =
    sessionKind == SupervisionSessionKind.MANUAL_FOCUS.storedValue ||
        sessionKind == SupervisionSessionKind.SCHEDULED_FOCUS.storedValue

private fun SupervisionSessionEntity.toCalendarEventOrNull(): ProductivityCalendarEvent? {
    val kind = SupervisionSessionKind.fromStoredValue(sessionKind) ?: return null
    val endedAt = endedAtEpochMillis
    if (startedAtEpochMillis < 0L || endedAt != null && endedAt < startedAtEpochMillis) return null
    val title = displayName.trim().takeIf(String::isNotEmpty) ?: return null
    val type = when (kind) {
        SupervisionSessionKind.MANUAL_FOCUS,
        SupervisionSessionKind.SCHEDULED_FOCUS -> CalendarEventType.FOCUS
        SupervisionSessionKind.MANUAL_GLOBAL,
        SupervisionSessionKind.SCHEDULED_GLOBAL,
        SupervisionSessionKind.APP -> CalendarEventType.SUPERVISION
    }
    return ProductivityCalendarEvent(
        id = if (type == CalendarEventType.FOCUS) {
            "focus:$sessionId"
        } else {
            "supervision:$sessionId"
        },
        title = title,
        type = type,
        startEpochMillis = startedAtEpochMillis,
        endEpochMillis = endedAt,
        supervisionDetails = if (type == CalendarEventType.SUPERVISION) {
            SupervisionCalendarEventDetails(
                kind = kind,
                packageName = packageName?.trim()?.takeIf(String::isNotEmpty),
                endReason = endReason?.let(SupervisionSessionEndReason::fromStoredValue)
            )
        } else {
            null
        }
    )
}

private fun LedgerEntryEntity.toCalendarEventOrNull(): ProductivityCalendarEvent? {
    if (occurredAtEpochMillis < 0L || !LedgerAmountCodec.isValidFen(amount)) return null
    val ledgerDirection = LedgerDirection.fromStoredValue(direction) ?: return null
    val ledgerCategory = LedgerCategory.fromStoredValue(category) ?: return null
    if (ledgerCategory.direction != ledgerDirection) return null
    val normalizedTitle = title.trim().takeIf(String::isNotEmpty) ?: return null
    return ProductivityCalendarEvent(
        id = "ledger:$id",
        title = normalizedTitle,
        type = CalendarEventType.LEDGER,
        startEpochMillis = occurredAtEpochMillis,
        ledgerDetails = LedgerCalendarEventDetails(
            amountFen = amount,
            direction = ledgerDirection,
            category = ledgerCategory,
            note = note?.trim()?.takeIf(String::isNotEmpty)
        )
    )
}

private fun LocalDate.coerceTo(month: YearMonth): LocalDate =
    month.atDay(dayOfMonth.coerceAtMost(month.lengthOfMonth()))

private const val CALENDAR_TICK_MILLIS = 30_000L
private const val CALENDAR_HISTORY_BUFFER_DAYS = 31L
private const val DEFAULT_RECOMMENDATION_DAYS = 7L
private const val RECOMMENDATION_HISTORY_DAYS = 180L
