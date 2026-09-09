package com.example.controlfree.widget

import com.example.controlfree.theme.BrandColorInts
import com.example.controlfree.theme.LightBrandColorInts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetThemeResolverTest {
    @Test
    fun `默认主题跟随应用明暗背景并保留透明度`() {
        val light = WidgetThemeResolver.resolve(false, "pure", 0, "", -1)
        val dark = WidgetThemeResolver.resolve(true, "pure", 0, "", -1)

        assertEquals(235, alpha(light.background))
        assertEquals(235, alpha(dark.background))
        assertEquals(red(LightBrandColorInts.Canvas), red(light.background))
        assertEquals(red(BrandColorInts.Canvas), red(dark.background))
        assertNotEquals(light.primaryText, dark.primaryText)
    }

    @Test
    fun `自定义纯色和渐变使用实际背景代表色`() {
        val pure = WidgetThemeResolver.resolve(false, "pure", 0xFF336699.toInt(), "", -1)
        val gradient = WidgetThemeResolver.resolve(
            false,
            "gradient",
            0,
            "#000000,#FFFFFF",
            -1
        )

        assertEquals(0x33, red(pure.background))
        assertEquals(0x66, green(pure.background))
        assertEquals(0x99, blue(pure.background))
        assertEquals(128, red(gradient.background))
        assertEquals(128, green(gradient.background))
        assertEquals(128, blue(gradient.background))
    }

    private fun alpha(color: Int): Int = color ushr 24 and 0xFF
    private fun red(color: Int): Int = color ushr 16 and 0xFF
    private fun green(color: Int): Int = color ushr 8 and 0xFF
    private fun blue(color: Int): Int = color and 0xFF
}
