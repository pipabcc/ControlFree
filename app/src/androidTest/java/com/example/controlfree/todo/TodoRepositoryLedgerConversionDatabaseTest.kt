package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryLedgerConversionDatabaseTest {
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(
            database,
            Clock.fixed(Instant.parse("2026-07-23T04:00:00Z"), ZoneOffset.UTC)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun convertNoteAtomicallySavesLedgerTodoArchivesAndAudits() = runBlocking {
        val mediaUri = "content://test/receipt.jpg"
        val note = repository.addQuickNote(
            content = "午餐28元，月底核对账单",
            mediaUri = mediaUri,
            mediaMimeType = "image/jpeg",
            mediaDisplayName = "receipt.jpg",
            mediaSizeBytes = 123L
        )
        val batchId = "batch-${note.id}"
        val result = repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = listOf(
                LedgerEntryDraft(
                    title = "午餐",
                    amountFen = 2_800L,
                    direction = LedgerDirection.EXPENSE,
                    category = LedgerCategory.FOOD,
                    occurredAtEpochMillis = 1_000L,
                    isEstimated = false,
                    aiConfidence = 0.95f,
                    emotion = "刚需",
                    necessity = "need",
                    note = "工作午餐",
                    warnings = listOf("无")
                )
            ),
            todoItems = listOf(
                LedgerTodoDraft(
                    content = "月底核对账单",
                    dueDateEpochMillis = 2_000L
                )
            ),
            batchId = batchId
        )

        assertTrue(result.wasCreated)
        assertEquals(batchId, result.targetId)
        val ledger = database.ledgerDao().observeLedgerEntries().first().single()
        assertEquals(note.id, ledger.sourceNoteId)
        assertEquals(batchId, ledger.sourceBatchId)
        assertEquals(LedgerEntrySourceType.AI.storedValue, ledger.sourceType)
        assertEquals(1_000L, ledger.occurredAtEpochMillis)
        assertEquals(0.95f, ledger.aiConfidence, 0.0001f)
        val warningsJson = ledger.aiWarningsJson
        assertNotNull(warningsJson)
        assertEquals(listOf("无"), LedgerWarningCodec.decode(warningsJson!!))

        val todos = repository.observeAllTodos().first()
        assertEquals(1, todos.size)
        assertEquals("月底核对账单", todos.single().title)
        assertEquals(2_000L, todos.single().dueDateEpochMillis)

        val archived = repository.observeAllQuickNotes().first().single()
        assertEquals(QuickNoteStatus.ARCHIVED.storedValue, archived.status)
        assertEquals("工作午餐", database.ledgerDao().observeLedgerEntries().first().single().note)
        assertEquals(mediaUri, archived.mediaUri)
        assertEquals("image/jpeg", archived.mediaMimeType)
        assertEquals("receipt.jpg", archived.mediaDisplayName)
        assertEquals(123L, archived.mediaSizeBytes)
        assertTrue(repository.observeQuickNoteInbox().first().isEmpty())
    }

    @Test
    fun repeatedConversionIsIdempotentAndDoesNotDuplicateWrites() = runBlocking {
        val note = repository.addQuickNote("午餐28元")
        val entries = listOf(
            LedgerEntryDraft(
                title = "午餐",
                amountFen = 2_800L,
                direction = LedgerDirection.EXPENSE,
                category = LedgerCategory.FOOD,
                occurredAtEpochMillis = 1_000L,
                isEstimated = false,
                aiConfidence = 0.9f,
                emotion = "刚需",
                necessity = "need"
            )
        )
        val first = repository.convertNoteToLedger(note.id, entries, emptyList(), "batch-id")
        val second = repository.convertNoteToLedger(note.id, entries, emptyList(), "other-batch-id")

        assertTrue(first.wasCreated)
        assertFalse(second.wasCreated)
        assertEquals(first.targetId, second.targetId)
        assertEquals(1, database.ledgerDao().observeLedgerEntries().first().size)
        assertEquals(0, repository.observeAllTodos().first().size)
        assertEquals(QuickNoteStatus.ARCHIVED.storedValue, repository.observeAllQuickNotes().first().single().status)
    }

    @Test
    fun changedPayloadForSameNoteAndBatchIsRejected() = runBlocking {
        val note = repository.addQuickNote("午餐28元")
        val entries = listOf(validExpenseDraft())
        repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = entries,
            todoItems = emptyList(),
            batchId = "note-conflict-batch"
        )
        val repeated = repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = entries,
            todoItems = emptyList(),
            batchId = "note-conflict-batch"
        )

        expectIllegalArgument {
            repository.convertNoteToLedger(
                noteId = note.id,
                ledgerEntries = listOf(validExpenseDraft().copy(amountFen = 2_900L)),
                todoItems = emptyList(),
                batchId = "note-conflict-batch"
            )
        }

        val persisted = database.ledgerDao().observeLedgerEntries().first().single()
        assertFalse(repeated.wasCreated)
        assertEquals(2_800L, persisted.amount)
        assertEquals("note-conflict-batch", persisted.sourceBatchId)
        assertEquals(QuickNoteStatus.ARCHIVED.storedValue, repository.observeAllQuickNotes().first().single().status)
    }

    @Test
    fun deletedConversionTargetsAreNotRebuiltOnRetry() = runBlocking {
        val note = repository.addQuickNote("午餐28元")
        val entries = listOf(validExpenseDraft())
        val first = repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = entries,
            todoItems = emptyList(),
            batchId = "deleted-target-batch"
        )
        val persistedId = database.ledgerDao().observeLedgerEntries().first().single().id
        assertEquals(1, repository.deleteLedgerEntry(persistedId))

        val repeated = repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = entries,
            todoItems = emptyList(),
            batchId = "deleted-target-batch"
        )

        assertFalse(repeated.wasCreated)
        assertEquals(first.targetId, repeated.targetId)
        assertTrue(database.ledgerDao().observeLedgerEntries().first().isEmpty())
    }

    @Test
    fun ledgerArchiveIsNotOverwrittenByLaterTodoConversion() = runBlocking {
        val note = repository.addQuickNote("午餐28元，记得月底核对")
        repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = listOf(validExpenseDraft()),
            todoItems = emptyList()
        )

        repository.convertNoteToTodo(
            noteId = note.id,
            todoTitle = "月底核对账单",
            priority = 1,
            dueDate = null
        )

        assertEquals(
            QuickNoteStatus.ARCHIVED.storedValue,
            database.quickNoteDao().getById(note.id)?.status
        )
        assertEquals(1, database.ledgerDao().observeLedgerEntries().first().size)
        assertEquals(1, repository.observeAllTodos().first().size)
    }

    @Test
    fun repeatedConversionRepairsLegacyUnarchivedState() = runBlocking {
        val note = repository.addQuickNote("午餐28元")
        val entries = listOf(validExpenseDraft())
        val first = repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = entries,
            todoItems = emptyList()
        )
        database.quickNoteDao().updateStatus(
            note.id,
            QuickNoteStatus.RAW.storedValue,
            2_000L
        )

        val repeated = repository.convertNoteToLedger(
            noteId = note.id,
            ledgerEntries = entries,
            todoItems = emptyList(),
            batchId = "应忽略的新批次"
        )

        assertFalse(repeated.wasCreated)
        assertEquals(first.targetId, repeated.targetId)
        assertEquals(
            QuickNoteStatus.ARCHIVED.storedValue,
            database.quickNoteDao().getById(note.id)?.status
        )
        assertEquals(1, database.ledgerDao().observeLedgerEntries().first().size)
    }

    @Test
    fun directRoutingBatchIsIdempotent() = runBlocking {
        val entries = listOf(
            LedgerEntryDraft(
                title = "工资",
                amountFen = 100_000L,
                direction = LedgerDirection.INCOME,
                category = LedgerCategory.SALARY,
                occurredAtEpochMillis = 1_000L,
                isEstimated = false,
                aiConfidence = 1f,
                emotion = "刚需",
                necessity = "need"
            )
        )
        val todos = listOf(LedgerTodoDraft(content = "核对工资单"))

        val first = repository.saveLedgerRouting("direct-batch", entries, todos)
        val second = repository.saveLedgerRouting("direct-batch", entries, todos)

        assertTrue(first.wasCreated)
        assertFalse(second.wasCreated)
        assertEquals(first.ledgerEntryIds, second.ledgerEntryIds)
        assertEquals(first.todoIds, second.todoIds)
        assertEquals(1, database.ledgerDao().countBySourceBatchId("direct-batch"))
        assertEquals(1, repository.observeAllTodos().first().size)
    }

    @Test
    fun changedDirectRoutingPayloadIsRejectedAndOriginalRemains() = runBlocking {
        val batchId = "conflict-batch"
        val entries = listOf(validExpenseDraft())
        val todos = listOf(LedgerTodoDraft(content = "核对午餐发票"))
        repository.saveLedgerRouting(batchId, entries, todos)

        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId,
                ledgerEntries = listOf(validExpenseDraft().copy(amountFen = 2_900L)),
                todoItems = todos
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId,
                ledgerEntries = entries,
                todoItems = emptyList()
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId,
                ledgerEntries = emptyList(),
                todoItems = todos
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId,
                ledgerEntries = entries + validExpenseDraft().copy(title = "晚餐", amountFen = 3_600L),
                todoItems = todos
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId,
                ledgerEntries = entries,
                todoItems = todos + LedgerTodoDraft(content = "核对晚餐发票")
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId,
                ledgerEntries = entries,
                todoItems = listOf(LedgerTodoDraft(content = "核对晚餐发票"))
            )
        }

        val persistedEntries = database.ledgerDao().observeLedgerEntries().first()
        assertEquals(1, persistedEntries.size)
        assertEquals(2_800L, persistedEntries.single().amount)
        assertEquals(listOf("核对午餐发票"), repository.observeAllTodos().first().map { it.title })
    }

    @Test
    fun todoOnlyBatchUsesCompletePayloadIdempotency() = runBlocking {
        val todos = listOf(LedgerTodoDraft(content = "核对银行卡账单"))

        val first = repository.saveLedgerRouting("todo-only-batch", emptyList(), todos)
        val repeated = repository.saveLedgerRouting("todo-only-batch", emptyList(), todos)
        expectIllegalArgument {
            repository.saveLedgerRouting(
                "todo-only-batch",
                ledgerEntries = emptyList(),
                todoItems = listOf(LedgerTodoDraft(content = "核对信用卡账单"))
            )
        }

        assertTrue(first.wasCreated)
        assertFalse(repeated.wasCreated)
        assertEquals(first.todoIds, repeated.todoIds)
        assertEquals(listOf("核对银行卡账单"), repository.observeAllTodos().first().map { it.title })
    }

    @Test
    fun invalidLedgerRollsBackWholeConversion() = runBlocking {
        val note = repository.addQuickNote("金额待确认")
        val invalid = LedgerEntryDraft(
            title = "无效金额",
            amountFen = 0L,
            direction = LedgerDirection.EXPENSE,
            category = LedgerCategory.FOOD,
            occurredAtEpochMillis = 1_000L,
            isEstimated = true,
            aiConfidence = 0.1f,
            emotion = "刚需",
            necessity = "need"
        )

        var failed = false
        try {
            repository.convertNoteToLedger(
                note.id,
                ledgerEntries = listOf(invalid),
                todoItems = listOf(LedgerTodoDraft("不应落库")),
                batchId = "rollback-batch"
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(0, database.ledgerDao().observeLedgerEntries().first().size)
        assertEquals(0, repository.observeAllTodos().first().size)
        val unchanged = repository.observeAllQuickNotes().first().single()
        assertEquals(QuickNoteStatus.RAW.storedValue, unchanged.status)
        assertNotNull(database.quickNoteDao().getById(note.id))
    }

    @Test
    fun emptyRoutingAndUnwarnedEstimateAreRejected() = runBlocking {
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId = "empty-batch",
                ledgerEntries = emptyList(),
                todoItems = emptyList()
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId = "estimated-without-warning",
                ledgerEntries = listOf(
                    LedgerEntryDraft(
                        title = "估算午餐",
                        amountFen = 2_800L,
                        direction = LedgerDirection.EXPENSE,
                        category = LedgerCategory.FOOD,
                        occurredAtEpochMillis = 1_000L,
                        isEstimated = true,
                        aiConfidence = 0.5f,
                        emotion = "刚需",
                        necessity = "need"
                    )
                ),
                todoItems = emptyList()
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId = "missing-ai-enum",
                ledgerEntries = listOf(validExpenseDraft().copy(emotion = "未知")),
                todoItems = emptyList()
            )
        }
        expectIllegalArgument {
            repository.saveLedgerRouting(
                batchId = "too-many-items",
                ledgerEntries = List(21) { index ->
                    validExpenseDraft().copy(title = "账目$index")
                },
                todoItems = emptyList()
            )
        }

        assertTrue(database.ledgerDao().observeLedgerEntries().first().isEmpty())
        assertTrue(repository.observeAllTodos().first().isEmpty())
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("预期抛出 IllegalArgumentException", failed)
    }

    private fun validExpenseDraft(): LedgerEntryDraft = LedgerEntryDraft(
        title = "午餐",
        amountFen = 2_800L,
        direction = LedgerDirection.EXPENSE,
        category = LedgerCategory.FOOD,
        occurredAtEpochMillis = 1_000L,
        isEstimated = false,
        aiConfidence = 0.9f,
        emotion = "刚需",
        necessity = "need"
    )
}
