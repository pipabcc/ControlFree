package com.example.controlfree.ui.todo.components

import android.content.Context

enum class CardActionHintTab(
    internal val storageKey: String,
    val itemLabel: String
) {
    TODO("todo", "待办卡片"),
    QUICK_NOTE("quick_note", "闪记卡片"),
    LEDGER("ledger", "账目卡片"),
    ANNIVERSARY("anniversary", "时刻卡片")
}

/** 跨进程重建持久化“空列表首次创建后”的一次性卡片操作提示。 */
class CardActionHintStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun armForCreation(
        tab: CardActionHintTab,
        isDataLoaded: Boolean,
        wasEmpty: Boolean,
        isNewItem: Boolean = true
    ) {
        if (
            !isDataLoaded ||
            !wasEmpty ||
            !isNewItem ||
            preferences.getBoolean(tab.shownKey, false)
        ) {
            return
        }
        preferences.edit().putBoolean(tab.pendingKey, true).apply()
    }

    /**
     * 只有数据流确认首张卡片已经出现后才允许展示；此处不消费状态，避免页面在
     * Snackbar 真正出现前销毁时永久丢失提示。
     */
    @Synchronized
    fun shouldShowWhenReady(tab: CardActionHintTab, hasItems: Boolean): Boolean {
        if (!hasItems || preferences.getBoolean(tab.shownKey, false)) return false
        return preferences.getBoolean(tab.pendingKey, false)
    }

    @Synchronized
    fun markShown(tab: CardActionHintTab): Boolean {
        if (!preferences.getBoolean(tab.pendingKey, false)) return false
        return preferences.edit()
            .putBoolean(tab.shownKey, true)
            .remove(tab.pendingKey)
            .commit()
    }

    private val CardActionHintTab.shownKey: String
        get() = "${storageKey}_shown"

    private val CardActionHintTab.pendingKey: String
        get() = "${storageKey}_pending"

    private companion object {
        const val PREFERENCES_NAME = "todo_swipe_action_hints"
    }
}

fun CardActionHintTab.message(): String = "点击$itemLabel，可编辑或删除"
