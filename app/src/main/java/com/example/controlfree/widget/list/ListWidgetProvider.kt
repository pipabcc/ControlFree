package com.example.controlfree.widget.list

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.controlfree.AppShortcutDestination
import com.example.controlfree.AppShortcutRoute
import com.example.controlfree.MainActivity
import com.example.controlfree.R
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.ui.todo.TodoSubTab
import com.example.controlfree.widget.WidgetCreateTarget
import com.example.controlfree.widget.WidgetNavigationContract
import com.example.controlfree.widget.WidgetThemeColors
import com.example.controlfree.widget.WidgetThemeResolver
import com.example.controlfree.widget.quicknote.QuickNoteWidgetProvider
import com.example.controlfree.widget.quicknote.QuickNoteWidgetService
import com.example.controlfree.widget.todo.TodoWidgetActionReceiver
import com.example.controlfree.widget.todo.TodoWidgetProvider
import com.example.controlfree.widget.todo.TodoWidgetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

enum class ListWidgetType(
    val title: String,
    val emptyText: String,
    val subTab: TodoSubTab,
    val createTarget: WidgetCreateTarget
) {
    TODO("待办", "暂无待办", TodoSubTab.TODO, WidgetCreateTarget.TODO),
    QUICK_NOTE("闪记", "暂无闪记", TodoSubTab.QUICK_NOTE, WidgetCreateTarget.QUICK_NOTE)
}

