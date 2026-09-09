package com.example.controlfree

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRestOverlayControllerTest {
    @Test
    fun `休息倒计时向上取整避免提前显示零秒`() {
        assertEquals(0, millisToDisplaySeconds(0L))
        assertEquals(1, millisToDisplaySeconds(1L))
        assertEquals(1, millisToDisplaySeconds(1_000L))
        assertEquals(2, millisToDisplaySeconds(1_001L))
    }

    @Test
    fun `倒计时按小时分钟秒显示且小时不按天折叠`() {
        assertEquals("0小时0分钟0秒", formatAppRestDuration(0))
        assertEquals("0小时1分钟1秒", formatAppRestDuration(61))
        assertEquals("18小时11分钟17秒", formatAppRestDuration(65_477))
        assertEquals("25小时0分钟0秒", formatAppRestDuration(90_000))
    }

    @Test
    fun `负倒计时按零处理`() {
        assertEquals("0小时0分钟0秒", formatAppRestDuration(-1))
    }

    @Test
    fun `监督悬浮窗不抢占系统按键焦点`() {
        assertTrue(
            appRestOverlayWindowFlags() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        )
    }

    @Test
    fun `挂载中窗口可以由重建后的服务实例接管且不会创建第二层`() {
        val coordinator = AppRestOverlayOwnershipCoordinator<Any, Any>()
        val oldOwner = Any()
        val newOwner = Any()
        val window = Any()

        assertFalse(coordinator.hasRegisteredWindow())
        assertTrue(coordinator.reserve(oldOwner, window))
        assertTrue(coordinator.hasRegisteredWindow())

        val claim = coordinator.claim(newOwner)

        assertTrue(claim is AppRestOverlayWindowClaim.Existing)
        assertSame(window, (claim as AppRestOverlayWindowClaim.Existing).window)
        assertSame(window, coordinator.ownedWindow(newOwner))
        assertFalse(coordinator.reserve(newOwner, Any()))
        assertTrue(coordinator.markActive(window))
    }

    @Test
    fun `移除和收敛期间禁止重建服务创建第二层`() {
        val coordinator = AppRestOverlayOwnershipCoordinator<Any, Any>()
        val oldOwner = Any()
        val newOwner = Any()
        val window = Any()

        assertTrue(coordinator.reserve(oldOwner, window))
        assertTrue(coordinator.markActive(window))
        assertTrue(coordinator.beginRemoval(oldOwner, window))
        assertEquals(
            AppRestOverlayWindowClaim.WaitingForRelease,
            coordinator.claim(newOwner)
        )
        assertFalse(coordinator.reserve(newOwner, Any()))

        assertTrue(coordinator.markSettling(window))
        assertEquals(
            AppRestOverlayWindowClaim.WaitingForRelease,
            coordinator.claim(newOwner)
        )
        assertFalse(coordinator.reserve(newOwner, Any()))

        assertTrue(coordinator.release(window))
        assertFalse(coordinator.hasRegisteredWindow())
        assertEquals(AppRestOverlayWindowClaim.Available, coordinator.claim(newOwner))
        assertTrue(coordinator.reserve(newOwner, Any()))
    }

    @Test
    fun `收敛期窗口意外挂回后可恢复移除且普通强制移除不会打断收敛`() {
        val coordinator = AppRestOverlayOwnershipCoordinator<Any, Any>()
        val owner = Any()
        val window = Any()

        assertTrue(coordinator.reserve(owner, window))
        assertTrue(coordinator.markActive(window))
        assertTrue(coordinator.beginRemoval(owner, window))
        assertTrue(coordinator.markSettling(window))

        assertFalse(coordinator.forceBeginRemoval(window))
        assertEquals(AppRestOverlayWindowPhase.SETTLING, coordinator.phaseOf(window))
        assertTrue(coordinator.resumeRemovalAfterReattach(window))
        assertEquals(AppRestOverlayWindowPhase.REMOVING, coordinator.phaseOf(window))
        assertFalse(coordinator.resumeRemovalAfterReattach(window))
    }

    @Test
    fun `不同目标应用不能接管现有窗口`() {
        val coordinator = AppRestOverlayOwnershipCoordinator<String, Any>()
        val oldOwner = Any()
        val newOwner = Any()

        assertTrue(coordinator.reserve(oldOwner, "地图窗口"))
        assertTrue(coordinator.markActive("地图窗口"))

        assertEquals(
            AppRestOverlayWindowClaim.Conflicting("地图窗口"),
            coordinator.claim(newOwner) { window -> window == "视频窗口" }
        )
        assertSame("地图窗口", coordinator.ownedWindow(oldOwner))
        assertFalse(coordinator.reserve(newOwner, "视频窗口"))
    }
}
