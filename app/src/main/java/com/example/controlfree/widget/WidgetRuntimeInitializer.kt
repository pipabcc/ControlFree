package com.example.controlfree.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import androidx.room.InvalidationTracker
import com.example.controlfree.supervision.persistence.ControlFreeDatabase

/**
 * 默认进程启动时注册 Room 与主题偏好监听，让桌面内容在应用内写入后立即刷新。
 * Provider 不导出且不在直启进程中运行，不会扩大数据库或组件的外部访问面。
 */
class WidgetRuntimeInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(WidgetRuntimeObserver::install)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

private object WidgetRuntimeObserver {
    private val monitor = Any()
    private var installed = false
    private var themePreferences: SharedPreferences? = null
    private var themeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(context: Context) {
        synchronized(monitor) {
            if (installed) return
            val appContext = context.applicationContext
            val database = ControlFreeDatabase.getInstance(appContext)
            database.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer(OBSERVED_TABLES.toTypedArray()) {
                    override fun onInvalidated(tables: Set<String>) {
                        WidgetRefreshCoordinator.refreshForTables(appContext, tables)
                    }
                }
            )
            val preferences = appContext.getSharedPreferences(
                CONTROL_FREE_PREFERENCES,
                Context.MODE_PRIVATE
            )
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in THEME_KEYS) WidgetRefreshCoordinator.refreshAll(appContext)
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            themePreferences = preferences
            themeListener = listener
            installed = true
        }
    }

    private val OBSERVED_TABLES = setOf(
        "supervision_plans",
        "supervision_time_ranges",
        "global_supervision_policies",
        "app_supervision_policies",
        "app_supervision_disabled_time_ranges",
        "plan_activation_reservations",
        "todo_items",
        "quick_notes",
        "anniversary_items"
    )
    private val THEME_KEYS = setOf(
        "dark_theme_enabled",
        "KEY_BACKGROUND_TYPE",
        "KEY_BACKGROUND_COLOR",
        "KEY_BACKGROUND_GRADIENT",
        "KEY_BACKGROUND_IMAGE_INDEX"
    )
    private const val CONTROL_FREE_PREFERENCES = "control_free_prefs"
}
