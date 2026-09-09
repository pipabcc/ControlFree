package com.example.controlfree.widget.plan

import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.GlobalCyclePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class PlanWidgetPresentation(
    val category: String,
    val title: String,
    val status: String,
    val details: String,
    val action: String
)

internal fun compatiblePlanTypes(type: com.example.controlfree.widget.ControlFreeWidgetType): Set<SupervisionPlanType> =
    when (type) {
        com.example.controlfree.widget.ControlFreeWidgetType.SUPERVISION ->
            setOf(SupervisionPlanType.GLOBAL, SupervisionPlanType.APP)
        com.example.controlfree.widget.ControlFreeWidgetType.FOCUS ->
            setOf(SupervisionPlanType.FOCUS)
        com.example.controlfree.widget.ControlFreeWidgetType.TODO,
        com.example.controlfree.widget.ControlFreeWidgetType.QUICK_NOTE -> emptySet()
    }

internal fun planWidgetPresentation(
    plan: SupervisionPlan,
    isActive: Boolean,
    now: Instant,
    deviceZoneId: ZoneId,
    appLabel: String? = null
): PlanWidgetPresentation {
    val nextBoundary = nextPlanBoundary(plan, now, deviceZoneId)
    val status = when {
        isActive -> nextBoundary?.let { "运行中 · 至 ${formatBoundary(it, now, deviceZoneId)}" }
            ?: "运行中"
        plan.enabled -> nextBoundary?.let { "已启用 · 下次 ${formatBoundary(it, now, deviceZoneId)}" }
            ?: "已启用"
        plan.scheduledEnableAtEpochMillis != null -> {
            val scheduled = Instant.ofEpochMilli(plan.scheduledEnableAtEpochMillis)
            "已预约 · ${formatBoundary(scheduled, now, deviceZoneId)}开启"
        }
        else -> "已停用"
    }
    val category = when (plan.type) {
        SupervisionPlanType.GLOBAL -> "全局监督"
        SupervisionPlanType.APP -> "App 独立监督"
        SupervisionPlanType.FOCUS -> "专注任务"
    }
    val details = when (val policy = plan.policy) {
        is GlobalCyclePolicy ->
            "可用${policy.usageDuration.toMinutes()}分钟 · 锁定${policy.lockDuration.toMinutes()}分钟"
        is AppRulePolicy -> {
            val displayApp = appLabel?.takeIf(String::isNotBlank) ?: policy.packageName
            "$displayApp · 可用${policy.usageAllowance.toMinutes()}分钟 · 休息${policy.restDuration.toMinutes()}分钟"
        }
        is FocusCyclePolicy ->
            "专注${policy.lockDuration.toMinutes()}分钟 · 休息${policy.playDuration.toMinutes()}分钟"
    }
    return PlanWidgetPresentation(
        category = category,
        title = plan.name,
        status = status,
        details = details,
        action = if (plan.enabled) "关闭" else "开启"
    )
}

private fun nextPlanBoundary(
    plan: SupervisionPlan,
    now: Instant,
    deviceZoneId: ZoneId
): Instant? {
    if (!plan.enabled) return plan.scheduledEnableAtEpochMillis?.let(Instant::ofEpochMilli)
    val oneTimeWindow = plan.oneTimeFocusWindow
    if (oneTimeWindow != null) {
        val nowMillis = now.toEpochMilli()
        return when {
            nowMillis < oneTimeWindow.startEpochMillis ->
                Instant.ofEpochMilli(oneTimeWindow.startEpochMillis)
            nowMillis < oneTimeWindow.endEpochMillis ->
                Instant.ofEpochMilli(oneTimeWindow.endEpochMillis)
            else -> null
        }
    }
    return plan.schedule.nextStateChangeAfter(now, deviceZoneId)
}

private fun formatBoundary(instant: Instant, now: Instant, zoneId: ZoneId): String {
    val dateTime = instant.atZone(zoneId)
    val nowDate = now.atZone(zoneId).toLocalDate()
    return if (dateTime.toLocalDate() == nowDate) {
        dateTime.format(TIME_FORMATTER)
    } else {
        dateTime.format(DATE_TIME_FORMATTER)
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
