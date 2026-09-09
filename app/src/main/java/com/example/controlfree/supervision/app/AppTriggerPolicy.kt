package com.example.controlfree.supervision.app

import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.supervision.MAX_PLAN_TRIGGER_APPS
import com.example.controlfree.supervision.isValidSupervisionPackageName

data class AppTriggerRule(
    val planId: String,
    val planUpdatedAtEpochMillis: Long,
    val planName: String,
    val packageNames: Set<String>,
    val occurrenceEndEpochMillis: Long
) {
    init {
        require(planId.isNotBlank()) { "App 触发计划编号不能为空" }
        require(planUpdatedAtEpochMillis >= 0L) { "App 触发计划版本无效" }
        require(planName.isNotBlank()) { "App 触发计划名称不能为空" }
        require(packageNames.isNotEmpty()) { "App 触发列表不能为空" }
        require(packageNames.size <= MAX_PLAN_TRIGGER_APPS) { "App 触发列表过多" }
        require(packageNames.all(::isValidSupervisionPackageName)) { "App 触发包名无效" }
        require(occurrenceEndEpochMillis > 0L) { "App 触发时段结束时间无效" }
    }
}

data class AppTriggerEdgeState(
    val baselineEstablished: Boolean = false,
    val foregroundPackage: String? = null,
    val armedPlanIds: Set<String> = emptySet()
)

data class AppTriggerEdgeDecision(
    val state: AppTriggerEdgeState,
    val triggeredRule: AppTriggerRule? = null,
    val triggerPackageName: String? = null
)

/**
 * 只把“目标 App 从非前台进入前台”视为触发。首次可用观察仅建立基线，
 * 因此手动停用任务时已在前台的目标 App 不会立即把任务重新开启。
 */
object AppTriggerEdgePolicy {
    fun reconcile(
        state: AppTriggerEdgeState,
        rules: Collection<AppTriggerRule>
    ): AppTriggerEdgeState {
        val activeIds = rules.mapTo(linkedSetOf(), AppTriggerRule::planId)
        val retained = state.armedPlanIds.filterTo(linkedSetOf()) { it in activeIds }
        if (state.baselineEstablished) {
            rules.asSequence()
                .filter { it.planId !in retained }
                .filter { state.foregroundPackage !in it.packageNames }
                .mapTo(retained, AppTriggerRule::planId)
        }
        return state.copy(armedPlanIds = retained)
    }

    fun onObservation(
        previous: AppTriggerEdgeState,
        rules: Collection<AppTriggerRule>,
        observation: ForegroundObservation
    ): AppTriggerEdgeDecision {
        val sortedRules = rules.sortedBy(AppTriggerRule::planId)
        var state = reconcile(previous, sortedRules)
        if (!observation.isAvailable) return AppTriggerEdgeDecision(state)
        if (!state.baselineEstablished) {
            val foreground = observation.packageName
            return AppTriggerEdgeDecision(
                AppTriggerEdgeState(
                    baselineEstablished = true,
                    foregroundPackage = foreground,
                    armedPlanIds = sortedRules
                        .filter { foreground !in it.packageNames }
                        .mapTo(linkedSetOf(), AppTriggerRule::planId)
                )
            )
        }

        var foreground = state.foregroundPackage
        val armed = state.armedPlanIds.toMutableSet()
        var triggeredRule: AppTriggerRule? = null
        var triggerPackage: String? = null

        fun enterPackage(packageName: String?) {
            if (packageName != foreground) {
                armed += sortedRules
                    .filter { foreground in it.packageNames }
                    .map(AppTriggerRule::planId)
            }
            foreground = packageName
            val matching = sortedRules.filter { packageName != null && packageName in it.packageNames }
            if (matching.isEmpty()) {
                armed += sortedRules.map(AppTriggerRule::planId)
                return
            }
            if (triggeredRule == null) {
                triggeredRule = matching.firstOrNull { it.planId in armed }
                if (triggeredRule != null) triggerPackage = packageName
            }
            // 同一次进入最多触发一个任务；其他重叠任务也必须先退出再进入。
            armed -= matching.map(AppTriggerRule::planId).toSet()
            armed += sortedRules
                .filter { packageName !in it.packageNames }
                .map(AppTriggerRule::planId)
        }

        observation.newEvents.forEach { event ->
            when (event.kind) {
                ForegroundEventKind.FOREGROUND -> enterPackage(event.packageName)
                ForegroundEventKind.BACKGROUND -> if (foreground == event.packageName) {
                    enterPackage(null)
                }
            }
        }
        if (observation.packageName != foreground) enterPackage(observation.packageName)

        state = AppTriggerEdgeState(
            baselineEstablished = true,
            foregroundPackage = observation.packageName,
            armedPlanIds = armed
        )
        return AppTriggerEdgeDecision(state, triggeredRule, triggerPackage)
    }
}
