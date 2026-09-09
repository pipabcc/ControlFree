package com.example.controlfree.widget.plan

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.controlfree.AppShortcutDestination
import com.example.controlfree.AppShortcutRoute
import com.example.controlfree.MainActivity
import com.example.controlfree.R
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.runtime.SupervisionScheduleEvaluation
import com.example.controlfree.supervision.runtime.SupervisionScheduleEvaluator
import com.example.controlfree.widget.ControlFreeWidgetType
import com.example.controlfree.widget.PlanWidgetPreferences
import com.example.controlfree.widget.WidgetNavigationContract
import com.example.controlfree.widget.WidgetThemeResolver
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

abstract class BoundPlanWidgetProvider : AppWidgetProvider() {
    protected abstract val widgetType: ControlFreeWidgetType

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        enqueueUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        enqueueUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun enqueueUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                BoundPlanWidgetRenderer.update(
                    context.applicationContext,
                    widgetType,
                    appWidgetManager,
                    appWidgetIds
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val preferences = PlanWidgetPreferences(context, widgetType)
        appWidgetIds.forEach { appWidgetId ->
            BoundPlanWidgetRenderer.invalidate(widgetType, appWidgetId)
            preferences.remove(appWidgetId)
        }
        super.onDeleted(context, appWidgetIds)
    }

