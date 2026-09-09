package com.example.controlfree.boot

internal data class BootRecoveryRetryPlan(
    val dispatchDelaysMillis: List<Long>,
    val stopGraceMillis: Long
)

/**
 * 开机恢复只在一个短而有界的窗口内重试，避免演变为无限保活循环。
 */
internal object BootRecoveryRetryPolicy {
    private val ACTIVE_RECOVERY_DELAYS = listOf(0L, 2_000L, 10_000L, 30_000L)
    private val RECONCILIATION_DELAYS = listOf(0L, 8_000L)

    fun createPlan(hint: BootRecoveryHint): BootRecoveryRetryPlan =
        if (hint.runtimeRecoveryRequired) {
            BootRecoveryRetryPlan(
                dispatchDelaysMillis = ACTIVE_RECOVERY_DELAYS,
                stopGraceMillis = 5_000L
            )
        } else {
            // 即使没有活动运行时，也要给计划和提醒一次解锁后重建机会。
            BootRecoveryRetryPlan(
                dispatchDelaysMillis = RECONCILIATION_DELAYS,
                stopGraceMillis = 3_000L
            )
        }
}
