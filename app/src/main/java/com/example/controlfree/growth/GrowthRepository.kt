package com.example.controlfree.growth

import android.content.Context
import androidx.room.withTransaction
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 成长值的原子结算入口。
 *
 * 调用方只提交已由监督运行时确认的事实。所有余额、流水、周期结果和解锁订单都在同一个
 * Room 事务内更新，sourceKey 与 orderId 负责跨进程重试时的幂等。
 */
class GrowthRepository private constructor(
    private val database: ControlFreeDatabase
) {
    private val dao = database.growthDao()

    suspend fun getAccount(nowEpochMillis: Long = System.currentTimeMillis()): GrowthAccount =
        database.withTransaction {
            ensureAccountInTransaction(nowEpochMillis).toDomain()
        }

    suspend fun getOrCreateAccount(nowEpochMillis: Long = System.currentTimeMillis()): GrowthAccount =
        getAccount(nowEpochMillis)

    fun observeAccount(): Flow<GrowthAccount> = flow {
        getAccount()
        emitAll(
            dao.observeAccount().map { entity ->
                checkNotNull(entity) { "成长账户在初始化后丢失" }.toDomain()
            }
        )
    }

    fun observeRecentLedger(limit: Int = 100): Flow<List<GrowthLedgerEntry>> {
        require(limit in 1..1_000) { "成长流水读取数量无效" }
        return flow {
            getAccount()
            emitAll(dao.observeRecentLedger(limit = limit).map { entries ->
                entries.map(GrowthLedgerEntity::toDomain)
            })
        }
    }

    fun recentLedger(limit: Int = 100): Flow<List<GrowthLedgerEntry>> = observeRecentLedger(limit)

    /**
     * 只在自然倒计时完成时产生奖励；跳过、取消和恢复不确定仅记录周期结果。
     * cycleId 及 runId+cycleOrdinal 都是稳定幂等键。
     */
    suspend fun settleCycle(request: GrowthCycleSettlementRequest): GrowthCycleSettlement =
        database.withTransaction {
            val existingById = dao.getCycleResult(request.cycleId)
            if (existingById != null) {
                check(existingById.matches(request)) { "同一成长周期 ID 的结算参数不一致" }
                return@withTransaction GrowthCycleSettlement(
                    cycle = existingById.toDomain(),
                    account = ensureAccountInTransaction(request.occurredAtEpochMillis).toDomain(),
                    wasAlreadySettled = true
                )
            }
            dao.getCycleResult(request.runId, request.cycleOrdinal)?.let { collision ->
                error(
                    "监督运行 ${request.runId} 的周期序号 ${request.cycleOrdinal} " +
                        "已由 ${collision.cycleId} 结算"
                )
            }

            val current = ensureAccountInTransaction(request.occurredAtEpochMillis)
            val earnedToday = dao.sumDailyCappedExperience(
                request.localDayStartEpochMillis,
                request.localDayEndExclusiveEpochMillis
            ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val awardedExperience = if (request.outcome == GrowthCycleOutcome.TIMER_COMPLETED) {
                GrowthPolicy.quoteSupervisionReward(
                    validSupervisedSeconds = request.validElapsedSeconds,
                    commonExperienceEarnedToday = earnedToday
                ).awardedPoints
            } else {
                0
            }
            val creditedBalance = min(
                awardedExperience,
                (GrowthPolicy.MAX_BALANCE_POINTS - current.balancePoints).coerceAtLeast(0)
            )
            val ledgerSourceKey = if (awardedExperience > 0) {
                "cycle:${request.cycleId}:natural-completion"
            } else {
                null
            }
            val resultEntity = GrowthCycleResultEntity(
                cycleId = request.cycleId,
                accountId = DEFAULT_GROWTH_ACCOUNT_ID,
                runId = request.runId,
                cycleOrdinal = request.cycleOrdinal,
                mode = request.mode,
                configuredDurationSeconds = request.configuredDurationSeconds,
                validElapsedSeconds = request.validElapsedSeconds,
                pausedSeconds = request.pausedSeconds,
                outcome = request.outcome.storedValue,
                awardedExperiencePoints = awardedExperience,
                creditedBalancePoints = creditedBalance,
                ledgerSourceKey = ledgerSourceKey,
                recordedAtEpochMillis = request.occurredAtEpochMillis
            )
            dao.insertCycleResult(resultEntity)

            val updated = if (awardedExperience > 0) {
                updateAccount(
                    current = current,
                    balancePoints = current.balancePoints + creditedBalance,
                    reservedPoints = current.reservedPoints,
                    lifetimeExperience = current.lifetimeExperience + awardedExperience,
                    lifetimeSpentPoints = current.lifetimeSpentPoints,
                    nowEpochMillis = request.occurredAtEpochMillis
                ).also {
                    dao.insertLedger(
                        ledgerEntity(
                            sourceKey = checkNotNull(ledgerSourceKey),
                            type = GrowthLedgerEntryType.REWARD,
                            reason = GrowthLedgerReason.NATURAL_SUPERVISION_COMPLETION,
                            balanceDeltaPoints = creditedBalance,
                            experienceDelta = awardedExperience,
                            countsTowardDailyCap = true,
                            relatedCycleId = request.cycleId,
                            occurredAtEpochMillis = request.occurredAtEpochMillis
                        )
                    )
                }
            } else {
                current
            }
            GrowthCycleSettlement(
                cycle = resultEntity.toDomain(),
                account = updated.toDomain(),
                wasAlreadySettled = false
            )
        }

    suspend fun awardContinuation(request: ContinuationRewardRequest): GrowthAwardResult =
        database.withTransaction {
            dao.getLedgerBySourceKey(request.sourceKey)?.let { existing ->
                check(existing.reason == GrowthLedgerReason.CONTINUED_SELF_DISCIPLINE.storedValue) {
                    "继续自律奖励 sourceKey 已被其他流水占用"
                }
                check(existing.relatedCycleId == request.cycleId) {
                    "继续自律奖励 sourceKey 对应的周期不一致"
                }
                return@withTransaction GrowthAwardResult(
                    entry = existing.toDomain(),
                    account = ensureAccountInTransaction(request.occurredAtEpochMillis).toDomain(),
                    wasAlreadyAwarded = true
                )
            }

            val current = ensureAccountInTransaction(request.occurredAtEpochMillis)
            val earnedToday = dao.sumDailyCappedExperience(
                request.localDayStartEpochMillis,
                request.localDayEndExclusiveEpochMillis
            ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val experience = GrowthPolicy.quoteContinuationReward(
                additionalValidSeconds = request.additionalValidSeconds,
                commonExperienceEarnedToday = earnedToday,
                alreadyGrantedForCycle = false
            )
            val creditedBalance = min(
                experience,
                (GrowthPolicy.MAX_BALANCE_POINTS - current.balancePoints).coerceAtLeast(0)
            )
            val entry = ledgerEntity(
                sourceKey = request.sourceKey,
                type = GrowthLedgerEntryType.REWARD,
                reason = GrowthLedgerReason.CONTINUED_SELF_DISCIPLINE,
                balanceDeltaPoints = creditedBalance,
                experienceDelta = experience,
                countsTowardDailyCap = true,
                relatedCycleId = request.cycleId,
                occurredAtEpochMillis = request.occurredAtEpochMillis
            )
            val updated = updateAccount(
                current = current,
                balancePoints = current.balancePoints + creditedBalance,
                reservedPoints = current.reservedPoints,
                lifetimeExperience = current.lifetimeExperience + experience,
                lifetimeSpentPoints = current.lifetimeSpentPoints,
                nowEpochMillis = request.occurredAtEpochMillis
            )
            dao.insertLedger(entry)
            GrowthAwardResult(entry.toDomain(), updated.toDomain(), wasAlreadyAwarded = false)
        }

    suspend fun awardProductivity(
        request: ProductivityRewardRequest
    ): GrowthAwardResult = database.withTransaction {
        dao.getLedgerBySourceKey(request.sourceKey)?.let { existing ->
            check(existing.reason == request.reason.storedValue) {
                "生产力奖励 sourceKey 已被其他奖励原因占用"
            }
            check(existing.experienceDelta == request.experiencePoints) {
                "生产力奖励 sourceKey 的经验值不一致"
            }
            return@withTransaction GrowthAwardResult(
                entry = existing.toDomain(),
                account = ensureAccountInTransaction(request.occurredAtEpochMillis).toDomain(),
                wasAlreadyAwarded = true
            )
        }

        val current = ensureAccountInTransaction(request.occurredAtEpochMillis)
        val creditedBalance = min(
            request.rewardPoints,
            (GrowthPolicy.MAX_BALANCE_POINTS - current.balancePoints).coerceAtLeast(0)
        )
        val entry = ledgerEntity(
            sourceKey = request.sourceKey,
            type = GrowthLedgerEntryType.REWARD,
            reason = request.reason,
            balanceDeltaPoints = creditedBalance,
            experienceDelta = request.experiencePoints,
            countsTowardDailyCap = false,
            occurredAtEpochMillis = request.occurredAtEpochMillis
        )
        val updated = updateAccount(
            current = current,
            balancePoints = current.balancePoints + creditedBalance,
            reservedPoints = current.reservedPoints,
            lifetimeExperience = current.lifetimeExperience + request.experiencePoints,
            lifetimeSpentPoints = current.lifetimeSpentPoints,
            nowEpochMillis = request.occurredAtEpochMillis
        )
        dao.insertLedger(entry)
        GrowthAwardResult(entry.toDomain(), updated.toDomain(), wasAlreadyAwarded = false)
    }

    @Deprecated("使用带明确奖励原因的 awardProductivity")
    suspend fun awardTodoOrHabit(
        sourceKey: String,
        balanceReward: Int,
        experienceReward: Int,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): GrowthAwardResult = awardProductivity(
        ProductivityRewardRequest(
            sourceKey = sourceKey,
            reason = if (sourceKey.startsWith("habit:")) {
                GrowthLedgerReason.HABIT_CHECKED_IN
            } else {
                GrowthLedgerReason.TODO_COMPLETED
            },
            rewardPoints = balanceReward,
            experiencePoints = experienceReward,
            occurredAtEpochMillis = nowEpochMillis
        )
    )

    /** 返回当日已经成功执行的暂停分钟数，供 [GrowthPolicy.quotePauseCost] 计算增量费用。 */
    suspend fun getAppliedPauseMinutes(
        localDayStartEpochMillis: Long,
        localDayEndExclusiveEpochMillis: Long
    ): Int {
        require(localDayStartEpochMillis >= 0L) { "自然日起点不能为负数" }
        require(localDayEndExclusiveEpochMillis > localDayStartEpochMillis) { "自然日区间无效" }
        return dao.sumAppliedPauseMinutes(
            localDayStartEpochMillis,
            localDayEndExclusiveEpochMillis
        ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 验证成功后预留成长值。普通操作不会动用 20 点应急保留值；应急操作可选择按余额部分扣费。
     */
    suspend fun prepareUnlockOrder(request: PrepareGrowthUnlockOrderRequest): GrowthPrepareResult =
        database.withTransaction {
            prepareUnlockOrderInTransaction(request)
        }

    /**
     * 计算当天已成功暂停的累计分钟并预留本次增量费用。挑战通关只把本次费用置零，仍要求验证凭据。
     */
    suspend fun preparePauseOrder(request: PreparePauseOrderRequest): GrowthPrepareResult =
        database.withTransaction {
            dao.getUnlockOrder(request.orderId)?.let { existing ->
                check(existing.matchesIdentity(request)) { "同一暂停订单 ID 的参数不一致" }
                val account = ensureAccountInTransaction(request.preparedAtEpochMillis)
                return@withTransaction GrowthPrepareResult(
                    GrowthPrepareStatus.ALREADY_EXISTS,
                    existing.toDomain(),
                    account.toDomain(),
                    existing.nominalCostPoints,
                    existing.reservedCostPoints
                )
            }
            val cost = if (request.waiveCost) {
                0
            } else {
                GrowthPolicy.quotePauseCost(
                    appliedOrders = dao.getAppliedPauseOrders(
                        request.localDayStartEpochMillis,
                        request.localDayEndExclusiveEpochMillis
                    ).map { order ->
                        AppliedPausePricingRecord(
                            pauseDurationMinutes = order.pauseDurationMinutes,
                            policyVersion = order.policyVersion,
                            costWaived = order.costWaived
                        )
                    },
                    requestedPauseMinutes = request.requestedPauseMinutes
                ).incrementalCostPoints
            }
            prepareUnlockOrderInTransaction(
                PrepareGrowthUnlockOrderRequest(
                    orderId = request.orderId,
                    lockSessionId = request.lockSessionId,
                    actionKind = GrowthUnlockActionKind.PAUSE,
                    pauseDurationMinutes = request.requestedPauseMinutes,
                    authenticationKind = request.authenticationKind,
                    authenticationId = request.authenticationId,
                    nominalCostPoints = cost,
                    preparedAtEpochMillis = request.preparedAtEpochMillis,
                    expiresAtEpochMillis = request.expiresAtEpochMillis,
                    costWaived = request.waiveCost
                )
            )
        }

    suspend fun prepareSkipOrder(request: PrepareSkipOrderRequest): GrowthPrepareResult =
        prepareUnlockOrder(
            PrepareGrowthUnlockOrderRequest(
                orderId = request.orderId,
                lockSessionId = request.lockSessionId,
                actionKind = GrowthUnlockActionKind.SKIP,
                authenticationKind = request.authenticationKind,
                authenticationId = request.authenticationId,
                nominalCostPoints = if (request.waiveCost) 0 else GrowthPolicy.SKIP_COST_POINTS,
                preparedAtEpochMillis = request.preparedAtEpochMillis,
                expiresAtEpochMillis = request.expiresAtEpochMillis,
                costWaived = request.waiveCost
            )
        )

    suspend fun commitOrder(orderId: String, nowEpochMillis: Long): GrowthUnlockOrder =
        commitUnlockOrder(orderId, nowEpochMillis)

    suspend fun refundOrder(orderId: String, nowEpochMillis: Long): GrowthUnlockOrder =
        refundUnlockOrder(orderId, nowEpochMillis)

    /** 监督快照成功落盘后调用；重复调用同一 orderId 不会重复扣费。 */
    suspend fun commitUnlockOrder(
        orderId: String,
        finalizedAtEpochMillis: Long
    ): GrowthUnlockOrder = database.withTransaction {
        val order = requireNotNull(dao.getUnlockOrder(orderId)) { "解锁订单不存在" }
        when (order.requireState()) {
            GrowthUnlockOrderState.APPLIED -> return@withTransaction order.toDomain()
            GrowthUnlockOrderState.REFUNDED,
            GrowthUnlockOrderState.EXPIRED -> error("终态解锁订单不能提交")
            GrowthUnlockOrderState.PREPARED -> Unit
        }
        require(finalizedAtEpochMillis >= order.preparedAtEpochMillis) { "订单完成时间无效" }
        val current = requireNotNull(dao.getAccount()) { "成长账户不存在" }
        check(current.reservedPoints >= order.reservedCostPoints) { "解锁订单预留成长值不完整" }
        check(current.balancePoints >= order.reservedCostPoints) { "解锁订单扣费超出余额" }
        check(
            dao.updateUnlockOrderState(
                orderId = orderId,
                expectedState = GrowthUnlockOrderState.PREPARED.storedValue,
                newState = GrowthUnlockOrderState.APPLIED.storedValue,
                finalizedAtEpochMillis = finalizedAtEpochMillis
            ) == 1
        ) { "解锁订单状态已并发变化" }
        updateAccount(
            current = current,
            balancePoints = current.balancePoints - order.reservedCostPoints,
            reservedPoints = current.reservedPoints - order.reservedCostPoints,
            lifetimeExperience = current.lifetimeExperience,
            lifetimeSpentPoints = current.lifetimeSpentPoints + order.reservedCostPoints,
            nowEpochMillis = finalizedAtEpochMillis
        )
        dao.insertLedger(
            ledgerEntity(
                sourceKey = "order:$orderId:applied",
                type = GrowthLedgerEntryType.SPEND,
                reason = GrowthLedgerReason.UNLOCK_APPLIED,
                balanceDeltaPoints = -order.reservedCostPoints,
                reservedDeltaPoints = -order.reservedCostPoints,
                spendingDeltaPoints = order.reservedCostPoints,
                relatedOrderId = orderId,
                occurredAtEpochMillis = finalizedAtEpochMillis
            )
        )
        checkNotNull(dao.getUnlockOrder(orderId)).toDomain()
    }

    /** 操作落盘失败时调用；既支持释放预留，也支持补偿已经提交的扣费。 */
    suspend fun refundUnlockOrder(
        orderId: String,
        finalizedAtEpochMillis: Long
    ): GrowthUnlockOrder = database.withTransaction {
        val order = requireNotNull(dao.getUnlockOrder(orderId)) { "解锁订单不存在" }
        val oldState = order.requireState()
        if (oldState == GrowthUnlockOrderState.REFUNDED || oldState == GrowthUnlockOrderState.EXPIRED) {
            return@withTransaction order.toDomain()
        }
        require(finalizedAtEpochMillis >= order.preparedAtEpochMillis) { "订单退款时间无效" }
        val current = requireNotNull(dao.getAccount()) { "成长账户不存在" }
        val refundBalance = if (oldState == GrowthUnlockOrderState.APPLIED) {
            order.reservedCostPoints
        } else {
            0
        }
        val releasedReservation = if (oldState == GrowthUnlockOrderState.PREPARED) {
            order.reservedCostPoints
        } else {
            0
        }
        check(current.reservedPoints >= releasedReservation) { "待退款订单预留成长值不完整" }
        check(
            dao.updateUnlockOrderState(
                orderId = orderId,
                expectedState = oldState.storedValue,
                newState = GrowthUnlockOrderState.REFUNDED.storedValue,
                finalizedAtEpochMillis = finalizedAtEpochMillis
            ) == 1
        ) { "解锁订单状态已并发变化" }
        updateAccount(
            current = current,
            balancePoints = current.balancePoints + refundBalance,
            reservedPoints = current.reservedPoints - releasedReservation,
            lifetimeExperience = current.lifetimeExperience,
            lifetimeSpentPoints = if (oldState == GrowthUnlockOrderState.APPLIED) {
                (current.lifetimeSpentPoints - order.reservedCostPoints).coerceAtLeast(0L)
            } else {
                current.lifetimeSpentPoints
            },
            nowEpochMillis = finalizedAtEpochMillis
        )
        dao.insertLedger(
            ledgerEntity(
                sourceKey = "order:$orderId:refunded",
                type = GrowthLedgerEntryType.REFUND,
                reason = GrowthLedgerReason.UNLOCK_REFUNDED,
                balanceDeltaPoints = refundBalance,
                reservedDeltaPoints = -releasedReservation,
                spendingDeltaPoints = if (oldState == GrowthUnlockOrderState.APPLIED) {
                    -order.reservedCostPoints
                } else {
                    0
                },
                relatedOrderId = orderId,
                occurredAtEpochMillis = finalizedAtEpochMillis
            )
        )
        checkNotNull(dao.getUnlockOrder(orderId)).toDomain()
    }

    suspend fun expirePreparedOrders(nowEpochMillis: Long): List<GrowthUnlockOrder> =
        database.withTransaction {
            require(nowEpochMillis >= 0L) { "过期清理时间不能为负数" }
            dao.getExpiredPreparedOrders(nowEpochMillis).map { order ->
                expirePreparedOrderInTransaction(order, nowEpochMillis).toDomain()
            }
        }

    suspend fun getUnlockOrder(orderId: String): GrowthUnlockOrder? =
        dao.getUnlockOrder(orderId)?.toDomain()

    private suspend fun expirePreparedOrderInTransaction(
        order: GrowthUnlockOrderEntity,
        nowEpochMillis: Long
    ): GrowthUnlockOrderEntity {
        check(order.requireState() == GrowthUnlockOrderState.PREPARED)
        val current = requireNotNull(dao.getAccount()) { "成长账户不存在" }
        check(current.reservedPoints >= order.reservedCostPoints) { "过期订单预留成长值不完整" }
        check(
            dao.updateUnlockOrderState(
                orderId = order.orderId,
                expectedState = GrowthUnlockOrderState.PREPARED.storedValue,
                newState = GrowthUnlockOrderState.EXPIRED.storedValue,
                finalizedAtEpochMillis = nowEpochMillis
            ) == 1
        ) { "解锁订单状态已并发变化" }
        updateAccount(
            current = current,
            balancePoints = current.balancePoints,
            reservedPoints = current.reservedPoints - order.reservedCostPoints,
            lifetimeExperience = current.lifetimeExperience,
            lifetimeSpentPoints = current.lifetimeSpentPoints,
            nowEpochMillis = nowEpochMillis
        )
        dao.insertLedger(
            ledgerEntity(
                sourceKey = "order:${order.orderId}:expired",
                type = GrowthLedgerEntryType.EXPIRATION,
                reason = GrowthLedgerReason.UNLOCK_RESERVATION_EXPIRED,
                reservedDeltaPoints = -order.reservedCostPoints,
                relatedOrderId = order.orderId,
                occurredAtEpochMillis = nowEpochMillis
            )
        )
        return checkNotNull(dao.getUnlockOrder(order.orderId))
    }

    private suspend fun prepareUnlockOrderInTransaction(
        request: PrepareGrowthUnlockOrderRequest
    ): GrowthPrepareResult {
        val current = ensureAccountInTransaction(request.preparedAtEpochMillis)
        dao.getUnlockOrder(request.orderId)?.let { existing ->
            check(existing.matches(request)) { "同一解锁订单 ID 的参数不一致" }
            return GrowthPrepareResult(
                status = GrowthPrepareStatus.ALREADY_EXISTS,
                order = existing.toDomain(),
                account = current.toDomain(),
                nominalCostPoints = existing.nominalCostPoints,
                payableCostPoints = existing.reservedCostPoints
            )
        }

        val spendQuote = when {
            request.allowPartialPayment -> GrowthPolicy.quoteEmergencySpend(
                request.nominalCostPoints,
                current.balancePoints,
                current.reservedPoints
            )
            request.allowReserveUse -> quoteFullSpendUsingReserve(
                request.nominalCostPoints,
                current.balancePoints,
                current.reservedPoints
            )
            else -> GrowthPolicy.quoteOrdinarySpend(
                request.nominalCostPoints,
                current.balancePoints,
                current.reservedPoints
            )
        }
        if (!spendQuote.allowed) {
            return GrowthPrepareResult(
                status = GrowthPrepareStatus.INSUFFICIENT_BALANCE,
                order = null,
                account = current.toDomain(),
                nominalCostPoints = request.nominalCostPoints,
                payableCostPoints = 0
            )
        }

        val order = GrowthUnlockOrderEntity(
            orderId = request.orderId,
            accountId = DEFAULT_GROWTH_ACCOUNT_ID,
            lockSessionId = request.lockSessionId,
            actionKind = request.actionKind.storedValue,
            pauseDurationMinutes = request.pauseDurationMinutes,
            authenticationKind = request.authenticationKind,
            authenticationId = request.authenticationId,
            nominalCostPoints = request.nominalCostPoints,
            reservedCostPoints = spendQuote.payableCostPoints,
            costWaived = request.costWaived,
            state = GrowthUnlockOrderState.PREPARED.storedValue,
            policyVersion = GrowthPolicy.POLICY_VERSION,
            preparedAtEpochMillis = request.preparedAtEpochMillis,
            expiresAtEpochMillis = request.expiresAtEpochMillis,
            finalizedAtEpochMillis = null
        )
        dao.insertUnlockOrder(order)
        val updated = updateAccount(
            current = current,
            balancePoints = current.balancePoints,
            reservedPoints = current.reservedPoints + spendQuote.payableCostPoints,
            lifetimeExperience = current.lifetimeExperience,
            lifetimeSpentPoints = current.lifetimeSpentPoints,
            nowEpochMillis = request.preparedAtEpochMillis
        )
        dao.insertLedger(
            ledgerEntity(
                sourceKey = "order:${request.orderId}:prepared",
                type = GrowthLedgerEntryType.RESERVATION,
                reason = GrowthLedgerReason.UNLOCK_RESERVED,
                reservedDeltaPoints = spendQuote.payableCostPoints,
                relatedOrderId = request.orderId,
                occurredAtEpochMillis = request.preparedAtEpochMillis
            )
        )
        return GrowthPrepareResult(
            status = GrowthPrepareStatus.PREPARED,
            order = order.toDomain(),
            account = updated.toDomain(),
            nominalCostPoints = request.nominalCostPoints,
            payableCostPoints = spendQuote.payableCostPoints
        )
    }

    private suspend fun ensureAccountInTransaction(nowEpochMillis: Long): GrowthAccountEntity {
        require(nowEpochMillis >= 0L) { "成长账户时间不能为负数" }
        dao.getAccount()?.let { return it }
        val created = GrowthAccountEntity(
            accountId = DEFAULT_GROWTH_ACCOUNT_ID,
            balancePoints = GrowthPolicy.INITIAL_BALANCE_POINTS,
            reservedPoints = 0,
            lifetimeExperience = 0L,
            lifetimeSpentPoints = 0L,
            revision = 0L,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis
        )
        val inserted = dao.insertAccount(created) != -1L
        val account = checkNotNull(dao.getAccount()) { "成长账户创建失败" }
        if (inserted) {
            dao.insertLedgerIfAbsent(
                ledgerEntity(
                    ledgerId = INITIAL_GRANT_LEDGER_ID,
                    sourceKey = INITIAL_GRANT_SOURCE_KEY,
                    type = GrowthLedgerEntryType.INITIAL_GRANT,
                    reason = GrowthLedgerReason.INSTALLATION_GRANT,
                    balanceDeltaPoints = GrowthPolicy.INITIAL_BALANCE_POINTS,
                    occurredAtEpochMillis = nowEpochMillis
                )
            )
        }
        return account
    }

    private suspend fun updateAccount(
        current: GrowthAccountEntity,
        balancePoints: Int,
        reservedPoints: Int,
        lifetimeExperience: Long,
        lifetimeSpentPoints: Long,
        nowEpochMillis: Long
    ): GrowthAccountEntity {
        // 余额上限只约束奖励入账；已扣费用的补偿退款必须完整返还，允许暂时超过上限。
        require(balancePoints >= 0) { "成长值余额不能为负数" }
        require(reservedPoints in 0..balancePoints) { "预留成长值越界" }
        require(lifetimeExperience >= 0L) { "终身成长经验不能为负数" }
        require(lifetimeSpentPoints >= 0L) { "终身消费成长值不能为负数" }
        val newRevision = current.revision + 1L
        check(
            dao.updateAccountIfRevisionMatches(
                accountId = current.accountId,
                balancePoints = balancePoints,
                reservedPoints = reservedPoints,
                lifetimeExperience = lifetimeExperience,
                lifetimeSpentPoints = lifetimeSpentPoints,
                expectedRevision = current.revision,
                newRevision = newRevision,
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, nowEpochMillis)
            ) == 1
        ) { "成长账户已发生并发变化" }
        return checkNotNull(dao.getAccount(current.accountId))
    }

    private fun ledgerEntity(
        ledgerId: String = UUID.randomUUID().toString(),
        sourceKey: String,
        type: GrowthLedgerEntryType,
        reason: GrowthLedgerReason,
        balanceDeltaPoints: Int = 0,
        reservedDeltaPoints: Int = 0,
        experienceDelta: Int = 0,
        spendingDeltaPoints: Int = 0,
        countsTowardDailyCap: Boolean = false,
        relatedCycleId: String? = null,
        relatedOrderId: String? = null,
        occurredAtEpochMillis: Long
    ) = GrowthLedgerEntity(
        ledgerId = ledgerId,
        accountId = DEFAULT_GROWTH_ACCOUNT_ID,
        sourceKey = sourceKey,
        entryType = type.storedValue,
        reason = reason.storedValue,
        balanceDeltaPoints = balanceDeltaPoints,
        reservedDeltaPoints = reservedDeltaPoints,
        experienceDelta = experienceDelta,
        spendingDeltaPoints = spendingDeltaPoints,
        countsTowardDailyCap = countsTowardDailyCap,
        relatedCycleId = relatedCycleId,
        relatedOrderId = relatedOrderId,
        policyVersion = GrowthPolicy.POLICY_VERSION,
        occurredAtEpochMillis = occurredAtEpochMillis
    )

    companion object {
        private const val INITIAL_GRANT_LEDGER_ID = "initial-growth-grant-v1"
        private const val INITIAL_GRANT_SOURCE_KEY = "account:default:initial:v1"

        @Volatile
        private var instance: GrowthRepository? = null

        fun getInstance(context: Context): GrowthRepository =
            instance ?: synchronized(this) {
                instance ?: GrowthRepository(
                    ControlFreeDatabase.getInstance(context.applicationContext)
                ).also { instance = it }
            }

        internal fun createForTest(database: ControlFreeDatabase): GrowthRepository =
            GrowthRepository(database)
    }
}

private fun quoteFullSpendUsingReserve(
    nominalCostPoints: Int,
    balancePoints: Int,
    reservedPoints: Int
): GrowthSpendQuote {
    require(nominalCostPoints >= 0)
    require(balancePoints >= 0)
    require(reservedPoints in 0..balancePoints)
    val available = balancePoints - reservedPoints
    return GrowthSpendQuote(
        nominalCostPoints = nominalCostPoints,
        payableCostPoints = if (available >= nominalCostPoints) nominalCostPoints else 0,
        availableBalancePoints = available,
        reserveToKeepPoints = 0,
        allowed = available >= nominalCostPoints,
        isPartialPayment = false
    )
}

private fun GrowthAccountEntity.toDomain() = GrowthAccount(
    accountId = accountId,
    balancePoints = balancePoints,
    reservedPoints = reservedPoints,
    lifetimeExperience = lifetimeExperience,
    lifetimeSpentPoints = lifetimeSpentPoints,
    revision = revision,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun GrowthLedgerEntity.toDomain() = GrowthLedgerEntry(
    ledgerId = ledgerId,
    accountId = accountId,
    sourceKey = sourceKey,
    type = requireNotNull(GrowthLedgerEntryType.fromStoredValue(entryType)) { "成长流水类型无效" },
    reason = requireNotNull(GrowthLedgerReason.fromStoredValue(reason)) { "成长流水原因无效" },
    balanceDeltaPoints = balanceDeltaPoints,
    reservedDeltaPoints = reservedDeltaPoints,
    experienceDelta = experienceDelta,
    spendingDeltaPoints = spendingDeltaPoints,
    countsTowardDailyCap = countsTowardDailyCap,
    relatedCycleId = relatedCycleId,
    relatedOrderId = relatedOrderId,
    policyVersion = policyVersion,
    occurredAtEpochMillis = occurredAtEpochMillis
)

private fun GrowthCycleResultEntity.toDomain() = GrowthCycleResult(
    cycleId = cycleId,
    accountId = accountId,
    runId = runId,
    cycleOrdinal = cycleOrdinal,
    mode = mode,
    configuredDurationSeconds = configuredDurationSeconds,
    validElapsedSeconds = validElapsedSeconds,
    pausedSeconds = pausedSeconds,
    outcome = requireNotNull(GrowthCycleOutcome.fromStoredValue(outcome)) { "成长周期结果无效" },
    awardedExperiencePoints = awardedExperiencePoints,
    creditedBalancePoints = creditedBalancePoints,
    ledgerSourceKey = ledgerSourceKey,
    recordedAtEpochMillis = recordedAtEpochMillis
)

private fun GrowthUnlockOrderEntity.toDomain() = GrowthUnlockOrder(
    orderId = orderId,
    accountId = accountId,
    lockSessionId = lockSessionId,
    actionKind = requireNotNull(GrowthUnlockActionKind.fromStoredValue(actionKind)) { "解锁动作无效" },
    pauseDurationMinutes = pauseDurationMinutes,
    authenticationKind = authenticationKind,
    authenticationId = authenticationId,
    nominalCostPoints = nominalCostPoints,
    reservedCostPoints = reservedCostPoints,
    costWaived = costWaived,
    state = requireState(),
    policyVersion = policyVersion,
    preparedAtEpochMillis = preparedAtEpochMillis,
    expiresAtEpochMillis = expiresAtEpochMillis,
    finalizedAtEpochMillis = finalizedAtEpochMillis
)

private fun GrowthUnlockOrderEntity.requireState(): GrowthUnlockOrderState =
    requireNotNull(GrowthUnlockOrderState.fromStoredValue(state)) { "解锁订单状态无效" }

private fun GrowthCycleResultEntity.matches(request: GrowthCycleSettlementRequest): Boolean =
    cycleId == request.cycleId &&
        runId == request.runId &&
        cycleOrdinal == request.cycleOrdinal &&
        mode == request.mode &&
        configuredDurationSeconds == request.configuredDurationSeconds &&
        validElapsedSeconds == request.validElapsedSeconds &&
        pausedSeconds == request.pausedSeconds &&
        outcome == request.outcome.storedValue

private fun GrowthUnlockOrderEntity.matches(request: PrepareGrowthUnlockOrderRequest): Boolean =
    orderId == request.orderId &&
        lockSessionId == request.lockSessionId &&
        actionKind == request.actionKind.storedValue &&
        pauseDurationMinutes == request.pauseDurationMinutes &&
        authenticationKind == request.authenticationKind &&
        authenticationId == request.authenticationId &&
        nominalCostPoints == request.nominalCostPoints &&
        costWaived == request.costWaived

private fun GrowthUnlockOrderEntity.matchesIdentity(request: PreparePauseOrderRequest): Boolean =
    orderId == request.orderId &&
        lockSessionId == request.lockSessionId &&
        actionKind == GrowthUnlockActionKind.PAUSE.storedValue &&
        pauseDurationMinutes == request.requestedPauseMinutes &&
        authenticationKind == request.authenticationKind &&
        authenticationId == request.authenticationId &&
        costWaived == request.waiveCost
