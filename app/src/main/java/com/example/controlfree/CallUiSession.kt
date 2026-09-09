package com.example.controlfree

import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation

/**
 * 通话只豁免已经确认处于前台的默认/系统拨号器。
 *
 * Home、最近任务和屏幕边界会要求一次新的拨号器前台事件，避免旧的
 * UsageEvents 缓存把用户重新放回已经离开的通话界面。
 */
data class CallUiSessionState(
    val isCallActive: Boolean = false,
    val allowedPackage: String? = null,
    val requiresFreshForegroundEvent: Boolean = false,
    val confirmationNotBeforeWallMillis: Long = 0L
) {
    val isCallUiAllowed: Boolean
        get() = isCallActive && allowedPackage != null
}

enum class CallUiSessionReason {
    CALL_INACTIVE,
    WAITING_FOR_DIALER,
    DIALER_CONFIRMED,
    DIALER_STILL_ACTIVE,
    NAVIGATION_INVALIDATED,
    SCREEN_NOT_INTERACTIVE,
    FOREGROUND_UNAVAILABLE,
    NON_DIALER_FOREGROUND
}

data class CallUiSessionUpdate(
    val state: CallUiSessionState,
    val reason: CallUiSessionReason
)

object CallUiSessionReducer {
    fun onCallStateChanged(
        state: CallUiSessionState,
        isCallActive: Boolean
    ): CallUiSessionUpdate {
        if (!isCallActive) {
            return CallUiSessionUpdate(CallUiSessionState(), CallUiSessionReason.CALL_INACTIVE)
        }
        if (state.isCallActive) {
            return CallUiSessionUpdate(
                state,
                if (state.isCallUiAllowed) {
                    CallUiSessionReason.DIALER_STILL_ACTIVE
                } else {
                    CallUiSessionReason.WAITING_FOR_DIALER
                }
            )
        }
        return CallUiSessionUpdate(
            CallUiSessionState(isCallActive = true),
            CallUiSessionReason.WAITING_FOR_DIALER
        )
    }

    fun invalidateNavigation(
        state: CallUiSessionState,
        nowWallMillis: Long
    ): CallUiSessionUpdate {
        if (!state.isCallActive) {
            return CallUiSessionUpdate(CallUiSessionState(), CallUiSessionReason.CALL_INACTIVE)
        }
        return CallUiSessionUpdate(
            state.copy(
                allowedPackage = null,
                requiresFreshForegroundEvent = true,
                confirmationNotBeforeWallMillis = maxOf(
                    state.confirmationNotBeforeWallMillis,
                    nowWallMillis.coerceAtLeast(0L)
                )
            ),
            CallUiSessionReason.NAVIGATION_INVALIDATED
        )
    }

    fun evaluate(
        state: CallUiSessionState,
        observation: ForegroundObservation,
        allowedCallPackages: Set<String>,
        isInteractive: Boolean,
        nowWallMillis: Long
    ): CallUiSessionUpdate {
        if (!state.isCallActive) {
            return CallUiSessionUpdate(CallUiSessionState(), CallUiSessionReason.CALL_INACTIVE)
        }
        if (!isInteractive) {
            return invalidateNavigation(state, nowWallMillis).copy(
                reason = CallUiSessionReason.SCREEN_NOT_INTERACTIVE
            )
        }
        if (!observation.isAvailable) {
            return invalidateNavigation(state, nowWallMillis).copy(
                reason = CallUiSessionReason.FOREGROUND_UNAVAILABLE
            )
        }

        val foregroundPackage = observation.packageName
        if (foregroundPackage == null || foregroundPackage !in allowedCallPackages) {
            val nextState = if (state.isCallUiAllowed) {
                invalidateNavigation(state, nowWallMillis).state
            } else {
                state.copy(allowedPackage = null)
            }
            return CallUiSessionUpdate(nextState, CallUiSessionReason.NON_DIALER_FOREGROUND)
        }

        if (state.requiresFreshForegroundEvent) {
            val hasFreshConfirmation = observation.newEvents.any { event ->
                event.kind == ForegroundEventKind.FOREGROUND &&
                    event.packageName == foregroundPackage &&
                    event.timestampMillis >= state.confirmationNotBeforeWallMillis
            }
            if (!hasFreshConfirmation) {
                return CallUiSessionUpdate(
                    state.copy(allowedPackage = null),
                    CallUiSessionReason.WAITING_FOR_DIALER
                )
            }
        }

        return CallUiSessionUpdate(
            state.copy(
                allowedPackage = foregroundPackage,
                requiresFreshForegroundEvent = false,
                confirmationNotBeforeWallMillis = 0L
            ),
            if (state.allowedPackage == foregroundPackage) {
                CallUiSessionReason.DIALER_STILL_ACTIVE
            } else {
                CallUiSessionReason.DIALER_CONFIRMED
            }
        )
    }
}
