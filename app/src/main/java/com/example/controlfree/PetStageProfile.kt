package com.example.controlfree

import android.content.Context
import com.example.controlfree.growth.GrowthStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 锁屏宠物的纯展示能力，由终身经验阶段决定，消费成长值不会使能力降级。 */
internal data class PetStageProfile(
    val stage: GrowthStage,
    val touchMotions: List<PetTouchMotion>,
    val touchMessages: List<String>,
    val expressionMark: String,
    val appearanceEffect: PetAppearanceEffect
) {
    init {
        require(touchMotions.isNotEmpty()) { "宠物阶段至少需要一个触摸动作" }
        require(touchMessages.isNotEmpty()) { "宠物阶段至少需要一条触摸气泡" }
    }

    val title: String
        get() = "${stage.displayName}想陪你停一下"
}

internal enum class PetTouchMotion {
    NOD,
    WAVE,
    SIDE_STEP,
    SPIN,
    DOUBLE_BEAT,
    GUARD_POSE,
    CELEBRATION,
    FULL_DANCE,
    STAR_BURST
}

internal enum class PetAppearanceEffect(
    val glowAlpha: Float,
    val haloLayers: Int
) {
    ORIGINAL(glowAlpha = 0f, haloLayers = 0),
    SOFT_GREEN_GLOW(glowAlpha = 0.18f, haloLayers = 1),
    GREEN_AURA(glowAlpha = 0.24f, haloLayers = 1),
    GUARDIAN_HALO(glowAlpha = 0.3f, haloLayers = 1),
    STAR_DOUBLE_HALO(glowAlpha = 0.38f, haloLayers = 2)
}

internal fun petStageProfile(stage: GrowthStage?): PetStageProfile = when (
    stage ?: GrowthStage.SEEDLING
) {
    GrowthStage.SEEDLING -> PetStageProfile(
        stage = GrowthStage.SEEDLING,
        touchMotions = listOf(PetTouchMotion.NOD, PetTouchMotion.WAVE),
        touchMessages = listOf(
            "轻轻呼吸一下，你已经比刚才更接近完成。",
            "每一个克制的当下，都在悄悄打造更好的你✨",
            "冲动就像一阵微风，吹过去就好了，加油！🌱",
            "先停下3秒钟，听听内心真正想要的是什么吧🌿"
        ),
        expressionMark = "●ᴗ●",
        appearanceEffect = PetAppearanceEffect.ORIGINAL
    )
    GrowthStage.NEW_LEAF -> PetStageProfile(
        stage = GrowthStage.NEW_LEAF,
        touchMotions = listOf(
            PetTouchMotion.NOD,
            PetTouchMotion.WAVE,
            PetTouchMotion.SIDE_STEP
        ),
        touchMessages = listOf(
            "今天的新叶也在为你的坚持鼓掌。",
            "把注意力放回下一小步，我们一起走。",
            "很好，你没有让一时冲动替你做决定。",
            "今天省下的每一分精力，都是给未来的惊喜成就🎁",
            "你比想象中更强大，小芽一直在为你掌舵~⚓"
        ),
        expressionMark = "•‿•",
        appearanceEffect = PetAppearanceEffect.SOFT_GREEN_GLOW
    )
    GrowthStage.GREEN_BRANCH -> PetStageProfile(
        stage = GrowthStage.GREEN_BRANCH,
        touchMotions = listOf(
            PetTouchMotion.NOD,
            PetTouchMotion.WAVE,
            PetTouchMotion.SIDE_STEP,
            PetTouchMotion.SPIN,
            PetTouchMotion.DOUBLE_BEAT
        ),
        touchMessages = listOf(
            "青枝正在生长，你的专注也一样。",
            "先守住这一分钟，再决定下一步。",
            "节奏找回来了，继续把任务向前推一点。",
            "每一次没有立刻放弃，都在让自律长大。",
            "坚持住！自律的汗水很快就会绽放出璀璨的花朵🌸",
            "深呼吸，把烦躁放飞，专注的感觉真的很棒呢😊"
        ),
        expressionMark = "◕‿◕",
        appearanceEffect = PetAppearanceEffect.GREEN_AURA
    )
    GrowthStage.GUARDIAN -> PetStageProfile(
        stage = GrowthStage.GUARDIAN,
        touchMotions = listOf(
            PetTouchMotion.NOD,
            PetTouchMotion.WAVE,
            PetTouchMotion.SIDE_STEP,
            PetTouchMotion.SPIN,
            PetTouchMotion.DOUBLE_BEAT,
            PetTouchMotion.GUARD_POSE,
            PetTouchMotion.CELEBRATION
        ),
        touchMessages = listOf(
            "守望会替你挡住冲动，你只需要守住当下。",
            "你已经走了很远，不必在这一刻轻易交出选择。",
            "坚定不是没有冲动，而是仍能做出自己的决定。",
            "剩余时间正在减少，你的掌控感正在增加。",
            "别着急退出，最精彩的蜕变往往就在这一刻发生！🌟",
            "掌控手机而不是被手机掌控，你才是生活的主角👑"
        ),
        expressionMark = "•̀ᴗ•́",
        appearanceEffect = PetAppearanceEffect.GUARDIAN_HALO
    )
    GrowthStage.STAR_BLOOM -> PetStageProfile(
        stage = GrowthStage.STAR_BLOOM,
        touchMotions = PetTouchMotion.entries,
        touchMessages = listOf(
            "星芽见证过你的每次坚持，这一次也会陪到底。",
            "把冲动交给星光，把选择留给清醒的自己。",
            "这一刻的自律，正在变成下一次更从容的你。",
            "完整舞步已解锁——为正在坚持的你庆祝。",
            "彩蛋时刻：你比刚开始时强大得多。",
            "每一次专注的积累，都是你迈向自由的坚实一步🚶‍♂️",
            "累了的话眨眨眼睛，小芽永远站在你这一边支持你哦~💚"
        ),
        expressionMark = "✦‿✦",
        appearanceEffect = PetAppearanceEffect.STAR_DOUBLE_HALO
    )
}

