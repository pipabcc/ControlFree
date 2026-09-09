package com.example.controlfree.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationSelectorTest {
    @Test
    fun `监督时长按一分钟步进并限制边界`() {
        assertEquals(5, normalizeDuration(1, 5, 180, 1))
        assertEquals(13, normalizeDuration(13, 5, 180, 1))
        assertEquals(31, normalizeDuration(31, 5, 180, 1))
        assertEquals(180, normalizeDuration(999, 5, 180, 1))
        assertEquals(7, normalizeDuration(7, 1, 60, 1))
    }

    @Test
    fun `六个快捷时间固定排列为每行三项`() {
        assertEquals(
            listOf(listOf(5, 15, 30), listOf(45, 60, 90)),
            durationPresetRows(listOf(5, 15, 30, 45, 60, 90), 5, 180)
        )
    }

    @Test
    fun `快捷时间过滤越界和重复项且统一使用分钟`() {
        assertEquals(
            listOf(listOf(5, 15, 30)),
            durationPresetRows(listOf(1, 5, 5, 15, 30, 240), 5, 180)
        )
        assertEquals("5 分钟", durationPresetLabel(5))
    }
}
