package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.ai.ParsedLedgerEntry
import com.example.controlfree.ai.ParsedTripleRoutingResult
import com.example.controlfree.ai.ProductivityAiCoordinator
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryDraft
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.todo.LedgerMonthSummary
import com.example.controlfree.todo.LedgerRoutingSaveResult
import com.example.controlfree.todo.LedgerTodoDraft
import com.example.controlfree.todo.TodoRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LedgerComposerState(
    val content: String = "",
    val isParsing: Boolean = false,
    val parsedResult: ParsedTripleRoutingResult? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
    /** 一次 AI 解析对应一个提交批次，重复点击复用该 ID。 */
    val analysisBatchId: String? = null
)

internal data class LedgerTimeRange(
    val startEpochMillis: Long,
    val endExclusiveEpochMillis: Long
) {
    init {
        require(startEpochMillis >= 0L)
        require(endExclusiveEpochMillis > startEpochMillis)
    }
}

internal data class LedgerSummaryRanges(
    val day: LedgerTimeRange,
    val week: LedgerTimeRange,
    val month: LedgerTimeRange,
    val year: LedgerTimeRange
)

/**
 * 账本页面只依赖这些窄接口，生产环境由 Repository/AI 协调器适配，测试可在不启动
 * Room 或网络栈的情况下验证请求竞态和批次提交行为。
 */
