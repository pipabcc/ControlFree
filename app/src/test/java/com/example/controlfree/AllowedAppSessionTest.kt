package com.example.controlfree

import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedAppSessionTest {
    @Test
    fun `启动宽限覆盖启动回调返回前的竞态窗口`() {
        val overlayLaunching = beginLaunch()
        val activityLaunching = AllowedAppSessionReducer.beginLaunch(
            packageName = TARGET,
            nowElapsedMillis = LAUNCH_ELAPSED_MILLIS,
            nowWallMillis = LAUNCH_WALL_MILLIS,
            allowPreConfirmationUiHandoff = true
        )

        assertTrue(
            AllowedAppSessionReducer.isLaunchGraceActive(
                activityLaunching,
                LAUNCH_ELAPSED_MILLIS + 100L
            )
        )
        assertFalse(
            AllowedAppSessionReducer.isLaunchGraceActive(
                overlayLaunching,
                LAUNCH_ELAPSED_MILLIS + 100L
            )
        )
        assertTrue(
            AllowedAppSessionReducer.isLaunchGraceActive(
                successfulLaunch(),
                LAUNCH_ELAPSED_MILLIS + 100L
            )
        )
        assertFalse(
            AllowedAppSessionReducer.isLaunchGraceActive(
                activityLaunching,
                LAUNCH_ELAPSED_MILLIS +
                    AllowedAppSessionReducer.DEFAULT_LAUNCH_TIMEOUT_MILLIS
            )
        )
        assertFalse(
            AllowedAppSessionReducer.isLaunchGraceActive(
                activeState(),
                LAUNCH_ELAPSED_MILLIS + 100L
            )
        )
    }

    @Test
    fun `旧缓存恰好是目标包也不能确认新启动`() {
        val launching = beginLaunch()
        val update = evaluate(
            state = launching,
            observation = observation(packageName = TARGET)
        )

        assertEquals(AllowedAppSessionPhase.LAUNCHING, update.state.phase)
        assertFalse(update.shouldAllowTarget)
        assertEquals(AllowedAppSessionReason.WAITING_FOR_TARGET, update.reason)
    }

    @Test
    fun `overlap 中早于启动请求的目标事件不能确认会话`() {
        val launching = beginLaunch()
        val update = evaluate(
            state = launching,
            observation = observation(
                packageName = TARGET,
                newEvents = listOf(foreground(TARGET, LAUNCH_WALL_MILLIS - 1L))
            )
        )

        assertEquals(AllowedAppSessionPhase.LAUNCHING, update.state.phase)
        assertFalse(update.shouldAllowTarget)
    }

    @Test
    fun `启动后的目标前台事件确认精确目标包`() {
        val launching = successfulLaunch()
        val update = evaluate(
            state = launching,
            observation = observation(
                packageName = TARGET,
                newEvents = listOf(foreground(TARGET, LAUNCH_WALL_MILLIS + 10L))
            )
        )

        assertEquals(AllowedAppSessionPhase.ACTIVE, update.state.phase)
        assertEquals(TARGET, update.allowedPackage)
        assertEquals(AllowedAppSessionReason.TARGET_CONFIRMED, update.reason)
    }

    @Test
    fun `目标已在锁层下方时启动成功无需新前台事件也能确认`() {
        val update = evaluate(
            state = successfulLaunch(),
            observation = observation(packageName = TARGET)
        )

        assertEquals(AllowedAppSessionPhase.ACTIVE, update.state.phase)
        assertEquals(TARGET, update.allowedPackage)
        assertEquals(AllowedAppSessionReason.TARGET_CONFIRMED, update.reason)
    }

    @Test
    fun `另一个白名单包不能借用当前目标会话`() {
        val active = activeState()
        val update = evaluate(
            state = active,
            observation = observation(
                packageName = OTHER_ALLOWED,
                newEvents = listOf(foreground(OTHER_ALLOWED, LAUNCH_WALL_MILLIS + 100L))
            )
        )

        assertEquals(AllowedAppSessionPhase.LOCKED, update.state.phase)
        assertEquals(AllowedAppSessionReason.TARGET_LEFT_FOREGROUND, update.reason)
    }

    @Test
    fun `目标使用期间切回桌面立即关闭会话`() {
        val update = evaluate(
            state = activeState(),
            observation = observation(
                packageName = "example.home",
                newEvents = listOf(foreground("example.home", LAUNCH_WALL_MILLIS + 100L))
            )
        )

        assertEquals(AllowedAppSessionPhase.LOCKED, update.state.phase)
        assertEquals(AllowedAppSessionReason.TARGET_LEFT_FOREGROUND, update.reason)
    }

    @Test
    fun `目标后台且没有后继前台包时立即回锁`() {
        val update = evaluate(
            state = activeState(),
            observation = observation(
                packageName = null,
                newEvents = listOf(background(TARGET, LAUNCH_WALL_MILLIS + 100L))
            )
        )

        assertEquals(AllowedAppSessionPhase.LOCKED, update.state.phase)
        assertEquals(AllowedAppSessionReason.TARGET_LEFT_FOREGROUND, update.reason)
    }

    @Test
    fun `目标刚启动又切回桌面时不进入放行状态`() {
        val update = evaluate(
            state = successfulLaunch(),
            observation = observation(
                packageName = "example.home",
                newEvents = listOf(
                    foreground(TARGET, LAUNCH_WALL_MILLIS + 10L),
                    foreground("example.home", LAUNCH_WALL_MILLIS + 20L)
                )
            )
        )

        assertEquals(AllowedAppSessionPhase.LOCKED, update.state.phase)
        assertEquals(AllowedAppSessionReason.TARGET_LEFT_FOREGROUND, update.reason)
    }

    @Test
    fun `目标持续前台且没有新事件时保持会话`() {
        val update = evaluate(
            state = activeState(),
            observation = observation(packageName = TARGET)
        )

        assertTrue(update.shouldAllowTarget)
        assertEquals(AllowedAppSessionReason.TARGET_ACTIVE, update.reason)
    }

    @Test
    fun `启动超时后失败关闭`() {
        val update = AllowedAppSessionReducer.evaluate(
            state = beginLaunch(),
            observation = observation(packageName = "com.example.controlfree"),
            nowElapsedMillis = LAUNCH_ELAPSED_MILLIS + 3_000L,
            isTargetAllowed = true,
            isInteractive = true,
            launchTimeoutMillis = 3_000L
        )

        assertEquals(AllowedAppSessionPhase.LOCKED, update.state.phase)
        assertEquals(AllowedAppSessionReason.LAUNCH_TIMEOUT, update.reason)
    }

    @Test
    fun `启动期间前台查询短暂失败仍等待目标直到启动超时`() {
        listOf(
            ForegroundObservationStatus.QUERY_FAILED,
            ForegroundObservationStatus.TIMEOUT,
            ForegroundObservationStatus.CIRCUIT_OPEN
        ).forEach { status ->
            val update = evaluate(
                successfulLaunch(),
                ForegroundObservation.unavailable(status)
            )

            assertEquals(AllowedAppSessionPhase.LAUNCHING, update.state.phase)
            assertEquals(AllowedAppSessionReason.WAITING_FOR_TARGET, update.reason)
            assertFalse(update.shouldAllowTarget)
        }
    }

    @Test
    fun `活动期间前台查询失败立即关闭`() {
        val update = evaluate(
            activeState(),
            ForegroundObservation.unavailable(ForegroundObservationStatus.TIMEOUT)
        )

        assertEquals(AllowedAppSessionPhase.LOCKED, update.state.phase)
        assertEquals(AllowedAppSessionReason.FOREGROUND_UNAVAILABLE, update.reason)
    }

    @Test
    fun `熄屏或目标移出白名单时清除会话`() {
        val screenOff = AllowedAppSessionReducer.evaluate(
            state = activeState(),
            observation = observation(packageName = TARGET),
            nowElapsedMillis = LAUNCH_ELAPSED_MILLIS + 100L,
            isTargetAllowed = true,
            isInteractive = false
        )
        val removed = AllowedAppSessionReducer.evaluate(
            state = activeState(),
            observation = observation(packageName = TARGET),
            nowElapsedMillis = LAUNCH_ELAPSED_MILLIS + 100L,
            isTargetAllowed = false,
            isInteractive = true
        )

        assertEquals(AllowedAppSessionReason.SCREEN_NOT_INTERACTIVE, screenOff.reason)
        assertEquals(AllowedAppSessionReason.TARGET_NO_LONGER_ALLOWED, removed.reason)
        assertFalse(screenOff.shouldAllowTarget)
        assertFalse(removed.shouldAllowTarget)
    }

    private fun beginLaunch() = AllowedAppSessionReducer.beginLaunch(
        packageName = TARGET,
        nowElapsedMillis = LAUNCH_ELAPSED_MILLIS,
        nowWallMillis = LAUNCH_WALL_MILLIS
    )

    private fun successfulLaunch() =
        AllowedAppSessionReducer.markLaunchSucceeded(
            state = beginLaunch(),
            nowElapsedMillis = LAUNCH_ELAPSED_MILLIS + 50L
        )

    private fun activeState() = beginLaunch().copy(
        phase = AllowedAppSessionPhase.ACTIVE,
        activatedEventTimestampMillis = LAUNCH_WALL_MILLIS + 10L
    )

    private fun evaluate(
        state: AllowedAppSessionState,
        observation: ForegroundObservation
    ) = AllowedAppSessionReducer.evaluate(
        state = state,
        observation = observation,
        nowElapsedMillis = LAUNCH_ELAPSED_MILLIS + 100L,
        isTargetAllowed = true,
        isInteractive = true
    )

    private fun observation(
        packageName: String?,
        newEvents: List<ForegroundAppEvent> = emptyList()
    ) = ForegroundObservation(
        packageName = packageName,
        transitionTimestampMillis = newEvents.lastOrNull()?.timestampMillis ?: 0L,
        newEvents = newEvents,
        status = ForegroundObservationStatus.AVAILABLE
    )

    private fun foreground(packageName: String, timestampMillis: Long) = ForegroundAppEvent(
        packageName,
        timestampMillis,
        ForegroundEventKind.FOREGROUND
    )

    private fun background(packageName: String, timestampMillis: Long) = ForegroundAppEvent(
        packageName,
        timestampMillis,
        ForegroundEventKind.BACKGROUND
    )

    companion object {
        private const val TARGET = "example.allowed"
        private const val OTHER_ALLOWED = "example.other.allowed"
        private const val LAUNCH_ELAPSED_MILLIS = 10_000L
        private const val LAUNCH_WALL_MILLIS = 1_000_000L
    }
}
