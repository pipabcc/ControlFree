package com.example.controlfree.growth

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "growth_accounts")
data class GrowthAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "balance_points")
    val balancePoints: Int,
    @ColumnInfo(name = "reserved_points")
    val reservedPoints: Int,
    @ColumnInfo(name = "lifetime_experience")
    val lifetimeExperience: Long,
    @ColumnInfo(name = "lifetime_spent_points")
    val lifetimeSpentPoints: Long,
    val revision: Long,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "growth_ledger",
    foreignKeys = [
        ForeignKey(
            entity = GrowthAccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["account_id", "occurred_at_epoch_millis"]),
        Index(value = ["related_cycle_id"]),
        Index(value = ["related_order_id"])
    ]
)
data class GrowthLedgerEntity(
    @PrimaryKey
    @ColumnInfo(name = "ledger_id")
    val ledgerId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "entry_type")
    val entryType: String,
    val reason: String,
    @ColumnInfo(name = "balance_delta_points")
    val balanceDeltaPoints: Int,
    @ColumnInfo(name = "reserved_delta_points")
    val reservedDeltaPoints: Int,
    @ColumnInfo(name = "experience_delta")
    val experienceDelta: Int,
    @ColumnInfo(name = "spending_delta_points")
    val spendingDeltaPoints: Int,
    @ColumnInfo(name = "counts_toward_daily_cap")
    val countsTowardDailyCap: Boolean,
    @ColumnInfo(name = "related_cycle_id")
    val relatedCycleId: String?,
    @ColumnInfo(name = "related_order_id")
    val relatedOrderId: String?,
    @ColumnInfo(name = "policy_version")
    val policyVersion: Int,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long
)

@Entity(
    tableName = "growth_cycle_outcomes",
    foreignKeys = [
        ForeignKey(
            entity = GrowthAccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["run_id", "cycle_ordinal"], unique = true),
        Index(value = ["ledger_source_key"])
    ]
)
data class GrowthCycleResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "cycle_id")
    val cycleId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Long,
    val mode: String,
    @ColumnInfo(name = "configured_duration_seconds")
    val configuredDurationSeconds: Long,
    @ColumnInfo(name = "valid_elapsed_seconds")
    val validElapsedSeconds: Long,
    @ColumnInfo(name = "paused_seconds")
    val pausedSeconds: Long,
    val outcome: String,
    @ColumnInfo(name = "awarded_experience_points")
    val awardedExperiencePoints: Int,
    @ColumnInfo(name = "credited_balance_points")
    val creditedBalancePoints: Int,
    @ColumnInfo(name = "ledger_source_key")
    val ledgerSourceKey: String?,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long
)

@Entity(
    tableName = "growth_unlock_orders",
    foreignKeys = [
        ForeignKey(
            entity = GrowthAccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["account_id", "state"]),
        Index(value = ["lock_session_id", "action_kind"])
    ]
)
data class GrowthUnlockOrderEntity(
    @PrimaryKey
    @ColumnInfo(name = "order_id")
    val orderId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "lock_session_id")
    val lockSessionId: String,
    @ColumnInfo(name = "action_kind")
    val actionKind: String,
    @ColumnInfo(name = "pause_duration_minutes")
    val pauseDurationMinutes: Int,
    @ColumnInfo(name = "authentication_kind")
    val authenticationKind: String,
    @ColumnInfo(name = "authentication_id")
    val authenticationId: String,
    @ColumnInfo(name = "nominal_cost_points")
    val nominalCostPoints: Int,
    @ColumnInfo(name = "reserved_cost_points")
    val reservedCostPoints: Int,
    @ColumnInfo(name = "cost_waived")
    val costWaived: Boolean,
    val state: String,
    @ColumnInfo(name = "policy_version")
    val policyVersion: Int,
    @ColumnInfo(name = "prepared_at_epoch_millis")
    val preparedAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at_epoch_millis")
    val expiresAtEpochMillis: Long,
    @ColumnInfo(name = "finalized_at_epoch_millis")
    val finalizedAtEpochMillis: Long?
)
