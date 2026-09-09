package com.example.controlfree.growth

const val DEFAULT_GROWTH_ACCOUNT_ID = "default"

data class GrowthAccount(
    val accountId: String,
    val balancePoints: Int,
    val reservedPoints: Int,
    val lifetimeExperience: Long,
    val lifetimeSpentPoints: Long,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
) {
    val availableBalancePoints: Int
        get() = balancePoints - reservedPoints

    val levelProgress: GrowthLevelProgress
        get() = GrowthPolicy.levelForLifetimeExperience(lifetimeExperience)
}

enum class GrowthLedgerEntryType(val storedValue: String) {
    INITIAL_GRANT("initial_grant"),
    REWARD("reward"),
    RESERVATION("reservation"),
    SPEND("spend"),
    REFUND("refund"),
    EXPIRATION("expiration");

    companion object {
        fun fromStoredValue(value: String): GrowthLedgerEntryType? =
            entries.firstOrNull { entry -> entry.storedValue == value }
    }
}

enum class GrowthLedgerReason(val storedValue: String) {
    INSTALLATION_GRANT("installation_grant"),
    NATURAL_SUPERVISION_COMPLETION("natural_supervision_completion"),
    CONTINUED_SELF_DISCIPLINE("continued_self_discipline"),
    TODO_COMPLETED("todo_completed"),
    HABIT_CHECKED_IN("habit_checked_in"),
    QUICK_NOTE_CAPTURED("quick_note_captured"),
    STREAK_BADGE_UNLOCKED("streak_badge_unlocked"),
    ANNIVERSARY_REACHED("anniversary_reached"),
    UNLOCK_RESERVED("unlock_reserved"),
    UNLOCK_APPLIED("unlock_applied"),
    UNLOCK_REFUNDED("unlock_refunded"),
    UNLOCK_RESERVATION_EXPIRED("unlock_reservation_expired");

    companion object {
        fun fromStoredValue(value: String): GrowthLedgerReason? =
            entries.firstOrNull { reason -> reason.storedValue == value }
    }
}

data class ProductivityRewardRequest(
    val sourceKey: String,
    val reason: GrowthLedgerReason,
    val rewardPoints: Int,
    val experiencePoints: Int = rewardPoints,
    val occurredAtEpochMillis: Long
) {
    init {
        require(sourceKey.isNotBlank()) { "生产力奖励来源不能为空" }
        require(reason in PRODUCTIVITY_REWARD_REASONS) { "奖励原因不属于生产力功能" }
        require(rewardPoints in 0..100) { "生产力成长值奖励越界" }
        require(experiencePoints in 0..100) { "生产力经验奖励越界" }
        require(occurredAtEpochMillis >= 0L) { "生产力奖励时间无效" }
    }
}

val PRODUCTIVITY_REWARD_REASONS: Set<GrowthLedgerReason> = setOf(
    GrowthLedgerReason.TODO_COMPLETED,
    GrowthLedgerReason.HABIT_CHECKED_IN,
    GrowthLedgerReason.QUICK_NOTE_CAPTURED,
    GrowthLedgerReason.STREAK_BADGE_UNLOCKED,
    GrowthLedgerReason.ANNIVERSARY_REACHED
)

data class GrowthLedgerEntry(
    val ledgerId: String,
    val accountId: String,
    val sourceKey: String,
    val type: GrowthLedgerEntryType,
    val reason: GrowthLedgerReason,
    val balanceDeltaPoints: Int,
    val reservedDeltaPoints: Int,
    val experienceDelta: Int,
    val spendingDeltaPoints: Int,
    val countsTowardDailyCap: Boolean,
    val relatedCycleId: String?,
    val relatedOrderId: String?,
    val policyVersion: Int,
    val occurredAtEpochMillis: Long
)

