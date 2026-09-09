package com.example.controlfree

import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation

enum class AllowedAppSessionPhase {
    LOCKED,
    LAUNCHING,
    ACTIVE
}

enum class AllowedAppSessionReason {
    LOCKED,
    WAITING_FOR_TARGET,
    TARGET_CONFIRMED,
    TARGET_ACTIVE,
    LAUNCH_FAILED,
    LAUNCH_TIMEOUT,
    TARGET_LEFT_FOREGROUND,
    TARGET_NO_LONGER_ALLOWED,
    SCREEN_NOT_INTERACTIVE,
    FOREGROUND_UNAVAILABLE
}

data class AllowedAppSessionState(
    val phase: AllowedAppSessionPhase = AllowedAppSessionPhase.LOCKED,
    val targetPackage: String? = null,
    val launchRequestedElapsedMillis: Long = 0L,
    val launchRequestedWallMillis: Long = 0L,
    val allowPreConfirmationUiHandoff: Boolean = false,
    val launchSucceeded: Boolean = false,
    val activatedEventTimestampMillis: Long = 0L
) {
    val isLocked: Boolean
        get() = phase == AllowedAppSessionPhase.LOCKED
}

data class AllowedAppSessionUpdate(
    val state: AllowedAppSessionState,
    val allowedPackage: String?,
    val reason: AllowedAppSessionReason
) {
    val shouldAllowTarget: Boolean
        get() = allowedPackage != null && state.phase == AllowedAppSessionPhase.ACTIVE
}

/**
 * 只临时放行用户从锁屏选择的精确目标包。
 * 会话只保存在内存中，服务重建时默认恢复锁定。
 */
object AllowedAppSessionReducer {
    fun isLaunchGraceActive(
        state: AllowedAppSessionState,
        nowElapsedMillis: Long,
        launchTimeoutMillis: Long = DEFAULT_LAUNCH_TIMEOUT_MILLIS
    ): Boolean =
        state.phase == AllowedAppSessionPhase.LAUNCHING &&
            (state.launchSucceeded || state.allowPreConfirmationUiHandoff) &&
            nowElapsedMillis - state.launchRequestedElapsedMillis <
            launchTimeoutMillis.coerceAtLeast(1L)

    fun beginLaunch(
        packageName: String,
        nowElapsedMillis: Long,
        nowWallMillis: Long,
        allowPreConfirmationUiHandoff: Boolean = false
    ): AllowedAppSessionState {
        require(packageName.isNotBlank()) { "Allowed app package must not be blank" }
        return AllowedAppSessionState(
            phase = AllowedAppSessionPhase.LAUNCHING,
            targetPackage = packageName,
            launchRequestedElapsedMillis = nowElapsedMillis,
            launchRequestedWallMillis = nowWallMillis,
            allowPreConfirmationUiHandoff = allowPreConfirmationUiHandoff
        )
    }

    fun launchFailed() = AllowedAppSessionUpdate(
        state = AllowedAppSessionState(),
        allowedPackage = null,
        reason = AllowedAppSessionReason.LAUNCH_FAILED
    )

    fun markLaunchSucceeded(
        state: AllowedAppSessionState,
        nowElapsedMillis: Long
    ): AllowedAppSessionState {
        require(state.phase == AllowedAppSessionPhase.LAUNCHING) {
            "Only a launching session can be marked successful"
        }
        require(nowElapsedMillis >= state.launchRequestedElapsedMillis) {
            "Launch success cannot precede the request"
        }
        return state.copy(launchSucceeded = true)
    }

