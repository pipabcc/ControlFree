package com.example.controlfree.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundContentPaletteTest {

    @Test
    fun `自定义背景只让页面根层透明`() {
        val customBackgrounds = listOf(
            Triple("pure", 0xFF123456.toInt(), true),
            Triple("gradient", 0, false),
            Triple("image", 0, true)
        )

        customBackgrounds.forEach { (type, color, darkTheme) ->
            assertEquals(
                Color.Transparent,
                resolvePageBackgroundColor(darkTheme, type, color)
            )
            assertEquals(1f, resolveThemePalette(darkTheme).canvas.alpha, 0f)
        }
    }

    @Test
    fun `默认背景的页面根层保持主题画布色`() {
        listOf(false, true).forEach { darkTheme ->
            assertEquals(
                resolveThemePalette(darkTheme).canvas,
                resolvePageBackgroundColor(darkTheme, "pure", 0)
            )
        }
    }

    @Test
    fun `暗色主题遇到浅色自定义背景时改用深色前景`() {
        val palette = resolveBackgroundContentPalette(
            darkTheme = true,
            bgType = "pure",
            bgColor = 0xFFE5C8C8.toInt(),
            bgGradient = "",
            bgImageIndex = -1
        )

        assertTrue(palette.usesDarkForeground)
        assertTrue(contrastRatio(palette.textPrimary, Color(0xFFE5C8C8)) >= 4.5f)
    }

    @Test
    fun `亮色主题遇到深色渐变时改用浅色前景`() {
        val palette = resolveBackgroundContentPalette(
            darkTheme = false,
            bgType = "gradient",
            bgColor = 0,
            bgGradient = "#061510,#12372B",
            bgImageIndex = -1
        )

        assertFalse(palette.usesDarkForeground)
        assertTrue(contrastRatio(palette.textPrimary, Color(0xFF061510)) >= 4.5f)
        assertTrue(contrastRatio(palette.textPrimary, Color(0xFF12372B)) >= 4.5f)
    }

    @Test
    fun `内置图片按最不利区域选择可读前景`() {
        repeat(4) { imageIndex ->
            val palette = resolveBackgroundContentPalette(
                darkTheme = true,
                bgType = "image",
                bgColor = 0,
                bgGradient = "",
                bgImageIndex = imageIndex
            )

            assertTrue("图片 $imageIndex 应使用深色前景", palette.usesDarkForeground)
        }
    }

    @Test
    fun `默认背景继续遵循用户选择的主题`() {
        val darkPalette = resolveBackgroundContentPalette(
            darkTheme = true,
            bgType = "pure",
            bgColor = 0,
            bgGradient = "",
            bgImageIndex = -1
        )
        val lightPalette = resolveBackgroundContentPalette(
            darkTheme = false,
            bgType = "pure",
            bgColor = 0,
            bgGradient = "",
            bgImageIndex = -1
        )

        assertFalse(darkPalette.usesDarkForeground)
        assertTrue(lightPalette.usesDarkForeground)
    }
}
