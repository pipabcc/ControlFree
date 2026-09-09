package com.example.controlfree.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.controlfree.R

/**
 * 绘制应用当前选中的整页背景，保证全屏覆盖层与主页面使用同一套背景规则。
 */
@Composable
internal fun AppBackground(
    bgType: String,
    bgColor: Int,
    bgGradient: String,
    bgImageIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val imageResource = if (bgType == "image") {
        backgroundImageResource(bgImageIndex)
    } else {
        null
    }
    val backgroundModifier = when {
        imageResource != null -> Modifier
        bgType == "pure" && bgColor != 0 -> Modifier.background(Color(bgColor))
        bgType == "gradient" -> {
            val colors = parseBackgroundGradient(bgGradient)
            if (colors.size >= 2) {
                Modifier.background(Brush.linearGradient(colors))
            } else {
                Modifier.background(BrandColors.Canvas)
            }
        }
        bgType == "image" -> Modifier.background(
            Brush.linearGradient(listOf(Color(0xFF061510), Color(0xFF1B8A9B)))
        )
        else -> Modifier.background(BrandColors.Canvas)
    }

    Box(modifier = modifier) {
        if (imageResource != null) {
            Image(
                painter = painterResource(imageResource),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(backgroundModifier)
        ) {
            content()
        }
    }
}

internal fun parseBackgroundGradient(encodedGradient: String): List<Color> =
    encodedGradient
        .split(',')
        .mapNotNull(::parseBackgroundColor)

private fun parseBackgroundColor(encodedColor: String): Color? {
    val hex = encodedColor.trim().removePrefix("#")
    if (hex.length !in setOf(6, 8)) return null
    val rawValue = hex.toLongOrNull(radix = 16) ?: return null
    val argb = if (hex.length == 6) rawValue or 0xFF000000L else rawValue
    return Color(argb.toInt())
}

@DrawableRes
private fun backgroundImageResource(imageIndex: Int): Int? = when (imageIndex) {
    0 -> R.drawable.bg_aurora
    1 -> R.drawable.bg_sunset
    2 -> R.drawable.bg_starry
    3 -> R.drawable.bg_sand
    else -> null
}
