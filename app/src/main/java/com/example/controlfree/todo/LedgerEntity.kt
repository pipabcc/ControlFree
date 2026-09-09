package com.example.controlfree.todo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ledger_entries",
    indices = [
        Index(value = ["occurred_at_epoch_millis"]),
        Index(value = ["direction"]),
        Index(value = ["category"]),
        Index(value = ["source_note_id"]),
        Index(value = ["source_batch_id"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val amount: Long,
    val direction: String,               // EXPENSE / INCOME
    val category: String,                // LedgerCategory.storedValue
    val title: String,
    val note: String?,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "source_note_id") val sourceNoteId: String?,
    @ColumnInfo(name = "isEstimated") val isEstimated: Boolean = false,
    val emotion: String?,
    val necessity: String?,
    @ColumnInfo(name = "createdAtEpochMillis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updatedAtEpochMillis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "ai_confidence", defaultValue = "0") val aiConfidence: Float = 0f,
    @ColumnInfo(name = "source_type", defaultValue = "'AI'") val sourceType: String = LedgerEntrySourceType.AI.storedValue,
    @ColumnInfo(name = "ai_warnings_json") val aiWarningsJson: String? = null,
    @ColumnInfo(name = "source_batch_id") val sourceBatchId: String? = null
)
