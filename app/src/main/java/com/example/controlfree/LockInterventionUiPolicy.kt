package com.example.controlfree

internal const val LOCK_PET_TITLE = "小芽想陪你停一下"
internal const val LOCK_PET_LOADING_TEXT = "正在更新建议…"
internal const val LOCK_PET_CONTINUE_SELF_DISCIPLINE = "继续自律"
internal const val LOCK_PET_ADVICE_MAX_CODE_POINTS = 180
internal const val LOCK_PET_TOUCH_RATE_LIMIT_MILLIS = 450L

/** 介入卡与暂停滑块的初始时长，也是从锁屏直接发起暂停时的默认值。 */
internal const val DEFAULT_LOCK_PAUSE_MINUTES = 1
internal val LOCK_PAUSE_PRESET_MINUTES = listOf(1, 5, 10)

internal enum class LockCompanionLayoutMode {
    COMPACT,
    STANDARD
}

internal data class LockPetTouchDecision(
    val accepted: Boolean,
    val responseIndex: Int,
    val message: String?
)

/**
 * 锁屏介入卡持有的不可变快照。请求编号和锁定会话共同阻止迟到结果污染新界面。
 */
internal data class LockPetInterventionUiState(
    val requestId: Long,
    val lockSessionId: Long,
    val action: LockPendingAction,
    val sessionMode: MonitorSessionMode,
    val remainingSeconds: Int,
    val message: String,
    val isLoading: Boolean,
    val chatHistory: List<com.example.controlfree.ai.AiChatMessage> = emptyList(),
    val isChatPanelOpen: Boolean = false,
    val isWaitingResponse: Boolean = false,
    val eyeDistanceStatus: com.example.controlfree.sensor.EyeDistanceStatus = com.example.controlfree.sensor.EyeDistanceStatus.SAFE,
    val eyeRelaxingActive: Boolean = false
)

/** 宠物是锁屏的本地交互能力；关闭或离线的 AI 只能停止云端建议，不能让入口消失。 */
internal fun shouldShowLockPetIntervention(
    @Suppress("UNUSED_PARAMETER") aiEnabled: Boolean
): Boolean = true

internal fun shouldRequestLockPetAiAdvice(aiEnabled: Boolean): Boolean = aiEnabled

internal fun initialLockPetInterventionUiState(
    requestId: Long,
    lockSessionId: Long,
    action: LockPendingAction,
    sessionMode: MonitorSessionMode,
    remainingSeconds: Int,
    requestAiAdvice: Boolean = true
): LockPetInterventionUiState {
    require(requestId > 0L) { "宠物介入请求编号必须为正数" }
    val localAdvice = localLockPetAdvice(action)
    return LockPetInterventionUiState(
        requestId = requestId,
        lockSessionId = lockSessionId,
        action = action,
        sessionMode = sessionMode,
        remainingSeconds = remainingSeconds.coerceAtLeast(0),
        message = localAdvice,
        isLoading = requestAiAdvice,
        chatHistory = listOf(com.example.controlfree.ai.AiChatMessage("assistant", localAdvice))
    )
}

/**
 * 仅接纳当前请求、当前锁定会话的结果；空白或异常结果继续使用本地温和建议。
 */
internal fun completeLockPetInterventionUiState(
    current: LockPetInterventionUiState,
    requestId: Long,
    lockSessionId: Long,
    message: String?
): LockPetInterventionUiState {
    if (current.requestId != requestId || current.lockSessionId != lockSessionId) return current
    val normalized = message
        ?.trim()
        ?.takeLockPetAdviceCodePoints(LOCK_PET_ADVICE_MAX_CODE_POINTS)
        .orEmpty()
        .ifBlank { current.message }
    return current.copy(
        message = normalized,
        isLoading = false,
        chatHistory = listOf(com.example.controlfree.ai.AiChatMessage("assistant", normalized))
    )
}

private val pauseAdvices = listOf(
    "先慢一点，做三次舒缓呼吸。你可以继续守住当前任务；如果确实需要休息，仍然可以选择暂停。",
    "先慢一点，做三次深呼吸。闭上眼静心十秒，让疲惫的情绪慢慢沉淀。",
    "自律不是紧绷的弦，合理的休息是蓄力。你想现在喝杯温水，放松一下吗？",
    "小芽一直在陪着你呢。我们可以试着再坚持一小会儿，如果确实累了，随时可以休息哦。",
    "给大脑做个深呼吸吧。闭上眼，把注意力放空十秒，自律的路上小芽为你加油。"
)

