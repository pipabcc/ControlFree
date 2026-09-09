package com.example.controlfree.todo

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeBlockEventDao {
    @Query("SELECT * FROM time_block_events ORDER BY start_at_epoch_millis, id")
    fun observeAll(): Flow<List<TimeBlockEventEntity>>

    @Query(
        "SELECT * FROM time_block_events " +
            "WHERE start_at_epoch_millis < :endExclusiveEpochMillis " +
            "AND end_at_epoch_millis > :startEpochMillis " +
            "ORDER BY start_at_epoch_millis, id"
    )
    fun observeInRange(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<TimeBlockEventEntity>>

    @Query("SELECT * FROM time_block_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TimeBlockEventEntity?

    @Upsert
    suspend fun upsert(event: TimeBlockEventEntity)

    @Query(
        "UPDATE time_block_events SET " +
            "is_completed = :isCompleted, completed_at_epoch_millis = :completedAt, " +
            "updated_at_epoch_millis = :updatedAt WHERE id = :id"
    )
    suspend fun updateCompletion(
        id: String,
        isCompleted: Boolean,
        completedAt: Long?,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM time_block_events WHERE id = :id")
    suspend fun delete(id: String): Int
}
