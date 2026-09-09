package com.example.controlfree.todo.anniversary.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.controlfree.AppShortcutDestination
import com.example.controlfree.AppShortcutRoute
import com.example.controlfree.MainActivity
import com.example.controlfree.R
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.ui.todo.TodoSubTab
import com.example.controlfree.ui.todo.anniversary.AndroidIcuLunarCalendar
import com.example.controlfree.ui.todo.anniversary.AnniversaryOccurrenceResolver
import com.example.controlfree.ui.todo.anniversary.AnniversaryType
import com.example.controlfree.ui.todo.anniversary.toSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.example.controlfree.widget.WidgetThemeColors
import com.example.controlfree.widget.WidgetThemeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AnniversaryWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        refreshGate.enable()
        super.onEnabled(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val refreshTokens = appWidgetIds.map(refreshGate::begin)
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                refreshTokens.forEach { token ->
                    updateWidget(context, appWidgetManager, token)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach(refreshGate::invalidate)
        val preferences = AnniversaryWidgetPreferences(context)
        appWidgetIds.forEach(preferences::remove)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        refreshGate.disable()
        super.onDisabled(context)
    }

    companion object {
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val refreshGate = WidgetRefreshGenerationGate()

        private var cachedItems: List<AnniversaryItemEntity>? = null
        private var lastCacheTime = 0L

        private fun clearCache() {
            cachedItems = null
        }

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            clearCache()
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, AnniversaryWidgetProvider::class.java))
            val refreshTokens = ids.map(refreshGate::begin)
            widgetScope.launch {
                refreshTokens.forEach { token -> updateWidget(appContext, manager, token) }
            }
        }

        suspend fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) = updateWidget(context, manager, refreshGate.begin(appWidgetId))

        private suspend fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            refreshToken: WidgetRefreshToken
        ) {
            val appContext = context.applicationContext
            val nowMs = System.currentTimeMillis()
            val allItems = if (cachedItems != null && (nowMs - lastCacheTime) < 5000L) {
                cachedItems!!
            } else {
                val dao = ControlFreeDatabase.getInstance(appContext).anniversaryDao()
                val items = runCatching { dao.observeAll().first() }.getOrDefault(emptyList())
                cachedItems = items
                lastCacheTime = nowMs
                items
            }
            val preferences = AnniversaryWidgetPreferences(appContext)
            val selectedId = preferences.getAnniversaryId(refreshToken.appWidgetId)
            val item = selectAnniversaryWidgetItem(allItems, selectedId)
            val remoteViews = if (item == null) {
                emptyRemoteViews(appContext)
            } else {
                itemRemoteViews(appContext, item)
            }
            refreshGate.commitIfCurrent(refreshToken) {
                if (item != null && selectedId == null) {
                    preferences.bind(refreshToken.appWidgetId, item.id)
                }
                manager.updateAppWidget(refreshToken.appWidgetId, remoteViews)
            }
        }

        private fun emptyRemoteViews(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_anniversary).apply {
                applyTheme(WidgetThemeResolver.resolve(context))
                setTextViewText(R.id.widget_anniversary_title, "选择一个时刻")
                setTextViewText(R.id.widget_anniversary_mode, "打开 ControlFree 完成设置")
                setViewVisibility(R.id.widget_anniversary_counter, View.GONE)
                setViewVisibility(R.id.widget_anniversary_reached, View.VISIBLE)
                setTextViewText(R.id.widget_anniversary_reached, "待配置")
                setTextViewText(R.id.widget_anniversary_target, "")
                setOnClickPendingIntent(R.id.widget_anniversary_root, openAppPendingIntent(context, 0))
            }

        private fun itemRemoteViews(context: Context, item: AnniversaryItemEntity): RemoteViews {
            val resolver = AnniversaryOccurrenceResolver(AndroidIcuLunarCalendar())
            val now = Instant.now()
            val spec = item.toSpec(ZoneId.systemDefault())
            val occurrence = resolver.resolve(spec, now)
            val countdown = spec.type == AnniversaryType.COUNTDOWN
            val reached = countdown && !occurrence.instant.isAfter(now) && !occurrence.isRecurring
            return RemoteViews(context.packageName, R.layout.widget_anniversary).apply {
                applyTheme(WidgetThemeResolver.resolve(context))
                setTextViewText(R.id.widget_anniversary_title, item.title)
                setTextViewText(R.id.widget_anniversary_mode, if (countdown) "倒数" else "已坚持")
                setTextViewText(
                    R.id.widget_anniversary_target,
                    occurrence.instant.atZone(spec.zoneId).format(TARGET_FORMATTER)
                )
                setViewVisibility(R.id.widget_anniversary_counter, if (reached) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_anniversary_reached, if (reached) View.VISIBLE else View.GONE)
                if (reached) {
                    setTextViewText(R.id.widget_anniversary_reached, "已到达")
                } else {
                    val chronometer = anniversaryChronometerState(
                        nowEpochMillis = now.toEpochMilli(),
                        occurrenceEpochMillis = occurrence.instant.toEpochMilli(),
                        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        countDown = countdown
                    )
                    setChronometer(
                        R.id.widget_anniversary_counter,
                        chronometer.baseElapsedRealtimeMillis,
                        chronometer.format,
                        true
                    )
                    setChronometerCountDown(
                        R.id.widget_anniversary_counter,
                        chronometer.countDown
                    )
                }
                setOnClickPendingIntent(
                    R.id.widget_anniversary_root,
                    openAppPendingIntent(context, item.id.hashCode())
                )
            }
        }

        private fun RemoteViews.applyTheme(theme: WidgetThemeColors) {
            setInt(R.id.widget_anniversary_background, "setColorFilter", theme.background)
            setTextColor(R.id.widget_anniversary_mode, theme.secondaryText)
            setTextColor(R.id.widget_anniversary_title, theme.primaryText)
            setTextColor(R.id.widget_anniversary_counter, theme.accent)
            setTextColor(R.id.widget_anniversary_reached, theme.accent)
            setTextColor(R.id.widget_anniversary_target, theme.secondaryText)
        }

        private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(AppShortcutRoute.EXTRA_DESTINATION, AppShortcutDestination.TODO.name)
                    putExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET, TodoSubTab.ANNIVERSARY.name)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private val TARGET_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.CHINA)
    }
}

