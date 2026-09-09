package com.example.controlfree.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.example.controlfree.widget.plan.FocusWidgetProvider
import com.example.controlfree.widget.plan.SupervisionWidgetProvider
import com.example.controlfree.widget.quicknote.QuickNoteWidgetProvider
import com.example.controlfree.widget.todo.TodoWidgetProvider

enum class ControlFreeWidgetType {
    SUPERVISION,
    FOCUS,
    TODO,
    QUICK_NOTE
}

enum class WidgetPinRequestResult {
    REQUESTED,
    UNSUPPORTED,
    FAILED
}

enum class WidgetCreateTarget {
    TODO,
    QUICK_NOTE
}

/** MainActivity 消费这些参数后，应定位到对应清单，并按需打开新建或目标记录。 */
object WidgetNavigationContract {
    const val EXTRA_CREATE_TARGET = "com.example.controlfree.extra.WIDGET_CREATE_TARGET"
    const val EXTRA_ITEM_ID = "com.example.controlfree.extra.WIDGET_ITEM_ID"
    const val EXTRA_PLAN_ID = "com.example.controlfree.extra.WIDGET_PLAN_ID"
}

/**
 * 统一封装 Android 8.0 以上的固定组件请求。
 *
 * 指定计划的监督/专注组件会把计划编号同时写入组件选项与短时请求存储；前者供
 * 规范启动器读取，后者兼容未透传自定义选项的启动器。最终绑定仍由配置页校验。
 */
object ControlFreeWidgetPinning {
    fun requestSupervision(context: Context, planId: String): WidgetPinRequestResult =
        request(context, ControlFreeWidgetType.SUPERVISION, planId)

    fun requestFocus(context: Context, planId: String): WidgetPinRequestResult =
        request(context, ControlFreeWidgetType.FOCUS, planId)

    fun requestTodo(context: Context): WidgetPinRequestResult =
        request(context, ControlFreeWidgetType.TODO, null)

    fun requestQuickNote(context: Context): WidgetPinRequestResult =
        request(context, ControlFreeWidgetType.QUICK_NOTE, null)

    private fun request(
        context: Context,
        type: ControlFreeWidgetType,
        planId: String?
    ): WidgetPinRequestResult = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return WidgetPinRequestResult.UNSUPPORTED
        }
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AppWidgetManager::class.java)
            ?: return WidgetPinRequestResult.FAILED
        if (!manager.isRequestPinAppWidgetSupported) {
            return WidgetPinRequestResult.UNSUPPORTED
        }
        val normalizedPlanId = planId?.trim()?.takeIf(String::isNotEmpty)
        if (planId != null && normalizedPlanId == null) {
            return WidgetPinRequestResult.FAILED
        }
        if (normalizedPlanId != null) {
            PendingPlanWidgetRequestStore(appContext).record(type, normalizedPlanId)
        }
        val options = normalizedPlanId?.let { id ->
            Bundle().apply { putString(WidgetBindingOptions.OPTION_PLAN_ID, id) }
        }
        val requested = manager.requestPinAppWidget(
            ComponentName(appContext, type.providerClass()),
            options,
            successCallback(appContext, type)
        )
        if (requested) {
            WidgetPinRequestResult.REQUESTED
        } else {
            if (normalizedPlanId != null) PendingPlanWidgetRequestStore(appContext).clear(type)
            WidgetPinRequestResult.FAILED
        }
    } catch (_: RuntimeException) {
        WidgetPinRequestResult.FAILED
    }

    private fun successCallback(
        context: Context,
        type: ControlFreeWidgetType
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        20_000 + type.ordinal,
        Intent(context, WidgetPinnedReceiver::class.java).apply {
            action = WidgetPinnedReceiver.ACTION_WIDGET_PINNED
            putExtra(WidgetPinnedReceiver.EXTRA_WIDGET_TYPE, type.name)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal object WidgetBindingOptions {
    const val OPTION_PLAN_ID = "com.example.controlfree.option.WIDGET_PLAN_ID"
}

private fun ControlFreeWidgetType.providerClass(): Class<*> = when (this) {
    ControlFreeWidgetType.SUPERVISION -> SupervisionWidgetProvider::class.java
    ControlFreeWidgetType.FOCUS -> FocusWidgetProvider::class.java
    ControlFreeWidgetType.TODO -> TodoWidgetProvider::class.java
    ControlFreeWidgetType.QUICK_NOTE -> QuickNoteWidgetProvider::class.java
}
