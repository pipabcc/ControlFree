package com.example.controlfree.growth

import kotlin.math.min

/**
 * 成长值的唯一规则来源。
 *
 * 这里只处理纯计算，不读取时钟或数据库，确保锁定服务可以先报价，再由持久化层原子结算。
 */
object GrowthPolicy {
    const val POLICY_VERSION = 2

    const val INITIAL_BALANCE_POINTS = 60
    const val MAX_BALANCE_POINTS = 300
    const val EMERGENCY_RESERVE_POINTS = 20

    const val POINTS_PER_VALID_INTERVAL = 1
    const val VALID_INTERVAL_SECONDS = 15L * 60L
    const val COMPLETION_BONUS_THRESHOLD_SECONDS = 25L * 60L
    const val COMPLETION_BONUS_POINTS = 3
    const val LONG_COMPLETION_BONUS_THRESHOLD_SECONDS = 60L * 60L
    const val LONG_COMPLETION_BONUS_POINTS = 2
    const val MAX_REWARD_PER_SUPERVISION = 12
    const val MAX_COMMON_REWARD_PER_DAY = 24
    const val MINIMUM_NATURAL_COMPLETION_REWARD_POINTS = 1

    const val SKIP_COST_POINTS = 15

    const val CONTINUATION_REWARD_SECONDS = 5L * 60L
    const val CONTINUATION_REWARD_POINTS = 1

    const val MAX_LEVEL = 30

