package com.example.controlfree

import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUiSessionTest {
    @Test
    fun `通话活跃但非拨号器前台时保持锁定`() {
        val activeCall = CallUiSessionReducer.onCallStateChanged(
            CallUiSessionState(),
            isCallActive = true
        ).state

        val update = evaluate(activeCall, observation(BROWSER))

        assertTrue(update.state.isCallActive)
        assertFalse(update.state.isCallUiAllowed)
        assertNull(update.state.allowedPackage)
        assertEquals(CallUiSessionReason.NON_DIALER_FOREGROUND, update.reason)
    }

    @Test
    fun `只在确认拨号器前台后放行通话界面`() {
        val activeCall = CallUiSessionReducer.onCallStateChanged(
            CallUiSessionState(),
            isCallActive = true
        ).state

        val update = evaluate(activeCall, observation(DIALER))

        assertTrue(update.state.isCallUiAllowed)
        assertEquals(DIALER, update.state.allowedPackage)
        assertEquals(CallUiSessionReason.DIALER_CONFIRMED, update.reason)
    }

    @Test
    fun `Home 后旧拨号器缓存不能重新放行`() {
        val allowed = evaluate(
            CallUiSessionState(isCallActive = true),
            observation(DIALER)
        ).state
        val invalidated = CallUiSessionReducer.invalidateNavigation(
            allowed,
            nowWallMillis = NAVIGATION_WALL_MILLIS
        ).state

        val stale = evaluate(invalidated, observation(DIALER))

        assertFalse(stale.state.isCallUiAllowed)
        assertTrue(stale.state.requiresFreshForegroundEvent)
        assertEquals(CallUiSessionReason.WAITING_FOR_DIALER, stale.reason)
    }

    @Test
    fun `Home 后新的拨号器前台事件可以恢复通话界面`() {
        val invalidated = CallUiSessionReducer.invalidateNavigation(
            CallUiSessionState(isCallActive = true, allowedPackage = DIALER),
            nowWallMillis = NAVIGATION_WALL_MILLIS
        ).state
        val freshDialer = observation(
            packageName = DIALER,
            events = listOf(
                ForegroundAppEvent(
                    packageName = DIALER,
                    timestampMillis = NAVIGATION_WALL_MILLIS + 1L,
                    kind = ForegroundEventKind.FOREGROUND
                )
            )
        )

        val update = evaluate(invalidated, freshDialer)

        assertTrue(update.state.isCallUiAllowed)
        assertFalse(update.state.requiresFreshForegroundEvent)
    }

    @Test
    fun `已放行拨号器切到其他应用立即失败关闭`() {
        val allowed = CallUiSessionState(
            isCallActive = true,
            allowedPackage = DIALER
        )

        val update = evaluate(allowed, observation(BROWSER))

        assertFalse(update.state.isCallUiAllowed)
        assertTrue(update.state.requiresFreshForegroundEvent)
        assertEquals(CallUiSessionReason.NON_DIALER_FOREGROUND, update.reason)
    }

    @Test
    fun `查询失败超时熔断和熄屏都清除拨号器放行`() {
        val allowed = CallUiSessionState(
            isCallActive = true,
            allowedPackage = DIALER
        )
        val unavailableUpdates = listOf(
            ForegroundObservationStatus.QUERY_FAILED,
            ForegroundObservationStatus.TIMEOUT,
            ForegroundObservationStatus.CIRCUIT_OPEN
        ).map { status ->
            CallUiSessionReducer.evaluate(
                state = allowed,
                observation = ForegroundObservation.unavailable(status),
                allowedCallPackages = setOf(DIALER),
                isInteractive = true,
                nowWallMillis = NAVIGATION_WALL_MILLIS
            )
        }
        val screenOff = CallUiSessionReducer.evaluate(
            state = allowed,
            observation = observation(DIALER),
            allowedCallPackages = setOf(DIALER),
            isInteractive = false,
            nowWallMillis = NAVIGATION_WALL_MILLIS
        )

        unavailableUpdates.forEach { unavailable ->
            assertFalse(unavailable.state.isCallUiAllowed)
            assertEquals(CallUiSessionReason.FOREGROUND_UNAVAILABLE, unavailable.reason)
        }
        assertFalse(screenOff.state.isCallUiAllowed)
        assertEquals(CallUiSessionReason.SCREEN_NOT_INTERACTIVE, screenOff.reason)
    }

    @Test
    fun `Service 销毁前撤销已确认的拨号器放行`() {
        val allowed = CallUiSessionState(
            isCallActive = true,
            allowedPackage = DIALER
        )

        val invalidated = CallUiSessionReducer.invalidateNavigation(
            allowed,
            nowWallMillis = NAVIGATION_WALL_MILLIS
        )

        assertTrue(invalidated.state.isCallActive)
        assertFalse(invalidated.state.isCallUiAllowed)
        assertTrue(invalidated.state.requiresFreshForegroundEvent)
    }

    private fun evaluate(
        state: CallUiSessionState,
        observation: ForegroundObservation
    ) = CallUiSessionReducer.evaluate(
        state = state,
        observation = observation,
        allowedCallPackages = setOf(DIALER),
        isInteractive = true,
        nowWallMillis = NAVIGATION_WALL_MILLIS
    )

    private fun observation(
        packageName: String?,
        events: List<ForegroundAppEvent> = emptyList()
    ) = ForegroundObservation(
        packageName = packageName,
        transitionTimestampMillis = events.lastOrNull()?.timestampMillis ?: 0L,
        newEvents = events,
        status = ForegroundObservationStatus.AVAILABLE
    )

    private companion object {
        const val DIALER = "example.dialer"
        const val BROWSER = "example.browser"
        const val NAVIGATION_WALL_MILLIS = 1_000_000L
    }
}