internal fun nextPetTouchMotion(profile: PetStageProfile, currentIndex: Int): Pair<Int, PetTouchMotion> {
    val safeIndex = currentIndex.takeIf { it in profile.touchMotions.indices } ?: -1
    val nextIndex = (safeIndex + 1) % profile.touchMotions.size
    return nextIndex to profile.touchMotions[nextIndex]
}

internal data class PetStageUpgradeEvent(
    val previousStage: GrowthStage,
    val currentStage: GrowthStage,
    /** 一次跨越多个阶段时完整列出，升级卡可以逐项展示且不会漏掉中间能力。 */
    val unlockedStages: List<GrowthStage>
)

internal fun resolvePetStageUpgrade(
    lastPresentedStage: GrowthStage,
    currentStage: GrowthStage
): PetStageUpgradeEvent? {
    if (currentStage.ordinal <= lastPresentedStage.ordinal) return null
    return PetStageUpgradeEvent(
        previousStage = lastPresentedStage,
        currentStage = currentStage,
        unlockedStages = GrowthStage.entries.filter { stage ->
            stage.ordinal in (lastPresentedStage.ordinal + 1)..currentStage.ordinal
        }
    )
}

/**
 * 阶段升级的一次性持久化门闩。
 *
 * 仅安全前台（成长中心）调用；锁屏和身份验证界面只读取当前阶段，绝不消费升级事件或弹窗。
 * 首次记录以“初芽”为基线，因此老用户已经达到更高阶段时仍能看到一次补偿升级说明。
 */
internal class PetStageUpgradeTracker(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    suspend fun consumePendingUpgrade(currentStage: GrowthStage): PetStageUpgradeEvent? =
        withContext(Dispatchers.IO) {
            synchronized(PERSISTENCE_LOCK) {
                val storedOrdinal = preferences.getInt(
                    KEY_LAST_PRESENTED_STAGE,
                    GrowthStage.SEEDLING.ordinal
                ).coerceIn(GrowthStage.SEEDLING.ordinal, GrowthStage.entries.lastIndex)
                val lastPresented = GrowthStage.entries[storedOrdinal]
                val event = resolvePetStageUpgrade(lastPresented, currentStage) ?: return@synchronized null
                check(
                    preferences.edit()
                        .putInt(KEY_LAST_PRESENTED_STAGE, currentStage.ordinal)
                        .commit()
                ) { "宠物阶段升级展示状态保存失败" }
                event
            }
        }

    private companion object {
        const val PREFERENCES_NAME = "pet_stage_upgrade_state"
        const val KEY_LAST_PRESENTED_STAGE = "last_presented_stage"
        val PERSISTENCE_LOCK = Any()
    }
}
