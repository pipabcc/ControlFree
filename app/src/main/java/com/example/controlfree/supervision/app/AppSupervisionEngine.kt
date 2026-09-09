package com.example.controlfree.supervision.app

import com.example.controlfree.supervision.isValidSupervisionPackageName

private const val MILLIS_PER_MINUTE = 60_000L
private const val MAX_POLICY_MILLIS = 24L * 60L * MILLIS_PER_MINUTE
const val UNKNOWN_APP_SUPERVISION_BOOT_COUNT = -1

data class AppSupervisionRule(
    val planId: String,
    val planUpdatedAtEpochMillis: Long,
    val planName: String,
    val packageName: String,
    val occurrenceEndEpochMillis: Long,
    val usageAllowanceMillis: Long,
    val restDurationMillis: Long,
    val dailyUsageLimitMillis: Long = MAX_POLICY_MILLIS,
    val dailyUsageDateEpochDay: Long = 0L,
    val dailyUsageResetAtEpochMillis: Long = Long.MAX_VALUE,
    val isExecutionWindowActive: Boolean = true,
    val cycleOccurrenceEndEpochMillis: Long = 0L,
    val disabledUntilEpochMillis: Long = 0L
) {
    val resolvedCycleOccurrenceEndEpochMillis: Long
        get() = cycleOccurrenceEndEpochMillis.takeIf { it > 0L }
            ?: occurrenceEndEpochMillis

    init {
        require(planId.isNotBlank() && planId == planId.trim()) { "App 监督计划编号无效" }
        require(planUpdatedAtEpochMillis >= 0L) { "App 监督计划版本无效" }
        require(planName.isNotBlank() && planName == planName.trim()) { "App 监督计划名称无效" }
        require(isValidSupervisionPackageName(packageName)) { "App 监督包名无效" }
        require(occurrenceEndEpochMillis > 0L) { "App 监督时段结束时间无效" }
        require(usageAllowanceMillis.isValidPolicyMillis()) { "App 可用额度无效" }
        require(restDurationMillis.isValidPolicyMillis()) { "App 休息时长无效" }
        require(dailyUsageLimitMillis.isValidPolicyMillis()) { "App 每日累计上限无效" }
        require(dailyUsageResetAtEpochMillis > 0L) { "App 每日累计重置时间无效" }
        require(
            if (isExecutionWindowActive) cycleOccurrenceEndEpochMillis >= 0L
            else cycleOccurrenceEndEpochMillis == 0L
        ) { "App 单次额度周期无效" }
        require(disabledUntilEpochMillis >= 0L) {
            "App 禁用时段截止时间无效"
        }
        require(isExecutionWindowActive || disabledUntilEpochMillis > 0L) {
            "App 规则必须处于执行或禁用时段"
        }
    }
}

enum class AppSupervisionPhase {
    ALLOWANCE,
    REST
}

data class AppSupervisionRuntimeState(
    val planId: String,
    val planUpdatedAtEpochMillis: Long,
    val occurrenceEndEpochMillis: Long,
    val phase: AppSupervisionPhase,
    val remainingAllowanceMillis: Long,
    val restUntilEpochMillis: Long,
    val checkpointElapsedMillis: Long,
    val bootCount: Int,
    val wasTargetForeground: Boolean,
    val wasInteractive: Boolean
) {
    init {
        require(planId.isNotBlank() && planId == planId.trim()) { "App 监督状态编号无效" }
        require(planUpdatedAtEpochMillis >= 0L) { "App 监督状态版本无效" }
        require(occurrenceEndEpochMillis > 0L) { "App 监督状态时段无效" }
        require(remainingAllowanceMillis in 0L..MAX_POLICY_MILLIS) { "App 剩余额度无效" }
        require(checkpointElapsedMillis >= 0L) { "App 监督检查点无效" }
        require(bootCount >= UNKNOWN_APP_SUPERVISION_BOOT_COUNT) { "App 监督开机编号无效" }
        when (phase) {
            AppSupervisionPhase.ALLOWANCE -> require(restUntilEpochMillis == 0L) {
                "可用阶段不能包含休息截止时间"
            }
            AppSupervisionPhase.REST -> require(restUntilEpochMillis > 0L) {
                "休息阶段缺少截止时间"
            }
        }
    }

    fun matches(rule: AppSupervisionRule): Boolean =
        planId == rule.planId &&
            planUpdatedAtEpochMillis == rule.planUpdatedAtEpochMillis &&
            rule.isExecutionWindowActive &&
            occurrenceEndEpochMillis == rule.resolvedCycleOccurrenceEndEpochMillis
}

