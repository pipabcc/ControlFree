package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.controlfree.ai.ParsedLedgerEntry
import com.example.controlfree.ai.ParsedTripleRoutingResult
import com.example.controlfree.todo.CategoryTotal
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryDraft
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.todo.LedgerMonthSummary
import com.example.controlfree.todo.LedgerRoutingSaveResult
import com.example.controlfree.todo.LedgerTodoDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun newerInputCannotBeOverwrittenByOlderAiResponse() = testScope.runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val ai = FakeLedgerAi { text, _ ->
            if (text == "旧输入") {
                oldStarted.complete(Unit)
                oldRelease.await()
            }
            parsedResult(text)
        }
        val viewModel = createViewModel(ai = ai)

        viewModel.updateInput("旧输入")
        viewModel.classifyWithAi()
        runCurrent()
        oldStarted.await()

        viewModel.updateInput("新输入")
        viewModel.classifyWithAi()
        advanceUntilIdle()

        assertEquals("新输入", viewModel.composerState.value.parsedResult?.ledgerEntries?.single()?.title)
        oldRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals("新输入", viewModel.composerState.value.parsedResult?.ledgerEntries?.single()?.title)
    }

    @Test
    fun confirmIsIdempotentWhileSavingAndPreservesAiFields() = testScope.runTest {
        val repository = FakeLedgerRepository()
        val ai = FakeLedgerAi { _, _ -> parsedResult("午餐") }
        val viewModel = createViewModel(repository = repository, ai = ai)

        viewModel.updateInput("午餐")
        viewModel.classifyWithAi()
        advanceUntilIdle()

        assertTrue(viewModel.confirmAndSave() != null)
        assertNull(viewModel.confirmAndSave())
        advanceUntilIdle()

        assertEquals(1, repository.saveCalls)
        val draft = repository.savedLedgerEntries.single()
        assertEquals(Instant.parse("2026-07-23T04:00:00Z").toEpochMilli(), draft.occurredAtEpochMillis)
        assertEquals(0.72f, draft.aiConfidence, 0.0001f)
        assertEquals(listOf("金额为估算值"), draft.warnings)
        assertFalse(viewModel.composerState.value.isSaving)
    }

    @Test
    fun classifyIsIgnoredWhileCurrentResultIsSaving() = testScope.runTest {
        val repository = FakeLedgerRepository(saveGate = CompletableDeferred())
        val aiCalls = AtomicInteger(0)
        val ai = FakeLedgerAi { _, _ ->
            aiCalls.incrementAndGet()
            parsedResult("午餐")
        }
        val viewModel = createViewModel(repository = repository, ai = ai)

        viewModel.updateInput("午餐")
        viewModel.classifyWithAi()
        advanceUntilIdle()
        viewModel.confirmAndSave()
        runCurrent()
        val parsedBeforeEdit = viewModel.composerState.value.parsedResult
        val originalEntry = parsedBeforeEdit?.ledgerEntries?.single()
        viewModel.updateLedgerEntry(0, checkNotNull(originalEntry).copy(title = "不应覆盖"))
        viewModel.removeLedgerEntry(0)
        assertEquals(parsedBeforeEdit, viewModel.composerState.value.parsedResult)
        assertNull(viewModel.classifyWithAi())
        assertEquals(1, aiCalls.get())
        repository.saveGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun undoDelegatesOriginalEntityToDedicatedRestorePath() = testScope.runTest {
        val repository = FakeLedgerRepository()
        val viewModel = createViewModel(repository = repository)
        val deleted = legacyEntry()

        viewModel.restoreEntry(deleted)
        advanceUntilIdle()

        assertEquals(listOf(deleted), repository.restoredEntries)
        assertEquals(0, repository.strictEntitySaveCalls)
    }

    @Test
    fun legacyEntryEditingUsesUserFieldUpdatePath() = testScope.runTest {
        val repository = FakeLedgerRepository()
        val viewModel = createViewModel(repository = repository)
        val edited = legacyEntry().copy(
            amount = 2_800L,
            direction = LedgerDirection.EXPENSE.storedValue,
            category = LedgerCategory.FOOD.storedValue,
            title = "修正后的午餐"
        )

        assertTrue(viewModel.updateSavedEntry(edited))

        assertEquals(edited.id, repository.lastUpdatedLedger?.id)
        assertEquals(edited.amount, repository.lastUpdatedLedger?.amount)
        assertEquals(0, repository.strictEntitySaveCalls)
    }

    @Test
    fun undoConflictEmitsUserVisibleFailure() = testScope.runTest {
        val repository = FakeLedgerRepository(restoreResult = false)
        val viewModel = createViewModel(repository = repository)
        val message = async { viewModel.messages.first() }
        runCurrent()

        viewModel.restoreEntry(legacyEntry())

        assertEquals("撤销失败：同一账目编号已存在", message.await())
    }

    private fun createViewModel(
        repository: FakeLedgerRepository = FakeLedgerRepository(),
        ai: FakeLedgerAi = FakeLedgerAi { _, _ -> parsedResult("默认") }
    ): LedgerViewModel = LedgerViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        repository = repository,
        aiCoordinator = ai,
        clock = Clock.fixed(
            Instant.parse("2026-07-23T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
        ),
        batchIdFactory = { "test-batch" }
    )

    private fun parsedResult(title: String): ParsedTripleRoutingResult = ParsedTripleRoutingResult(
        ledgerEntries = listOf(
            ParsedLedgerEntry(
                title = title,
                amountFen = 2_800L,
                isEstimated = true,
                direction = LedgerDirection.EXPENSE,
                category = LedgerCategory.FOOD,
                emotion = "刚需",
                necessity = "need",
                occurredAtEpochMillis = Instant.parse("2026-07-23T04:00:00Z").toEpochMilli(),
                confidence = 0.72f
            )
        ),
        todoItems = emptyList(),
        tip = "注意餐饮",
        warnings = listOf("金额为估算值")
    )

    private fun legacyEntry(): LedgerEntryEntity = LedgerEntryEntity(
        id = "legacy-entry",
        amount = -2_800L,
        direction = "UNKNOWN",
        category = "UNKNOWN",
        title = "旧账目",
        note = "旧版保留备注",
        occurredAtEpochMillis = -1L,
        sourceNoteId = "legacy-source",
        emotion = "未知情绪",
        necessity = "unknown",
        createdAtEpochMillis = -1L,
        updatedAtEpochMillis = -1L
    )
}

