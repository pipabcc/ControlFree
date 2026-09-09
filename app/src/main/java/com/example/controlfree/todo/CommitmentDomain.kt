package com.example.controlfree.todo

import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CommitmentPolicyInput(
    val enabled: Boolean,
    val localDeadlineMinute: Int?,
    val graceMinutes: Int,
    val maxLockMinutes: Int,
    val zoneId: String,
    val blockedPackages: Set<String>
) {
    init {
        require(localDeadlineMinute == null || localDeadlineMinute in 0..1_439)
        require(graceMinutes in 0..1_440)
        require(maxLockMinutes in 1..1_440)
        require(runCatching { ZoneId.of(zoneId) }.isSuccess)
        require(!enabled || blockedPackages.isNotEmpty())
    }
}

object CommitmentPolicyValidator {
    fun requireValid(policy: CommitmentPolicyEntity, packageNames: Collection<String>) {
        require(policy.id.isNotBlank() && policy.id == policy.id.trim()) { "防拖延策略编号无效" }
        require(policy.sourceId.isNotBlank() && policy.sourceId == policy.sourceId.trim()) {
            "防拖延来源编号无效"
        }
        require(CommitmentSourceType.entries.any { it.storedValue == policy.sourceType }) {
            "防拖延来源类型无效"
        }
        require(policy.localDeadlineMinute == null || policy.localDeadlineMinute in 0..1_439) {
            "防拖延截止分钟无效"
        }
        require(policy.graceMinutes in 0..1_440) { "防拖延宽限时长无效" }
        require(policy.maxLockMinutes in 1..1_440) { "防拖延最长锁定时长无效" }
        require(policy.updatedAtEpochMillis >= policy.createdAtEpochMillis) { "防拖延策略时间无效" }
        try {
            ZoneId.of(policy.zoneId)
        } catch (_: DateTimeException) {
            throw IllegalArgumentException("防拖延策略时区无效")
        }
        require(packageNames.size <= MAX_BLOCKED_APPS_PER_POLICY) { "单个策略关联 App 过多" }
        require(packageNames.all(PACKAGE_NAME_PATTERN::matches)) { "防拖延关联 App 包名无效" }
    }

    private val PACKAGE_NAME_PATTERN = Regex(
        "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"
    )
    private const val MAX_BLOCKED_APPS_PER_POLICY = 100
}

object CommitmentOccurrenceFactory {
    fun forTodo(
        policy: CommitmentPolicyEntity,
        todo: TodoItemEntity,
        nowEpochMillis: Long
    ): CommitmentOccurrenceEntity? {
        require(policy.sourceType == CommitmentSourceType.TODO.storedValue)
        require(policy.sourceId == todo.id)
        val dueAt = todo.dueDateEpochMillis ?: todo.scheduledEndEpochMillis
            ?: todo.scheduledStartEpochMillis ?: return null
        return occurrence(
            policy = policy,
            occurrenceKey = todo.recurrenceSeriesId?.let { "$it:${todo.recurrenceSequence}" } ?: todo.id,
            deadlineEpochMillis = dueAt,
            satisfiedAtEpochMillis = todo.completedAtEpochMillis.takeIf { todo.isCompleted },
            nowEpochMillis = nowEpochMillis
        )
    }

    fun forHabit(
        policy: CommitmentPolicyEntity,
        habit: HabitItemEntity,
        date: LocalDate,
        record: HabitRecordEntity?,
        nowEpochMillis: Long
    ): CommitmentOccurrenceEntity? {
        require(policy.sourceType == CommitmentSourceType.HABIT.storedValue)
        require(policy.sourceId == habit.id)
        if (!HabitScheduleCalculator.isScheduled(habit, date)) return null
        val deadlineMinute = policy.localDeadlineMinute ?: return null
        val zoneId = ZoneId.of(policy.zoneId)
        val deadline = date.atStartOfDay(zoneId).plusMinutes(deadlineMinute.toLong()).toInstant()
        val isSatisfied = (record?.completionCount ?: 0) >= habit.targetCountPerDay.coerceAtLeast(1)
        return occurrence(
            policy = policy,
            occurrenceKey = date.toString(),
            deadlineEpochMillis = deadline.toEpochMilli(),
            satisfiedAtEpochMillis = record?.updatedAtEpochMillis.takeIf { isSatisfied },
            nowEpochMillis = nowEpochMillis
        )
    }

