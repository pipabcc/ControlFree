package com.example.controlfree.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSessionPresetTest {
    @Test
    fun `内置专注模板名称唯一且全部落在可配置范围内`() {
        assertEquals(
            focusSessionPresets.size,
            focusSessionPresets.map(FocusSessionPreset::title).distinct().size
        )
        assertTrue(
            focusSessionPresets.all { preset ->
                preset.lockMinutes in MIN_FOCUS_LOCK_MINUTES..MAX_FOCUS_LOCK_MINUTES
            }
        )
        assertTrue(focusSessionPresets.all { preset -> preset.playMinutes in 1..60 })
        assertEquals(
            listOf(5 to 1, 15 to 3, 30 to 5),
            focusSessionPresets.map { preset -> preset.lockMinutes to preset.playMinutes }
        )
    }

    @Test
    fun `非法专注模板会在进入界面前被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            FocusSessionPreset(" ", lockMinutes = 30, playMinutes = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FocusSessionPreset("过长", lockMinutes = 181, playMinutes = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FocusSessionPreset("无玩机", lockMinutes = 30, playMinutes = 0)
        }
    }

    @Test
    fun `未运行时展示快速开始标题`() {
        assertEquals("快速开始专注任务", focusSessionOverviewTitle(isFocusRunning = false))
        assertEquals("专注任务进行中", focusSessionOverviewTitle(isFocusRunning = true))
    }

    @Test
    fun `待确认预设只影响启动候选参数`() {
        val currentLockMinutes = 42
        val currentPlayMinutes = 7
        val preset = focusSessionPresets.single { it.title == "标准" }

        val pendingConfiguration = resolveFocusStartConfiguration(
            pendingPreset = preset,
            configuredLockMinutes = currentLockMinutes,
            configuredPlayMinutes = currentPlayMinutes
        )
        val cancelledConfiguration = resolveFocusStartConfiguration(
            pendingPreset = null,
            configuredLockMinutes = currentLockMinutes,
            configuredPlayMinutes = currentPlayMinutes
        )

        assertEquals(FocusStartConfiguration(15, 3), pendingConfiguration)
        assertEquals(FocusStartConfiguration(42, 7), cancelledConfiguration)
        assertEquals(42, currentLockMinutes)
        assertEquals(7, currentPlayMinutes)
    }

    @Test
    fun `权限确认不会把一分钟专注回夹到五分钟`() {
        assertEquals(
            FocusStartConfiguration(lockMinutes = 1, playMinutes = 1),
            resolveFocusStartConfiguration(
                pendingPreset = null,
                configuredLockMinutes = 1,
                configuredPlayMinutes = 1
            )
        )
    }

    @Test
    fun `只有未启动且没有其他运行任务时允许应用快速模板`() {
        assertTrue(
            areFocusPresetsEnabled(
                isAnyMonitorRunning = false,
                isStarting = false
            )
        )
        assertFalse(
            areFocusPresetsEnabled(
                isAnyMonitorRunning = false,
                isStarting = true
            )
        )
        assertFalse(
            areFocusPresetsEnabled(
                isAnyMonitorRunning = true,
                isStarting = false
            )
        )
        assertFalse(
            areFocusPresetsEnabled(
                isAnyMonitorRunning = true,
                isStarting = true
            )
        )
    }
}
