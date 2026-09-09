package com.example.controlfree.supervision.commitment

enum class CommitmentSourceType {
    TODO,
    HABIT
}

data class ActiveCommitment(
    val policyId: String,
    val sourceType: CommitmentSourceType,
    val sourceId: String,
    val displayName: String,
    val enabled: Boolean,
    val deadlineEpochMillis: Long,
    val graceMinutes: Int,
    val satisfiedAtEpochMillis: Long?,
    val blockedPackages: Set<String>
) {
    init {
        require(policyId.isNotBlank())
        require(sourceId.isNotBlank())
        require(displayName.isNotBlank())
        require(deadlineEpochMillis >= 0L)
        require(graceMinutes in 0..MAX_GRACE_MINUTES)
        require(blockedPackages.none(String::isBlank))
    }

    val activationEpochMillis: Long
        get() = saturatedAdd(deadlineEpochMillis, graceMinutes.toLong() * 60_000L)

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val MAX_GRACE_MINUTES = 7 * 24 * 60
    }
}

data class CommitmentLockDecision(
    val shouldBlock: Boolean,
    val policyId: String? = null,
    val sourceType: CommitmentSourceType? = null,
    val sourceId: String? = null,
    val displayName: String? = null,
    val activatedAtEpochMillis: Long? = null
) {
    init {
        if (shouldBlock) {
            require(!policyId.isNullOrBlank())
            require(sourceType != null)
            require(!sourceId.isNullOrBlank())
            require(!displayName.isNullOrBlank())
            require(activatedAtEpochMillis != null && activatedAtEpochMillis >= 0L)
        }
    }

    companion object {
        val ALLOW = CommitmentLockDecision(shouldBlock = false)
    }
}

/**
 * 防拖延只执行用户显式配置且已经逾期的承诺；输入缺失时失败开放，避免数据库故障误锁。
 */
object CommitmentLockEvaluator {
    fun evaluate(
        commitments: Collection<ActiveCommitment>,
        foregroundPackage: String?,
        isObservationAvailable: Boolean,
        isInteractive: Boolean,
        protectedPackages: Set<String>,
        nowEpochMillis: Long
    ): CommitmentLockDecision {
        require(nowEpochMillis >= 0L)
        val packageName = foregroundPackage?.trim().orEmpty()
        if (!isObservationAvailable || !isInteractive || packageName.isEmpty()) {
            return CommitmentLockDecision.ALLOW
        }
        if (packageName in protectedPackages) return CommitmentLockDecision.ALLOW

        val match = commitments.asSequence()
            .filter(ActiveCommitment::enabled)
            .filter { it.satisfiedAtEpochMillis == null }
            .filter { packageName in it.blockedPackages }
            .filter { it.activationEpochMillis <= nowEpochMillis }
            .minWithOrNull(compareBy(ActiveCommitment::activationEpochMillis, ActiveCommitment::policyId))
            ?: return CommitmentLockDecision.ALLOW

        return CommitmentLockDecision(
            shouldBlock = true,
            policyId = match.policyId,
            sourceType = match.sourceType,
            sourceId = match.sourceId,
            displayName = match.displayName,
            activatedAtEpochMillis = match.activationEpochMillis
        )
    }
}
