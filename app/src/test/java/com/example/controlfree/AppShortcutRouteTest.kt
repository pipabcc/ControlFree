package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutRouteTest {
    @Test
    fun `四个公开动作只映射到对应页面`() {
        assertEquals(
            AppShortcutDestination.FOCUS,
            AppShortcutRoute.destinationForAction(AppShortcutRoute.ACTION_FOCUS)
        )
        assertEquals(
            AppShortcutDestination.MONITOR,
            AppShortcutRoute.destinationForAction(AppShortcutRoute.ACTION_MONITOR)
        )
        assertEquals(
            AppShortcutDestination.STATISTICS,
            AppShortcutRoute.destinationForAction(AppShortcutRoute.ACTION_STATISTICS)
        )
        assertEquals(
            AppShortcutDestination.SETTINGS,
            AppShortcutRoute.destinationForAction(AppShortcutRoute.ACTION_SETTINGS)
        )
    }

    @Test
    fun `未知空白和大小写不一致的动作均被拒绝`() {
        assertNull(AppShortcutRoute.destinationForAction(null))
        assertNull(AppShortcutRoute.destinationForAction(""))
        assertNull(AppShortcutRoute.destinationForAction("com.example.controlfree.shortcut.focus"))
        assertNull(AppShortcutRoute.destinationForAction("android.intent.action.VIEW"))
    }

    @Test
    fun `主界面只接受枚举的精确序列化值`() {
        AppShortcutDestination.entries.forEach { destination ->
            assertEquals(
                destination,
                AppShortcutRoute.destinationForStoredValue(destination.name)
            )
        }
        assertNull(AppShortcutRoute.destinationForStoredValue(null))
        assertNull(AppShortcutRoute.destinationForStoredValue("focus"))
        assertNull(AppShortcutRoute.destinationForStoredValue("UNKNOWN"))
    }

    @Test
    fun `重复点击同一快捷方式仍产生新的导航请求`() {
        val first = AppShortcutRoute.nextRequest(null, AppShortcutDestination.FOCUS.name)
        val second = AppShortcutRoute.nextRequest(first, AppShortcutDestination.FOCUS.name)

        assertEquals(AppShortcutDestination.FOCUS, first?.destination)
        assertEquals(AppShortcutDestination.FOCUS, second?.destination)
        assertTrue(second!!.revision > first!!.revision)
    }

    @Test
    fun `非法目的值不会改变已有导航请求`() {
        val existing = AppShortcutNavigationRequest(AppShortcutDestination.MONITOR, revision = 8L)

        assertNull(AppShortcutRoute.nextRequest(existing, "DELETE_ALL_DATA"))
    }

    @Test
    fun `版本号溢出时安全回到正数`() {
        val previous = AppShortcutNavigationRequest(
            destination = AppShortcutDestination.SETTINGS,
            revision = Long.MAX_VALUE
        )

        assertEquals(
            AppShortcutNavigationRequest(AppShortcutDestination.SETTINGS, revision = 1L),
            AppShortcutRoute.nextRequest(previous, AppShortcutDestination.SETTINGS.name)
        )
    }
}
