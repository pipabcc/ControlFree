package com.example.controlfree.widget

import android.content.Context
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.theme.BrandColorInts
import com.example.controlfree.theme.LightBrandColorInts
import kotlin.math.roundToInt

data class WidgetThemeColors(
    val background: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val mutedAccent: Int
)

object WidgetThemeResolver {
    fun resolve(context: Context): WidgetThemeColors {
        val preferences = PreferenceManager(context.applicationContext)
        return resolve(
            darkTheme = preferences.isDarkThemeEnabled(),
            backgroundType = preferences.getBackgroundType(),
            backgroundColor = preferences.getBackgroundColor(),
            backgroundGradient = preferences.getBackgroundGradient(),
            backgroundImageIndex = preferences.getBackgroundImageIndex()
        )
    }

    internal fun resolve(
        darkTheme: Boolean,
        backgroundType: String,
        backgroundColor: Int,
        backgroundGradient: String,
        backgroundImageIndex: Int
    ): WidgetThemeColors {
        val base = representativeBackground(
            darkTheme,
            backgroundType,
            backgroundColor,
            backgroundGradient,
            backgroundImageIndex
        )
        val useDarkForeground = contrastRatio(DARK_TEXT, base) >= contrastRatio(LIGHT_TEXT, base)
        val primaryText = if (useDarkForeground) DARK_TEXT else LIGHT_TEXT
        val secondaryText = if (useDarkForeground) 0xFF435249.toInt() else 0xFFD2DED7.toInt()
        val accent = if (useDarkForeground) LightBrandColorInts.Primary else BrandColorInts.PrimaryBright
        return WidgetThemeColors(
            background = argb(
                WIDGET_BACKGROUND_ALPHA,
                red(base),
                green(base),
                blue(base)
            ),
            primaryText = primaryText,
            secondaryText = secondaryText,
            accent = accent,
            mutedAccent = argb(
                74,
                red(accent),
                green(accent),
                blue(accent)
            )
        )
    }

    private fun representativeBackground(
        darkTheme: Boolean,
        type: String,
        color: Int,
        gradient: String,
        imageIndex: Int
    ): Int {
        val fallback = if (darkTheme) BrandColorInts.Canvas else LightBrandColorInts.Canvas
        return when (type) {
            "pure" -> color.takeIf { it != 0 } ?: fallback
            "gradient" -> averageColors(parseGradient(gradient)).takeIf { it != 0 } ?: fallback
            "image" -> IMAGE_BACKGROUNDS.getOrElse(imageIndex) { fallback }
            else -> fallback
        }
    }

    private fun parseGradient(value: String): List<Int> = value.split(',').mapNotNull { item ->
        parseHexColor(item.trim())
    }

    private fun averageColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        return argb(
            255,
            colors.map(::red).average().roundToInt(),
            colors.map(::green).average().roundToInt(),
            colors.map(::blue).average().roundToInt()
        )
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val light = maxOf(relativeLuminance(first), relativeLuminance(second))
        val dark = minOf(relativeLuminance(first), relativeLuminance(second))
        return (light + 0.05) / (dark + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(red(color)) +
            0.7152 * channel(green(color)) +
            0.0722 * channel(blue(color))
    }

    private fun parseHexColor(value: String): Int? {
        if (!value.startsWith('#')) return null
        val digits = value.drop(1)
        val normalized = when (digits.length) {
            6 -> "FF$digits"
            8 -> digits
            else -> return null
        }
        return normalized.toLongOrNull(16)?.toInt()
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xFF) shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)

    private fun red(color: Int): Int = color ushr 16 and 0xFF
    private fun green(color: Int): Int = color ushr 8 and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private const val WIDGET_BACKGROUND_ALPHA = 235
    private val DARK_TEXT = 0xFF17231C.toInt()
    private val LIGHT_TEXT = 0xFFF5FAF7.toInt()
    private val IMAGE_BACKGROUNDS = listOf(
        0xFF0E2721.toInt(),
        0xFF9A4569.toInt(),
        0xFF14254F.toInt(),
        0xFFE3CED0.toInt()
    )
}
