package com.example.controlfree

import android.content.Intent

/** 悬浮锁层请求前台锁定 Activity 承接的强类型动作。 */
internal sealed interface OverlayLockActivityRequest {
    data class Intervention(
        val action: LockPendingAction,
        val openChat: Boolean = false
    ) : OverlayLockActivityRequest

    data object QuickNote : OverlayLockActivityRequest

    data class KnowledgeChallenge(
        val action: LockPendingAction
    ) : OverlayLockActivityRequest

    data class VerifiedAction(
        val request: AuthorizedLockAction
    ) : OverlayLockActivityRequest
}
/**
 * 服务在启动 Activity 前登记的一次性交接凭证。凭证只用于区分用户主动交接和
 * 意外出现的双层锁屏，不参与暂停、跳过等动作授权。
 */
internal data class ExpectedLockActivityHandoff(
    val token: String,
    val lockSessionId: Long,
    val expiresAtElapsedMillis: Long
) {
    init {
        require(token.isNotBlank()) { "锁屏交接令牌不能为空" }
        require(lockSessionId != NO_LOCK_SESSION) { "锁屏交接会话无效" }
        require(expiresAtElapsedMillis >= 0L) { "锁屏交接过期时间无效" }
    }
}

internal object LockActivityHandoffContract {
    private const val EXTRA_HANDOFF_TOKEN =
        "com.example.controlfree.extra.LOCK_ACTIVITY_HANDOFF_TOKEN"

    fun writeToken(intent: Intent, token: String) {
        require(token.isNotBlank()) { "锁屏交接令牌不能为空" }
        intent.putExtra(EXTRA_HANDOFF_TOKEN, token)
    }

    fun readToken(intent: Intent): String? =
        intent.getStringExtra(EXTRA_HANDOFF_TOKEN)?.takeIf(String::isNotBlank)
}

internal fun isExpectedLockActivityHandoff(
    expected: ExpectedLockActivityHandoff?,
    incomingToken: String?,
    incomingSessionId: Long,
    isVisible: Boolean,
    nowElapsedMillis: Long
): Boolean =
    isVisible &&
        expected != null &&
        incomingToken != null &&
        nowElapsedMillis >= 0L &&
        nowElapsedMillis <= expected.expiresAtElapsedMillis &&
        incomingSessionId == expected.lockSessionId &&
        incomingToken == expected.token
