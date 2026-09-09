package com.example.controlfree

/**
 * 把持久化进度恢复到当前进程。墙钟只参与旧 schema 的一次性迁移；
 * 新 schema 的权威计时始终使用 elapsedRealtime。
 *
 * 同次开机且恢复前后的屏幕状态一致时，可以安全地按已保存状态结算服务缺席区间。
 * 若当前阶段的计时规则与屏幕状态无关，即使恢复时检测到亮灭屏状态变化，也应
 * 连续结算缺席时间。普通监督从“已知熄屏锁定”恢复到亮屏时，也按发现亮屏的时刻
 * 结算此前区间，避免进程被系统回收后整段熄屏时间丢失。其余未知边沿仍只重建锚点。
 */
class MonitorProgressRestorer(private val cycle: MonitorCycle) {

    fun restore(
        saved: MonitorCycleSnapshot,
        nowElapsedMillis: Long,
        currentBootCount: Int,
        currentInteractive: Boolean
    ): MonitorCycleSnapshot {
        val hasReliableMonotonicInterval =
            saved.bootCount >= 0 &&
                currentBootCount >= 0 &&
                saved.bootCount == currentBootCount &&
                saved.checkpointElapsedMillis >= 0L &&
                nowElapsedMillis >= saved.checkpointElapsedMillis
        val canSettleScreenState =
            saved.isInteractive == currentInteractive ||
                cycle.canSettleAcrossInteractiveStateChange(saved.phase) ||
                (
                    saved.phase == MonitorPhase.LOCK &&
                        !saved.isInteractive &&
                        currentInteractive
                    )
        val canSettleMissingInterval =
            hasReliableMonotonicInterval && canSettleScreenState

        if (!canSettleMissingInterval) {
            return cycle.reanchor(
                snapshot = saved,
                nowElapsedMillis = nowElapsedMillis,
                bootCount = currentBootCount,
                isInteractive = currentInteractive
            )
        }

        return cycle.advance(saved, nowElapsedMillis).copy(
            bootCount = currentBootCount,
            isInteractive = currentInteractive
        )
    }

    fun migrateLegacy(
        legacyPhase: MonitorPhase,
        legacyPhaseEndsAtWallMillis: Long,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
        currentBootCount: Int,
        currentInteractive: Boolean
    ): MonitorCycleSnapshot {
        val migratedPhase: MonitorPhase
        val migratedRemainingMillis: Long
        val legacyPhaseDuration = cycle.durationMillis(legacyPhase)

        if (legacyPhaseEndsAtWallMillis <= 0L) {
            migratedPhase = legacyPhase
            migratedRemainingMillis = legacyPhaseDuration
        } else if (legacyPhaseEndsAtWallMillis > nowWallMillis) {
            migratedPhase = legacyPhase
            migratedRemainingMillis =
                (legacyPhaseEndsAtWallMillis - nowWallMillis)
                    .coerceIn(1L, legacyPhaseDuration)
        } else {
            val elapsedAfterDeadline =
                (nowWallMillis - legacyPhaseEndsAtWallMillis).coerceAtLeast(0L)
            val nextPhase = legacyPhase.next()
            val nextDuration = cycle.durationMillis(nextPhase)
            val cycleDuration = if (Long.MAX_VALUE - nextDuration < legacyPhaseDuration) {
                Long.MAX_VALUE
            } else {
                nextDuration + legacyPhaseDuration
            }
            val offset = elapsedAfterDeadline % cycleDuration

            if (offset < nextDuration) {
                migratedPhase = nextPhase
                migratedRemainingMillis = nextDuration - offset
            } else {
                migratedPhase = legacyPhase
                migratedRemainingMillis = legacyPhaseDuration - (offset - nextDuration)
            }
        }

        return MonitorCycleSnapshot(
            phase = migratedPhase,
            remainingMillis = migratedRemainingMillis,
            checkpointElapsedMillis = nowElapsedMillis,
            bootCount = currentBootCount,
            isInteractive = currentInteractive
        )
    }
}
