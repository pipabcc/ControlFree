package com.example.controlfree.ui.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRefreshCoordinatorTest {
    @Test
    fun `首次加载期间的刷新请求合并为一次补刷`() {
        val coordinator = UsageRefreshCoordinator(initialLoadActive = true)

        assertFalse(coordinator.requestRefresh())
        assertFalse(coordinator.requestRefresh())
        assertTrue(coordinator.finishLoadAndShouldRefresh())
        assertFalse(coordinator.finishLoadAndShouldRefresh())
    }

    @Test
    fun `空闲时刷新立即启动且运行中不并发`() {
        val coordinator = UsageRefreshCoordinator()

        assertTrue(coordinator.requestRefresh())
        assertFalse(coordinator.requestRefresh())
        assertTrue(coordinator.finishLoadAndShouldRefresh())
        assertFalse(coordinator.finishLoadAndShouldRefresh())
        assertTrue(coordinator.requestRefresh())
    }

    @Test
    fun `页面退出会清除当前加载和待刷新状态`() {
        val coordinator = UsageRefreshCoordinator(initialLoadActive = true)
        assertFalse(coordinator.requestRefresh())

        coordinator.cancelLoad()

        assertTrue(coordinator.requestRefresh())
        assertFalse(coordinator.finishLoadAndShouldRefresh())
    }
}