enum class GrowthCycleOutcome(val storedValue: String) {
    TIMER_COMPLETED("timer_completed"),
    SKIPPED("skipped"),
    CANCELLED("cancelled"),
    RECOVERY_UNCERTAIN("recovery_uncertain");

    companion object {
        fun fromStoredValue(value: String): GrowthCycleOutcome? =
            entries.firstOrNull { outcome -> outcome.storedValue == value }
    }
}

data class GrowthCycleResult(
    val cycleId: String,
    val accountId: String,
    val runId: String,
    val cycleOrdinal: Long,
    val mode: String,
    val configuredDurationSeconds: Long,
    val validElapsedSeconds: Long,
    val pausedSeconds: Long,
    val outcome: GrowthCycleOutcome,
    val awardedExperiencePoints: Int,
    val creditedBalancePoints: Int,
    val ledgerSourceKey: String?,
    val recordedAtEpochMillis: Long
)

data class GrowthCycleSettlementRequest(
    val cycleId: String,
    val runId: String,
    val cycleOrdinal: Long,
    val mode: String,
    val configuredDurationSeconds: Long,
    val validElapsedSeconds: Long,
    val pausedSeconds: Long,
    val outcome: GrowthCycleOutcome,
    val occurredAtEpochMillis: Long,
    val localDayStartEpochMillis: Long,
    val localDayEndExclusiveEpochMillis: Long
) {
    init {
        require(cycleId.isNotBlank()) { "成长周期 ID 不能为空" }
        require(runId.isNotBlank()) { "监督运行 ID 不能为空" }
        require(cycleOrdinal >= 0L) { "成长周期序号不能为负数" }
        require(mode.isNotBlank()) { "监督模式不能为空" }
        require(configuredDurationSeconds >= 0L) { "配置时长不能为负数" }
        require(validElapsedSeconds >= 0L) { "有效时长不能为负数" }
        require(pausedSeconds >= 0L) { "暂停时长不能为负数" }
        require(occurredAtEpochMillis >= 0L) { "结算时间不能为负数" }
        require(localDayStartEpochMillis >= 0L) { "自然日起点不能为负数" }
        require(localDayEndExclusiveEpochMillis > localDayStartEpochMillis) {
            "自然日区间无效"
        }
        require(occurredAtEpochMillis in localDayStartEpochMillis until localDayEndExclusiveEpochMillis) {
            "结算时间必须位于指定自然日内"
        }
    }
}

data class GrowthCycleSettlement(
    val cycle: GrowthCycleResult,
    val account: GrowthAccount,
    val wasAlreadySettled: Boolean
)

data class ContinuationRewardRequest(
    val sourceKey: String,
    val cycleId: String,
    val additionalValidSeconds: Long,
    val occurredAtEpochMillis: Long,
    val localDayStartEpochMillis: Long,
    val localDayEndExclusiveEpochMillis: Long
) {
    init {
        require(sourceKey.isNotBlank()) { "继续自律奖励来源不能为空" }
        require(cycleId.isNotBlank()) { "成长周期 ID 不能为空" }
        require(additionalValidSeconds >= GrowthPolicy.CONTINUATION_REWARD_SECONDS) {
            "继续自律奖励必须在有效坚持满 5 分钟后结算"
        }
        require(occurredAtEpochMillis in localDayStartEpochMillis until localDayEndExclusiveEpochMillis) {
            "奖励时间必须位于指定自然日内"
        }
    }
}

data class GrowthAwardResult(
    val entry: GrowthLedgerEntry,
    val account: GrowthAccount,
    val wasAlreadyAwarded: Boolean
)

enum class GrowthUnlockOrderState(val storedValue: String) {
    PREPARED("prepared"),
    APPLIED("applied"),
    REFUNDED("refunded"),
    EXPIRED("expired");

    companion object {
        fun fromStoredValue(value: String): GrowthUnlockOrderState? =
            entries.firstOrNull { state -> state.storedValue == value }
    }
}

