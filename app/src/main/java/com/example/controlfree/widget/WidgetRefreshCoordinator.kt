package com.example.controlfree.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.controlfree.todo.anniversary.widget.AnniversaryWidgetProvider
import com.example.controlfree.widget.plan.FocusWidgetProvider
import com.example.controlfree.widget.plan.SupervisionWidgetProvider
import com.example.controlfree.widget.quicknote.QuickNoteWidgetProvider
import com.example.controlfree.widget.todo.TodoWidgetProvider

object WidgetRefreshCoordinator {
    private val monitor = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRefresh: Runnable? = null
    private var pendingContext: Context? = null
    private val pendingFullTargets = linkedSetOf<WidgetRefreshTarget>()
    private val pendingCollectionTargets = linkedSetOf<WidgetRefreshTarget>()

    fun refreshAll(context: Context) {
        requestRefresh(context, WidgetRefreshTarget.entries.toSet(), delayMillis = 0L)
    }

    internal fun refreshForTables(context: Context, tables: Set<String>) {
        val plan = widgetRefreshPlanForTables(tables)
        requestRefresh(context, plan.fullTargets, plan.collectionTargets)
    }

    internal fun refreshPlans(context: Context) {
        requestRefresh(
            context,
            setOf(WidgetRefreshTarget.SUPERVISION, WidgetRefreshTarget.FOCUS)
        )
    }

    internal fun requestRefresh(
        context: Context,
        targets: Set<WidgetRefreshTarget>,
        delayMillis: Long = REFRESH_DEBOUNCE_MILLIS
    ) = requestRefresh(context, targets, emptySet(), delayMillis)

    private fun requestRefresh(
        context: Context,
        fullTargets: Set<WidgetRefreshTarget>,
        collectionTargets: Set<WidgetRefreshTarget>,
        delayMillis: Long = REFRESH_DEBOUNCE_MILLIS
    ) {
        if (fullTargets.isEmpty() && collectionTargets.isEmpty()) return
        val runnable = synchronized(monitor) {
            pendingContext = context.applicationContext
            pendingFullTargets += fullTargets
            pendingCollectionTargets += collectionTargets
            pendingCollectionTargets -= pendingFullTargets
            pendingRefresh?.let(handler::removeCallbacks)
            Runnable(::flush).also { pendingRefresh = it }
        }
        handler.postDelayed(runnable, delayMillis)
    }

    private fun flush() {
        val pending = synchronized(monitor) {
            val appContext = pendingContext
            val fullSnapshot = pendingFullTargets.toSet()
            val collectionSnapshot = pendingCollectionTargets.toSet() - fullSnapshot
            pendingContext = null
            pendingFullTargets.clear()
            pendingCollectionTargets.clear()
            pendingRefresh = null
            PendingWidgetRefresh(appContext, fullSnapshot, collectionSnapshot)
        }
        val context = pending.context ?: return
        if (WidgetRefreshTarget.SUPERVISION in pending.fullTargets) {
            SupervisionWidgetProvider.updateAll(context)
        }
        if (WidgetRefreshTarget.FOCUS in pending.fullTargets) {
            FocusWidgetProvider.updateAll(context)
        }
        if (WidgetRefreshTarget.TODO in pending.fullTargets) {
            TodoWidgetProvider.updateAll(context)
        } else if (WidgetRefreshTarget.TODO in pending.collectionTargets) {
            TodoWidgetProvider.refreshCollection(context)
        }
        if (WidgetRefreshTarget.QUICK_NOTE in pending.fullTargets) {
            QuickNoteWidgetProvider.updateAll(context)
        } else if (WidgetRefreshTarget.QUICK_NOTE in pending.collectionTargets) {
            QuickNoteWidgetProvider.refreshCollection(context)
        }
        if (WidgetRefreshTarget.ANNIVERSARY in pending.fullTargets) {
            AnniversaryWidgetProvider.updateAll(context)
        }
    }

    private const val REFRESH_DEBOUNCE_MILLIS = 250L
}

private data class PendingWidgetRefresh(
    val context: Context?,
    val fullTargets: Set<WidgetRefreshTarget>,
    val collectionTargets: Set<WidgetRefreshTarget>
)

internal data class WidgetTableRefreshPlan(
    val fullTargets: Set<WidgetRefreshTarget>,
    val collectionTargets: Set<WidgetRefreshTarget>
)

internal fun widgetRefreshPlanForTables(tables: Set<String>): WidgetTableRefreshPlan =
    WidgetTableRefreshPlan(
        fullTargets = buildSet {
            if (tables.any { it in PLAN_WIDGET_TABLES }) {
                add(WidgetRefreshTarget.SUPERVISION)
                add(WidgetRefreshTarget.FOCUS)
            }
            if ("anniversary_items" in tables) add(WidgetRefreshTarget.ANNIVERSARY)
        },
        collectionTargets = buildSet {
            if ("todo_items" in tables) add(WidgetRefreshTarget.TODO)
            if ("quick_notes" in tables) add(WidgetRefreshTarget.QUICK_NOTE)
        }
    )

private val PLAN_WIDGET_TABLES = setOf(
    "supervision_plans",
    "supervision_time_ranges",
    "global_supervision_policies",
    "app_supervision_policies",
    "app_supervision_disabled_time_ranges",
    "plan_activation_reservations"
)

internal enum class WidgetRefreshTarget {
    SUPERVISION,
    FOCUS,
    TODO,
    QUICK_NOTE,
    ANNIVERSARY
}
