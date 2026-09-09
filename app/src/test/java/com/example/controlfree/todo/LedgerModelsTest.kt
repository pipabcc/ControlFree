package com.example.controlfree.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerModelsTest {
    @Test
    fun `收支方向持久化值可严格往返`() {
        LedgerDirection.entries.forEach { direction ->
            assertEquals(direction, LedgerDirection.fromStoredValue(direction.storedValue))
        }
        assertNull(LedgerDirection.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun `分类持久化值唯一且方向完整`() {
        val categories = LedgerCategory.entries

        assertEquals(categories.size, categories.map { it.storedValue }.toSet().size)
        assertTrue(categories.any { it.direction == LedgerDirection.EXPENSE })
        assertTrue(categories.any { it.direction == LedgerDirection.INCOME })
        categories.forEach { category ->
            assertEquals(category, LedgerCategory.fromStoredValue(category.storedValue))
        }
        assertNull(LedgerCategory.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun `账目来源持久化值可严格往返`() {
        LedgerEntrySourceType.entries.forEach { sourceType ->
            assertEquals(
                sourceType,
                LedgerEntrySourceType.fromStoredValue(sourceType.storedValue)
            )
        }
        assertNull(LedgerEntrySourceType.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun `实体默认记录AI来源和零置信度`() {
        val entry = LedgerEntryEntity(
            id = "ledger-1",
            amount = 1_200L,
            direction = LedgerDirection.EXPENSE.storedValue,
            category = LedgerCategory.FOOD.storedValue,
            title = "午餐",
            note = null,
            occurredAtEpochMillis = 1_000L,
            sourceNoteId = null,
            emotion = null,
            necessity = null,
            createdAtEpochMillis = 2_000L,
            updatedAtEpochMillis = 2_000L
        )

        assertEquals(LedgerEntrySourceType.AI.storedValue, entry.sourceType)
        assertEquals(0f, entry.aiConfidence, 0f)
        assertEquals(false, entry.isEstimated)
        assertNull(entry.aiWarningsJson)
        assertNull(entry.sourceBatchId)
    }

    @Test
    fun `金额编解码拒绝舍入和溢出`() {
        assertEquals(1_234L, LedgerAmountCodec.parseFen("12.34"))
        assertEquals(100L, LedgerAmountCodec.parseFen("1"))
        assertEquals(null, LedgerAmountCodec.parseFen("12.345"))
        assertEquals(null, LedgerAmountCodec.parseFen("-1"))
        assertEquals(null, LedgerAmountCodec.parseFen("100000000001"))
        assertEquals("0.00", LedgerAmountCodec.formatFen(0L))
        assertEquals("1000000000.00", LedgerAmountCodec.formatFen(100_000_000_000L))
        assertEquals("-12.34", LedgerAmountCodec.formatFen(-1_234L))
        assertEquals("-92233720368547758.08", LedgerAmountCodec.formatFen(Long.MIN_VALUE))
    }

    @Test
    fun `警告编码保留转义并对空值返回空`() {
        assertEquals(null, LedgerWarningCodec.encode(listOf(" ", "")))
        assertEquals("[\"含\\\"引号\"]", LedgerWarningCodec.encode(listOf("含\"引号")))
    }

    @Test
    fun `警告解码拒绝非法JSON和空警告`() {
        assertEquals(listOf("估算金额"), LedgerWarningCodec.decode("[\"估算金额\"]"))
        assertEquals(null, LedgerWarningCodec.decode("[1]"))
        assertEquals(null, LedgerWarningCodec.decode("[\"\"]"))
        assertEquals(null, LedgerWarningCodec.decode("not-json"))
    }
}
