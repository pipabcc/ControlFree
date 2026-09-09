package com.example.controlfree.supervision.app

/**
 * 最近一次可信阻止状态。休息截止时间使用墙钟，宽限窗口使用单调时钟，分别避免时间
 * 调整延长休息和绕过观察故障保护。
 */
data class AppSupervisionEnforcementState(
    val trustedBlock: BlockedAppSupervision? = null,
    val validUntilEpochMillis: Long = 0L,
    val lastConfirmedElapsedMillis: Long = 0L,
    val unavailableSinceElapsedMillis: Long? = null,
    val recoveryRequested: Boolean = false
) {
    init {
        require(validUntilEpochMillis >= 0L) { "阻止状态截止时间无效" }
        require(lastConfirmedElapsedMillis >= 0L) { "阻止状态确认时间无效" }
        require(unavailableSinceElapsedMillis == null || unavailableSinceElapsedMillis >= 0L) {
            "不可用观察起始时间无效"
        }
        if (trustedBlock == null) {
            require(validUntilEpochMillis == 0L) { "空阻止状态不能包含截止时间" }
            require(lastConfirmedElapsedMillis == 0L) { "空阻止状态不能包含确认时间" }
        }
    }
}

data class AppSupervisionEnforcementDecision(
    val state: AppSupervisionEnforcementState,
    val blocked: BlockedAppSupervision?,
    val observationDegraded: Boolean,
    val requestSafeRecovery: Boolean,
    val recoveryRule: AppSupervisionRule?
)

/**
 * 把引擎结果转换为锁层动作。
 *
 * UsageStats 暂时不可用时，不能把上一次的阻止状态立即当成“已离开”而放行。最近一次
 * 可信阻止会在有限宽限期内继续生效；宽限期耗尽后只发出一次安全回退请求，由 Service
 * 将用户带回 Home 并重建观察窗口，避免静默放行或无限期卡在旧遮罩上。只有 AVAILABLE
 * 结果才能清除可信阻止状态（休息截止也属于明确的墙钟状态转换）。
 */
object AppSupervisionEnforcementPolicy {
    /** 足以覆盖查询超时、三次失败、熔断冷却和失败退避的有限窗口。 */
    const val STALE_BLOCK_GRACE_MILLIS = 30_000L

    /** 只有系统角色查询明确返回 true 时才扩大监督豁免；异常或未知必须 fail-closed。 */
    fun isProtectedPackageBypassAllowed(protectedResult: Boolean?): Boolean =
        protectedResult == true

    /** Home 请求是异步提示，可信阻止在墙钟截止前仍需保留到健康观察确认离开。 */
    fun recoveryFallbackBlock(
        state: AppSupervisionEnforcementState,
        nowWallEpochMillis: Long
    ): BlockedAppSupervision? {
        require(nowWallEpochMillis >= 0L) { "当前墙钟无效" }
        val trusted = state.trustedBlock ?: return null
        val remaining = state.validUntilEpochMillis - nowWallEpochMillis
        if (remaining <= 0L) return null
        return trusted.copy(remainingRestMillis = remaining)
    }

