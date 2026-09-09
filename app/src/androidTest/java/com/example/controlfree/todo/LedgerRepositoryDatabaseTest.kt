package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryDatabaseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-07-22T04:00:00Z")
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(database, Clock.fixed(fixedNow, zone))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ledgerDaoOrdersByOccurredAtAndSupportsDelete() = runBlocking {
        val older = ledger("older", 1_000L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, 100L)
        val newer = ledger("newer", 2_000L, LedgerDirection.INCOME, LedgerCategory.SALARY, 200L)

        repository.saveLedgerEntries(listOf(older, newer))

        val observed = database.ledgerDao().observeLedgerEntries().first()
        assertEquals(listOf("newer", "older"), observed.map { it.id })
        assertEquals(2, observed.size)

        repository.deleteLedgerEntry("newer")
        assertEquals(listOf("older"), database.ledgerDao().observeLedgerEntries().first().map { it.id })
        assertEquals(0, repository.deleteLedgerEntry("missing"))
    }

    @Test
    fun restoreDeletedEntryPreservesEntityAndDoesNotOverwriteNewId() = runBlocking {
        val historical = LedgerEntryEntity(
            id = "legacy-entry",
            amount = Long.MIN_VALUE,
            direction = "UNKNOWN",
            category = "UNKNOWN",
            title = "旧账目",
            note = "旧备注",
            occurredAtEpochMillis = -1L,
            sourceNoteId = "legacy-note",
            isEstimated = true,
            emotion = "未知情绪",
            necessity = "unknown",
            createdAtEpochMillis = -2L,
            updatedAtEpochMillis = -3L,
            aiConfidence = -1f,
            sourceType = "LEGACY",
            aiWarningsJson = "not-json",
            sourceBatchId = "legacy-batch"
        )
        database.ledgerDao().insertLedgerEntries(listOf(historical))

        assertEquals(1, repository.deleteLedgerEntry(historical.id))
        assertTrue(repository.restoreDeletedLedgerEntry(historical))
        assertEquals(historical, database.ledgerDao().getLedgerEntryById(historical.id))

        assertEquals(1, repository.deleteLedgerEntry(historical.id))
        val replacement = historical.copy(
            amount = 1_000L,
            direction = LedgerDirection.EXPENSE.storedValue,
            category = LedgerCategory.FOOD.storedValue,
            title = "新记录"
        )
        database.ledgerDao().insertLedgerEntries(listOf(replacement))

        assertFalse(repository.restoreDeletedLedgerEntry(historical))
        assertEquals(replacement, database.ledgerDao().getLedgerEntryById(historical.id))
    }

    @Test
    fun userEditUpdatesEditableFieldsAndPreservesLegacyMetadata() = runBlocking {
        val historical = LedgerEntryEntity(
            id = "legacy-${"x".repeat(200)}",
            amount = Long.MIN_VALUE,
            direction = "UNKNOWN",
            category = "UNKNOWN",
            title = "",
            note = "n".repeat(201),
            occurredAtEpochMillis = -1L,
            sourceNoteId = "source-${"x".repeat(200)}",
            isEstimated = true,
            emotion = "未知情绪",
            necessity = "unknown",
            createdAtEpochMillis = -2L,
            updatedAtEpochMillis = -3L,
            aiConfidence = -1f,
            sourceType = "LEGACY",
            aiWarningsJson = "not-json",
            sourceBatchId = "legacy-batch"
        )
        database.ledgerDao().insertLedgerEntries(listOf(historical))

        val updated = repository.updateLedgerEntryUserFields(
            id = historical.id,
            title = " 修正账目 ",
            amountFen = 1_234L,
            direction = LedgerDirection.EXPENSE,
            category = LedgerCategory.FOOD
        )

        val expected = historical.copy(
            amount = 1_234L,
            direction = LedgerDirection.EXPENSE.storedValue,
            category = LedgerCategory.FOOD.storedValue,
            title = "修正账目",
            isEstimated = false,
            updatedAtEpochMillis = fixedNow.toEpochMilli()
        )
        assertEquals(expected, updated)
        assertEquals(expected, database.ledgerDao().getLedgerEntryById(historical.id))
    }

    @Test
    fun monthlySummaryAggregatesByDirectionAndCategory() = runBlocking {
        val monthTimestamp = fixedNow.toEpochMilli()
        repository.saveLedgerEntries(
            listOf(
                ledger(
                    "food-a",
                    2_800L,
                    LedgerDirection.EXPENSE,
                    LedgerCategory.FOOD,
                    monthTimestamp + 10L
                ),
                ledger(
                    "food-b",
                    1_200L,
                    LedgerDirection.EXPENSE,
                    LedgerCategory.FOOD,
                    monthTimestamp + 20L
                ),
                ledger(
                    "transport",
                    500L,
                    LedgerDirection.EXPENSE,
                    LedgerCategory.TRANSPORT,
                    monthTimestamp + 30L
                ),
                ledger(
                    "salary",
                    100_000L,
                    LedgerDirection.INCOME,
                    LedgerCategory.SALARY,
                    monthTimestamp + 40L
                )
            )
        )

        val summary = repository.observeMonthSummary(2026, 7).first()
        assertEquals(4_500L, summary.totalExpense)
        assertEquals(100_000L, summary.totalIncome)
        assertEquals(95_500L, summary.balance)
        assertEquals(
            listOf(CategoryTotal(LedgerCategory.FOOD.storedValue, 4_000L), CategoryTotal(LedgerCategory.TRANSPORT.storedValue, 500L)),
            summary.categoryTotals
        )
    }

    @Test
    fun monthlySummaryIgnoresInvalidMigratedRowsWithoutOverflow() = runBlocking {
        val monthTimestamp = fixedNow.toEpochMilli()
        database.ledgerDao().insertLedgerEntries(
            listOf(
                ledger("expense", 2_800L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, monthTimestamp),
                ledger("income", 10_000L, LedgerDirection.INCOME, LedgerCategory.SALARY, monthTimestamp),
                ledger(
                    "legacy-overflow",
                    Long.MAX_VALUE,
                    LedgerDirection.EXPENSE,
                    LedgerCategory.FOOD,
                    monthTimestamp
                ),
                ledger("legacy-negative", -1L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, monthTimestamp),
                ledger("legacy-zero", 0L, LedgerDirection.INCOME, LedgerCategory.SALARY, monthTimestamp),
                ledger("legacy-unknown", 9_999L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, monthTimestamp)
                    .copy(direction = "UNKNOWN", category = "UNKNOWN"),
                ledger("legacy-mismatch", 8_888L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, monthTimestamp)
                    .copy(category = LedgerCategory.SALARY.storedValue)
            )
        )

        val summary = repository.observeMonthSummary(2026, 7).first()

        assertEquals(2_800L, summary.totalExpense)
        assertEquals(10_000L, summary.totalIncome)
        assertEquals(7_200L, summary.balance)
        assertEquals(listOf(CategoryTotal(LedgerCategory.FOOD.storedValue, 2_800L)), summary.categoryTotals)
    }

    @Test
    fun monthQueryUsesHalfOpenRangeWithoutDroppingLastMillis() = runBlocking {
        val lastMillisecond = Instant.parse("2026-07-31T15:59:59.999Z").toEpochMilli()
        val nextMonthStart = Instant.parse("2026-07-31T16:00:00Z").toEpochMilli()
        repository.saveLedgerEntries(
            listOf(
                ledger("last", 2_800L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, lastMillisecond),
                ledger("next", 9_900L, LedgerDirection.EXPENSE, LedgerCategory.FOOD, nextMonthStart)
            )
        )

        val july = repository.observeMonthSummary(2026, 7).first()
        assertEquals(2_800L, july.totalExpense)
        val julyEntries = database.ledgerDao().observeLedgerEntriesBetween(
            Instant.parse("2026-07-01T16:00:00Z").toEpochMilli(),
            nextMonthStart
        ).first()
        assertTrue(julyEntries.any { it.id == "last" })
        assertFalse(julyEntries.any { it.id == "next" })
        assertFalse(database.ledgerDao().observeLedgerEntriesBetween(
            nextMonthStart,
            Instant.parse("2026-08-31T16:00:00Z").toEpochMilli()
        ).first().any { it.id == "last" })
    }

    private fun ledger(
        id: String,
        amount: Long,
        direction: LedgerDirection,
        category: LedgerCategory,
        occurredAt: Long
    ): LedgerEntryEntity = LedgerEntryEntity(
        id = id,
        amount = amount,
        direction = direction.storedValue,
        category = category.storedValue,
        title = id,
        note = null,
        occurredAtEpochMillis = occurredAt,
        sourceNoteId = null,
        emotion = null,
        necessity = null,
        createdAtEpochMillis = occurredAt,
        updatedAtEpochMillis = occurredAt
    )
}
