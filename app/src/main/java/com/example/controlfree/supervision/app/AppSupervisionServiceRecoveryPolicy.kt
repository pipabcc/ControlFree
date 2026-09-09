package com.example.controlfree.supervision.app

/** 可独立测试的 Service 恢复决策，避免主动停止与系统回收共用同一条重启路径。 */
object AppSupervisionServiceRecoveryPolicy {
    fun shouldScheduleRetry(
        startupFailed: Boolean,
        isStoppingIntentionally: Boolean,
        activeRuleCount: Int,
        hasActiveCommitment: Boolean = false
    ): Boolean {
        require(activeRuleCount >= 0) { "活动规则数量无效" }
        return startupFailed ||
            (!isStoppingIntentionally && (activeRuleCount > 0 || hasActiveCommitment))
    }

    /** 禁用时段本身没有单次额度周期，不能伪造无法与规则匹配的休息状态。 */
    fun failSafeRestRules(
        activeRules: Collection<AppSupervisionRule>
    ): List<AppSupervisionRule> = activeRules.filter(AppSupervisionRule::isExecutionWindowActive)
}