    /**
     * 恢复进程内阻止状态。旧快照没有 enforcement 扩展时，仅从最后一次明确
     * 记录“目标 App 在前台且屏幕可交互”的休息状态中推导信任阻止。
     */
    fun restore(
        persisted: AppSupervisionEnforcementState?,
        rules: Collection<AppSupervisionRule>,
        states: Collection<AppSupervisionRuntimeState>,
        dailyUsageStates: Collection<AppSupervisionDailyUsageState> = emptyList(),
        nowWallEpochMillis: Long,
        nowElapsedMillis: Long,
        currentBootCount: Int
    ): AppSupervisionEnforcementState {
        require(nowWallEpochMillis >= 0L) { "当前墙钟无效" }
        require(nowElapsedMillis >= 0L) { "当前单调时钟无效" }
        require(currentBootCount >= UNKNOWN_APP_SUPERVISION_BOOT_COUNT) { "当前开机编号无效" }

        val rulesById = rules.associateBy(AppSupervisionRule::planId)
        val statesById = states.associateBy(AppSupervisionRuntimeState::planId)
        val dailyById = dailyUsageStates.associateBy(AppSupervisionDailyUsageState::planId)
        require(rulesById.size == rules.size) { "App 监督规则编号重复" }
        require(statesById.size == states.size) { "App 监督状态编号重复" }
        require(dailyById.size == dailyUsageStates.size) { "App 每日累计状态编号重复" }

        val candidate = persisted?.trustedBlock?.let { blocked ->
            val rule = rulesById[blocked.rule.planId]
            val state = statesById[blocked.rule.planId]
            val valid = rule == blocked.rule && when (blocked.reason) {
                AppSupervisionBlockReason.REST ->
                    state?.matches(rule) == true && state.phase == AppSupervisionPhase.REST
                AppSupervisionBlockReason.DAILY_LIMIT ->
                    dailyById[blocked.rule.planId]?.remainingUsageMillis == 0L
                AppSupervisionBlockReason.DISABLED_TIME ->
                    rule.disabledUntilEpochMillis > nowWallEpochMillis
            }
            if (valid) {
                RestoredBlockCandidate(
                    rule = requireNotNull(rule),
                    state = state,
                    validUntilEpochMillis = persisted.validUntilEpochMillis,
                    reason = blocked.reason
                )
            } else {
                null
            }
        } ?: if (persisted == null) {
            states.asSequence()
                .filter { state ->
                    state.phase == AppSupervisionPhase.REST &&
                        state.wasTargetForeground && state.wasInteractive
                }
                .mapNotNull { state ->
                    rulesById[state.planId]
                        ?.takeIf(state::matches)
                        ?.let { rule ->
                            RestoredBlockCandidate(
                                rule = rule,
                                state = state,
                                validUntilEpochMillis = minOf(
                                    state.restUntilEpochMillis,
                                    rule.occurrenceEndEpochMillis
                                ),
                                reason = AppSupervisionBlockReason.REST
                            )
                        }
                }
                .maxByOrNull(RestoredBlockCandidate::validUntilEpochMillis)
        } else {
            null
        }

        if (candidate == null) {
            val unavailableSince = persisted?.unavailableSinceElapsedMillis
                ?.takeIf { it <= nowElapsedMillis }
                ?: persisted?.unavailableSinceElapsedMillis?.let { nowElapsedMillis }
            return AppSupervisionEnforcementState(
                unavailableSinceElapsedMillis = unavailableSince,
                recoveryRequested = false
            )
        }

        val validUntil = minOf(
            candidate.validUntilEpochMillis,
            candidate.reasonEndEpochMillis,
            candidate.rule.occurrenceEndEpochMillis
        )
        val remainingRestMillis = validUntil - nowWallEpochMillis
        if (remainingRestMillis <= 0L) return AppSupervisionEnforcementState()

        val monotonicClockIsContinuous = persisted != null &&
            currentBootCount >= 0 &&
            candidate.state?.bootCount == currentBootCount &&
            persisted.lastConfirmedElapsedMillis <= nowElapsedMillis
        val lastConfirmedElapsedMillis = if (monotonicClockIsContinuous) {
            persisted.lastConfirmedElapsedMillis
        } else {
            nowElapsedMillis
        }
        val unavailableSinceElapsedMillis = persisted?.unavailableSinceElapsedMillis?.let { since ->
            if (monotonicClockIsContinuous && since <= nowElapsedMillis) since else nowElapsedMillis
        }
        return AppSupervisionEnforcementState(
            trustedBlock = BlockedAppSupervision(
                candidate.rule,
                remainingRestMillis,
                candidate.reason
            ),
            validUntilEpochMillis = validUntil,
            lastConfirmedElapsedMillis = lastConfirmedElapsedMillis,
            unavailableSinceElapsedMillis = unavailableSinceElapsedMillis,
            recoveryRequested = false
        )
    }

