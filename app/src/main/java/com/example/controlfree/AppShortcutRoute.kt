package com.example.controlfree

/**
 * 桌面快捷方式只产生页面导航请求，不承载任何启动或停止监督服务的命令。
 */
internal enum class AppShortcutDestination {
    FOCUS,
    MONITOR,
    TODO,
    STATISTICS,
    SETTINGS
}

internal data class AppShortcutNavigationRequest(
    val destination: AppShortcutDestination,
    val revision: Long
)

internal object AppShortcutRoute {
    const val ACTION_FOCUS = "com.example.controlfree.shortcut.FOCUS"
    const val ACTION_MONITOR = "com.example.controlfree.shortcut.MONITOR"
    const val ACTION_STATISTICS = "com.example.controlfree.shortcut.STATISTICS"
    const val ACTION_SETTINGS = "com.example.controlfree.shortcut.SETTINGS"
    const val EXTRA_DESTINATION = "com.example.controlfree.extra.SHORTCUT_DESTINATION"

    fun destinationForAction(action: String?): AppShortcutDestination? = when (action) {
        ACTION_FOCUS -> AppShortcutDestination.FOCUS
        ACTION_MONITOR -> AppShortcutDestination.MONITOR
        ACTION_STATISTICS -> AppShortcutDestination.STATISTICS
        ACTION_SETTINGS -> AppShortcutDestination.SETTINGS
        else -> null
    }

    fun destinationForStoredValue(value: String?): AppShortcutDestination? =
        AppShortcutDestination.entries.firstOrNull { destination -> destination.name == value }

    /**
     * 每次合法请求都增加版本号，即使目的页面未变化，也能让页面消费一次新的导航事件。
     */
    fun nextRequest(
        previous: AppShortcutNavigationRequest?,
        destinationValue: String?
    ): AppShortcutNavigationRequest? {
        val destination = destinationForStoredValue(destinationValue) ?: return null
        val nextRevision = when (previous?.revision) {
            null, Long.MAX_VALUE -> 1L
            else -> previous.revision + 1L
        }
        return AppShortcutNavigationRequest(destination, nextRevision)
    }
}
