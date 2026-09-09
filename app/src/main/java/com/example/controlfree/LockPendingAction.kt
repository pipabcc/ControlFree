package com.example.controlfree

import android.content.Intent

internal const val MIN_LOCK_PAUSE_MINUTES = 1
internal const val MAX_LOCK_PAUSE_MINUTES = 30

internal enum class LockPendingActionKind(val wireValue: String) {
    PAUSE("pause"),
    SKIP("skip");

    companion object {
        fun fromWireValue(value: String?): LockPendingActionKind? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 凭据验证期间持有的不可变动作快照。服务仍会基于会话 ID 和当前状态进行最终校验。
 */
@ConsistentCopyVisibility
internal data class LockPendingAction private constructor(
    val kind: LockPendingActionKind,
    val pauseMinutes: Int?
) {
    val serviceAction: String
        get() = when (kind) {
            LockPendingActionKind.PAUSE -> LockActionContract.ACTION_PAUSE_LOCK
            LockPendingActionKind.SKIP -> LockActionContract.ACTION_SKIP_CURRENT_LOCK
        }

    val title: String
        get() = when (kind) {
            LockPendingActionKind.PAUSE -> "验证暂停操作"
            LockPendingActionKind.SKIP -> "验证跳过操作"
        }

    val confirmationText: String
        get() = when (kind) {
            LockPendingActionKind.PAUSE -> "验证通过后暂停 ${requireNotNull(pauseMinutes)} 分钟"
            LockPendingActionKind.SKIP -> "验证通过后跳过本次锁定并重新开始监督"
        }

    companion object {
        val Skip = LockPendingAction(LockPendingActionKind.SKIP, null)

        fun pause(minutes: Int): LockPendingAction {
            require(minutes in MIN_LOCK_PAUSE_MINUTES..MAX_LOCK_PAUSE_MINUTES) {
                "暂停时长必须在 $MIN_LOCK_PAUSE_MINUTES 到 $MAX_LOCK_PAUSE_MINUTES 分钟之间"
            }
            return LockPendingAction(LockPendingActionKind.PAUSE, minutes)
        }

        fun fromWireValue(kindValue: String?, pauseMinutes: Int?): LockPendingAction? =
            when (LockPendingActionKind.fromWireValue(kindValue)) {
                LockPendingActionKind.PAUSE -> pauseMinutes
                    ?.takeIf { it in MIN_LOCK_PAUSE_MINUTES..MAX_LOCK_PAUSE_MINUTES }
                    ?.let(::pause)
                LockPendingActionKind.SKIP -> Skip
                null -> null
            }
    }
}

internal object LockActionContract {
    const val ACTION_PAUSE_LOCK = "com.example.controlfree.action.PAUSE_LOCK"
    const val ACTION_SKIP_CURRENT_LOCK = "com.example.controlfree.action.SKIP_CURRENT_LOCK"

    const val EXTRA_PAUSE_MINUTES = "com.example.controlfree.extra.PAUSE_MINUTES"
    private const val EXTRA_VERIFIED_ACTION_KIND =
        "com.example.controlfree.extra.VERIFIED_LOCK_ACTION_KIND"

    fun writeVerifiedHandoff(intent: Intent, action: LockPendingAction) {
        intent.putExtra(EXTRA_VERIFIED_ACTION_KIND, action.kind.wireValue)
        action.pauseMinutes?.let { intent.putExtra(EXTRA_PAUSE_MINUTES, it) }
    }

    fun writeVerifiedHandoff(intent: Intent, request: AuthorizedLockAction) {
        writeVerifiedHandoff(intent, request.action)
        LockActionAuthorizationContract.write(intent, request.authorization)
    }

    /** 一次性消费认证结果，非法或不完整的参数按无动作处理。 */
    fun consumeVerifiedHandoff(intent: Intent): LockPendingAction? {
        val kindValue = intent.getStringExtra(EXTRA_VERIFIED_ACTION_KIND)
        val pauseMinutes = if (intent.hasExtra(EXTRA_PAUSE_MINUTES)) {
            intent.getIntExtra(EXTRA_PAUSE_MINUTES, Int.MIN_VALUE)
        } else {
            null
        }
        intent.removeExtra(EXTRA_VERIFIED_ACTION_KIND)
        intent.removeExtra(EXTRA_PAUSE_MINUTES)
        return LockPendingAction.fromWireValue(kindValue, pauseMinutes)
    }

    fun consumeAuthorizedVerifiedHandoff(intent: Intent): AuthorizedLockAction? {
        val action = consumeVerifiedHandoff(intent)
        val authorization = LockActionAuthorizationContract.consume(intent)
        return if (action != null && authorization != null) {
            AuthorizedLockAction(action, authorization)
        } else {
            null
        }
    }

    fun writeServiceExtras(intent: Intent, action: LockPendingAction) {
        action.pauseMinutes?.let { intent.putExtra(EXTRA_PAUSE_MINUTES, it) }
    }

    fun writeServiceExtras(intent: Intent, request: AuthorizedLockAction) {
        writeServiceExtras(intent, request.action)
        LockActionAuthorizationContract.write(intent, request.authorization)
    }

    fun readServiceRequest(intent: Intent): AuthorizedLockAction? {
        val pauseMinutes = if (intent.hasExtra(EXTRA_PAUSE_MINUTES)) {
            intent.getIntExtra(EXTRA_PAUSE_MINUTES, Int.MIN_VALUE)
        } else {
            null
        }
        val action = when (intent.action) {
            ACTION_PAUSE_LOCK -> pauseMinutes
                ?.takeIf { it in MIN_LOCK_PAUSE_MINUTES..MAX_LOCK_PAUSE_MINUTES }
                ?.let(LockPendingAction::pause)
            ACTION_SKIP_CURRENT_LOCK -> LockPendingAction.Skip
            else -> null
        }
        val authorization = LockActionAuthorizationContract.read(intent)
        return if (action != null && authorization != null) {
            AuthorizedLockAction(action, authorization)
        } else {
            null
        }
    }
}

internal data class LockInterventionRequest(
    val action: LockPendingAction,
    val openChat: Boolean
)

/** 悬浮层进入暂停、跳过或聊天界面时使用，必须连同自定义暂停时长一起传递。 */
internal object LockInterventionContract {
    private const val EXTRA_ACTION_KIND =
        "com.example.controlfree.extra.INTERVENTION_ACTION_KIND"
    private const val EXTRA_PAUSE_MINUTES =
        "com.example.controlfree.extra.INTERVENTION_PAUSE_MINUTES"
    private const val EXTRA_OPEN_CHAT =
        "com.example.controlfree.extra.INTERVENTION_OPEN_CHAT"

    fun writeRequest(intent: Intent, request: LockInterventionRequest) {
        intent.putExtra(EXTRA_ACTION_KIND, request.action.kind.wireValue)
        request.action.pauseMinutes?.let { intent.putExtra(EXTRA_PAUSE_MINUTES, it) }
        intent.putExtra(EXTRA_OPEN_CHAT, request.openChat)
    }

    /** 一次性消费界面请求，非法或不完整的暂停参数按无请求处理。 */
    fun consumeRequest(intent: Intent): LockInterventionRequest? {
        val actionKind = intent.getStringExtra(EXTRA_ACTION_KIND)
        val pauseMinutes = if (intent.hasExtra(EXTRA_PAUSE_MINUTES)) {
            intent.getIntExtra(EXTRA_PAUSE_MINUTES, Int.MIN_VALUE)
        } else {
            null
        }
        val openChat = intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)
        intent.removeExtra(EXTRA_ACTION_KIND)
        intent.removeExtra(EXTRA_PAUSE_MINUTES)
        intent.removeExtra(EXTRA_OPEN_CHAT)
        return LockPendingAction.fromWireValue(actionKind, pauseMinutes)?.let { action ->
            LockInterventionRequest(action = action, openChat = openChat)
        }
    }
}
