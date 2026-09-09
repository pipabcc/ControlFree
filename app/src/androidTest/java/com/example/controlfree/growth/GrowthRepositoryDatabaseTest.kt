package com.example.controlfree.growth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GrowthRepositoryDatabaseTest {
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: GrowthRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = GrowthRepository.createForTest(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 新账户获得六十点但初始赠送不增加终身经验() = runBlocking {
        val account = repository.getOrCreateAccount(1_000L)
        val ledger = repository.recentLedger().first()

        assertEquals(60, account.balancePoints)
        assertEquals(0L, account.lifetimeExperience)
        assertEquals(1, account.levelProgress.level)
        assertEquals(1, ledger.size)
        assertEquals(GrowthLedgerEntryType.INITIAL_GRANT, ledger.single().type)
        assertEquals(0, ledger.single().experienceDelta)
    }

    @Test
    fun 自然完成周期原子奖励且重复结算不重复入账() = runBlocking {
        val request = cycle(
            cycleId = "cycle-1",
            runId = "run-1",
            ordinal = 1,
            validSeconds = 25 * 60L,
            now = 10_000L
        )
        val first = repository.settleCycle(request)
        val repeated = repository.settleCycle(request)

        assertEquals(4, first.cycle.awardedExperiencePoints)
        assertEquals(64, first.account.balancePoints)
        assertEquals(4L, first.account.lifetimeExperience)
        assertTrue(repeated.wasAlreadySettled)
        assertEquals(64, repeated.account.balancePoints)
        assertEquals(2, repository.recentLedger().first().size)
    }

    @Test
    fun 短时自然完成按第二版规则获得最低一点奖励() = runBlocking {
        val settled = repository.settleCycle(
            cycle(
                cycleId = "short-cycle",
                runId = "short-run",
                ordinal = 0,
                validSeconds = 5 * 60L,
                now = 10_000L
            )
        )

        assertEquals(1, settled.cycle.awardedExperiencePoints)
        assertEquals(61, settled.account.balancePoints)
        assertEquals(1L, settled.account.lifetimeExperience)
        val rewardLedger = repository.recentLedger().first().first()
        assertEquals(GrowthPolicy.POLICY_VERSION, rewardLedger.policyVersion)
    }

    @Test
    fun 跳过和不确定恢复只记录结果不产生奖励() = runBlocking {
        val skipped = repository.settleCycle(
            cycle("cycle-s", "run-s", 0, 60 * 60L, 20_000L, GrowthCycleOutcome.SKIPPED)
        )
        val uncertain = repository.settleCycle(
            cycle("cycle-u", "run-u", 0, 60 * 60L, 30_000L, GrowthCycleOutcome.RECOVERY_UNCERTAIN)
        )

        assertEquals(0, skipped.cycle.awardedExperiencePoints)
        assertEquals(0, uncertain.cycle.awardedExperiencePoints)
        assertEquals(60, uncertain.account.balancePoints)
        assertEquals(1, repository.recentLedger().first().size)
    }

    @Test
    fun 余额达到上限后继续累计终身经验但余额不越界() = runBlocking {
        repeat(21) { index ->
            val dayStart = index * DAY_MILLIS
            repository.settleCycle(
                GrowthCycleSettlementRequest(
                    cycleId = "cap-cycle-$index",
                    runId = "cap-run-$index",
                    cycleOrdinal = 0,
                    mode = "global",
                    configuredDurationSeconds = 10 * 60L * 60L,
                    validElapsedSeconds = 10 * 60L * 60L,
                    pausedSeconds = 0,
                    outcome = GrowthCycleOutcome.TIMER_COMPLETED,
                    occurredAtEpochMillis = dayStart + 1_000L,
                    localDayStartEpochMillis = dayStart,
                    localDayEndExclusiveEpochMillis = dayStart + DAY_MILLIS
                )
            )
        }

        val account = repository.getAccount()
        assertEquals(300, account.balancePoints)
        assertEquals(252L, account.lifetimeExperience)
    }

    @Test
    fun 暂停订单按当日已应用分钟计算增量并防止拆分降费() = runBlocking {
        val first = repository.preparePauseOrder(pause("pause-1", 5, now = 1_000L))
        assertEquals(1, first.payableCostPoints)
        repository.commitOrder("pause-1", 2_000L)

        val second = repository.preparePauseOrder(pause("pause-2", 10, now = 3_000L))
        assertEquals(1, second.payableCostPoints)
        repository.commitOrder("pause-2", 4_000L)

        val third = repository.preparePauseOrder(pause("pause-3", 1, now = 5_000L))
        assertEquals(0, third.payableCostPoints)
        assertEquals(15, repository.getAppliedPauseMinutes(0, DAY_MILLIS))
        repository.commitOrder("pause-3", 6_000L)

        assertEquals(58, repository.getAccount().balancePoints)
        assertEquals(16, repository.getAppliedPauseMinutes(0, DAY_MILLIS))
    }

    @Test
    fun 跨午夜提交的暂停仍计入订单创建所在自然日() = runBlocking {
        val preparedAt = DAY_MILLIS - 1_000L
        repository.preparePauseOrder(pause("pause-before-midnight", 5, now = preparedAt))
        repository.commitOrder("pause-before-midnight", DAY_MILLIS + 1_000L)

        assertEquals(5, repository.getAppliedPauseMinutes(0L, DAY_MILLIS))
        assertEquals(0, repository.getAppliedPauseMinutes(DAY_MILLIS, DAY_MILLIS * 2L))
    }

    @Test
    fun 相同订单重试可刷新调用时间但仍复用首次订单() = runBlocking {
        repository.prepareSkipOrder(skip("retry-skip", 1_000L))
        val repeatedSkip = repository.prepareSkipOrder(skip("retry-skip", 2_000L))

        val firstPause = pause("retry-pause", 5, now = 3_000L)
        repository.preparePauseOrder(firstPause)
        val repeatedPause = repository.preparePauseOrder(
            firstPause.copy(
                preparedAtEpochMillis = 4_000L,
                expiresAtEpochMillis = 65_000L
            )
        )

        assertEquals(GrowthPrepareStatus.ALREADY_EXISTS, repeatedSkip.status)
        assertEquals(1_000L, repeatedSkip.order?.preparedAtEpochMillis)
        assertEquals(GrowthPrepareStatus.ALREADY_EXISTS, repeatedPause.status)
        assertEquals(3_000L, repeatedPause.order?.preparedAtEpochMillis)
    }

    @Test
    fun 挑战通关暂停费用为零但订单仍保存验证身份与暂停时长() = runBlocking {
        val prepared = repository.preparePauseOrder(
            pause("challenge-pause", 30, now = 1_000L, waiveCost = true)
        )

        assertEquals(GrowthPrepareStatus.PREPARED, prepared.status)
        assertEquals(0, prepared.payableCostPoints)
        assertEquals(30, prepared.order?.pauseDurationMinutes)
        assertTrue(prepared.order?.costWaived == true)
        assertEquals("credential-1", prepared.order?.authenticationId)
        repository.commitOrder("challenge-pause", 2_000L)
        assertEquals(60, repository.getAccount().balancePoints)
    }

    @Test
    fun 跳过订单预留提交幂等且不会侵占二十点应急保留值() = runBlocking {
        suspend fun skip(orderId: String, now: Long): GrowthPrepareResult =
            repository.prepareSkipOrder(
                PrepareSkipOrderRequest(
                    orderId = orderId,
                    lockSessionId = "lock-$orderId",
                    authenticationKind = "password",
                    authenticationId = "auth-$orderId",
                    preparedAtEpochMillis = now,
                    expiresAtEpochMillis = now + 60_000L
                )
            )

        val first = skip("skip-1", 1_000L)
        val duplicate = skip("skip-1", 1_000L)
        assertEquals(GrowthPrepareStatus.PREPARED, first.status)
        assertEquals(GrowthPrepareStatus.ALREADY_EXISTS, duplicate.status)
        assertEquals(15, first.account.reservedPoints)
        repository.commitOrder("skip-1", 2_000L)
        repository.commitOrder("skip-1", 2_000L)

        skip("skip-2", 3_000L)
        repository.commitOrder("skip-2", 4_000L)
        val rejected = skip("skip-3", 5_000L)

        assertEquals(GrowthPrepareStatus.INSUFFICIENT_BALANCE, rejected.status)
        assertNull(rejected.order)
        assertEquals(30, repository.getAccount().balancePoints)
    }

    @Test
    fun 预留退款与已提交补偿退款都保持余额和预留一致() = runBlocking {
        repository.prepareSkipOrder(skip("refund-prepared", 1_000L))
        repository.refundOrder("refund-prepared", 2_000L)
        assertEquals(60, repository.getAccount().balancePoints)
        assertEquals(0, repository.getAccount().reservedPoints)

        repository.prepareSkipOrder(skip("refund-applied", 3_000L))
        repository.commitOrder("refund-applied", 4_000L)
        assertEquals(45, repository.getAccount().balancePoints)
        repository.refundOrder("refund-applied", 5_000L)
        repository.refundOrder("refund-applied", 5_000L)

        val account = repository.getAccount()
        assertEquals(60, account.balancePoints)
        assertEquals(0, account.reservedPoints)
        assertEquals(0L, account.lifetimeSpentPoints)
    }

    @Test
    fun 已提交订单补偿退款完整返还且允许余额暂时超过奖励上限() = runBlocking {
        repeat(20) { day ->
            val dayStart = day * DAY_MILLIS
            repository.settleCycle(
                cycleForDay(
                    cycleId = "fill-$day",
                    runId = "fill-run-$day",
                    validSeconds = 10L * 60L * 60L,
                    dayStart = dayStart,
                    offsetMillis = 1_000L
                )
            )
        }
        assertEquals(GrowthPolicy.MAX_BALANCE_POINTS, repository.getAccount().balancePoints)

        val spendDayStart = 20L * DAY_MILLIS
        repository.prepareSkipOrder(skip("refund-over-cap", spendDayStart + 1_000L))
        repository.commitOrder("refund-over-cap", spendDayStart + 2_000L)
        repository.settleCycle(
            cycleForDay(
                cycleId = "refill-large",
                runId = "refill-run",
                validSeconds = 10L * 60L * 60L,
                dayStart = spendDayStart,
                offsetMillis = 3_000L
            )
        )
        repository.settleCycle(
            cycleForDay(
                cycleId = "refill-small",
                runId = "refill-run",
                ordinal = 1L,
                validSeconds = 25L * 60L,
                dayStart = spendDayStart,
                offsetMillis = 4_000L
            )
        )
        assertEquals(GrowthPolicy.MAX_BALANCE_POINTS, repository.getAccount().balancePoints)

        repository.refundOrder("refund-over-cap", spendDayStart + 5_000L)

        val account = repository.getAccount()
        val refundLedger = repository.recentLedger().first().first {
            it.relatedOrderId == "refund-over-cap" && it.type == GrowthLedgerEntryType.REFUND
        }
        assertEquals(GrowthPolicy.MAX_BALANCE_POINTS + GrowthPolicy.SKIP_COST_POINTS, account.balancePoints)
        assertEquals(0L, account.lifetimeSpentPoints)
        assertEquals(GrowthPolicy.SKIP_COST_POINTS, refundLedger.balanceDeltaPoints)
        assertEquals(-GrowthPolicy.SKIP_COST_POINTS, refundLedger.spendingDeltaPoints)
    }

    @Test
    fun 继续自律奖励使用sourceKey保证幂等() = runBlocking {
        val request = ContinuationRewardRequest(
            sourceKey = "cycle:continue:five-minutes",
            cycleId = "continue",
            additionalValidSeconds = 300,
            occurredAtEpochMillis = 10_000L,
            localDayStartEpochMillis = 0,
            localDayEndExclusiveEpochMillis = DAY_MILLIS
        )
        val first = repository.awardContinuation(request)
        val second = repository.awardContinuation(request)

        assertEquals(1, first.entry.experienceDelta)
        assertEquals(61, first.account.balancePoints)
        assertTrue(second.wasAlreadyAwarded)
        assertEquals(61, second.account.balancePoints)
    }

    @Test
    fun 并发生产力奖励使用sourceKey保证成长账本只入账一次() = runBlocking {
        val request = ProductivityRewardRequest(
            sourceKey = "todo:complete:concurrent",
            reason = GrowthLedgerReason.TODO_COMPLETED,
            rewardPoints = 1,
            experiencePoints = 1,
            occurredAtEpochMillis = 10_000L
        )

        val results = coroutineScope {
            List(12) {
                async(Dispatchers.Default) { repository.awardProductivity(request) }
            }.awaitAll()
        }

        val account = repository.getAccount()
        val matchingEntries = repository.recentLedger().first().filter {
            it.sourceKey == request.sourceKey
        }
        assertEquals(1, results.count { !it.wasAlreadyAwarded })
        assertEquals(1, matchingEntries.size)
        assertEquals(61, account.balancePoints)
        assertEquals(1L, account.lifetimeExperience)
    }

    private fun cycle(
        cycleId: String,
        runId: String,
        ordinal: Long,
        validSeconds: Long,
        now: Long,
        outcome: GrowthCycleOutcome = GrowthCycleOutcome.TIMER_COMPLETED
    ) = GrowthCycleSettlementRequest(
        cycleId = cycleId,
        runId = runId,
        cycleOrdinal = ordinal,
        mode = "global",
        configuredDurationSeconds = validSeconds,
        validElapsedSeconds = validSeconds,
        pausedSeconds = 0,
        outcome = outcome,
        occurredAtEpochMillis = now,
        localDayStartEpochMillis = 0,
        localDayEndExclusiveEpochMillis = DAY_MILLIS
    )

    private fun cycleForDay(
        cycleId: String,
        runId: String,
        ordinal: Long = 0L,
        validSeconds: Long,
        dayStart: Long,
        offsetMillis: Long
    ) = GrowthCycleSettlementRequest(
        cycleId = cycleId,
        runId = runId,
        cycleOrdinal = ordinal,
        mode = "global",
        configuredDurationSeconds = validSeconds,
        validElapsedSeconds = validSeconds,
        pausedSeconds = 0L,
        outcome = GrowthCycleOutcome.TIMER_COMPLETED,
        occurredAtEpochMillis = dayStart + offsetMillis,
        localDayStartEpochMillis = dayStart,
        localDayEndExclusiveEpochMillis = dayStart + DAY_MILLIS
    )

    private fun pause(
        orderId: String,
        minutes: Int,
        now: Long,
        waiveCost: Boolean = false
    ) = PreparePauseOrderRequest(
        orderId = orderId,
        lockSessionId = "lock-$orderId",
        requestedPauseMinutes = minutes,
        authenticationKind = "password",
        authenticationId = "credential-1",
        preparedAtEpochMillis = now,
        expiresAtEpochMillis = now + 60_000L,
        localDayStartEpochMillis = 0,
        localDayEndExclusiveEpochMillis = DAY_MILLIS,
        waiveCost = waiveCost
    )

    private fun skip(orderId: String, now: Long) = PrepareSkipOrderRequest(
        orderId = orderId,
        lockSessionId = "lock-$orderId",
        authenticationKind = "password",
        authenticationId = "auth-$orderId",
        preparedAtEpochMillis = now,
        expiresAtEpochMillis = now + 60_000L
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
