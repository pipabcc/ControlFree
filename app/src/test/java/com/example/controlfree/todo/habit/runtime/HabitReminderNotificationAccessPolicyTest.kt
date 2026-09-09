package com.example.controlfree.todo.habit.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitReminderNotificationAccessPolicyTest {
    @Test
    fun `API 25 不读取通知频道也不因频道状态拒绝`() {
        assertTrue(
            isHabitReminderNotificationAccessAvailable(
                notificationsEnabled = true,
                runtimePermissionRequired = false,
                runtimePermissionGranted = true,
                notificationChannelsSupported = false,
                channelDisabled = true
            )
        )
    }

    @Test
    fun `API 26 频道被用户关闭时不可用`() {
        assertFalse(
            isHabitReminderNotificationAccessAvailable(
                notificationsEnabled = true,
                runtimePermissionRequired = false,
                runtimePermissionGranted = true,
                notificationChannelsSupported = true,
                channelDisabled = true
            )
        )
    }

    @Test
    fun `Android 13 未授予通知权限时不可用`() {
        assertFalse(
            isHabitReminderNotificationAccessAvailable(
                notificationsEnabled = true,
                runtimePermissionRequired = true,
                runtimePermissionGranted = false,
                notificationChannelsSupported = true,
                channelDisabled = false
            )
        )
    }

    @Test
    fun `系统通知总开关关闭时不可用`() {
        assertFalse(
            isHabitReminderNotificationAccessAvailable(
                notificationsEnabled = false,
                runtimePermissionRequired = false,
                runtimePermissionGranted = true,
                notificationChannelsSupported = false,
                channelDisabled = false
            )
        )
    }
}