enum class GrowthUnlockActionKind(val storedValue: String) {
    PAUSE("pause"),
    SKIP("skip"),
    EMERGENCY_END("emergency_end");

    companion object {
        fun fromStoredValue(value: String): GrowthUnlockActionKind? =
            entries.firstOrNull { kind -> kind.storedValue == value }
    }
}

data class GrowthUnlockOrder(
    val orderId: String,
    val accountId: String,
    val lockSessionId: String,
    val actionKind: GrowthUnlockActionKind,
    val pauseDurationMinutes: Int,
    val authenticationKind: String,
    val authenticationId: String,
    val nominalCostPoints: Int,
    val reservedCostPoints: Int,
    val costWaived: Boolean,
    val state: GrowthUnlockOrderState,
    val policyVersion: Int,
    val preparedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long?
)

data class PrepareGrowthUnlockOrderRequest(
    val orderId: String,
    val lockSessionId: String,
    val actionKind: GrowthUnlockActionKind,
    val pauseDurationMinutes: Int = 0,
    val authenticationKind: String,
    val authenticationId: String,
    val nominalCostPoints: Int,
    val preparedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val allowReserveUse: Boolean = false,
    val allowPartialPayment: Boolean = false,
    val costWaived: Boolean = false
) {
    init {
        require(orderId.isNotBlank()) { "解锁订单 ID 不能为空" }
        require(lockSessionId.isNotBlank()) { "锁定会话 ID 不能为空" }
        require(authenticationKind.isNotBlank()) { "验证方式不能为空" }
        require(authenticationId.isNotBlank()) { "验证凭据 ID 不能为空" }
        when (actionKind) {
            GrowthUnlockActionKind.PAUSE -> require(pauseDurationMinutes in 1..30) {
                "暂停订单时长必须在 1 到 30 分钟之间"
            }
            else -> require(pauseDurationMinutes == 0) { "非暂停订单不能携带暂停时长" }
        }
        require(nominalCostPoints >= 0) { "成长值费用不能为负数" }
        require(preparedAtEpochMillis >= 0L) { "订单时间不能为负数" }
        require(expiresAtEpochMillis > preparedAtEpochMillis) { "订单过期时间无效" }
        require(!allowPartialPayment || allowReserveUse) {
            "部分扣费只允许用于可动用应急保留值的操作"
        }
        require(!costWaived || nominalCostPoints == 0) {
            "免除费用的订单实际费用必须为零"
        }
    }
}

data class PreparePauseOrderRequest(
    val orderId: String,
    val lockSessionId: String,
    val requestedPauseMinutes: Int,
    val authenticationKind: String,
    val authenticationId: String,
    val preparedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val localDayStartEpochMillis: Long,
    val localDayEndExclusiveEpochMillis: Long,
    /** 百科挑战通关时仅免除本次费用，身份验证字段仍然必填。 */
    val waiveCost: Boolean = false
) {
    init {
        require(requestedPauseMinutes in 1..30) { "暂停时长必须在 1 到 30 分钟之间" }
        require(preparedAtEpochMillis in localDayStartEpochMillis until localDayEndExclusiveEpochMillis) {
            "暂停订单时间必须位于指定自然日内"
        }
    }
}

data class PrepareSkipOrderRequest(
    val orderId: String,
    val lockSessionId: String,
    val authenticationKind: String,
    val authenticationId: String,
    val preparedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    /** 百科挑战通关时仅免除本次费用，身份验证字段仍然必填。 */
    val waiveCost: Boolean = false
)

enum class GrowthPrepareStatus {
    PREPARED,
    ALREADY_EXISTS,
    INSUFFICIENT_BALANCE
}

data class GrowthPrepareResult(
    val status: GrowthPrepareStatus,
    val order: GrowthUnlockOrder?,
    val account: GrowthAccount,
    val nominalCostPoints: Int,
    val payableCostPoints: Int
)
