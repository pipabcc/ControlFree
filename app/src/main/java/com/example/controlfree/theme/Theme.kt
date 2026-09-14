package com.example.controlfree.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

private val ControlFreeDarkColorScheme = createDarkColorScheme(DarkBrandPalette)
private val ControlFreeLightColorScheme = createLightColorScheme(LightBrandPalette)

private fun createDarkColorScheme(palette: BrandPalette): ColorScheme = darkColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.primaryBright,
    secondary = palette.secondary,
    onSecondary = palette.onPrimary,
    secondaryContainer = palette.secondaryContainer,
    onSecondaryContainer = palette.secondary,
    tertiary = palette.appAccent,
    onTertiary = palette.onPrimary,
    error = palette.danger,
    onError = palette.onPrimary,
    errorContainer = palette.dangerContainer,
    onErrorContainer = palette.danger,
    background = palette.canvas,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceRaised,
    onSurfaceVariant = palette.textSecondary,
    outline = palette.outline,
    outlineVariant = palette.outlineSoft,
    scrim = palette.canvas
)

private fun createLightColorScheme(palette: BrandPalette): ColorScheme = lightColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.primaryBright,
    secondary = palette.secondary,
    onSecondary = palette.onPrimary,
    secondaryContainer = palette.secondaryContainer,
    onSecondaryContainer = palette.secondary,
    tertiary = palette.appAccent,
    onTertiary = palette.onPrimary,
    error = palette.danger,
    onError = palette.onPrimary,
    errorContainer = palette.dangerContainer,
    onErrorContainer = palette.danger,
    background = palette.canvas,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceRaised,
    onSurfaceVariant = palette.textSecondary,
    outline = palette.outline,
    outlineVariant = palette.outlineSoft,
    scrim = palette.textPrimary
)

@Composable
fun ControlFreeTheme(
    darkTheme: Boolean = false,
    bgType: String = "pure",
    bgColor: Int = 0,
    bgGradient: String = "",
    bgImageIndex: Int = -1,
    content: @Composable () -> Unit
) {
    val palette = resolveThemePalette(darkTheme)
    val pageBackgroundColor = resolvePageBackgroundColor(darkTheme, bgType, bgColor)

    val backgroundContentPalette = remember(
        darkTheme,
        bgType,
        bgColor,
        bgGradient,
        bgImageIndex
    ) {
        resolveBackgroundContentPalette(
            darkTheme = darkTheme,
            bgType = bgType,
            bgColor = bgColor,
            bgGradient = bgGradient,
            bgImageIndex = bgImageIndex
        )
    }
    val isCustomBg = pageBackgroundColor == Color.Transparent
    val finalPrimary = if (isCustomBg) backgroundContentPalette.accent else palette.primary
    val finalPrimaryContainer = if (isCustomBg) backgroundContentPalette.accentContainer else palette.primaryContainer
    val finalOnPrimary = if (isCustomBg) Color.White else palette.onPrimary
    val colorScheme = (if (darkTheme) {
        createDarkColorScheme(palette)
    } else {
        createLightColorScheme(palette)
    }).copy(
        primary = finalPrimary,
        primaryContainer = finalPrimaryContainer,
        onPrimary = finalOnPrimary,
        onBackground = backgroundContentPalette.textPrimary
    )
    CompositionLocalProvider(
        LocalBrandPalette provides palette,
        LocalPageBackgroundColor provides pageBackgroundColor,
        LocalBackgroundContentPalette provides backgroundContentPalette,
        LocalBackgroundType provides bgType
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BrandTypography,
            shapes = BrandShapes,
            content = content
        )
    }
}

internal fun resolveThemePalette(darkTheme: Boolean): BrandPalette =
    if (darkTheme) DarkBrandPalette else LightBrandPalette

internal fun resolvePageBackgroundColor(
    darkTheme: Boolean,
    bgType: String,
    bgColor: Int
): Color = if (bgType != "pure" || bgColor != 0) {
    Color.Transparent
} else {
    resolveThemePalette(darkTheme).canvas
}

/**
 * 根据实际背景样本决定前景明暗，并从背景主色生成协调的选中强调色。
 *
 * 此调色板只影响直接叠在背景上的界面元素，卡片内部仍遵循用户选择的亮/暗主题。
 */
