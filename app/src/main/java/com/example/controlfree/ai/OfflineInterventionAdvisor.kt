package com.example.controlfree.ai

import com.example.controlfree.LockPendingActionKind

/** 网络、权限或密钥不可用时仍能立即给出可操作且不强迫用户的本地建议。 */
internal object OfflineInterventionAdvisor {
    fun createAdvice(request: AiInterventionRequest): AiCompanionAdvice = AiCompanionAdvice(
        message = AiAdviceTextSanitizer.sanitize(buildMessage(request)),
        source = AiAdviceSource.LOCAL
    )

    /** 暂停场景下的 11 条随机高吸引力文案。 */
    private val pauseMessages = listOf(
        "想停下歇歇很正常，但自律的旅程最怕半途而废。跟小芽深呼吸三次，给这次坚持充个电吧！",
        "每一次想暂停的念头，都是磨练意志力的好机会。小芽陪你再坚持一小会儿，好不好？",
        "暂停一下没关系，但先闭眼深呼吸三次。很多冲动会在 20 秒后自己消散哦。",
        "你已经坚持了这么久，现在放弃太可惜了。小芽相信你可以再撑一下！",
        "自律不是从不想放弃，而是想放弃时依然选择坚持。你比你想象的更有毅力！",
        "休息一下也可以，但先问问自己：这是真的需要，还是只是一时冲动呢？",
        "小芽知道你有点累了。放低手机，望向远方 20 秒，让大脑重新充满能量吧！",
        "暂停的念头来了又走，但你的坚持会变成明天的骄傲。再给自己一次机会！",
        "每多坚持一分钟，你就比昨天的自己更厉害。小芽为你骄傲！",
        "今天的自律，是给未来的自己一份最好的礼物。咱们再坚持一会儿！",
        "你知道吗？成功的人不是不会想放弃，而是每次都选择再试一次。小芽挺你！"
    )

    /** 跳过场景下的 11 条随机高吸引力文案。 */
    private val skipMessages = listOf(
        "现在的放弃，会让之前的汗水白费哦。和小芽再坚持一下，做说到做到的自己吧！",
        "跳过很容易，但坚持下来才是真正的酷。小芽陪你一起走完这段路！",
        "每次想跳过的时候，都是你离自律更近一步的时刻。试着再坚持看看？",
        "你已经走了这么远，现在跳过就像跑马拉松到一半下车，太亏了！",
        "小芽相信你不是轻易放弃的人。深呼吸，把手机放下 20 秒，冲动就会过去的。",
        "跳过一次不会怎样，但每一次坚持都会让你变得更强大。选择权在你手里！",
        "想想你开始自律时的初心，那个决心满满的自己还在等你坚持下去呢！",
        "你有没有发现？每次觉得撑不下去又撑过来后，那种成就感特别棒！",
        "小芽想悄悄告诉你：坚持到最后的人，总会收获意想不到的惊喜。",
        "自律的路上没有捷径，但小芽会一直陪在你身边。咱们一起加油！",
        "别急着跳过，给自己 20 秒。也许 20 秒后你会发现，其实没那么难。"
    )

    internal fun buildMessage(
        request: AiInterventionRequest
    ): String {
        val opening = when (request.actionKind) {
            LockPendingActionKind.PAUSE -> pauseMessages.random()
            LockPendingActionKind.SKIP -> skipMessages.random()
        }
        val pauseDetail = request.requestedPauseMinutes
            ?.takeIf { request.actionKind == LockPendingActionKind.PAUSE }
            ?.coerceIn(1, 30)
            ?.let { minutes -> "\n你选择暂停 $minutes 分钟。" }
            .orEmpty()
        return "$opening$pauseDetail"
    }
}
