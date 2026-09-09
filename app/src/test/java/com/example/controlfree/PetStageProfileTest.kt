package com.example.controlfree

import com.example.controlfree.growth.GrowthStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetStageProfileTest {
    @Test
    fun `成长阶段逐步累加触摸动作和外观层次`() {
        val profiles = GrowthStage.entries.map(::petStageProfile)

        assertEquals(2, profiles.first().touchMotions.size)
        assertTrue(profiles.zipWithNext().all { (previous, next) ->
            next.touchMotions.containsAll(previous.touchMotions) &&
                next.touchMotions.size > previous.touchMotions.size
        })
        assertEquals(0, profiles.first().appearanceEffect.haloLayers)
        assertEquals(2, profiles.last().appearanceEffect.haloLayers)
    }

    @Test
    fun `触摸动作只在当前阶段已解锁集合内循环`() {
        val profile = petStageProfile(GrowthStage.NEW_LEAF)
        var index = -1
        val motions = List(profile.touchMotions.size * 2) {
            nextPetTouchMotion(profile, index).also { index = it.first }.second
        }

        assertEquals(profile.touchMotions + profile.touchMotions, motions)
    }

    @Test
    fun `跨阶段升级完整返回中间阶段且不会处理降级`() {
        val upgrade = requireNotNull(
            resolvePetStageUpgrade(GrowthStage.SEEDLING, GrowthStage.GUARDIAN)
        )

        assertEquals(GrowthStage.SEEDLING, upgrade.previousStage)
        assertEquals(GrowthStage.GUARDIAN, upgrade.currentStage)
        assertEquals(
            listOf(GrowthStage.NEW_LEAF, GrowthStage.GREEN_BRANCH, GrowthStage.GUARDIAN),
            upgrade.unlockedStages
        )
        assertNull(resolvePetStageUpgrade(GrowthStage.GUARDIAN, GrowthStage.GUARDIAN))
        assertNull(resolvePetStageUpgrade(GrowthStage.GUARDIAN, GrowthStage.NEW_LEAF))
    }
}