abstract class ListWidgetProvider : AppWidgetProvider() {
    protected abstract val widgetType: ListWidgetType

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context.applicationContext, appWidgetManager, appWidgetId, widgetType)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateWidget(context.applicationContext, appWidgetManager, appWidgetId, widgetType)
    }

    companion object {
        internal fun updateAll(
            context: Context,
            type: ListWidgetType,
            providerClass: Class<out AppWidgetProvider>
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, providerClass))
            ids.forEach { updateWidget(appContext, manager, it, type) }
        }

        internal fun refreshCollection(
            context: Context,
            providerClass: Class<out AppWidgetProvider>
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, providerClass))
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_items)
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            type: ListWidgetType
        ) {
            val theme = WidgetThemeResolver.resolve(context)
            val views = RemoteViews(context.packageName, R.layout.widget_content_list).apply {
                setInt(R.id.widget_list_background, "setColorFilter", theme.background)
                setTextViewText(R.id.widget_list_title, type.title)
                setTextColor(R.id.widget_list_title, theme.primaryText)
                setTextColor(R.id.widget_list_add, theme.accent)
                setTextViewText(R.id.widget_list_empty, type.emptyText)
                setTextColor(R.id.widget_list_empty, theme.secondaryText)
                setRemoteAdapter(
                    R.id.widget_list_items,
                    Intent(context, type.serviceClass()).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        data = Uri.parse("controlfree://widget/${type.name.lowercase()}/$appWidgetId")
                    }
                )
                setEmptyView(R.id.widget_list_items, R.id.widget_list_empty)
                setOnClickPendingIntent(
                    R.id.widget_list_title,
                    openListPendingIntent(context, type, appWidgetId)
                )
                setOnClickPendingIntent(
                    R.id.widget_list_add,
                    createPendingIntent(context, type, appWidgetId)
                )
                setPendingIntentTemplate(
                    R.id.widget_list_items,
                    rowPendingIntentTemplate(context, type, appWidgetId)
                )
            }
            manager.updateAppWidget(appWidgetId, views)
            manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_items)
        }

        private fun ListWidgetType.serviceClass(): Class<out RemoteViewsService> = when (this) {
            ListWidgetType.TODO -> TodoWidgetService::class.java
            ListWidgetType.QUICK_NOTE -> QuickNoteWidgetService::class.java
        }

        private fun openListPendingIntent(
            context: Context,
            type: ListWidgetType,
            appWidgetId: Int
        ): PendingIntent = PendingIntent.getActivity(
            context,
            70_000 + type.ordinal * 10_000 + appWidgetId,
            listIntent(context, type),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun createPendingIntent(
            context: Context,
            type: ListWidgetType,
            appWidgetId: Int
        ): PendingIntent = PendingIntent.getActivity(
            context,
            90_000 + type.ordinal * 10_000 + appWidgetId,
            listIntent(context, type).putExtra(
                WidgetNavigationContract.EXTRA_CREATE_TARGET,
                type.createTarget.name
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun rowPendingIntentTemplate(
            context: Context,
            type: ListWidgetType,
            appWidgetId: Int
        ): PendingIntent = when (type) {
            ListWidgetType.TODO ->
                TodoWidgetActionReceiver.pendingIntentTemplate(context, appWidgetId)
            ListWidgetType.QUICK_NOTE -> PendingIntent.getActivity(
                context,
                110_000 + type.ordinal * 10_000 + appWidgetId,
                listIntent(context, type),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        private fun listIntent(context: Context, type: ListWidgetType): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(AppShortcutRoute.EXTRA_DESTINATION, AppShortcutDestination.TODO.name)
                putExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET, type.subTab.name)
            }
    }
}

internal abstract class ListWidgetFactory(
    context: Context,
    private val type: ListWidgetType
) : RemoteViewsService.RemoteViewsFactory {
    private val appContext = context.applicationContext
    private var rows: List<ListWidgetRow> = emptyList()
    private var theme: WidgetThemeColors = WidgetThemeResolver.resolve(appContext)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        theme = WidgetThemeResolver.resolve(appContext)
        rows = runCatching {
            runBlocking(Dispatchers.IO) {
                val repository = TodoRepository.getInstance(appContext)
                when (type) {
                    ListWidgetType.TODO -> todoWidgetRows(repository.observeAllTodos().first())
                    ListWidgetType.QUICK_NOTE ->
                        quickNoteWidgetRows(repository.observeQuickNoteInbox().first())
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val row = rows.getOrNull(position) ?: return null
        val titleColor = if (row.isMuted) theme.secondaryText else theme.primaryText
        return RemoteViews(appContext.packageName, R.layout.widget_content_row).apply {
            setTextViewText(R.id.widget_row_marker, row.marker)
            setTextColor(R.id.widget_row_marker, if (row.isMuted) theme.secondaryText else theme.accent)
            setTextViewText(R.id.widget_row_title, row.title.withCompletionStyle(row.isCompleted))
            setTextColor(R.id.widget_row_title, titleColor)
            setTextViewText(R.id.widget_row_subtitle, row.subtitle)
            setTextColor(R.id.widget_row_subtitle, theme.secondaryText)
            setViewVisibility(
                R.id.widget_row_subtitle,
                if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
            )
            when (type) {
                ListWidgetType.TODO -> {
                    setOnClickFillInIntent(
                        R.id.widget_row_root,
                        TodoWidgetActionReceiver.openItemFillInIntent(row.id)
                    )
                    setOnClickFillInIntent(
                        R.id.widget_row_marker,
                        TodoWidgetActionReceiver.completionFillInIntent(
                            itemId = row.id,
                            targetCompleted = !row.isCompleted
                        )
                    )
                    setContentDescription(
                        R.id.widget_row_marker,
                        if (row.isCompleted) "标记未完成" else "标记完成"
                    )
                }
                ListWidgetType.QUICK_NOTE -> setOnClickFillInIntent(
                    R.id.widget_row_root,
                    Intent().putExtra(WidgetNavigationContract.EXTRA_ITEM_ID, row.id)
                )
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: 0L

    override fun hasStableIds(): Boolean = true
}

private fun String.withCompletionStyle(isCompleted: Boolean): CharSequence {
    if (!isCompleted || isEmpty()) return this
    return SpannableString(this).apply {
        setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