    fun quoteSupervisionReward(
        validSupervisedSeconds: Long,
        commonExperienceEarnedToday: Int = 0
    ): GrowthRewardQuote {
        require(validSupervisedSeconds >= 0L) { "有效监督时长不能为负数" }
        require(commonExperienceEarnedToday >= 0) { "当日成长经验不能为负数" }

        val intervalPoints = (validSupervisedSeconds / VALID_INTERVAL_SECONDS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt() * POINTS_PER_VALID_INTERVAL
        val completionBonus = when {
            validSupervisedSeconds >= LONG_COMPLETION_BONUS_THRESHOLD_SECONDS ->
                COMPLETION_BONUS_POINTS + LONG_COMPLETION_BONUS_POINTS
            validSupervisedSeconds >= COMPLETION_BONUS_THRESHOLD_SECONDS ->
                COMPLETION_BONUS_POINTS
            else -> 0
        }
        val calculatedPoints = intervalPoints.saturatedAdd(completionBonus)
        val minimumCompletionPoints = if (
            validSupervisedSeconds > 0L && calculatedPoints == 0
        ) {
            MINIMUM_NATURAL_COMPLETION_REWARD_POINTS
        } else {
            0
        }
        val beforeSessionCap = calculatedPoints.saturatedAdd(minimumCompletionPoints)
        val afterSessionCap = min(beforeSessionCap, MAX_REWARD_PER_SUPERVISION)
        val dailyRemaining = (MAX_COMMON_REWARD_PER_DAY - commonExperienceEarnedToday)
            .coerceAtLeast(0)
        return GrowthRewardQuote(
            intervalPoints = intervalPoints,
            completionBonusPoints = completionBonus,
            minimumCompletionPoints = minimumCompletionPoints,
            pointsBeforeCaps = beforeSessionCap,
            pointsAfterSessionCap = afterSessionCap,
            awardedPoints = min(afterSessionCap, dailyRemaining),
            limitedBySessionCap = beforeSessionCap > afterSessionCap,
            limitedByDailyCap = afterSessionCap > dailyRemaining
        )
    }

    /**
     * 暂停费用按“选择暂停多长时间即扣多少积分”计算（1 分钟 = 1 积分）。
     */
    fun quotePauseCost(
        previousDailyPauseMinutes: Int,
        requestedPauseMinutes: Int
    ): PauseCostQuote {
        require(previousDailyPauseMinutes >= 0) { "当日累计暂停时长不能为负数" }
        require(requestedPauseMinutes in 1..1440) { "单次暂停时长必须在 1 到 1440 分钟之间" }

        val newDailyPauseMinutes = previousDailyPauseMinutes.saturatedAdd(requestedPauseMinutes)
        val cost = requestedPauseMinutes
        return PauseCostQuote(
            previousDailyPauseMinutes = previousDailyPauseMinutes,
            requestedPauseMinutes = requestedPauseMinutes,
            newDailyPauseMinutes = newDailyPauseMinutes,
            previousTotalCostPoints = previousDailyPauseMinutes,
            newTotalCostPoints = newDailyPauseMinutes,
            incrementalCostPoints = cost
        )
    }

    /**
     * 历史订单只累计当天已经使用的暂停分钟；无论旧规则版本或是否曾免单，都不会在本次追缴。
     * 当前请求始终独立按 1 分钟 1 积分报价。
     */
    fun quotePauseCost(
        appliedOrders: List<AppliedPausePricingRecord>,
        requestedPauseMinutes: Int
    ): PauseCostQuote {
        val previousDailyPauseMinutes = appliedOrders.fold(0) { acc, order ->
            require(order.pauseDurationMinutes in 1..1440) { "历史暂停订单时长无效" }
            require(order.policyVersion >= 1) { "历史暂停订单规则版本无效" }
            acc.saturatedAdd(order.pauseDurationMinutes)
        }
        return quotePauseCost(
            previousDailyPauseMinutes = previousDailyPauseMinutes,
            requestedPauseMinutes = requestedPauseMinutes
        )
    }

    fun quoteContinuationReward(
        additionalValidSeconds: Long,
        commonExperienceEarnedToday: Int,
        alreadyGrantedForCycle: Boolean
    ): Int {
        require(additionalValidSeconds >= 0L) { "继续自律时长不能为负数" }
        require(commonExperienceEarnedToday >= 0) { "当日成长经验不能为负数" }
        if (alreadyGrantedForCycle || additionalValidSeconds < CONTINUATION_REWARD_SECONDS) return 0
        return min(
            CONTINUATION_REWARD_POINTS,
            (MAX_COMMON_REWARD_PER_DAY - commonExperienceEarnedToday).coerceAtLeast(0)
        )
    }

    fun quoteOrdinarySpend(
        nominalCostPoints: Int,
        balancePoints: Int,
        reservedPoints: Int = 0
    ): GrowthSpendQuote = quoteSpend(
        nominalCostPoints = nominalCostPoints,
        balancePoints = balancePoints,
        reservedPoints = reservedPoints,
        reserveToKeepPoints = EMERGENCY_RESERVE_POINTS,
        allowPartialPayment = false
    )

    /** 应急操作永远可继续；余额不足时只消费当前实际可用值，且不会产生负余额。 */
    fun quoteEmergencySpend(
        nominalCostPoints: Int,
        balancePoints: Int,
        reservedPoints: Int = 0
    ): GrowthSpendQuote = quoteSpend(
        nominalCostPoints = nominalCostPoints,
        balancePoints = balancePoints,
        reservedPoints = reservedPoints,
        reserveToKeepPoints = 0,
        allowPartialPayment = true
    )

    fun levelForLifetimeExperience(lifetimeExperience: Long): GrowthLevelProgress {
        require(lifetimeExperience >= 0L) { "终身成长经验不能为负数" }
        var level = 1
        while (level < MAX_LEVEL && lifetimeExperience >= experienceRequiredForLevel(level + 1)) {
            level++
        }
        val currentThreshold = experienceRequiredForLevel(level)
        val nextThreshold = if (level == MAX_LEVEL) null else experienceRequiredForLevel(level + 1)
        return GrowthLevelProgress(
            level = level,
            stage = GrowthStage.fromLevel(level),
            lifetimeExperience = lifetimeExperience,
            currentLevelThreshold = currentThreshold,
            nextLevelThreshold = nextThreshold,
            experienceIntoLevel = lifetimeExperience - currentThreshold,
            experienceNeededForNextLevel = nextThreshold?.minus(lifetimeExperience)?.coerceAtLeast(0L)
        )
    }

    /** 达到指定等级所需的累计经验；初始赠送余额不计入此经验。 */
    fun experienceRequiredForLevel(level: Int): Long {
        require(level in 1..MAX_LEVEL) { "成长等级必须在 1 到 $MAX_LEVEL 之间" }
        val completedLevels = (level - 1).toLong()
        return 5L * completedLevels * level.toLong()
    }

    private fun quoteSpend(
        nominalCostPoints: Int,
        balancePoints: Int,
        reservedPoints: Int,
        reserveToKeepPoints: Int,
        allowPartialPayment: Boolean
    ): GrowthSpendQuote {
        require(nominalCostPoints >= 0) { "成长值费用不能为负数" }
        require(balancePoints >= 0) { "成长值余额不能为负数" }
        require(reservedPoints in 0..balancePoints) { "预留成长值超出余额" }
        require(reserveToKeepPoints >= 0) { "保留成长值不能为负数" }

        val available = balancePoints - reservedPoints
        val payable = if (allowPartialPayment) min(nominalCostPoints, available) else nominalCostPoints
        // 免费动作不应因为余额已经低于应急保留值而被拒绝，它不会继续消耗余额。
        val allowed = nominalCostPoints == 0 ||
            allowPartialPayment ||
            available - payable >= reserveToKeepPoints
        return GrowthSpendQuote(
            nominalCostPoints = nominalCostPoints,
            payableCostPoints = if (allowed) payable else 0,
            availableBalancePoints = available,
            reserveToKeepPoints = reserveToKeepPoints,
            allowed = allowed,
            isPartialPayment = allowed && payable < nominalCostPoints
        )
    }
}

data class GrowthRewardQuote(
    val intervalPoints: Int,
    val completionBonusPoints: Int,
    val minimumCompletionPoints: Int,
    val pointsBeforeCaps: Int,
    val pointsAfterSessionCap: Int,
    val awardedPoints: Int,
    val limitedBySessionCap: Boolean,
    val limitedByDailyCap: Boolean
)

data class PauseCostQuote(
    val previousDailyPauseMinutes: Int,
    val requestedPauseMinutes: Int,
    val newDailyPauseMinutes: Int,
    val previousTotalCostPoints: Int,
    val newTotalCostPoints: Int,
    val incrementalCostPoints: Int
)

/**
 * 一笔当天已成功执行的暂停。
 * [policyVersion] 与 [costWaived] 保留审计语义；当前报价只累计历史分钟，不追缴或退还旧订单。
 */
data class AppliedPausePricingRecord(
    val pauseDurationMinutes: Int,
    val policyVersion: Int,
    val costWaived: Boolean
)

data class GrowthSpendQuote(
    val nominalCostPoints: Int,
    val payableCostPoints: Int,
    val availableBalancePoints: Int,
    val reserveToKeepPoints: Int,
    val allowed: Boolean,
    val isPartialPayment: Boolean
)

enum class GrowthStage(
    val firstLevel: Int,
    val lastLevel: Int,
    val displayName: String,
    val touchActionUnlocks: String,
    val bubbleExpressionUnlocks: String,
    val appearanceEffectUnlocks: String
) {
    SEEDLING(
        firstLevel = 1,
        lastLevel = 5,
        displayName = "初芽",
        touchActionUnlocks = "萌发双子叶",
        bubbleExpressionUnlocks = "基础温暖心声气泡",
        appearanceEffectUnlocks = "质朴嫩绿外观"
    ),
    NEW_LEAF(
        firstLevel = 6,
        lastLevel = 11,
        displayName = "新叶",
        touchActionUnlocks = "新萌侧生幼芽",
        bubbleExpressionUnlocks = "解锁开心、鼓励心声气泡",
        appearanceEffectUnlocks = "柔和绿光"
    ),
    GREEN_BRANCH(
        firstLevel = 12,
        lastLevel = 19,
        displayName = "青枝",
        touchActionUnlocks = "分叉枝桠且长出花骨朵",
        bubbleExpressionUnlocks = "解锁专注、加油心声气泡",
        appearanceEffectUnlocks = "青绿光晕"
    ),
    GUARDIAN(
        firstLevel = 20,
        lastLevel = 29,
        displayName = "守望",
        touchActionUnlocks = "顶端盛开白色守护花",
        bubbleExpressionUnlocks = "解锁坚定、守护心声气泡",
        appearanceEffectUnlocks = "强化守护光晕"
    ),
    STAR_BLOOM(
        firstLevel = 30,
        lastLevel = 30,
        displayName = "星芽",
        touchActionUnlocks = "挂满红熟浆果与旋转星环",
        bubbleExpressionUnlocks = "解锁全套惊喜心声与彩蛋",
        appearanceEffectUnlocks = "星光双层光环"
    );

    val levelRangeText: String
        get() = if (firstLevel == lastLevel) "Lv.$firstLevel" else "Lv.$firstLevel～$lastLevel"

    val cumulativeExperienceThreshold: Long
        get() = GrowthPolicy.experienceRequiredForLevel(firstLevel)

    companion object {
        fun fromLevel(level: Int): GrowthStage {
            require(level in 1..GrowthPolicy.MAX_LEVEL) { "成长等级无效" }
            return entries.last { stage -> level >= stage.firstLevel }
        }
    }
}

data class GrowthLevelProgress(
    val level: Int,
    val stage: GrowthStage,
    val lifetimeExperience: Long,
    val currentLevelThreshold: Long,
    val nextLevelThreshold: Long?,
    val experienceIntoLevel: Long,
    val experienceNeededForNextLevel: Long?
)

private fun Int.saturatedAdd(other: Int): Int =
    (toLong() + other.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
