package com.example.controlfree

enum class LockAccessReason {
    NOT_LOCK_PHASE,
    ACTIVE_CALL,
    ALLOWED_APP,
    HARD_LOCK
}

data class LockAccessDecision(
    val shouldShowLockUi: Boolean,
    val shouldHoldAudioFocus: Boolean,
    val reason: LockAccessReason
)

object LockAccessPolicy {
    fun decide(
        isLockPhase: Boolean,
        isCallActive: Boolean,
        isCallUiConfirmed: Boolean,
        hasUsageAccess: Boolean,
        foregroundPackage: String?,
        allowedPackages: Set<String>
    ): LockAccessDecision {
        if (!isLockPhase) return allow(LockAccessReason.NOT_LOCK_PHASE)
        if (isCallActive && isCallUiConfirmed) return allow(LockAccessReason.ACTIVE_CALL)
        if (
            hasUsageAccess &&
            foregroundPackage != null &&
            foregroundPackage in allowedPackages
        ) {
            return allow(LockAccessReason.ALLOWED_APP)
        }

        return LockAccessDecision(
            shouldShowLockUi = true,
            shouldHoldAudioFocus = true,
            reason = LockAccessReason.HARD_LOCK
        )
    }

    private fun allow(reason: LockAccessReason) = LockAccessDecision(
        shouldShowLockUi = false,
        shouldHoldAudioFocus = false,
        reason = reason
    )
}
