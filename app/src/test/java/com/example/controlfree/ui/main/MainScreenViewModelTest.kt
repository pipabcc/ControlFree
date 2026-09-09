package com.example.controlfree.ui.main

import com.example.controlfree.MonitorSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenViewModelTest {
    @Test
    fun `初始化前使用安全默认状态`() {
        val viewModel = MainScreenViewModel()

        assertEquals(30, viewModel.usageTimeState.value)
        assertEquals(5, viewModel.lockTimeState.value)
        assertEquals(5, viewModel.focusLockTimeState.value)
        assertEquals(1, viewModel.focusPlayTimeState.value)
        assertFalse(viewModel.isRunningState.value)
        assertFalse(viewModel.isInitializedState.value)
    }

    @Test
    fun `启动请求只能消费一次且拒绝重复请求`() {
        val viewModel = MainScreenViewModel()

        assertTrue(viewModel.requestMonitorStart(31, 5))
        val attemptId = viewModel.startAttemptIdState.value
        assertFalse(viewModel.requestMonitorStart(32, 6))
        assertEquals(
            PendingMonitorStart(requireNotNull(attemptId), MonitorSessionMode.SUPERVISION),
            viewModel.consumePendingStart()
        )
        assertNull(viewModel.consumePendingStart())
        assertTrue(viewModel.isStartingState.value)
    }

    @Test
    fun `专注启动保存独立参数并携带专注模式`() {
        val viewModel = MainScreenViewModel()

        assertTrue(viewModel.requestFocusStart(lock = 15, play = 3))

        assertEquals(15, viewModel.focusLockTimeState.value)
        assertEquals(3, viewModel.focusPlayTimeState.value)
        assertEquals(
            PendingMonitorStart(
                attemptId = requireNotNull(viewModel.startAttemptIdState.value),
                sessionMode = MonitorSessionMode.FOCUS
            ),
            viewModel.consumePendingStart()
        )
    }

    @Test
    fun `陈旧启动失败不会取消当前请求`() {
        val viewModel = MainScreenViewModel()
        assertTrue(viewModel.requestMonitorStart(30, 5))
        val attemptId = requireNotNull(viewModel.startAttemptIdState.value)

        assertFalse(viewModel.acceptMonitorStartFailure(attemptId + 1L))
        assertTrue(viewModel.isStartingState.value)
        assertTrue(viewModel.acceptMonitorStartFailure(attemptId))
        assertFalse(viewModel.isStartingState.value)
    }
}