data class AppSupervisionDailyUsageState(
    val planId: String,
    val planUpdatedAtEpochMillis: Long,
    val localDateEpochDay: Long,
    val remainingUsageMillis: Long
) {
    init {
        require(planId.isNotBlank() && planId == planId.trim()) { "App 每日累计状态编号无效" }
        require(planUpdatedAtEpochMillis >= 0L) { "App 每日累计状态版本无效" }
        require(remainingUsageMillis in 0L..MAX_POLICY_MILLIS) { "App 每日累计剩余时长无效" }
    }

    fun matches(rule: AppSupervisionRule): Boolean =
        planId == rule.planId &&
            planUpdatedAtEpochMillis == rule.planUpdatedAtEpochMillis &&
            localDateEpochDay == rule.dailyUsageDateEpochDay
}

data class AppSupervisionObservation(
    val isAvailable: Boolean,
    val foregroundPackage: String?,
    val isInteractive: Boolean,
    val status: AppSupervisionObservationStatus = if (isAvailable) {
        AppSupervisionObservationStatus.AVAILABLE
    } else {
        AppSupervisionObservationStatus.QUERY_FAILED
    },
    /**
     * 由 UsageEvents 跟踪器补齐、但没有出现在本次或上次前台采样端点的完整会话时长。
     * 值按包名聚合，且应只包含已经确认发生在亮屏监督窗口内的时长。
     */
    val unobservedForegroundMillisByPackage: Map<String, Long> = emptyMap()
) {
    init {
        require(isAvailable || foregroundPackage == null) {
            "不可用的前台观察不能携带包名"
        }
        require(isAvailable == (status == AppSupervisionObservationStatus.AVAILABLE)) {
            "前台观察可用状态与结果状态不一致"
        }
        unobservedForegroundMillisByPackage.forEach { (packageName, durationMillis) ->
            require(packageName.isNotBlank() && packageName == packageName.trim()) {
                "额外前台时长包名无效"
            }
            require(durationMillis >= 0L) { "额外前台时长不能为负数" }
        }
    }
}

/**
 * 前台观察结果的稳定域模型。这里不直接暴露 UsageStats 实现类型，便于在无 Android
 * 环境的单元测试中验证监督的保守拦截策略。
 */
enum class AppSupervisionObservationStatus {
    AVAILABLE,
    ACCESS_DENIED,
    QUERY_FAILED,
    TIMEOUT,
    CIRCUIT_OPEN
}

data class BlockedAppSupervision(
    val rule: AppSupervisionRule,
    val remainingRestMillis: Long,
    val reason: AppSupervisionBlockReason = AppSupervisionBlockReason.REST
) {
    init {
        require(remainingRestMillis > 0L)
    }
}

enum class AppSupervisionBlockReason {
    REST,
    DAILY_LIMIT,
    DISABLED_TIME
}

data class AppSupervisionTickResult(
    val states: List<AppSupervisionRuntimeState>,
    val blocked: BlockedAppSupervision?,
    val dailyUsageStates: List<AppSupervisionDailyUsageState> = emptyList()
)