private val skipAdvices = listOf(
    "想立刻退出的冲动通常会慢慢回落。先把注意力放回当前一分钟；如果仍想跳过，你依然保有选择。",
    "想立刻退出的冲动就像潮水，来得快去得也快。多给自己一分钟的专注时间，再看看吧。",
    "自律的关卡总是充满挑战，但每一次坚持都会让你更强大。试着把注意力收回当下一分钟。",
    "冲动是暂时的，完成目标的喜悦是长久的。拍拍尘土，和小芽一起继续专注吧！",
    "每一分钟的克制，都是走向自由的积淀。相信自己的力量，我们可以一起走得更远。"
)

internal fun localLockPetAdvice(action: LockPendingAction): String = when (action.kind) {
    LockPendingActionKind.PAUSE -> pauseAdvices[java.util.Random().nextInt(pauseAdvices.size)]
    LockPendingActionKind.SKIP -> skipAdvices[java.util.Random().nextInt(skipAdvices.size)]
}

internal fun resolveLockCompanionLayoutMode(
    screenHeightDp: Int,
    fontScale: Float
): LockCompanionLayoutMode = if (screenHeightDp < 600 || fontScale >= 1.55f) {
    LockCompanionLayoutMode.COMPACT
} else {
    LockCompanionLayoutMode.STANDARD
}

internal fun resolveLockCompanionMaxHeightDp(
    screenHeightDp: Int,
    fontScale: Float
): Int {
    val safeHeight = screenHeightDp.coerceAtLeast(320)
    val preferred = when (resolveLockCompanionLayoutMode(safeHeight, fontScale)) {
        LockCompanionLayoutMode.COMPACT -> if (safeHeight < 480) 88 else 224
        LockCompanionLayoutMode.STANDARD -> 304
    }
    return preferred.coerceAtMost((safeHeight * 50) / 100).coerceAtLeast(1)
}

internal fun resolveLockPetTouch(
    currentResponseIndex: Int,
    lastAcceptedElapsedMillis: Long?,
    nowElapsedMillis: Long,
    responses: List<String> = LOCK_PET_TOUCH_RESPONSES
): LockPetTouchDecision {
    require(responses.isNotEmpty()) { "宠物触摸回应不能为空" }
    val safeIndex = currentResponseIndex
        .takeIf { it in responses.indices }
        ?: -1
    val canAccept = lastAcceptedElapsedMillis == null ||
        nowElapsedMillis < lastAcceptedElapsedMillis ||
        nowElapsedMillis - lastAcceptedElapsedMillis >= LOCK_PET_TOUCH_RATE_LIMIT_MILLIS
    if (!canAccept) {
        return LockPetTouchDecision(false, safeIndex, null)
    }
    val nextIndex = if (responses.size > 1) {
        var rand = kotlin.random.Random.nextInt(responses.size)
        if (rand == safeIndex) {
            rand = (rand + 1) % responses.size
        }
        rand
    } else {
        0
    }
    return LockPetTouchDecision(
        accepted = true,
        responseIndex = nextIndex,
        message = responses[nextIndex]
    )
}

private val LOCK_PET_TOUCH_RESPONSES = listOf(
    "我在这里，陪你把这一分钟守住。",
    "轻轻呼吸一下，你已经比刚才更接近完成。",
    "每一次没有立刻放弃，都在让自律慢慢长大。",
    "先看一眼剩余时间，再决定也不迟。",
    "每一个克制的当下，都在悄悄打造更好的你✨",
    "冲动就像一阵微风，吹过去就好了，加油！🌱",
    "你比想象中更强大，小芽一直在为你掌舵~⚓",
    "先停下3秒钟，听听内心真正想要的是什么吧🌿",
    "今天省下的每一分精力，都是给未来的惊喜成就🎁",
    "坚持住！自律的汗水很快就会绽放出璀璨的花朵🌸",
    "别着急退出，最精彩的蜕变往往就在这一刻发生！🌟",
    "深呼吸，把烦躁放飞，专注的感觉真的很棒呢😊",
    "小芽相信你，你一定能战胜当下的这股小冲动💪",
    "每一次专注的积累，都是你迈向自由的坚实一步🚶‍♂️",
    "掌控手机而不是被手机掌控，你才是生活的主角👑",
    "累了的话眨眨眼睛，小芽永远站在你这一边支持你哦~💚"
)

private fun String.takeLockPetAdviceCodePoints(maxCodePoints: Int): String {
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints)).trimEnd()
}