    private fun occurrence(
        policy: CommitmentPolicyEntity,
        occurrenceKey: String,
        deadlineEpochMillis: Long,
        satisfiedAtEpochMillis: Long?,
        nowEpochMillis: Long
    ): CommitmentOccurrenceEntity {
        // maxLockMinutes 仅保留为旧数据兼容字段；防拖延策略必须持续到承诺完成、
        // 删除或显式关闭，不能因为经过固定时长而自动放行。
        val expiresAt = Long.MAX_VALUE
        val id = UUID.nameUUIDFromBytes(
            "commitment:${policy.id}:$occurrenceKey".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        return CommitmentOccurrenceEntity(
            id = id,
            policyId = policy.id,
            occurrenceKey = occurrenceKey,
            deadlineEpochMillis = deadlineEpochMillis,
            expiresAtEpochMillis = expiresAt,
            activatedAtEpochMillis = null,
            satisfiedAtEpochMillis = satisfiedAtEpochMillis,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis
        )
    }

}

object CommitmentActivationPolicy {
    fun activationEpochMillis(
        policy: CommitmentPolicyEntity,
        occurrence: CommitmentOccurrenceEntity
    ): Long? {
        if (policy.graceMinutes !in 0..MAX_GRACE_MINUTES || occurrence.deadlineEpochMillis < 0L) {
            return null
        }
        val graceMillis = policy.graceMinutes.toLong() * MILLIS_PER_MINUTE
        return if (occurrence.deadlineEpochMillis > Long.MAX_VALUE - graceMillis) {
            Long.MAX_VALUE
        } else {
            occurrence.deadlineEpochMillis + graceMillis
        }
    }

    fun isActive(
        policy: CommitmentPolicyEntity,
        occurrence: CommitmentOccurrenceEntity,
        nowEpochMillis: Long
    ): Boolean {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        if (occurrence.activatedAtEpochMillis?.let { it >= 0L } == true) return true
        val activationAt = activationEpochMillis(policy, occurrence) ?: return false
        return activationAt != Long.MAX_VALUE && nowEpochMillis >= activationAt
    }

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MAX_GRACE_MINUTES = 24 * 60
}

object CommitmentLockEvaluator {
    fun evaluate(
        snapshot: CommitmentRuntimeSnapshot,
        foregroundPackage: String,
        now: Instant
    ): ActiveCommitmentBlock? = evaluate(snapshot, foregroundPackage, now.toEpochMilli())

    fun evaluate(
        snapshot: CommitmentRuntimeSnapshot,
        foregroundPackage: String,
        nowEpochMillis: Long
    ): ActiveCommitmentBlock? {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        val policiesById = snapshot.policies.associateBy { it.policy.id }
        return snapshot.openOccurrences.asSequence()
            .mapNotNull { occurrence ->
                val relation = policiesById[occurrence.policyId] ?: return@mapNotNull null
                val policy = relation.policy
                if (!policy.enabled) return@mapNotNull null
                if (relation.blockedApps.none { it.packageName == foregroundPackage }) {
                    return@mapNotNull null
                }
                if (!CommitmentActivationPolicy.isActive(policy, occurrence, nowEpochMillis)) {
                    return@mapNotNull null
                }
                val sourceType = CommitmentSourceType.entries.firstOrNull {
                    it.storedValue == policy.sourceType
                } ?: return@mapNotNull null
                ActiveCommitmentBlock(
                    policyId = policy.id,
                    occurrenceId = occurrence.id,
                    sourceType = sourceType,
                    sourceId = policy.sourceId,
                    packageName = foregroundPackage,
                    deadlineEpochMillis = occurrence.deadlineEpochMillis,
                    expiresAtEpochMillis = Long.MAX_VALUE
                )
            }
            .minByOrNull(ActiveCommitmentBlock::deadlineEpochMillis)
    }

}