    fun reduce(
        previous: AppSupervisionEnforcementState,
        candidateBlocked: BlockedAppSupervision?,
        observation: AppSupervisionObservation,
        nowWallEpochMillis: Long,
        nowElapsedMillis: Long,
        staleBlockGraceMillis: Long = STALE_BLOCK_GRACE_MILLIS
    ): AppSupervisionEnforcementDecision {
        require(nowWallEpochMillis >= 0L) { "当前墙钟无效" }
        require(nowElapsedMillis >= 0L) { "当前单调时钟无效" }
        require(staleBlockGraceMillis >= 0L) { "保守阻止宽限时间无效" }

        // 熄屏只结束本次可交互锁层，不代表目标 App 已离开；保留最近可信阻止，
        // 由下一次亮屏观察决定是否真正放行。
        if (observation.isAvailable && !observation.isInteractive) {
            return AppSupervisionEnforcementDecision(
                state = previous,
                blocked = null,
                observationDegraded = false,
                requestSafeRecovery = false,
                recoveryRule = previous.trustedBlock?.rule
            )
        }

        // UsageEvents 查询成功但没有得到当前前台包，并不等于“已明确离开目标 App”。
        // 新观察窗口、厂商裁剪历史事件或长时间前台都可能产生 AVAILABLE + null；
        // 只有明确识别到某个前台包时，才允许清除最近一次可信阻止。
        if (observation.isAvailable && observation.foregroundPackage != null) {
            val nextState = candidateBlocked?.let { blocked ->
                val validUntil = saturatedAdd(
                    nowWallEpochMillis,
                    blocked.remainingRestMillis
                ).coerceAtMost(blocked.rule.blockReasonEndEpochMillis(blocked.reason))
                AppSupervisionEnforcementState(
                    trustedBlock = blocked,
                    validUntilEpochMillis = validUntil,
                    lastConfirmedElapsedMillis = nowElapsedMillis,
                    recoveryRequested = false
                )
            } ?: AppSupervisionEnforcementState()
            return AppSupervisionEnforcementDecision(
                state = nextState,
                blocked = candidateBlocked,
                observationDegraded = false,
                requestSafeRecovery = false,
                recoveryRule = null
            )
        }

        val trusted = previous.trustedBlock
        if (trusted == null) {
            val unavailableSince = previous.unavailableSinceElapsedMillis
                ?: nowElapsedMillis
            val unavailableAge = elapsedSince(unavailableSince, nowElapsedMillis)
            val shouldRequestRecovery = observation.isInteractive &&
                unavailableAge > staleBlockGraceMillis &&
                !previous.recoveryRequested
            val nextState = previous.copy(
                unavailableSinceElapsedMillis = unavailableSince,
                recoveryRequested = previous.recoveryRequested || shouldRequestRecovery
            )
            return AppSupervisionEnforcementDecision(
                state = nextState,
                blocked = null,
                observationDegraded = true,
                requestSafeRecovery = shouldRequestRecovery,
                recoveryRule = null
            )
        }

        val ageMillis = elapsedSince(previous.lastConfirmedElapsedMillis, nowElapsedMillis)
        val remainingRestMillis = previous.validUntilEpochMillis - nowWallEpochMillis
        val withinGrace = ageMillis <= staleBlockGraceMillis
        if (observation.isInteractive && withinGrace && remainingRestMillis > 0L) {
            return AppSupervisionEnforcementDecision(
                state = previous,
                blocked = trusted.copy(remainingRestMillis = remainingRestMillis),
                observationDegraded = true,
                requestSafeRecovery = false,
                recoveryRule = trusted.rule
            )
        }

        // 非交互状态下由屏幕事件负责隐藏锁层；保留可信状态，屏幕重新亮起后再判断。
        if (!observation.isInteractive) {
            return AppSupervisionEnforcementDecision(
                state = previous,
                blocked = null,
                observationDegraded = true,
                requestSafeRecovery = false,
                recoveryRule = trusted.rule
            )
        }

        if (remainingRestMillis <= 0L) {
            return AppSupervisionEnforcementDecision(
                state = AppSupervisionEnforcementState(),
                blocked = null,
                observationDegraded = true,
                requestSafeRecovery = false,
                recoveryRule = trusted.rule
            )
        }

        val shouldRequestRecovery = !previous.recoveryRequested
        val nextState = previous.copy(recoveryRequested = true)
        return AppSupervisionEnforcementDecision(
            state = nextState,
            blocked = null,
            observationDegraded = true,
            requestSafeRecovery = shouldRequestRecovery,
            recoveryRule = trusted.rule
        )
    }

    private fun elapsedSince(previous: Long, current: Long): Long = when {
        // 单调时钟回退意味着观察连续性不再可信，立即走安全恢复，不延长旧阻止窗口。
        current < previous -> Long.MAX_VALUE
        else -> current - previous
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class RestoredBlockCandidate(
        val rule: AppSupervisionRule,
        val state: AppSupervisionRuntimeState?,
        val validUntilEpochMillis: Long,
        val reason: AppSupervisionBlockReason
    ) {
        val reasonEndEpochMillis: Long
            get() = when (reason) {
                AppSupervisionBlockReason.REST -> requireNotNull(state).restUntilEpochMillis
                AppSupervisionBlockReason.DAILY_LIMIT -> rule.dailyUsageResetAtEpochMillis
                AppSupervisionBlockReason.DISABLED_TIME -> rule.disabledUntilEpochMillis
            }
    }
}

private fun AppSupervisionRule.blockReasonEndEpochMillis(
    reason: AppSupervisionBlockReason
): Long = minOf(
    occurrenceEndEpochMillis,
    when (reason) {
        AppSupervisionBlockReason.REST -> occurrenceEndEpochMillis
        AppSupervisionBlockReason.DAILY_LIMIT -> dailyUsageResetAtEpochMillis
        AppSupervisionBlockReason.DISABLED_TIME -> disabledUntilEpochMillis
    }
)
