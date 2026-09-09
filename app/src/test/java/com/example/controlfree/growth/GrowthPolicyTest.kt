package com.example.controlfree.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthPolicyTest {
    @Test
    fun `成长规则使用第二版`() {
        assertEquals(2, GrowthPolicy.POLICY_VERSION)
    }

    @Test
    fun `可用成长值余额上限为三百点`() {
        assertEquals(300, GrowthPolicy.MAX_BALANCE_POINTS)
    }

    @Test
    fun `任何正时长的自然完成至少奖励一点`() {
        val zero = GrowthPolicy.quoteSupervisionReward(0)
        assertEquals(0, zero.minimumCompletionPoints)
        assertEquals(0, zero.awardedPoints)

        val oneSecond = GrowthPolicy.quoteSupervisionReward(1)
        assertEquals(1, oneSecond.minimumCompletionPoints)
        assertEquals(1, oneSecond.awardedPoints)

        val fourteenMinutes = GrowthPolicy.quoteSupervisionReward(14 * 60L)
        assertEquals(1, fourteenMinutes.minimumCompletionPoints)
        assertEquals(1, fourteenMinutes.awardedPoints)

        val fifteenMinutes = GrowthPolicy.quoteSupervisionReward(15 * 60L)
        assertEquals(0, fifteenMinutes.minimumCompletionPoints)
        assertEquals(1, fifteenMinutes.awardedPoints)
    }

    @Test
    fun `监督奖励按十五分钟和完成档位累计且保留原有额外奖励`() {

        val twentyFiveMinutes = GrowthPolicy.quoteSupervisionReward(25 * 60L)
        assertEquals(1, twentyFiveMinutes.intervalPoints)
        assertEquals(3, twentyFiveMinutes.completionBonusPoints)
        assertEquals(4, twentyFiveMinutes.awardedPoints)

        val oneHour = GrowthPolicy.quoteSupervisionReward(60 * 60L)
        assertEquals(4, oneHour.intervalPoints)
        assertEquals(5, oneHour.completionBonusPoints)
        assertEquals(9, oneHour.awardedPoints)
    }

    @Test
    fun `单次奖励最高十二点且每日普通奖励最高二十四点`() {
        val sessionCapped = GrowthPolicy.quoteSupervisionReward(10 * 60L * 60L)
        assertEquals(12, sessionCapped.awardedPoints)
        assertTrue(sessionCapped.limitedBySessionCap)

        val dailyCapped = GrowthPolicy.quoteSupervisionReward(
            validSupervisedSeconds = 60 * 60L,
            commonExperienceEarnedToday = 22
        )
        assertEquals(2, dailyCapped.awardedPoints)
        assertTrue(dailyCapped.limitedByDailyCap)
        assertEquals(0, GrowthPolicy.quoteSupervisionReward(60 * 60L, 24).awardedPoints)
    }

    @Test
    fun `暂停按本次选择分钟数线性计费且累计值保持一致`() {
        assertPause(previous = 0, requested = 1, incremental = 1, total = 1)
        assertPause(previous = 0, requested = 10, incremental = 10, total = 10)
        assertPause(previous = 9, requested = 1, incremental = 1, total = 10)
        assertPause(previous = 10, requested = 1, incremental = 1, total = 11)
        assertPause(previous = 19, requested = 1, incremental = 1, total = 20)
        assertPause(previous = 20, requested = 1, incremental = 1, total = 21)
        assertPause(previous = 21, requested = 30, incremental = 30, total = 51)
    }

    @Test
    fun `拆分暂停不会比一次暂停更便宜`() {
        val single = GrowthPolicy.quotePauseCost(0, 30).incrementalCostPoints
        val split = listOf(5, 5, 5, 5, 5, 5).fold(0 to 0) { (minutes, cost), part ->
            val quote = GrowthPolicy.quotePauseCost(minutes, part)
            quote.newDailyPauseMinutes to cost + quote.incrementalCostPoints
        }
        assertEquals(single, split.second)
        assertEquals(30, split.second)
    }

    @Test
    fun `旧版历史暂停只累计已使用分钟且新请求独立线性计费`() {
        val quote = GrowthPolicy.quotePauseCost(
            appliedOrders = listOf(
                pauseRecord(minutes = 5, policyVersion = 1)
            ),
            requestedPauseMinutes = 1
        )

        assertEquals(5, quote.previousDailyPauseMinutes)
        assertEquals(5, quote.previousTotalCostPoints)
        assertEquals(6, quote.newTotalCostPoints)
        assertEquals(1, quote.incrementalCostPoints)
    }

    @Test
    fun `挑战免单历史计入已用分钟但后续只收取本次费用`() {
        val sameTier = GrowthPolicy.quotePauseCost(
            appliedOrders = listOf(
                pauseRecord(minutes = 5, policyVersion = 2, costWaived = true)
            ),
            requestedPauseMinutes = 5
        )
        assertEquals(5, sameTier.previousTotalCostPoints)
        assertEquals(10, sameTier.newTotalCostPoints)
        assertEquals(5, sameTier.incrementalCostPoints)

        val nextTier = GrowthPolicy.quotePauseCost(
            appliedOrders = listOf(
                pauseRecord(minutes = 10, policyVersion = 2, costWaived = true)
            ),
            requestedPauseMinutes = 1
        )
        assertEquals(10, nextTier.previousTotalCostPoints)
        assertEquals(11, nextTier.newTotalCostPoints)
        assertEquals(1, nextTier.incrementalCostPoints)
    }

    @Test
    fun `跨版本历史订单统一累计分钟且不会影响本次线性价格`() {
        val quote = GrowthPolicy.quotePauseCost(
            appliedOrders = listOf(
                pauseRecord(minutes = 5, policyVersion = 1),
                pauseRecord(minutes = 10, policyVersion = 1),
                pauseRecord(minutes = 5, policyVersion = 2, costWaived = true)
            ),
            requestedPauseMinutes = 1
        )

        assertEquals(20, quote.previousDailyPauseMinutes)
        assertEquals(20, quote.previousTotalCostPoints)
        assertEquals(21, quote.newTotalCostPoints)
        assertEquals(1, quote.incrementalCostPoints)
    }

    @Test
    fun `损坏的历史暂停订单不能静默参与报价`() {
        assertThrows(IllegalArgumentException::class.java) {
            GrowthPolicy.quotePauseCost(
                appliedOrders = listOf(pauseRecord(minutes = 0, policyVersion = 2)),
                requestedPauseMinutes = 1
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GrowthPolicy.quotePauseCost(
                appliedOrders = listOf(pauseRecord(minutes = 5, policyVersion = 0)),
                requestedPauseMinutes = 1
            )
        }
    }

    @Test
    fun `普通消费保护二十点应急余额但零费用动作始终允许`() {
        assertTrue(GrowthPolicy.quoteOrdinarySpend(15, 35).allowed)
        assertFalse(GrowthPolicy.quoteOrdinarySpend(15, 34).allowed)

        val freeAtLowBalance = GrowthPolicy.quoteOrdinarySpend(0, 3)
        assertTrue(freeAtLowBalance.allowed)
        assertEquals(0, freeAtLowBalance.payableCostPoints)
    }

    @Test
    fun `应急消费余额不足时部分扣费且不会产生负值`() {
        val quote = GrowthPolicy.quoteEmergencySpend(
            nominalCostPoints = 20,
            balancePoints = 7
        )
        assertTrue(quote.allowed)
        assertTrue(quote.isPartialPayment)
        assertEquals(7, quote.payableCostPoints)
    }

    @Test
    fun `继续自律满五分钟每周期只奖励一次并受每日上限约束`() {
        assertEquals(0, GrowthPolicy.quoteContinuationReward(299, 0, false))
        assertEquals(1, GrowthPolicy.quoteContinuationReward(300, 23, false))
        assertEquals(0, GrowthPolicy.quoteContinuationReward(300, 24, false))
        assertEquals(0, GrowthPolicy.quoteContinuationReward(300, 0, true))
    }

    @Test
    fun `等级使用终身经验计算并与可消费余额无关`() {
        val initial = GrowthPolicy.levelForLifetimeExperience(0)
        assertEquals(1, initial.level)
        assertEquals(GrowthStage.SEEDLING, initial.stage)

        val levelSix = GrowthPolicy.levelForLifetimeExperience(
            GrowthPolicy.experienceRequiredForLevel(6)
        )
        assertEquals(6, levelSix.level)
        assertEquals(GrowthStage.NEW_LEAF, levelSix.stage)

        val maximum = GrowthPolicy.levelForLifetimeExperience(Long.MAX_VALUE)
        assertEquals(30, maximum.level)
        assertEquals(GrowthStage.STAR_BLOOM, maximum.stage)
        assertNull(maximum.nextLevelThreshold)
    }

    @Test
    fun `五个阶段的等级阈值和解锁说明完整`() {
        val expectedThresholds = listOf(0L, 150L, 660L, 1_900L, 4_350L)

        assertEquals(expectedThresholds, GrowthStage.entries.map { it.cumulativeExperienceThreshold })
        assertEquals(
            listOf("Lv.1～5", "Lv.6～11", "Lv.12～19", "Lv.20～29", "Lv.30"),
            GrowthStage.entries.map { it.levelRangeText }
        )
        GrowthStage.entries.forEach { stage ->
            assertTrue(stage.touchActionUnlocks.isNotBlank())
            assertTrue(stage.bubbleExpressionUnlocks.isNotBlank())
            assertTrue(stage.appearanceEffectUnlocks.isNotBlank())
        }
    }

    private fun assertPause(previous: Int, requested: Int, incremental: Int, total: Int) {
        val quote = GrowthPolicy.quotePauseCost(previous, requested)
        assertEquals(incremental, quote.incrementalCostPoints)
        assertEquals(total, quote.newTotalCostPoints)
    }

    private fun pauseRecord(
        minutes: Int,
        policyVersion: Int,
        costWaived: Boolean = false
    ) = AppliedPausePricingRecord(
        pauseDurationMinutes = minutes,
        policyVersion = policyVersion,
        costWaived = costWaived
    )
}