object AppSupervisionEngine {
    fun tick(
        rules: Collection<AppSupervisionRule>,
        previousStates: Collection<AppSupervisionRuntimeState>,
        observation: AppSupervisionObservation,
        nowWallEpochMillis: Long,
        nowElapsedMillis: Long,
        bootCount: Int,
        previousDailyUsageStates: Collection<AppSupervisionDailyUsageState> = emptyList()
    ): AppSupervisionTickResult {
        require(nowWallEpochMillis >= 0L) { "当前墙钟无效" }
        require(nowElapsedMillis >= 0L) { "当前单调时钟无效" }
        require(bootCount >= UNKNOWN_APP_SUPERVISION_BOOT_COUNT) { "当前开机编号无效" }

        val activeRules = rules
            .filter { rule -> nowWallEpochMillis < rule.occurrenceEndEpochMillis }
            .sortedBy(AppSupervisionRule::planId)
        require(activeRules.map(AppSupervisionRule::planId).distinct().size == activeRules.size) {
            "活动 App 监督计划编号重复"
        }
        val previousById = previousStates.associateBy(AppSupervisionRuntimeState::planId)
        require(previousById.size == previousStates.size) { "App 监督状态编号重复" }
        val previousDailyById = previousDailyUsageStates.associateBy(
            AppSupervisionDailyUsageState::planId
        )
        require(previousDailyById.size == previousDailyUsageStates.size) {
            "App 每日累计状态编号重复"
        }
        val disabledPackages = activeRules.asSequence()
            .filter { rule -> rule.disabledUntilEpochMillis > nowWallEpochMillis }
            .mapTo(hashSetOf(), AppSupervisionRule::packageName)

        val evolvedRules = activeRules.map { rule ->
            evolveRule(
                rule = rule,
                previous = previousById[rule.planId]?.takeIf { state -> state.matches(rule) },
                previousDaily = previousDailyById[rule.planId]
                    ?.takeIf { state -> state.matches(rule) },
                observation = observation,
                nowWallEpochMillis = nowWallEpochMillis,
                nowElapsedMillis = nowElapsedMillis,
                bootCount = bootCount,
                packageDisabled = rule.packageName in disabledPackages
            )
        }
        val blocked = if (!observation.isAvailable || !observation.isInteractive) {
            null
        } else {
            val foregroundPackage = observation.foregroundPackage
            val foregroundRules = evolvedRules.asSequence()
                .filter { evolved -> evolved.rule.packageName == observation.foregroundPackage }
            val disabledRule = foregroundRules
                .filter { evolved ->
                    evolved.rule.disabledUntilEpochMillis > nowWallEpochMillis
                }
                .maxByOrNull { evolved -> evolved.rule.disabledUntilEpochMillis }
            val candidates = evolvedRules.asSequence()
                .filter { evolved -> evolved.rule.packageName == foregroundPackage }
                .mapNotNull { evolved -> evolved.blockedAt(nowWallEpochMillis) }
            disabledRule?.let { evolved ->
                BlockedAppSupervision(
                    rule = evolved.rule,
                    remainingRestMillis = minOf(
                        evolved.rule.disabledUntilEpochMillis,
                        evolved.rule.occurrenceEndEpochMillis
                    ) - nowWallEpochMillis,
                    reason = AppSupervisionBlockReason.DISABLED_TIME
                )
            } ?: candidates.maxWithOrNull(
                    compareBy<BlockedAppSupervision> { it.reason.priority }
                        .thenBy { it.remainingRestMillis }
                )
        }
        return AppSupervisionTickResult(
            states = evolvedRules.mapNotNull(EvolvedRule::state),
            blocked = blocked,
            dailyUsageStates = evolvedRules.map(EvolvedRule::dailyUsageState)
        )
    }

    private fun evolveRule(
        rule: AppSupervisionRule,
        previous: AppSupervisionRuntimeState?,
        previousDaily: AppSupervisionDailyUsageState?,
        observation: AppSupervisionObservation,
        nowWallEpochMillis: Long,
        nowElapsedMillis: Long,
        bootCount: Int,
        packageDisabled: Boolean
    ): EvolvedRule {
        val dailyState = previousDaily ?: AppSupervisionDailyUsageState(
            planId = rule.planId,
            planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
            localDateEpochDay = rule.dailyUsageDateEpochDay,
            remainingUsageMillis = rule.dailyUsageLimitMillis
        )
        if (!rule.isExecutionWindowActive) {
            return EvolvedRule(rule, null, dailyState)
        }
        val canConsume = !packageDisabled &&
            dailyState.remainingUsageMillis > 0L
        val cycle = evolveCycle(
            rule = rule,
            previous = previous,
            observation = observation,
            nowWallEpochMillis = nowWallEpochMillis,
            nowElapsedMillis = nowElapsedMillis,
            bootCount = bootCount,
            canConsume = canConsume
        )
        return EvolvedRule(
            rule = rule,
            state = cycle.state,
            dailyUsageState = dailyState.copy(
                remainingUsageMillis = (
                    dailyState.remainingUsageMillis - cycle.consumedMillis
                    ).coerceAtLeast(0L)
            )
        )
    }

