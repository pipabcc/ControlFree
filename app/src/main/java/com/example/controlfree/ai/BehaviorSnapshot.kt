package com.example.controlfree.ai

/**
 * 提供给本机介入建议层的最小统计快照。
 *
 * 快照只包含归一化后的数值聚合，不携带 App 名称、包名、任务标题或任何会话标识。
 * 数据源不可用时对应指标保持为零，调用方必须同时检查 [quality]。
 */
internal data class BehaviorSnapshot(
    val appUsage: AppUsageBehaviorSnapshot,
    val supervision: SupervisionBehaviorSnapshot,
    val quality: BehaviorSnapshotQuality
)

internal data class AppUsageBehaviorSnapshot(
    val todayMinutes: Int,
    val sevenDayMinutesOldestFirst: List<Int>,
    val sevenDayDailyAverageMinutes: Int,
    val topAppShares: List<TopAppUsageShare>,
    val todayLaunchCount: Int
) {
    init {
        require(todayMinutes in 0..MINUTES_PER_DAY) { "今日用机分钟数越界" }
        require(sevenDayMinutesOldestFirst.size == DAYS_PER_WINDOW) { "用机趋势必须覆盖七天" }
        require(sevenDayMinutesOldestFirst.all { it in 0..MINUTES_PER_DAY }) {
            "每日用机分钟数越界"
        }
        require(
            sevenDayDailyAverageMinutes ==
                sevenDayMinutesOldestFirst.sum() / DAYS_PER_WINDOW
        ) { "七日用机日均值与每日数据不一致" }
        require(topAppShares.size <= TOP_APP_LIMIT) { "主要 App 数量越界" }
        require(topAppShares.sumOf(TopAppUsageShare::durationMinutes) <= todayMinutes) {
            "主要 App 时长不能超过今日总时长"
        }
        require(topAppShares.sumOf(TopAppUsageShare::sharePercent) <= 100) {
            "主要 App 占比不能超过百分之百"
        }
        require(todayLaunchCount >= 0) { "今日启动次数不能为负数" }
    }
}

internal data class TopAppUsageShare(
    val durationMinutes: Int,
    val sharePercent: Int
) {
    init {
        require(durationMinutes in 1..MINUTES_PER_DAY) { "主要 App 用机分钟数越界" }
        require(sharePercent in 0..100) { "主要 App 用机占比越界" }
    }
}

internal data class SupervisionBehaviorSnapshot(
    val sevenDayCoverageMinutes: Int,
    val sessionCount: Int,
    val completedCount: Int,
    val endedEarlyCount: Int,
    val replacedCount: Int
) {
    init {
        require(sevenDayCoverageMinutes in 0..MINUTES_PER_SEVEN_DAYS) {
            "七日监督覆盖分钟数越界"
        }
        require(sessionCount >= 0) { "监督会话数不能为负数" }
        require(completedCount >= 0) { "已完成监督数不能为负数" }
        require(endedEarlyCount >= 0) { "提前结束监督数不能为负数" }
        require(replacedCount >= 0) { "已替换监督数不能为负数" }
        require(completedCount.toLong() + endedEarlyCount + replacedCount <= sessionCount) {
            "监督结束状态计数不能超过会话总数"
        }
    }
}

internal data class BehaviorSnapshotQuality(
    val appUsageAvailable: Boolean,
    val sevenDayUsageComplete: Boolean,
    val todayLaunchCountComplete: Boolean,
    val supervisionAvailable: Boolean
)

internal const val DAYS_PER_WINDOW = 7
internal const val MINUTES_PER_DAY = 24 * 60
internal const val MINUTES_PER_SEVEN_DAYS = DAYS_PER_WINDOW * MINUTES_PER_DAY
internal const val TOP_APP_LIMIT = 3
