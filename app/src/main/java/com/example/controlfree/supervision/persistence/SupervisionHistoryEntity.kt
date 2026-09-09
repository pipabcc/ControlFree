package com.example.controlfree.supervision.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "supervision_sessions",
    indices = [
        Index(value = ["runtime_slot"], unique = true),
        Index(value = ["started_at_epoch_millis"]),
        Index(value = ["session_kind", "started_at_epoch_millis"]),
        Index(value = ["plan_id"]),
        Index(value = ["package_name"])
    ]
)
data class SupervisionSessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "runtime_slot") val runtimeSlot: String?,
    @ColumnInfo(name = "session_kind") val sessionKind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "plan_id") val planId: String?,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "usage_minutes") val usageMinutes: Int?,
    @ColumnInfo(name = "lock_minutes") val lockMinutes: Int?,
    @ColumnInfo(name = "started_at_epoch_millis") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "ended_at_epoch_millis") val endedAtEpochMillis: Long?,
    @ColumnInfo(name = "end_reason") val endReason: String?,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)