internal fun resolveBackgroundContentPalette(
    darkTheme: Boolean,
    bgType: String,
    bgColor: Int,
    bgGradient: String,
    bgImageIndex: Int
): BackgroundContentPalette {
    val basePalette = if (darkTheme) DarkBrandPalette else LightBrandPalette
    if (bgType == "image") {
        val textPrimary = Color(0xFF17231C)
        val textSecondary = Color(0xFF3E4D44)
        val textTertiary = Color(0xFF58675E)
        val accent = when (bgImageIndex) {
            0 -> Color(0xFF2B6045) // 绿意：常青绿
            1 -> Color(0xFF8C5144) // 童趣：肉桂粉橘
            2 -> Color(0xFF3B5B49) // 清植：灰湖绿
            3 -> Color(0xFF843F4C) // 彩愿：烟熏玫瑰
            else -> basePalette.primary
        }
        val chromeContainer = Color.White.copy(alpha = 0.14f)
        return BackgroundContentPalette(
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textTertiary = textTertiary,
            accent = accent,
            accentContainer = accent.copy(alpha = 0.16f),
            divider = textPrimary.copy(alpha = 0.18f),
            chromeContainer = chromeContainer,
            usesDarkForeground = true
        )
    }
    if (bgType == "pure") {
        if (bgColor == 0) {
            return basePalette.toBackgroundContentPalette()
        }
        val textPrimary = Color(0xFF17231C)
        val textSecondary = Color(0xFF3E4D44)
        val textTertiary = Color(0xFF58675E)
        val accent = when (bgColor) {
            0xFFE5C8C8.toInt() -> Color(0xFF844C4C) // 柔粉：莫兰迪玫瑰红棕
            0xFFC7D3C6.toInt() -> Color(0xFF4A6B52) // 雅绿：莫兰迪苔藓绿
            0xFFC6D5E5.toInt() -> Color(0xFF445E7A) // 静蓝：莫兰迪雾霾深蓝
            0xFFDACAE5.toInt() -> Color(0xFF5F4878) // 丁香：莫兰迪香芋深紫
            else -> basePalette.primary // 默认
        }
        val chromeContainer = Color.Transparent
        return BackgroundContentPalette(
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textTertiary = textTertiary,
            accent = accent,
            accentContainer = accent.copy(alpha = 0.16f),
            divider = textPrimary.copy(alpha = 0.18f),
            chromeContainer = chromeContainer,
            usesDarkForeground = true
        )
    }
    val backgroundSamples = backgroundSamples(
        bgType = bgType,
        bgColor = bgColor,
        bgGradient = bgGradient,
        bgImageIndex = bgImageIndex
    )
    if (backgroundSamples.isEmpty()) {
        return basePalette.toBackgroundContentPalette()
    }

    val darkText = Color(0xFF17231C)
    val lightText = Color(0xFFF5FAF7)
    val darkTextContrast = minimumContrastRatio(darkText, backgroundSamples)
    val lightTextContrast = minimumContrastRatio(lightText, backgroundSamples)
    val usesDarkForeground = darkTextContrast >= lightTextContrast
    val textPrimary = if (usesDarkForeground) darkText else lightText
    val textSecondary = if (usesDarkForeground) Color(0xFF3E4D44) else Color(0xFFD8E4DD)
    val textTertiary = if (usesDarkForeground) Color(0xFF58675E) else Color(0xFFB7C6BD)
    val accent = harmonizedBackgroundAccent(
        backgroundSamples = backgroundSamples,
        usesDarkForeground = usesDarkForeground
    )
    val chromeContainer = if (backgroundSamples.size > 1) {
        if (usesDarkForeground) {
            Color.White.copy(alpha = 0.14f)
        } else {
            Color.Black.copy(alpha = 0.14f)
        }
    } else {
        Color.Transparent
    }

    return BackgroundContentPalette(
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        accent = accent,
        accentContainer = accent.copy(alpha = 0.16f),
        divider = textPrimary.copy(alpha = 0.18f),
        chromeContainer = chromeContainer,
        usesDarkForeground = usesDarkForeground
    )
}

internal fun shouldUseDarkSystemBarAppearance(
    darkTheme: Boolean,
    bgType: String,
    bgColor: Int,
    bgGradient: String,
    bgImageIndex: Int
): Boolean = !resolveBackgroundContentPalette(
    darkTheme = darkTheme,
    bgType = bgType,
    bgColor = bgColor,
    bgGradient = bgGradient,
    bgImageIndex = bgImageIndex
).usesDarkForeground