internal data class AnniversaryChronometerState(
    val baseElapsedRealtimeMillis: Long,
    val countDown: Boolean,
    val format: String
)

internal fun anniversaryChronometerState(
    nowEpochMillis: Long,
    occurrenceEpochMillis: Long,
    elapsedRealtimeMillis: Long,
    countDown: Boolean
): AnniversaryChronometerState {
    val distance = if (countDown) {
        (occurrenceEpochMillis - nowEpochMillis).coerceAtLeast(0L)
    } else {
        (nowEpochMillis - occurrenceEpochMillis).coerceAtLeast(0L)
    }
    val base = if (countDown) {
        elapsedRealtimeMillis.saturatingAdd(distance)
    } else {
        elapsedRealtimeMillis.coerceAtLeast(0L) - distance
    }
    return AnniversaryChronometerState(
        baseElapsedRealtimeMillis = base,
        countDown = countDown,
        format = if (countDown) "还有 %s" else "已过 %s"
    )
}

private fun Long.saturatingAdd(other: Long): Long =
    if (other > Long.MAX_VALUE - this) Long.MAX_VALUE else this + other

internal data class WidgetRefreshToken(
    val appWidgetId: Int,
    val generation: Long,
    val lifecycleGeneration: Long
)

/**
 * 将刷新结果的提交与代际校验原子化，避免较慢的旧数据库快照覆盖较新的组件状态。
 * Android 生命周期回调不是挂起函数，因此使用 JVM 监视器保证删除和禁用能立即失效在途刷新。
 */
internal class WidgetRefreshGenerationGate {
    private val monitor = Any()
    private val generations = mutableMapOf<Int, Long>()
    private var lifecycleGeneration = 0L
    private var isEnabled = true

    fun begin(appWidgetId: Int): WidgetRefreshToken = synchronized(monitor) {
        val generation = generations.getOrDefault(appWidgetId, 0L) + 1L
        generations[appWidgetId] = generation
        WidgetRefreshToken(appWidgetId, generation, lifecycleGeneration)
    }

    fun invalidate(appWidgetId: Int) {
        synchronized(monitor) {
            generations[appWidgetId] = generations.getOrDefault(appWidgetId, 0L) + 1L
        }
    }

    fun enable() {
        synchronized(monitor) {
            lifecycleGeneration += 1L
            isEnabled = true
        }
    }

    fun disable() {
        synchronized(monitor) {
            lifecycleGeneration += 1L
            isEnabled = false
        }
    }

    fun commitIfCurrent(token: WidgetRefreshToken, commit: () -> Unit): Boolean =
        synchronized(monitor) {
            val isCurrent = isEnabled &&
                token.lifecycleGeneration == lifecycleGeneration &&
                generations[token.appWidgetId] == token.generation
            if (isCurrent) commit()
            isCurrent
        }
}

/** 已绑定的纪念日被关闭展示时保持空状态，不自动跳到另一条记录，避免用户误以为设置仍生效。 */
internal fun selectAnniversaryWidgetItem(
    items: List<AnniversaryItemEntity>,
    selectedId: String?
): AnniversaryItemEntity? {
    if (selectedId != null) {
        return items.firstOrNull { item -> item.id == selectedId && item.showOnWidget }
    }
    return items.firstOrNull { item -> item.showOnWidget && item.isPinnedTop }
        ?: items.firstOrNull(AnniversaryItemEntity::showOnWidget)
}

class AnniversaryWidgetPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun bind(appWidgetId: Int, anniversaryId: String) {
        preferences.edit().putString(key(appWidgetId), anniversaryId).apply()
    }

    fun getAnniversaryId(appWidgetId: Int): String? = preferences.getString(key(appWidgetId), null)

    fun remove(appWidgetId: Int) {
        preferences.edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int): String = "widget_$appWidgetId"

    private companion object {
        const val PREFERENCES_NAME = "anniversary_widgets"
    }
}
