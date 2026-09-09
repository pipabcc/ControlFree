package com.example.controlfree.widget

import android.content.Context

internal class PlanWidgetPreferences(
    context: Context,
    private val type: ControlFreeWidgetType
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "control_free_plan_widgets",
        Context.MODE_PRIVATE
    )

    fun bind(appWidgetId: Int, planId: String): Boolean =
        preferences.edit().putString(key(appWidgetId), planId).commit()

    fun planId(appWidgetId: Int): String? = preferences.getString(key(appWidgetId), null)

    fun remove(appWidgetId: Int) {
        preferences.edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int): String = "${type.name.lowercase()}_$appWidgetId"
}

internal class PendingPlanWidgetRequestStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "control_free_widget_pin_requests",
        Context.MODE_PRIVATE
    )

    fun record(type: ControlFreeWidgetType, planId: String) {
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        preferences.edit()
            .putString(planKey(type), planId)
            .putLong(timeKey(type), now)
            .commit()
    }

    fun consume(type: ControlFreeWidgetType, nowEpochMillis: Long): String? {
        val recordedAt = preferences.getLong(timeKey(type), -1L)
        val planId = preferences.getString(planKey(type), null)
        preferences.edit().remove(planKey(type)).remove(timeKey(type)).commit()
        return planId?.takeIf {
            recordedAt >= 0L && nowEpochMillis >= recordedAt &&
                nowEpochMillis - recordedAt <= MAX_AGE_MILLIS
        }
    }

    fun clear(type: ControlFreeWidgetType) {
        preferences.edit().remove(planKey(type)).remove(timeKey(type)).commit()
    }

    private fun planKey(type: ControlFreeWidgetType): String = "${type.name.lowercase()}_plan"
    private fun timeKey(type: ControlFreeWidgetType): String = "${type.name.lowercase()}_time"

    private companion object {
        const val MAX_AGE_MILLIS = 2 * 60_000L
    }
}
