package com.example.controlfree.supervision.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SupervisionPlanDao {
    @Transaction
    @Query("SELECT * FROM supervision_plans ORDER BY created_at_epoch_millis, plan_id")
    fun observeAll(): Flow<List<SupervisionPlanWithRanges>>

    @Transaction
    @Query("SELECT * FROM supervision_plans ORDER BY created_at_epoch_millis, plan_id")
    suspend fun getAll(): List<SupervisionPlanWithRanges>

    @Transaction
    @Query("SELECT * FROM supervision_plans WHERE plan_id = :planId")
    suspend fun getById(planId: String): SupervisionPlanWithRanges?

    @Upsert
    suspend fun upsertPlan(plan: SupervisionPlanEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlanIfAbsent(plan: SupervisionPlanEntity): Long

    @Upsert
    suspend fun upsertGlobalPolicy(policy: GlobalSupervisionPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGlobalPolicyIfAbsent(policy: GlobalSupervisionPolicyEntity): Long

    @Upsert
    suspend fun upsertAppPolicy(policy: AppSupervisionPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppPolicyIfAbsent(policy: AppSupervisionPolicyEntity): Long

    @Upsert
    suspend fun upsertActivationReservation(reservation: PlanActivationReservationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivationReservationIfAbsent(
        reservation: PlanActivationReservationEntity
    ): Long

    @Query("SELECT * FROM plan_activation_reservations WHERE plan_id = :planId")
    suspend fun getActivationReservation(planId: String): PlanActivationReservationEntity?

    @Query(
        "SELECT * FROM plan_activation_reservations " +
            "WHERE enable_at_epoch_millis <= :nowEpochMillis " +
            "ORDER BY enable_at_epoch_millis, plan_id"
    )
    suspend fun getDueActivationReservations(
        nowEpochMillis: Long
    ): List<PlanActivationReservationEntity>

    @Query("DELETE FROM plan_activation_reservations WHERE plan_id = :planId")
    suspend fun deleteActivationReservation(planId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimeRanges(ranges: List<SupervisionTimeRangeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTimeRangesIfAbsent(ranges: List<SupervisionTimeRangeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDisabledTimeRanges(ranges: List<AppSupervisionDisabledTimeRangeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTriggerApps(apps: List<SupervisionPlanTriggerAppEntity>)

    @Query("DELETE FROM supervision_time_ranges WHERE plan_id = :planId")
    suspend fun deleteTimeRanges(planId: String)

    @Query("DELETE FROM app_supervision_disabled_time_ranges WHERE plan_id = :planId")
    suspend fun deleteDisabledTimeRanges(planId: String)

    @Query("DELETE FROM supervision_plan_trigger_apps WHERE plan_id = :planId")
    suspend fun deleteTriggerApps(planId: String)

    @Query("DELETE FROM global_supervision_policies WHERE plan_id = :planId")
    suspend fun deleteGlobalPolicy(planId: String)

    @Query("DELETE FROM app_supervision_policies WHERE plan_id = :planId")
    suspend fun deleteAppPolicy(planId: String)

    @Query("DELETE FROM supervision_plans WHERE plan_id = :planId")
    suspend fun delete(planId: String): Int

    @Transaction
    suspend fun replace(record: SupervisionPlanRecord) {
        upsertPlan(record.plan)
        deleteTimeRanges(record.plan.planId)
        deleteDisabledTimeRanges(record.plan.planId)
        deleteTriggerApps(record.plan.planId)
        deleteGlobalPolicy(record.plan.planId)
        deleteAppPolicy(record.plan.planId)
        record.globalPolicy?.let { upsertGlobalPolicy(it) }
        record.appPolicy?.let { upsertAppPolicy(it) }
        insertTimeRanges(record.ranges)
        insertDisabledTimeRanges(record.disabledRanges)
        insertTriggerApps(record.triggerApps)
    }
}