    private fun evolveCycle(
        rule: AppSupervisionRule,
        previous: AppSupervisionRuntimeState?,
        observation: AppSupervisionObservation,
        nowWallEpochMillis: Long,
        nowElapsedMillis: Long,
        bootCount: Int,
        canConsume: Boolean
    ): CycleEvolution {
        val isTargetForeground = observation.isAvailable &&
            observation.foregroundPackage == rule.packageName
        if (previous == null) {
            return CycleEvolution(allowanceState(
                rule,
                rule.usageAllowanceMillis,
                nowElapsedMillis,
                bootCount,
                isTargetForeground,
                observation.isInteractive
            ), 0L)
        }
        if (previous.phase == AppSupervisionPhase.REST) {
            return if (nowWallEpochMillis >= previous.restUntilEpochMillis) {
                CycleEvolution(allowanceState(
                    rule,
                    rule.usageAllowanceMillis,
                    nowElapsedMillis,
                    bootCount,
                    isTargetForeground,
                    observation.isInteractive
                ), 0L)
            } else {
                CycleEvolution(previous.copy(
                    checkpointElapsedMillis = nowElapsedMillis,
                    bootCount = bootCount,
                    wasTargetForeground = isTargetForeground,
                    wasInteractive = observation.isInteractive
                ), 0L)
            }
        }

        // UsageStats 不可用时不能把上一次前台标记当作当前事实继续扣减额度；
        // 否则故障窗口可能凭空进入休息阶段，却没有可验证的阻止快照。
        val endpointConsumedMillis = if (canConsume && observation.isAvailable) {
            trustedElapsedDelta(previous, nowElapsedMillis, bootCount)
                .takeIf { previous.wasTargetForeground && previous.wasInteractive }
                ?: 0L
        } else {
            0L
        }
        // 完整短会话已经由 UsageEvents 精确求得，不能再依赖端点状态；两者只会在
        // 端点未出现目标包时同时存在，因此这里以饱和加法合并，避免 Long 溢出绕回负数。
        val unobservedConsumedMillis = if (
            canConsume && observation.isAvailable && observation.isInteractive
        ) {
            observation.unobservedForegroundMillisByPackage[rule.packageName] ?: 0L
        } else {
            0L
        }
        val consumedMillis = saturatedAdd(endpointConsumedMillis, unobservedConsumedMillis)
        val remaining = (previous.remainingAllowanceMillis - consumedMillis).coerceAtLeast(0L)
        if (remaining == 0L) {
            return CycleEvolution(AppSupervisionRuntimeState(
                planId = rule.planId,
                planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
                occurrenceEndEpochMillis = rule.resolvedCycleOccurrenceEndEpochMillis,
                phase = AppSupervisionPhase.REST,
                remainingAllowanceMillis = 0L,
                restUntilEpochMillis = saturatedAdd(nowWallEpochMillis, rule.restDurationMillis),
                checkpointElapsedMillis = nowElapsedMillis,
                bootCount = bootCount,
                wasTargetForeground = isTargetForeground,
                wasInteractive = observation.isInteractive
            ), consumedMillis)
        }
        return CycleEvolution(allowanceState(
            rule,
            remaining,
            nowElapsedMillis,
            bootCount,
            isTargetForeground,
            observation.isInteractive
        ), consumedMillis)
    }

    private fun allowanceState(
        rule: AppSupervisionRule,
        remainingAllowanceMillis: Long,
        checkpointElapsedMillis: Long,
        bootCount: Int,
        wasTargetForeground: Boolean,
        wasInteractive: Boolean
    ) = AppSupervisionRuntimeState(
        planId = rule.planId,
        planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
        occurrenceEndEpochMillis = rule.resolvedCycleOccurrenceEndEpochMillis,
        phase = AppSupervisionPhase.ALLOWANCE,
        remainingAllowanceMillis = remainingAllowanceMillis,
        restUntilEpochMillis = 0L,
        checkpointElapsedMillis = checkpointElapsedMillis,
        bootCount = bootCount,
        wasTargetForeground = wasTargetForeground,
        wasInteractive = wasInteractive
    )

