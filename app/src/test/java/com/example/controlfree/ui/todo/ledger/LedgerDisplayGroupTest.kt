package com.example.controlfree.ui.todo.ledger

import com.example.controlfree.todo.LedgerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerDisplayGroupTest {
    @Test
    fun `同批次条目合并为一组并保持首次出现位置`() {
        val groups = ledgerDisplayGroups(
            listOf(
                entry(id = "a", batchId = "b1", amountFen = 500L),
                entry(id = "b", batchId = null, amountFen = 100L),
                entry(id = "c", batchId = "b1", amountFen = 300L),
                entry(id = "d", batchId = "b2", amountFen = 800L)
            )
        )

        assertEquals(3, groups.size)
        assertEquals("b1", groups[0].batchId)
        assertEquals(listOf("a", "c"), groups[0].entries.map(LedgerEntryEntity::id))
        assertNull(groups[1].batchId)
        assertEquals(listOf("b"), groups[1].entries.map(LedgerEntryEntity::id))
        assertEquals("b2", groups[2].batchId)
    }

    @Test
    fun `无批次条目各自成组`() {
        val groups = ledgerDisplayGroups(
            listOf(
                entry(id = "a", batchId = null, amountFen = 100L),
                entry(id = "b", batchId = null, amountFen = 200L)
            )
        )

        assertEquals(2, groups.size)
        assertEquals(1, groups[0].entries.size)
        assertEquals(1, groups[1].entries.size)
    }

    @Test
    fun `空列表返回空分组`() {
        assertEquals(0, ledgerDisplayGroups(emptyList()).size)
    }

    private fun entry(
        id: String,
        batchId: String?,
        amountFen: Long
    ) = LedgerEntryEntity(
        id = id,
        amount = amountFen,
        direction = "EXPENSE",
        category = "FOOD",
        title = "条目$id",
        note = null,
        occurredAtEpochMillis = 1_800_000_000_000L,
        sourceNoteId = null,
        isEstimated = false,
        emotion = null,
        necessity = null,
        createdAtEpochMillis = 1_800_000_000_000L,
        updatedAtEpochMillis = 1_800_000_000_000L,
        sourceBatchId = batchId
    )
}
