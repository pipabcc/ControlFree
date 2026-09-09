package com.example.controlfree.ui.todo.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.todo.TodoItemEntity
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.TodoSubtaskEntity
import com.example.controlfree.todo.TimeBlockEventEntity
import com.example.controlfree.todo.TimeBlockEventRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn

private data class SearchCoreSources(
    val todos: List<TodoItemEntity>,
    val subtasks: List<TodoSubtaskEntity>,
    val habits: List<HabitItemEntity>,
    val habitRecords: List<HabitRecordEntity>
)

private data class SearchContentSources(
    val core: SearchCoreSources,
    val notes: List<QuickNoteEntity>,
    val ledger: List<LedgerEntryEntity>,
    val anniversaries: List<AnniversaryItemEntity>
)

private data class SearchSources(
    val content: SearchContentSources,
    val events: List<TimeBlockEventEntity>
)

internal data class SuperSearchResultsState(
    val query: String = "",
    val filter: SuperSearchFilter = SuperSearchFilter.ALL,
    val results: List<SuperSearchResult> = emptyList()
)

@OptIn(FlowPreview::class)
class SuperSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TodoRepository.getInstance(application)
    private val eventRepository = TimeBlockEventRepository.getInstance(application)
    private val zoneId = ZoneId.systemDefault()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _filter = MutableStateFlow(SuperSearchFilter.ALL)
    val filter: StateFlow<SuperSearchFilter> = _filter.asStateFlow()

    private val sources = combine(
        combine(
            repository.observeAllTodos(),
            repository.observeAllSubtasks(),
            repository.observeAllHabits(),
            repository.observeAllHabitRecords(),
            ::SearchCoreSources
        ),
        repository.observeAllQuickNotes(),
        repository.observeLedgerEntries(),
        repository.observeAllAnniversaries(),
        ::SearchContentSources
    ).combine(eventRepository.observeAll(), ::SearchSources)

    internal val searchState: StateFlow<SuperSearchResultsState> = combine(
        sources,
        _query.debounce(SEARCH_DEBOUNCE_MILLIS),
        _filter
    ) { data, rawQuery, selectedFilter ->
        SuperSearchResultsState(
            query = rawQuery,
            filter = selectedFilter,
            results = buildResults(data, rawQuery, selectedFilter, zoneId)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SuperSearchResultsState()
    )

    fun updateQuery(value: String) {
        _query.value = value.take(MAX_QUERY_CHARS)
    }

    fun selectFilter(value: SuperSearchFilter) {
        _filter.value = value
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 200L
        const val MAX_QUERY_CHARS = 160
    }
}

