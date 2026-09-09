package com.example.controlfree.todo.habit.runtime

/**
 * 计算习惯提醒是否具备显示条件。Android API 查询留在调用方，便于纯 JVM 测试边界行为。
 */
internal fun isHabitReminderNotificationAccessAvailable(
    notificationsEnabled: Boolean,
    runtimePermissionRequired: Boolean,
    runtimePermissionGranted: Boolean,
    notificationChannelsSupported: Boolean,
    channelDisabled: Boolean
): Boolean {
    if (!notificationsEnabled) return false
    if (runtimePermissionRequired && !runtimePermissionGranted) return false
    return !notificationChannelsSupported || !channelDisabled
}