internal interface LedgerViewModelRepository {
    fun observeLedgerEntriesBetween(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<LedgerEntryEntity>>

    fun observeLedgerSummaryBetween(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<LedgerMonthSummary>

    suspend fun saveLedgerRouting(
        batchId: String,
        ledgerEntries: List<LedgerEntryDraft>,
        todoItems: List<LedgerTodoDraft>
    ): LedgerRoutingSaveResult

    suspend fun deleteLedgerEntry(id: String): Int

    suspend fun restoreDeletedLedgerEntry(entry: LedgerEntryEntity): Boolean

    suspend fun updateLedgerEntryUserFields(
        id: String,
        title: String,
        amountFen: Long,
        direction: LedgerDirection,
        category: LedgerCategory
    ): LedgerEntryEntity

    suspend fun saveLedgerEntries(entries: List<LedgerEntryEntity>): Int
}

internal interface LedgerViewModelAi {
    suspend fun classifyLedger(text: String, now: ZonedDateTime): ParsedTripleRoutingResult
}

private class TodoRepositoryLedgerViewModelRepository(
    private val repository: TodoRepository
) : LedgerViewModelRepository {
    override fun observeLedgerEntriesBetween(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<LedgerEntryEntity>> =
        repository.observeLedgerEntriesBetween(startEpochMillis, endExclusiveEpochMillis)

    override fun observeLedgerSummaryBetween(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<LedgerMonthSummary> =
        repository.observeLedgerSummaryBetween(startEpochMillis, endExclusiveEpochMillis)

    override suspend fun saveLedgerRouting(
        batchId: String,
        ledgerEntries: List<LedgerEntryDraft>,
        todoItems: List<LedgerTodoDraft>
    ): LedgerRoutingSaveResult = repository.saveLedgerRouting(batchId, ledgerEntries, todoItems)

    override suspend fun deleteLedgerEntry(id: String): Int = repository.deleteLedgerEntry(id)

    override suspend fun restoreDeletedLedgerEntry(entry: LedgerEntryEntity): Boolean =
        repository.restoreDeletedLedgerEntry(entry)

    override suspend fun updateLedgerEntryUserFields(
        id: String,
        title: String,
        amountFen: Long,
        direction: LedgerDirection,
        category: LedgerCategory
    ): LedgerEntryEntity = repository.updateLedgerEntryUserFields(
        id = id,
        title = title,
        amountFen = amountFen,
        direction = direction,
        category = category
    )

    override suspend fun saveLedgerEntries(entries: List<LedgerEntryEntity>): Int =
        repository.saveLedgerEntries(entries)
}

private class ProductivityAiLedgerViewModelAi(
    private val coordinator: ProductivityAiCoordinator
) : LedgerViewModelAi {
    override suspend fun classifyLedger(
        text: String,
        now: ZonedDateTime
    ): ParsedTripleRoutingResult = coordinator.classifyLedger(text, now)
}

internal fun ledgerSummaryRanges(
    year: Int,
    month: Int,
    now: ZonedDateTime,
    zoneId: ZoneId
): LedgerSummaryRanges {
    val selectedMonth = LocalDate.of(year, month, 1)
    val currentDate = now.withZoneSameInstant(zoneId).toLocalDate()
    fun LocalDate.toEpochMillis(): Long = atStartOfDay(zoneId).toInstant().toEpochMilli()
    fun range(start: LocalDate, endExclusive: LocalDate): LedgerTimeRange = LedgerTimeRange(
        startEpochMillis = start.toEpochMillis(),
        endExclusiveEpochMillis = endExclusive.toEpochMillis()
    )

    val weekStart = currentDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return LedgerSummaryRanges(
        day = range(currentDate, currentDate.plusDays(1)),
        week = range(weekStart, weekStart.plusDays(7)),
        month = range(selectedMonth, selectedMonth.plusMonths(1)),
        year = range(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel internal constructor(
    application: Application,
    private val repository: LedgerViewModelRepository,
    private val aiCoordinator: LedgerViewModelAi,
    private val clock: Clock,
    private val batchIdFactory: () -> String
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        repository = TodoRepositoryLedgerViewModelRepository(TodoRepository.getInstance(application)),
        aiCoordinator = ProductivityAiLedgerViewModelAi(ProductivityAiCoordinator.getInstance(application)),
        clock = Clock.systemDefaultZone(),
        batchIdFactory = { UUID.randomUUID().toString() }
    )

    private val _composerState = MutableStateFlow(LedgerComposerState())
    val composerState: StateFlow<LedgerComposerState> = _composerState.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _selectedYearMonth = MutableStateFlow(
        run {
            val now = ZonedDateTime.now(clock)
            now.year to now.monthValue
        }
    )
    val selectedYearMonth: StateFlow<Pair<Int, Int>> = _selectedYearMonth.asStateFlow()

    private val selectedRanges: Flow<LedgerSummaryRanges> = _selectedYearMonth
        .map { (year, month) -> ledgerSummaryRanges(year, month, ZonedDateTime.now(clock), clock.zone) }

    private val _areMonthEntriesLoaded = MutableStateFlow(false)
    val areMonthEntriesLoaded: StateFlow<Boolean> = _areMonthEntriesLoaded.asStateFlow()

    val monthEntries: StateFlow<List<LedgerEntryEntity>> = selectedRanges
        .flatMapLatest { range ->
            repository.observeLedgerEntriesBetween(
                range.month.startEpochMillis,
                range.month.endExclusiveEpochMillis
            )
                .onStart { _areMonthEntriesLoaded.value = false }
                .onEach { _areMonthEntriesLoaded.value = true }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val daySummary: StateFlow<LedgerMonthSummary> = selectedRanges
        .flatMapLatest { range -> summaryFlow(range.day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_SUMMARY)

    val weekSummary: StateFlow<LedgerMonthSummary> = selectedRanges
        .flatMapLatest { range -> summaryFlow(range.week) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_SUMMARY)

    val monthSummary: StateFlow<LedgerMonthSummary> = selectedRanges
        .flatMapLatest { range -> summaryFlow(range.month) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_SUMMARY)

    val yearSummary: StateFlow<LedgerMonthSummary> = selectedRanges
        .flatMapLatest { range -> summaryFlow(range.year) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_SUMMARY)

    private var classifyJob: Job? = null
    private var inputVersion = 0L
    private val submitLock = Any()

    private fun summaryFlow(range: LedgerTimeRange): Flow<LedgerMonthSummary> =
        repository.observeLedgerSummaryBetween(
            range.startEpochMillis,
            range.endExclusiveEpochMillis
        )

    fun updateInput(text: String) {
        val current = _composerState.value
        val normalizedText = text.take(MAX_INPUT_CHARS)
        if (current.content == normalizedText) return
        inputVersion++
        classifyJob?.cancel()
        classifyJob = null
        _composerState.value = current.copy(
            content = normalizedText,
            isParsing = false,
            parsedResult = null,
            analysisBatchId = null,
            error = null
        )
    }

    fun classifyWithAi(): Job? {
        val snapshot = _composerState.value
        if (snapshot.isSaving || snapshot.isParsing) return null
        val content = snapshot.content.trim()
        if (content.isBlank()) {
            _composerState.value = snapshot.copy(error = "内容不能为空")
            return null
        }

        inputVersion++
        val requestVersion = inputVersion
        classifyJob?.cancel()
        _composerState.value = snapshot.copy(
            isParsing = true,
            parsedResult = null,
            analysisBatchId = null,
            error = null
        )
        val contentSnapshot = snapshot.content
        val job = viewModelScope.launch {
            try {
                val result = aiCoordinator.classifyLedger(content, ZonedDateTime.now(clock))
                if (!isCurrentRequest(requestVersion, contentSnapshot)) return@launch
                val hasRoutableContent = result.ledgerEntries.isNotEmpty() || result.todoItems.isNotEmpty()
                _composerState.value = _composerState.value.copy(
                    isParsing = false,
                    parsedResult = result.takeIf { hasRoutableContent },
                    analysisBatchId = result.takeIf { hasRoutableContent }?.let {
                        batchIdFactory()
                    },
                    error = if (hasRoutableContent) {
                        null
                    } else {
                        result.warnings.firstOrNull()
                            ?: "未检测到有效财务信息，请尝试更明确的表述，例如「午餐花了 20 元」"
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentRequest(requestVersion, contentSnapshot)) {
                    _composerState.value = _composerState.value.copy(
                        isParsing = false,
                        error = error.localizedMessage ?: "AI 整理失败，请稍后重试"
                    )
                }
            } finally {
                if (isCurrentRequest(requestVersion, contentSnapshot)) classifyJob = null
            }
        }
        classifyJob = job
        return job
    }

    private fun isCurrentRequest(version: Long, contentSnapshot: String): Boolean =
        version == inputVersion && _composerState.value.content == contentSnapshot

    fun confirmAndSave(): Job? {
        val snapshot = synchronized(submitLock) {
            val current = _composerState.value
            if (current.isSaving || current.parsedResult == null || current.analysisBatchId == null) {
                return null
            }
            _composerState.value = current.copy(isSaving = true, error = null)
            current
        }
        val parsed = snapshot.parsedResult ?: return null
        val batchId = snapshot.analysisBatchId ?: return null
        val job = viewModelScope.launch {
            try {
                repository.saveLedgerRouting(
                    batchId = batchId,
                    ledgerEntries = parsed.ledgerEntries.map { it.toDraft(parsed.warnings) },
                    todoItems = parsed.todoItems.map { item ->
                        LedgerTodoDraft(
                            content = item.content,
                            dueDateEpochMillis = item.dueDateEpochMillis
                        )
                    }
                )
                val current = _composerState.value
                if (current.content == snapshot.content && current.analysisBatchId == batchId) {
                    _composerState.value = LedgerComposerState()
                } else {
                    _composerState.value = current.copy(isSaving = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _composerState.value = _composerState.value.copy(
                    isSaving = false,
                    error = "保存失败：${error.localizedMessage ?: "请稍后重试"}"
                )
            }
        }
        return job
    }

    fun updateLedgerEntry(index: Int, updated: ParsedLedgerEntry) {
        if (_composerState.value.isSaving) return
        require(LedgerAmountCodec.isValidFen(updated.amountFen)) { "金额无效" }
        val result = _composerState.value.parsedResult ?: return
        val list = result.ledgerEntries.toMutableList()
        if (index in list.indices) {
            list[index] = updated
            _composerState.value = _composerState.value.copy(
                parsedResult = result.copy(ledgerEntries = list)
            )
        }
    }

    fun removeLedgerEntry(index: Int) {
        if (_composerState.value.isSaving) return
        val result = _composerState.value.parsedResult ?: return
        val list = result.ledgerEntries.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _composerState.value = _composerState.value.copy(
                parsedResult = result.copy(ledgerEntries = list)
            )
        }
    }

    suspend fun deleteEntry(id: String): Boolean = try {
        val wasDeleted = repository.deleteLedgerEntry(id) > 0
        if (!wasDeleted) _messages.emit("删除失败，账目可能已不存在")
        wasDeleted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        _messages.emit("删除失败：${error.localizedMessage ?: "请稍后重试"}")
        false
    }

    fun restoreEntry(entry: LedgerEntryEntity): Job = viewModelScope.launch {
        try {
            if (repository.restoreDeletedLedgerEntry(entry)) {
                _messages.emit("已撤销删除")
            } else {
                _messages.emit("撤销失败：同一账目编号已存在")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _messages.emit("撤销失败：${error.localizedMessage ?: "请稍后重试"}")
        }
    }

    suspend fun updateSavedEntry(entry: LedgerEntryEntity): Boolean {
        val direction = LedgerDirection.fromStoredValue(entry.direction)
        val category = LedgerCategory.fromStoredValue(entry.category)
        if (direction == null || category == null || category.direction != direction) {
            _messages.emit("保存失败：账目分类与收支方向无效")
            return false
        }
        return try {
            repository.updateLedgerEntryUserFields(
                id = entry.id,
                title = entry.title,
                amountFen = entry.amount,
                direction = direction,
                category = category
            )
            _messages.emit("账目已更新")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _messages.emit("保存失败：${error.localizedMessage ?: "请稍后重试"}")
            false
        }
    }

    fun switchMonth(offset: Int) {
        require(offset in -120..120) { "月份偏移超出范围" }
        val (year, month) = _selectedYearMonth.value
        val next = LocalDate.of(year, month, 1).plusMonths(offset.toLong())
        _selectedYearMonth.value = next.year to next.monthValue
    }

    fun selectMonth(date: LocalDate) {
        _selectedYearMonth.value = date.year to date.monthValue
    }

    override fun onCleared() {
        classifyJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_INPUT_CHARS = 1_000
        val EMPTY_SUMMARY = LedgerMonthSummary(
            totalExpense = 0L,
            totalIncome = 0L,
            balance = 0L,
            categoryTotals = emptyList()
        )
    }
}

private fun ParsedLedgerEntry.toDraft(warnings: List<String>): LedgerEntryDraft = LedgerEntryDraft(
    title = title,
    amountFen = amountFen,
    direction = direction,
    category = category,
    occurredAtEpochMillis = occurredAtEpochMillis,
    isEstimated = isEstimated,
    aiConfidence = confidence,
    emotion = emotion,
    necessity = necessity,
    note = note,
    warnings = warnings
)
