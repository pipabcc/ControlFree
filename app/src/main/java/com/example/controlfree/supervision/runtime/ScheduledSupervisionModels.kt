package com.example.controlfree.supervision.runtime

import com.example.controlfree.supervision.SupervisionPlan

data class ScheduledMonitorOwner(
    val planId: String,
    val planUpdatedAtEpochMillis: Long,
    val activeUntilEpochMillis: Long
) {
    init {
        require(planId.isNotBlank()) { "定时监督计划编号不能为空" }
        require(planUpdatedAtEpochMillis >= 0L) { "定时监督计划版本无效" }
        require(activeUntilEpochMillis > 0L) { "定时监督结束时间无效" }
    }
}

data class ScheduledOccurrenceSuppression(
    val planId: String,
    val planUpdatedAtEpochMillis: Long,
    val suppressUntilEpochMillis: Long
) {
    init {
        require(planId.isNotBlank()) { "跳过计划编号不能为空" }
        require(planUpdatedAtEpochMillis >= 0L) { "跳过计划版本无效" }
        require(suppressUntilEpochMillis > 0L) { "跳过截止时间无效" }
    }

    fun suppresses(plan: SupervisionPlan, nowEpochMillis: Long): Boolean =
        plan.id == planId &&
            plan.updatedAtEpochMillis == planUpdatedAtEpochMillis &&
            nowEpochMillis < suppressUntilEpochMillis
}

sealed interface ScheduledOwnerReadResult {
    data object None : ScheduledOwnerReadResult
    data class Available(val owner: ScheduledMonitorOwner) : ScheduledOwnerReadResult
    data object Corrupted : ScheduledOwnerReadResult
}
