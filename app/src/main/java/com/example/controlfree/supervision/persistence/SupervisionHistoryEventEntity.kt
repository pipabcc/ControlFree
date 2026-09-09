package com.example.controlfree.supervision.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "supervision_history_events",
    indices = [
        Index(value = ["boot_instance_key"], unique = true),
        Index(value = ["occurred_at_epoch_millis"]),
        Index(value = ["event_type", "occurred_at_epoch_millis"])
    ]
)
data class SupervisionHistoryEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "boot_instance_key") val bootInstanceKey: String,
    @ColumnInfo(name = "boot_count") val bootCount: Int?,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis") val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "runtime_slot") val runtimeSlot: String?,
    @ColumnInfo(name = "recovery_status") val recoveryStatus: String,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)