private fun buildResults(
    sources: SearchSources,
    rawQuery: String,
    filter: SuperSearchFilter,
    zoneId: ZoneId
): List<SuperSearchResult> {
    val query = rawQuery.trim().lowercase(Locale.ROOT)
    if (query.isEmpty()) return emptyList()

    val core = sources.content.core
    val subtasksByTodo = core.subtasks.groupBy(TodoSubtaskEntity::todoId)
    val recordsByHabit = core.habitRecords.groupBy(HabitRecordEntity::habitId)
    val candidates = buildList {
        core.todos.forEach { todo ->
            val subtaskText = subtasksByTodo[todo.id].orEmpty().joinToString(" ") { it.title }
            val text = listOfNotNull(todo.title, todo.description, todo.category, subtaskText)
                .joinToString(" ")
            add(
                SuperSearchResult(
                    stableKey = "todo:${todo.id}",
                    source = SuperSearchSource.TODO,
                    entityId = todo.id,
                    title = todo.title,
                    searchableText = text,
                    snippet = todo.description?.takeIf(String::isNotBlank),
                    metadata = if (todo.isCompleted) "已完成" else "待完成",
                    timestampEpochMillis = todo.updatedAtEpochMillis
                )
            )
        }
        sources.events.forEach { event ->
            add(event.toCalendarSearchResult(zoneId))
        }
        core.habits.filterNot(HabitItemEntity::isArchived).forEach { habit ->
            val recordText = recordsByHabit[habit.id].orEmpty().joinToString(" ") { it.note.orEmpty() }
            val text = "${habit.name} ${habit.frequencyType} $recordText"
            add(
                SuperSearchResult(
                    stableKey = "habit:${habit.id}",
                    source = SuperSearchSource.HABIT,
                    entityId = habit.id,
                    title = habit.name,
                    searchableText = text,
                    snippet = recordsByHabit[habit.id].orEmpty().firstNotNullOfOrNull { it.note },
                    metadata = "连续 ${habit.currentStreak} 天",
                    timestampEpochMillis = habit.updatedAtEpochMillis
                )
            )
        }
        sources.content.notes.filter { it.status != "ARCHIVED" }.forEach { note ->
            val title = note.content.lineSequence().firstOrNull()?.trim().orEmpty()
                .ifBlank { note.mediaDisplayName ?: "图片闪记" }
                .take(80)
            val text = listOf(
                note.content,
                note.mediaDisplayName.orEmpty(),
                note.aiAdvice.orEmpty()
            ).joinToString(" ")
            add(
                SuperSearchResult(
                    stableKey = "quick-note:${note.id}",
                    source = SuperSearchSource.QUICK_NOTE,
                    entityId = note.id,
                    title = title,
                    searchableText = text,
                    snippet = note.content.take(140).takeIf(String::isNotBlank),
                    metadata = formatDateTime(note.createdAtEpochMillis, zoneId),
                    timestampEpochMillis = note.updatedAtEpochMillis
                )
            )
        }
        sources.content.ledger.forEach { entry ->
            val amountText = formatAmount(entry.amount)
            val text = listOf(
                entry.title,
                entry.note.orEmpty(),
                entry.category,
                entry.direction,
                amountText
            ).joinToString(" ")
            add(
                SuperSearchResult(
                    stableKey = "ledger:${entry.id}",
                    source = SuperSearchSource.LEDGER,
                    entityId = entry.id,
                    title = entry.title,
                    searchableText = text,
                    snippet = entry.note,
                    metadata = "$amountText · ${formatDateTime(entry.occurredAtEpochMillis, zoneId)}",
                    timestampEpochMillis = entry.updatedAtEpochMillis,
                    targetDate = Instant.ofEpochMilli(entry.occurredAtEpochMillis).atZone(zoneId).toLocalDate()
                )
            )
        }
        sources.content.anniversaries.forEach { item ->
            val date = Instant.ofEpochMilli(item.targetDateEpochMillis).atZone(zoneId).toLocalDate()
            val text = "${item.title} ${item.type} ${item.repeatRule} $date"
            add(
                SuperSearchResult(
                    stableKey = "anniversary:${item.id}",
                    source = SuperSearchSource.ANNIVERSARY,
                    entityId = item.id,
                    title = item.title,
                    searchableText = text,
                    snippet = null,
                    metadata = "$date · ${item.repeatRule}",
                    timestampEpochMillis = item.updatedAtEpochMillis,
                    targetDate = date
                )
            )
        }
    }

    return rankSearchResults(candidates, rawQuery, filter)
}

internal fun rankSearchResults(
    candidates: List<SuperSearchResult>,
    rawQuery: String,
    filter: SuperSearchFilter
): List<SuperSearchResult> {
    val query = rawQuery.trim().lowercase(Locale.ROOT)
    if (query.isEmpty()) return emptyList()
    return candidates.asSequence()
        .filter { filter.accepts(it.source) }
        .mapNotNull { candidate ->
            val score = matchScore(candidate.title, candidate.searchableText, query)
            candidate.takeIf { score > 0 }?.copy(score = score)
        }
        .sortedWith(compareByDescending<SuperSearchResult> { it.score }.thenByDescending { it.timestampEpochMillis })
        .take(MAX_RESULTS)
        .toList()
}

internal fun TimeBlockEventEntity.toCalendarSearchResult(zoneId: ZoneId): SuperSearchResult {
    val start = Instant.ofEpochMilli(startAtEpochMillis).atZone(zoneId)
    val end = Instant.ofEpochMilli(endAtEpochMillis).atZone(zoneId)
    val date = start.toLocalDate()
    val timeRange = "${TIME_FORMATTER.format(start)}–${TIME_FORMATTER.format(end)}"
    val completionLabel = if (isCompleted) "已完成" else "未完成"
    return SuperSearchResult(
        stableKey = "calendar:event:$id",
        source = SuperSearchSource.CALENDAR,
        entityId = id,
        title = title,
        searchableText = listOf(
            title,
            description.orEmpty(),
            project,
            completionLabel,
            date.toString(),
            timeRange
        ).joinToString(" "),
        snippet = description,
        metadata = "$project · $date $timeRange · $completionLabel",
        timestampEpochMillis = updatedAtEpochMillis,
        targetDate = date
    )
}

private fun matchScore(title: String, searchableText: String, query: String): Int {
    val normalizedTitle = title.trim().lowercase(Locale.ROOT)
    val normalizedText = searchableText.lowercase(Locale.ROOT)
    return when {
        normalizedTitle == query -> 400
        normalizedTitle.startsWith(query) -> 300
        query in normalizedTitle -> 200
        query in normalizedText -> 100
        else -> 0
    }
}

private fun formatDateTime(epochMillis: Long, zoneId: ZoneId): String =
    DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

private fun formatAmount(amountFen: Long): String {
    return "¥${LedgerAmountCodec.formatFen(amountFen)}"
}

private const val MAX_RESULTS = 200
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