private fun BrandPalette.toBackgroundContentPalette(): BackgroundContentPalette =
    BackgroundContentPalette(
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        accent = primary,
        accentContainer = primaryContainer,
        divider = outlineSoft,
        chromeContainer = Color.Transparent,
        usesDarkForeground = this === LightBrandPalette
    )

private fun backgroundSamples(
    bgType: String,
    bgColor: Int,
    bgGradient: String,
    bgImageIndex: Int
): List<Color> = when (bgType) {
    "pure" -> if (bgColor == 0) emptyList() else listOf(Color(bgColor))
    "gradient" -> parseBackgroundGradient(bgGradient)
        .takeIf { it.size >= 2 }
        .orEmpty()
    "image" -> imageBackgroundSamples(bgImageIndex)
    else -> emptyList()
}

/**
 * 当前图片背景均为内置资源。这里保存其顶部、中部和底部的代表色，
 * 对比度按最不利样本计算，避免只取平均色时在局部失去可读性。
 */
private fun imageBackgroundSamples(imageIndex: Int): List<Color> = when (imageIndex) {
    0 -> listOf(Color(0xFFD3D2B2), Color(0xFFE0E1CC), Color(0xFFEFEEE4)) // 4.png 绿意
    1 -> listOf(Color(0xFFCDB9A5), Color(0xFFE9DCCC), Color(0xFFE0D0C1)) // 7.png 童趣
    2 -> listOf(Color(0xFFC1C0DC), Color(0xFFD9DFE3), Color(0xFFF6F7F7)) // 2.png 清植
    3 -> listOf(Color(0xFFEBD5BD), Color(0xFFFCE7DF), Color(0xFFFDE0DB)) // 6.png 彩愿
    else -> listOf(Color(0xFF061510), Color(0xFF1B8A9B))
}

private fun harmonizedBackgroundAccent(
    backgroundSamples: List<Color>,
    usesDarkForeground: Boolean
): Color {
    val averageBackground = averageColor(backgroundSamples)
    val hsl = averageBackground.toHsl()
    val fallback = if (usesDarkForeground) {
        LightBrandPalette.primary
    } else {
        DarkBrandPalette.primaryBright
    }
    if (hsl[1] < 0.08f) return fallback

    hsl[1] = hsl[1].coerceIn(0.35f, 0.55f)
    hsl[2] = if (usesDarkForeground) 0.38f else 0.74f
    val harmonized = hsl.toColor()
    return listOf(harmonized, fallback).maxBy { candidate ->
        minimumContrastRatio(candidate, backgroundSamples)
    }
}

private fun averageColor(colors: List<Color>): Color {
    val size = colors.size.coerceAtLeast(1)
    return Color(
        red = colors.sumOf { it.red.toDouble() }.toFloat() / size,
        green = colors.sumOf { it.green.toDouble() }.toFloat() / size,
        blue = colors.sumOf { it.blue.toDouble() }.toFloat() / size,
        alpha = 1f
    )
}

private fun Color.toHsl(): FloatArray {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    val hue = when {
        delta == 0f -> 0f
        maximum == red -> 60f * (((green - blue) / delta) % 6f)
        maximum == green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }.let { rawHue -> if (rawHue < 0f) rawHue + 360f else rawHue }
    val saturation = if (delta == 0f) {
        0f
    } else {
        delta / (1f - abs(2f * lightness - 1f))
    }
    return floatArrayOf(hue, saturation, lightness)
}

private fun FloatArray.toColor(): Color {
    val hue = get(0)
    val saturation = get(1).coerceIn(0f, 1f)
    val lightness = get(2).coerceIn(0f, 1f)
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val hueSegment = (hue % 360f) / 60f
    val intermediate = chroma * (1f - abs(hueSegment % 2f - 1f))
    val (redPrime, greenPrime, bluePrime) = when {
        hueSegment < 1f -> Triple(chroma, intermediate, 0f)
        hueSegment < 2f -> Triple(intermediate, chroma, 0f)
        hueSegment < 3f -> Triple(0f, chroma, intermediate)
        hueSegment < 4f -> Triple(0f, intermediate, chroma)
        hueSegment < 5f -> Triple(intermediate, 0f, chroma)
        else -> Triple(chroma, 0f, intermediate)
    }
    val match = lightness - chroma / 2f
    return Color(
        red = redPrime + match,
        green = greenPrime + match,
        blue = bluePrime + match,
        alpha = 1f
    )
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun minimumContrastRatio(foreground: Color, backgrounds: List<Color>): Float =
    backgrounds.minOf { background -> contrastRatio(foreground, background) }
