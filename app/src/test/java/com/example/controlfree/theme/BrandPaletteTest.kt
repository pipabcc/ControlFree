package com.example.controlfree.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class BrandPaletteTest {
    @Test
    fun `主要文本和交互色在深色背景上达到可读对比度`() {
        assertContrastAtLeast(BrandColorInts.TextPrimary, BrandColorInts.Canvas, 7.0)
        assertContrastAtLeast(BrandColorInts.TextSecondary, BrandColorInts.SurfaceCard, 4.5)
        assertContrastAtLeast(BrandColorInts.Primary, BrandColorInts.Canvas, 4.5)
        assertContrastAtLeast(BrandColorInts.Danger, BrandColorInts.Canvas, 4.5)
        assertContrastAtLeast(BrandColorInts.OnPrimary, BrandColorInts.Primary, 4.5)
    }

    @Test
    fun `亮色模式的小号文本和交互色达到可读对比度`() {
        assertContrastAtLeast(
            LightBrandColorInts.TextTertiary,
            LightBrandColorInts.SurfaceMuted,
            4.5
        )
        assertContrastAtLeast(
            LightBrandColorInts.TextSecondary,
            LightBrandColorInts.SurfaceCard,
            4.5
        )
        assertContrastAtLeast(
            LightBrandColorInts.Primary,
            LightBrandColorInts.Canvas,
            4.5
        )
        assertContrastAtLeast(
            LightBrandColorInts.OnPrimary,
            LightBrandColorInts.Primary,
            4.5
        )
    }

    @Test
    fun `品牌色均为不透明且主色属于绿色色相`() {
        val colors = listOf(
            BrandColorInts.Canvas,
            BrandColorInts.SurfaceCard,
            BrandColorInts.Primary,
            BrandColorInts.Secondary,
            BrandColorInts.Danger,
            BrandColorInts.TextPrimary
        )
        assertTrue(colors.all { color -> (color ushr 24) == 0xFF })

        val red = BrandColorInts.Primary shr 16 and 0xFF
        val green = BrandColorInts.Primary shr 8 and 0xFF
        val blue = BrandColorInts.Primary and 0xFF
        assertTrue(green > red && green > blue)
    }

    private fun assertContrastAtLeast(foreground: Int, background: Int, expected: Double) {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        val contrast = (lighter + 0.05) / (darker + 0.05)
        assertTrue("对比度 $contrast 低于 $expected", contrast >= expected)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val normalized = channel / 255.0
            return if (normalized <= 0.04045) {
                normalized / 12.92
            } else {
                Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
        }
        val red = linear(color shr 16 and 0xFF)
        val green = linear(color shr 8 and 0xFF)
        val blue = linear(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
}
