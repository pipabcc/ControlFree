package com.example.controlfree.growth

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GrowthDao {
    @Query("SELECT * FROM growth_accounts WHERE account_id = :accountId LIMIT 1")
    suspend fun getAccount(accountId: String = DEFAULT_GROWTH_ACCOUNT_ID): GrowthAccountEntity?

    @Query("SELECT * FROM growth_accounts WHERE account_id = :accountId LIMIT 1")
    fun observeAccount(accountId: String = DEFAULT_GROWTH_ACCOUNT_ID): Flow<GrowthAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(entity: GrowthAccountEntity): Long

    @Query(
        "UPDATE growth_accounts SET balance_points = :balancePoints, " +
            "reserved_points = :reservedPoints, lifetime_experience = :lifetimeExperience, " +
            "lifetime_spent_points = :lifetimeSpentPoints, revision = :newRevision, " +
            "updated_at_epoch_millis = :updatedAtEpochMillis " +
            "WHERE account_id = :accountId AND revision = :expectedRevision"
    )
    suspend fun updateAccountIfRevisionMatches(
        accountId: String,
        balancePoints: Int,
        reservedPoints: Int,
        lifetimeExperience: Long,
        lifetimeSpentPoints: Long,
        expectedRevision: Long,
        newRevision: Long,
        updatedAtEpochMillis: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedger(entity: GrowthLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedgerIfAbsent(entity: GrowthLedgerEntity): Long

    @Query("SELECT * FROM growth_ledger WHERE source_key = :sourceKey LIMIT 1")
    suspend fun getLedgerBySourceKey(sourceKey: String): GrowthLedgerEntity?

    @Query(
        "SELECT * FROM growth_ledger WHERE account_id = :accountId " +
            "ORDER BY occurred_at_epoch_millis DESC, ledger_id DESC LIMIT :limit"
    )
    fun observeRecentLedger(
        accountId: String = DEFAULT_GROWTH_ACCOUNT_ID,
        limit: Int
    ): Flow<List<GrowthLedgerEntity>>

    @Query(
        "SELECT COALESCE(SUM(experience_delta), 0) FROM growth_ledger " +
            "WHERE account_id = :accountId AND counts_toward_daily_cap = 1 " +
            "AND occurred_at_epoch_millis >= :startEpochMillis " +
            "AND occurred_at_epoch_millis < :endExclusiveEpochMillis"
    )
    suspend fun sumDailyCappedExperience(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        accountId: String = DEFAULT_GROWTH_ACCOUNT_ID
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCycleResult(entity: GrowthCycleResultEntity)

    @Query("SELECT * FROM growth_cycle_outcomes WHERE cycle_id = :cycleId LIMIT 1")
    suspend fun getCycleResult(cycleId: String): GrowthCycleResultEntity?

    @Query(
        "SELECT * FROM growth_cycle_outcomes " +
            "WHERE run_id = :runId AND cycle_ordinal = :cycleOrdinal LIMIT 1"
    )
    suspend fun getCycleResult(runId: String, cycleOrdinal: Long): GrowthCycleResultEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUnlockOrder(entity: GrowthUnlockOrderEntity)

    @Query("SELECT * FROM growth_unlock_orders WHERE order_id = :orderId LIMIT 1")
    suspend fun getUnlockOrder(orderId: String): GrowthUnlockOrderEntity?

    @Query(
        "UPDATE growth_unlock_orders SET state = :newState, " +
            "finalized_at_epoch_millis = :finalizedAtEpochMillis " +
            "WHERE order_id = :orderId AND state = :expectedState"
    )
    suspend fun updateUnlockOrderState(
        orderId: String,
        expectedState: String,
        newState: String,
        finalizedAtEpochMillis: Long
    ): Int

    @Query(
        "SELECT COALESCE(SUM(pause_duration_minutes), 0) FROM growth_unlock_orders " +
            "WHERE account_id = :accountId AND action_kind = 'pause' AND state = 'applied' " +
            "AND prepared_at_epoch_millis >= :startEpochMillis " +
            "AND prepared_at_epoch_millis < :endExclusiveEpochMillis"
    )
    suspend fun sumAppliedPauseMinutes(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        accountId: String = DEFAULT_GROWTH_ACCOUNT_ID
    ): Long

    @Query(
        "SELECT * FROM growth_unlock_orders " +
            "WHERE account_id = :accountId AND action_kind = 'pause' AND state = 'applied' " +
            "AND prepared_at_epoch_millis >= :startEpochMillis " +
            "AND prepared_at_epoch_millis < :endExclusiveEpochMillis " +
            "ORDER BY prepared_at_epoch_millis, order_id"
    )
    suspend fun getAppliedPauseOrders(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        accountId: String = DEFAULT_GROWTH_ACCOUNT_ID
    ): List<GrowthUnlockOrderEntity>

    @Query(
        "SELECT * FROM growth_unlock_orders WHERE state = 'prepared' " +
            "AND expires_at_epoch_millis <= :nowEpochMillis " +
            "ORDER BY expires_at_epoch_millis, order_id"
    )
    suspend fun getExpiredPreparedOrders(nowEpochMillis: Long): List<GrowthUnlockOrderEntity>
}