    fun evaluate(
        state: AllowedAppSessionState,
        observation: ForegroundObservation,
        nowElapsedMillis: Long,
        isTargetAllowed: Boolean,
        isInteractive: Boolean,
        launchTimeoutMillis: Long = DEFAULT_LAUNCH_TIMEOUT_MILLIS
    ): AllowedAppSessionUpdate {
        if (state.isLocked) return locked(AllowedAppSessionReason.LOCKED)
        if (!isInteractive) return locked(AllowedAppSessionReason.SCREEN_NOT_INTERACTIVE)
        if (!isTargetAllowed) return locked(AllowedAppSessionReason.TARGET_NO_LONGER_ALLOWED)
        val targetPackage = state.targetPackage
            ?: return locked(AllowedAppSessionReason.TARGET_NO_LONGER_ALLOWED)
        if (!observation.isAvailable) {
            return if (state.phase == AllowedAppSessionPhase.LAUNCHING) {
                waitForLaunchObservation(
                    state = state,
                    nowElapsedMillis = nowElapsedMillis,
                    launchTimeoutMillis = launchTimeoutMillis
                )
            } else {
                locked(AllowedAppSessionReason.FOREGROUND_UNAVAILABLE)
            }
        }

        return when (state.phase) {
            AllowedAppSessionPhase.LOCKED -> locked(AllowedAppSessionReason.LOCKED)
            AllowedAppSessionPhase.LAUNCHING -> evaluateLaunch(
                state = state,
                targetPackage = targetPackage,
                observation = observation,
                nowElapsedMillis = nowElapsedMillis,
                launchTimeoutMillis = launchTimeoutMillis
            )

            AllowedAppSessionPhase.ACTIVE -> {
                if (observation.packageName == targetPackage) {
                    AllowedAppSessionUpdate(
                        state = state,
                        allowedPackage = targetPackage,
                        reason = AllowedAppSessionReason.TARGET_ACTIVE
                    )
                } else {
                    locked(AllowedAppSessionReason.TARGET_LEFT_FOREGROUND)
                }
            }
        }
    }

    private fun waitForLaunchObservation(
        state: AllowedAppSessionState,
        nowElapsedMillis: Long,
        launchTimeoutMillis: Long
    ): AllowedAppSessionUpdate = if (
        nowElapsedMillis - state.launchRequestedElapsedMillis >=
        launchTimeoutMillis.coerceAtLeast(1L)
    ) {
        locked(AllowedAppSessionReason.LAUNCH_TIMEOUT)
    } else {
        AllowedAppSessionUpdate(
            state = state,
            allowedPackage = null,
            reason = AllowedAppSessionReason.WAITING_FOR_TARGET
        )
    }

    private fun evaluateLaunch(
        state: AllowedAppSessionState,
        targetPackage: String,
        observation: ForegroundObservation,
        nowElapsedMillis: Long,
        launchTimeoutMillis: Long
    ): AllowedAppSessionUpdate {
        if (
            nowElapsedMillis - state.launchRequestedElapsedMillis >=
            launchTimeoutMillis.coerceAtLeast(1L)
        ) {
            return locked(AllowedAppSessionReason.LAUNCH_TIMEOUT)
        }

        val confirmingEvent = observation.newEvents.lastOrNull { event ->
            event.kind == ForegroundEventKind.FOREGROUND &&
                event.packageName == targetPackage &&
                event.timestampMillis >= state.launchRequestedWallMillis
        }
        val navigationAwayEvent = observation.newEvents.lastOrNull { event ->
            event.timestampMillis >= state.launchRequestedWallMillis &&
                (event.kind == ForegroundEventKind.BACKGROUND &&
                    event.packageName == targetPackage ||
                    event.kind == ForegroundEventKind.FOREGROUND &&
                    event.packageName != targetPackage)
        }
        if (navigationAwayEvent != null && observation.packageName != targetPackage) {
            return locked(AllowedAppSessionReason.TARGET_LEFT_FOREGROUND)
        }
        if (
            !state.launchSucceeded ||
            observation.packageName != targetPackage
        ) {
            return AllowedAppSessionUpdate(
                state = state,
                allowedPackage = null,
                reason = AllowedAppSessionReason.WAITING_FOR_TARGET
            )
        }

        val activeState = state.copy(
            phase = AllowedAppSessionPhase.ACTIVE,
            activatedEventTimestampMillis =
                confirmingEvent?.timestampMillis ?: observation.transitionTimestampMillis
        )
        return AllowedAppSessionUpdate(
            state = activeState,
            allowedPackage = targetPackage,
            reason = AllowedAppSessionReason.TARGET_CONFIRMED
        )
    }

    private fun locked(reason: AllowedAppSessionReason) = AllowedAppSessionUpdate(
        state = AllowedAppSessionState(),
        allowedPackage = null,
        reason = reason
    )

    // OEM 设备的 UsageStats 事件常有数秒延迟，5 秒会导致白名单确认在慢速设备上
    // 100% 超时重锁；放宽到 12 秒，离开检测仍由前台观察与系统导航监听兜底。
    const val DEFAULT_LAUNCH_TIMEOUT_MILLIS = 12_000L
}
