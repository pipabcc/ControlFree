package com.example.controlfree.supervision.commitment

import com.example.controlfree.todo.ActiveCommitmentBlock
import com.example.controlfree.todo.CommitmentActivationPolicy
import com.example.controlfree.todo.CommitmentLockEvaluator
import com.example.controlfree.todo.CommitmentRuntimeSnapshot

/**
 * 防拖延运行时只消费已经落库的显式策略。保护包查询未知时失败开放，避免误锁系统入口。
 */
object CommitmentRuntimePolicy {
    fun evaluate(
        snapshot: CommitmentRuntimeSnapshot,
        foregroundPackage: String?,
        isObservationAvailable: Boolean,
        isInteractive: Boolean,
        isCallActive: Boolean,
        isProtectedPackage: Boolean?,
        nowEpochMillis: Long
    ): ActiveCommitmentBlock? {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        val packageName = foregroundPackage?.trim().orEmpty()
        if (
            !isObservationAvailable ||
            !isInteractive ||
            isCallActive ||
            packageName.isEmpty() ||
            isProtectedPackage != false
        ) {
            return null
        }
        val openOccurrences = snapshot.openOccurrences.filter {
            it.satisfiedAtEpochMillis == null
        }
        return CommitmentLockEvaluator.evaluate(
            snapshot = if (openOccurrences.size == snapshot.openOccurrences.size) {
                snapshot
            } else {
                snapshot.copy(openOccurrences = openOccurrences)
            },
            foregroundPackage = packageName,
            nowEpochMillis = nowEpochMillis
        )
    }

    fun hasActiveWindow(
        snapshot: CommitmentRuntimeSnapshot,
        nowEpochMillis: Long
    ): Boolean {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        return runtimeWindows(snapshot).any { window ->
            window.isDurablyActivated || nowEpochMillis >= window.startsAtEpochMillis
        }
    }

    fun isActiveBlockedPackage(
        snapshot: CommitmentRuntimeSnapshot,
        packageName: String,
        nowEpochMillis: Long
    ): Boolean {
        if (packageName.isBlank()) return false
        return runtimeWindows(snapshot).any { window ->
            (window.isDurablyActivated || nowEpochMillis >= window.startsAtEpochMillis) &&
                packageName in window.blockedPackages
        }
    }

    /** 返回严格晚于当前时刻的最近启用/失效边界，供进程退出后恢复。 */
    fun nextBoundaryEpochMillis(
        snapshot: CommitmentRuntimeSnapshot,
        nowEpochMillis: Long
    ): Long? {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        return runtimeWindows(snapshot)
            .filterNot(RuntimeWindow::isDurablyActivated)
            .map { window -> window.startsAtEpochMillis }
            .filter { boundary -> boundary != Long.MAX_VALUE && boundary > nowEpochMillis }
            .minOrNull()
    }

    private fun runtimeWindows(snapshot: CommitmentRuntimeSnapshot): Sequence<RuntimeWindow> {
        val policiesById = snapshot.policies.associateBy { relation -> relation.policy.id }
        return snapshot.openOccurrences.asSequence().mapNotNull { occurrence ->
            if (occurrence.satisfiedAtEpochMillis != null) return@mapNotNull null
            val relation = policiesById[occurrence.policyId] ?: return@mapNotNull null
            val policy = relation.policy
            val startsAt = CommitmentActivationPolicy.activationEpochMillis(policy, occurrence)
                ?: return@mapNotNull null
            val blockedPackages = relation.blockedApps
                .asSequence()
                .map { app -> app.packageName.trim() }
                .filter(String::isNotEmpty)
                .toSet()
            if (!policy.enabled || blockedPackages.isEmpty()) return@mapNotNull null
            RuntimeWindow(
                startsAtEpochMillis = startsAt,
                isDurablyActivated = occurrence.activatedAtEpochMillis?.let { it >= 0L } == true,
                blockedPackages = blockedPackages
            )
        }
    }

    private data class RuntimeWindow(
        val startsAtEpochMillis: Long,
        val isDurablyActivated: Boolean,
        val blockedPackages: Set<String>
    )
}