    internal companion object {
        val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class SupervisionWidgetProvider : BoundPlanWidgetProvider() {
    override val widgetType = ControlFreeWidgetType.SUPERVISION

    companion object {
        fun updateAll(context: Context) = BoundPlanWidgetRenderer.updateAll(
            context,
            ControlFreeWidgetType.SUPERVISION,
            SupervisionWidgetProvider::class.java
        )
    }
}

class FocusWidgetProvider : BoundPlanWidgetProvider() {
    override val widgetType = ControlFreeWidgetType.FOCUS

    companion object {
        fun updateAll(context: Context) = BoundPlanWidgetRenderer.updateAll(
            context,
            ControlFreeWidgetType.FOCUS,
            FocusWidgetProvider::class.java
        )
    }
}

internal object BoundPlanWidgetRenderer {
    private val updateGates = mapOf(
        ControlFreeWidgetType.SUPERVISION to PlanWidgetUpdateGate(),
        ControlFreeWidgetType.FOCUS to PlanWidgetUpdateGate()
    )

    fun invalidate(type: ControlFreeWidgetType, appWidgetId: Int) {
        updateGates[type]?.invalidate(appWidgetId)
    }

    fun updateAll(
        context: Context,
        type: ControlFreeWidgetType,
        providerClass: Class<out AppWidgetProvider>
    ) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, providerClass))
        if (ids.isEmpty()) return
        BoundPlanWidgetProvider.widgetScope.launch {
            update(appContext, type, manager, ids)
        }
    }

    suspend fun update(
        context: Context,
        type: ControlFreeWidgetType,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val gate = requireNotNull(updateGates[type]) { "不支持的计划组件类型：$type" }
        val refreshTokens = appWidgetIds.associateWith(gate::begin)
        val preferences = PlanWidgetPreferences(context, type)
        val plans = when (val result = SupervisionPlanRepository.getInstance(context).loadPlans()) {
            is PlanLoadResult.Success -> result.plans
            is PlanLoadResult.CorruptData,
            PlanLoadResult.StorageFailure -> emptyList()
        }
        val now = Instant.now()
        val deviceZoneId = ZoneId.systemDefault()
        val activePlanIds = activePlanIds(context, plans, now, deviceZoneId)
        val compatibleTypes = compatiblePlanTypes(type)
        appWidgetIds.forEach { appWidgetId ->
            val selectedId = preferences.planId(appWidgetId)
            val plan = plans.firstOrNull { it.id == selectedId && it.type in compatibleTypes }
            val layoutId = planWidgetLayout(
                type = type,
                minHeightDp = manager.getAppWidgetOptions(appWidgetId).getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    0
                )
            )
            val views = if (plan == null) {
                emptyRemoteViews(context, type, selectedId != null, layoutId)
            } else {
                planRemoteViews(
                    context = context,
                    type = type,
                    appWidgetId = appWidgetId,
                    layoutId = layoutId,
                    plan = plan,
                    isActive = plan.id in activePlanIds,
                    now = now,
                    deviceZoneId = deviceZoneId
                )
            }
            gate.commitIfCurrent(requireNotNull(refreshTokens[appWidgetId])) {
                manager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun activePlanIds(
        context: Context,
        plans: List<SupervisionPlan>,
        now: Instant,
        deviceZoneId: ZoneId
    ): Set<String> {
        val suppression = PreferenceManager(context).getScheduledOccurrenceSuppression(now.toEpochMilli())
        return when (
            val evaluation = SupervisionScheduleEvaluator.evaluate(
                plans = plans,
                now = now,
                deviceZoneId = deviceZoneId,
                suppression = suppression
            )
        ) {
            is SupervisionScheduleEvaluation.Ready -> buildSet {
                evaluation.active?.plan?.id?.let(::add)
                evaluation.activeApps.mapTo(this) { it.plan.id }
            }
            is SupervisionScheduleEvaluation.ConflictingActivePlans -> evaluation.planIds
        }
    }

    private fun emptyRemoteViews(
        context: Context,
        type: ControlFreeWidgetType,
        wasBound: Boolean,
        layoutId: Int
    ): RemoteViews = RemoteViews(context.packageName, layoutId).apply {
        val theme = WidgetThemeResolver.resolve(context)
        applyTheme(theme)
        setTextViewText(
            R.id.widget_plan_category,
            if (type == ControlFreeWidgetType.FOCUS) "专注任务" else "监督任务"
        )
        setTextViewText(R.id.widget_plan_title, if (wasBound) "任务已不存在" else "选择一个任务")
        setTextViewText(R.id.widget_plan_status, "打开 ControlFree 完成设置")
        setTextViewText(R.id.widget_plan_details, "")
        setViewVisibility(R.id.widget_plan_toggle, View.GONE)
        setOnClickPendingIntent(R.id.widget_plan_root, openPlanPageIntent(context, type, null, 0))
    }

    private fun planRemoteViews(
        context: Context,
        type: ControlFreeWidgetType,
        appWidgetId: Int,
        layoutId: Int,
        plan: SupervisionPlan,
        isActive: Boolean,
        now: Instant,
        deviceZoneId: ZoneId
    ): RemoteViews {
        val appLabel = (plan.policy as? AppRulePolicy)?.packageName?.let { packageName ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
        val presentation = planWidgetPresentation(plan, isActive, now, deviceZoneId, appLabel)
        return RemoteViews(context.packageName, layoutId).apply {
            val theme = WidgetThemeResolver.resolve(context)
            applyTheme(theme)
            setTextViewText(R.id.widget_plan_category, presentation.category)
            setTextViewText(R.id.widget_plan_title, presentation.title)
            setTextViewText(R.id.widget_plan_status, presentation.status)
            setTextViewText(R.id.widget_plan_details, presentation.details)
            setTextViewText(R.id.widget_plan_toggle, presentation.action)
            setViewVisibility(R.id.widget_plan_toggle, View.VISIBLE)
            setOnClickPendingIntent(
                R.id.widget_plan_root,
                openPlanPageIntent(context, type, plan.id, appWidgetId)
            )
            setOnClickPendingIntent(
                R.id.widget_plan_toggle,
                if (
                    requiresPlanWidgetAuthentication(
                        type = type,
                        targetEnabled = false,
                        hasCredentials = CredentialStore(context).hasAnyCredential()
                    ) && plan.enabled
                ) {
                    PlanWidgetAuthenticationActivity.pendingIntent(
                        context = context,
                        type = type,
                        appWidgetId = appWidgetId,
                        expectedPlanId = plan.id
                    )
                } else {
                    PlanWidgetActionReceiver.pendingIntent(
                        context = context,
                        type = type,
                        appWidgetId = appWidgetId,
                        expectedPlanId = plan.id,
                        targetEnabled = !plan.enabled
                    )
                }
            )
        }
    }

    private fun planWidgetLayout(type: ControlFreeWidgetType, minHeightDp: Int): Int =
        if (usesCompactSupervisionLayout(type, minHeightDp)) {
            R.layout.widget_plan_compact
        } else {
            R.layout.widget_plan
        }

    private fun RemoteViews.applyTheme(theme: com.example.controlfree.widget.WidgetThemeColors) {
        setInt(R.id.widget_plan_background, "setColorFilter", theme.background)
        setTextColor(R.id.widget_plan_category, theme.secondaryText)
        setTextColor(R.id.widget_plan_title, theme.primaryText)
        setTextColor(R.id.widget_plan_status, theme.accent)
        setTextColor(R.id.widget_plan_details, theme.secondaryText)
        setTextColor(R.id.widget_plan_toggle, theme.accent)
    }

    private fun openPlanPageIntent(
        context: Context,
        type: ControlFreeWidgetType,
        planId: String?,
        requestSeed: Int
    ): PendingIntent = PendingIntent.getActivity(
        context,
        30_000 + type.ordinal * 10_000 + requestSeed,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                AppShortcutRoute.EXTRA_DESTINATION,
                if (type == ControlFreeWidgetType.FOCUS) {
                    AppShortcutDestination.FOCUS.name
                } else {
                    AppShortcutDestination.MONITOR.name
                }
            )
            planId?.let { putExtra(WidgetNavigationContract.EXTRA_PLAN_ID, it) }
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal fun usesCompactSupervisionLayout(
    type: ControlFreeWidgetType,
    minHeightDp: Int
): Boolean = type == ControlFreeWidgetType.SUPERVISION &&
    (minHeightDp <= SUPERVISION_COMPACT_MAX_HEIGHT_DP || minHeightDp <= 0)

private const val SUPERVISION_COMPACT_MAX_HEIGHT_DP = 88

internal data class PlanWidgetUpdateToken(val appWidgetId: Int, val generation: Long)

internal class PlanWidgetUpdateGate {
    private val monitor = Any()
    private val generations = mutableMapOf<Int, Long>()

    fun begin(appWidgetId: Int): PlanWidgetUpdateToken = synchronized(monitor) {
        val generation = generations.getOrDefault(appWidgetId, 0L) + 1L
        generations[appWidgetId] = generation
        PlanWidgetUpdateToken(appWidgetId, generation)
    }

    fun invalidate(appWidgetId: Int) {
        synchronized(monitor) {
            generations[appWidgetId] = generations.getOrDefault(appWidgetId, 0L) + 1L
        }
    }

    fun commitIfCurrent(token: PlanWidgetUpdateToken, commit: () -> Unit): Boolean =
        synchronized(monitor) {
            val isCurrent = generations[token.appWidgetId] == token.generation
            if (isCurrent) commit()
            isCurrent
        }
}
