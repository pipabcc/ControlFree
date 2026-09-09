package com.example.controlfree.ui.todo.anniversary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversarySaveSideEffectsTest {
    @Test
    fun 新建时开启锁屏与桌面组件会请求对应系统能力() {
        val effects = anniversarySaveSideEffects(
            wasWidgetEnabled = false,
            willWidgetBeEnabled = true,
            willLockScreenBeEnabled = true,
            notificationAccess = AnniversaryNotificationAccess.RUNTIME_PERMISSION_REQUIRED
        )

        assertTrue(effects.shouldRequestNotificationPermission)
        assertFalse(effects.shouldOpenNotificationSettings)
        assertTrue(effects.shouldRequestWidgetPin)
    }

    @Test
    fun 已授权且组件原本已开启时不会重复请求() {
        val effects = anniversarySaveSideEffects(
            wasWidgetEnabled = true,
            willWidgetBeEnabled = true,
            willLockScreenBeEnabled = true,
            notificationAccess = AnniversaryNotificationAccess.AVAILABLE
        )

        assertFalse(effects.shouldRequestNotificationPermission)
        assertFalse(effects.shouldOpenNotificationSettings)
        assertFalse(effects.shouldRequestWidgetPin)
    }

    @Test
    fun 未开启系统功能时不请求权限或固定组件() {
        val effects = anniversarySaveSideEffects(
            wasWidgetEnabled = false,
            willWidgetBeEnabled = false,
            willLockScreenBeEnabled = false,
            notificationAccess = AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED
        )

        assertFalse(effects.shouldRequestNotificationPermission)
        assertFalse(effects.shouldOpenNotificationSettings)
        assertFalse(effects.shouldRequestWidgetPin)
    }

    @Test
    fun 系统通知关闭时开启锁屏会转到设置而非请求运行时权限() {
        val effects = anniversarySaveSideEffects(
            wasWidgetEnabled = false,
            willWidgetBeEnabled = false,
            willLockScreenBeEnabled = true,
            notificationAccess = AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED
        )

        assertFalse(effects.shouldRequestNotificationPermission)
        assertTrue(effects.shouldOpenNotificationSettings)
    }

    @Test
    fun 通知能力优先处理缺失的运行时权限() {
        assertEquals(
            AnniversaryNotificationAccess.RUNTIME_PERMISSION_REQUIRED,
            anniversaryNotificationAccess(
                runtimePermissionRequired = true,
                runtimePermissionGranted = false,
                appNotificationsEnabled = false,
                channelEnabled = false
            )
        )
    }

    @Test
    fun 应用通知或纪念日频道关闭时需要进入系统设置() {
        assertEquals(
            AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED,
            anniversaryNotificationAccess(
                runtimePermissionRequired = true,
                runtimePermissionGranted = true,
                appNotificationsEnabled = false,
                channelEnabled = true
            )
        )
        assertEquals(
            AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED,
            anniversaryNotificationAccess(
                runtimePermissionRequired = false,
                runtimePermissionGranted = true,
                appNotificationsEnabled = true,
                channelEnabled = false
            )
        )
    }

    @Test
    fun 权限与系统设置均可用时允许发布通知() {
        assertEquals(
            AnniversaryNotificationAccess.AVAILABLE,
            anniversaryNotificationAccess(
                runtimePermissionRequired = true,
                runtimePermissionGranted = true,
                appNotificationsEnabled = true,
                channelEnabled = true
            )
        )
    }

    @Test
    fun 组件未启用时后续保存仍会请求固定() {
        val retryEffects = anniversarySaveSideEffects(
            wasWidgetEnabled = false,
            willWidgetBeEnabled = true,
            willLockScreenBeEnabled = false,
            notificationAccess = AnniversaryNotificationAccess.AVAILABLE
        )
        assertTrue(retryEffects.shouldRequestWidgetPin)
    }
}
