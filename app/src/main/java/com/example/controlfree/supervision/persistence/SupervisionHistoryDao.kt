package com.example.controlfree.supervision.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SupervisionHistoryDao {
    @Query("SELECT * FROM supervision_sessions WHERE runtime_slot = :runtimeSlot LIMIT 1")
    suspend fun getActiveBySlot(runtimeSlot: String): SupervisionSessionEntity?

    @Query(
        "SELECT * FROM supervision_sessions " +
            "WHERE runtime_slot IS NOT NULL AND session_kind = 'app' " +
            "ORDER BY runtime_slot"
    )
    suspend fun getActiveApps(): List<SupervisionSessionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SupervisionSessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(entity: SupervisionHistoryEventEntity): Long

    @Query(
        "SELECT * FROM supervision_history_events " +
            "WHERE boot_instance_key = :bootInstanceKey LIMIT 1"
    )
    suspend fun getEventByBootInstance(
        bootInstanceKey: String
    ): SupervisionHistoryEventEntity?

    @Query(
        "UPDATE supervision_history_events SET recovery_status = :recoveryStatus, " +
            "updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis) " +
            "WHERE boot_instance_key = :bootInstanceKey"
    )
    suspend fun updateBootRecoveryStatus(
        bootInstanceKey: String,
        recoveryStatus: String,
        updatedAtEpochMillis: Long
    ): Int

    @Query(
        "UPDATE supervision_sessions SET runtime_slot = NULL, " +
            "ended_at_epoch_millis = :endedAtEpochMillis, end_reason = :endReason, " +
            "updated_at_epoch_millis = :endedAtEpochMillis " +
            "WHERE session_id = :sessionId AND runtime_slot IS NOT NULL"
    )
    suspend fun closeActive(
        sessionId: String,
        endedAtEpochMillis: Long,
        endReason: String
    ): Int

    @Query(
        "SELECT * FROM supervision_sessions " +
            "ORDER BY started_at_epoch_millis DESC, session_id DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<SupervisionSessionEntity>>

    @Query(
        "SELECT * FROM supervision_sessions " +
            "ORDER BY started_at_epoch_millis DESC, session_id DESC"
    )
    fun observeAll(): Flow<List<SupervisionSessionEntity>>

    @Query(
        "SELECT * FROM supervision_history_events " +
            "ORDER BY occurred_at_epoch_millis DESC, event_id DESC LIMIT :limit"
    )
    fun observeRecentEvents(limit: Int): Flow<List<SupervisionHistoryEventEntity>>

    @Query(
        "SELECT * FROM supervision_sessions " +
            "WHERE started_at_epoch_millis < :endExclusiveEpochMillis " +
            "AND (ended_at_epoch_millis IS NULL OR ended_at_epoch_millis > :startEpochMillis) " +
            "ORDER BY started_at_epoch_millis, session_id"
    )
    fun observeOverlapping(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<SupervisionSessionEntity>>

    @Query(
        "SELECT * FROM supervision_sessions " +
            "ORDER BY started_at_epoch_millis DESC, session_id DESC"
    )
    suspend fun getAll(): List<SupervisionSessionEntity>

    @Query(
        "SELECT * FROM supervision_history_events " +
            "ORDER BY occurred_at_epoch_millis, event_id"
    )
    suspend fun getAllEvents(): List<SupervisionHistoryEventEntity>

    @Query(
        "SELECT * FROM supervision_sessions " +
            "WHERE started_at_epoch_millis < :endExclusiveEpochMillis " +
            "AND COALESCE(ended_at_epoch_millis, :activeEndEpochMillis) > :startEpochMillis " +
            "ORDER BY started_at_epoch_millis, session_id"
    )
    suspend fun getOverlapping(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        activeEndEpochMillis: Long
    ): List<SupervisionSessionEntity>
}