private class FakeLedgerAi(
    private val handler: suspend (String, ZonedDateTime) -> ParsedTripleRoutingResult
) : LedgerViewModelAi {
    override suspend fun classifyLedger(
        text: String,
        now: ZonedDateTime
    ): ParsedTripleRoutingResult = handler(text, now)
}

private class FakeLedgerRepository(
    val saveGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    private val restoreResult: Boolean = true
) : LedgerViewModelRepository {
    private val entries = MutableStateFlow<List<LedgerEntryEntity>>(emptyList())
    private val summary = MutableStateFlow(
        LedgerMonthSummary(0L, 0L, 0L, emptyList<CategoryTotal>())
    )
    var saveCalls: Int = 0
        private set
    val savedLedgerEntries = mutableListOf<LedgerEntryDraft>()
    val restoredEntries = mutableListOf<LedgerEntryEntity>()
    var strictEntitySaveCalls: Int = 0
        private set
    var lastUpdatedLedger: LedgerEntryEntity? = null
        private set

    override fun observeLedgerEntriesBetween(startEpochMillis: Long, endExclusiveEpochMillis: Long): Flow<List<LedgerEntryEntity>> = entries

    override fun observeLedgerSummaryBetween(startEpochMillis: Long, endExclusiveEpochMillis: Long): Flow<LedgerMonthSummary> = summary

    override suspend fun saveLedgerRouting(
        batchId: String,
        ledgerEntries: List<LedgerEntryDraft>,
        todoItems: List<LedgerTodoDraft>
    ): LedgerRoutingSaveResult {
        saveCalls += 1
        savedLedgerEntries += ledgerEntries
        saveGate.await()
        return LedgerRoutingSaveResult(batchId, emptyList(), emptyList(), true)
    }

    override suspend fun deleteLedgerEntry(id: String): Int = 1

    override suspend fun restoreDeletedLedgerEntry(entry: LedgerEntryEntity): Boolean {
        restoredEntries += entry
        return restoreResult
    }

    override suspend fun updateLedgerEntryUserFields(
        id: String,
        title: String,
        amountFen: Long,
        direction: LedgerDirection,
        category: LedgerCategory
    ): LedgerEntryEntity = LedgerEntryEntity(
        id = id,
        amount = amountFen,
        direction = direction.storedValue,
        category = category.storedValue,
        title = title,
        note = null,
        occurredAtEpochMillis = 0L,
        sourceNoteId = null,
        emotion = null,
        necessity = null,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L
    ).also { lastUpdatedLedger = it }

    override suspend fun saveLedgerEntries(entries: List<LedgerEntryEntity>): Int {
        strictEntitySaveCalls += 1
        return entries.size
    }
}