    private fun EvolvedRule.blockedAt(nowWallEpochMillis: Long): BlockedAppSupervision? {
        val block = when {
            rule.disabledUntilEpochMillis > nowWallEpochMillis -> BlockedAppSupervision(
                rule = rule,
                remainingRestMillis = minOf(
                    rule.disabledUntilEpochMillis,
                    rule.occurrenceEndEpochMillis
                ) - nowWallEpochMillis,
                reason = AppSupervisionBlockReason.DISABLED_TIME
            )
            dailyUsageState.remainingUsageMillis == 0L && rule.isExecutionWindowActive ->
                BlockedAppSupervision(
                    rule = rule,
                    remainingRestMillis = minOf(
                        rule.dailyUsageResetAtEpochMillis,
                        rule.occurrenceEndEpochMillis
                    ) - nowWallEpochMillis,
                    reason = AppSupervisionBlockReason.DAILY_LIMIT
                )
            state?.phase == AppSupervisionPhase.REST -> BlockedAppSupervision(
                rule = rule,
                remainingRestMillis = minOf(
                    state.restUntilEpochMillis,
                    rule.occurrenceEndEpochMillis
                ) - nowWallEpochMillis,
                reason = AppSupervisionBlockReason.REST
            )
            else -> null
        }
        return block?.takeIf { it.remainingRestMillis > 0L }
    }

    private data class CycleEvolution(
        val state: AppSupervisionRuntimeState,
        val consumedMillis: Long
    )

    private data class EvolvedRule(
        val rule: AppSupervisionRule,
        val state: AppSupervisionRuntimeState?,
        val dailyUsageState: AppSupervisionDailyUsageState
    )

    private fun trustedElapsedDelta(
        state: AppSupervisionRuntimeState,
        nowElapsedMillis: Long,
        currentBootCount: Int
    ): Long {
        if (nowElapsedMillis < state.checkpointElapsedMillis) return 0L
        val sameKnownBoot = currentBootCount >= 0 && state.bootCount == currentBootCount
        val shortUnknownBootGap = currentBootCount == UNKNOWN_APP_SUPERVISION_BOOT_COUNT &&
            state.bootCount == UNKNOWN_APP_SUPERVISION_BOOT_COUNT &&
            nowElapsedMillis - state.checkpointElapsedMillis <= MAX_UNKNOWN_BOOT_GAP_MILLIS
        return if (sameKnownBoot || shortUnknownBootGap) {
            nowElapsedMillis - state.checkpointElapsedMillis
        } else {
            0L
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private const val MAX_UNKNOWN_BOOT_GAP_MILLIS = 30_000L
}

private val AppSupervisionBlockReason.priority: Int
    get() = when (this) {
        AppSupervisionBlockReason.REST -> 1
        AppSupervisionBlockReason.DAILY_LIMIT -> 2
        AppSupervisionBlockReason.DISABLED_TIME -> 3
    }

/**
 * 进程或服务重启后无法确认快照保存至恢复之间目标 App 是否一直在前台，恢复时必须切断
 * 这段观察连续性，避免把未知停摆时长误扣为使用额度。休息阶段仍保留墙钟截止时间。
 */
fun resetAppSupervisionObservationContinuity(
    states: Collection<AppSupervisionRuntimeState>,
    nowElapsedMillis: Long,
    bootCount: Int,
    isInteractive: Boolean
): List<AppSupervisionRuntimeState> {
    require(nowElapsedMillis >= 0L) { "当前单调时钟无效" }
    require(bootCount >= UNKNOWN_APP_SUPERVISION_BOOT_COUNT) { "当前开机编号无效" }
    return states.map { state ->
        state.copy(
            checkpointElapsedMillis = nowElapsedMillis,
            bootCount = bootCount,
            wasTargetForeground = false,
            wasInteractive = isInteractive
        )
    }
}

private fun Long.isValidPolicyMillis(): Boolean =
    this in MILLIS_PER_MINUTE..MAX_POLICY_MILLIS && this % MILLIS_PER_MINUTE == 0L
