package com.example.controlfree.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

enum class WindowHeightClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

data class ResponsiveLayoutSpec(
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass,
    val isLandscape: Boolean,
    val isLargeFont: Boolean,
    val useTwoPane: Boolean,
    val useNavigationRail: Boolean,
    val preferredContentMaxWidthDp: Int,
    val pageHorizontalPaddingDp: Int
)

object ResponsiveLayoutPolicy {
    private const val MEDIUM_WIDTH_DP = 600
    private const val EXPANDED_WIDTH_DP = 840
    private const val MEDIUM_HEIGHT_DP = 480
    private const val EXPANDED_HEIGHT_DP = 900
    private const val LARGE_FONT_SCALE = 1.3f
    private const val TWO_PANE_EFFECTIVE_WIDTH_DP = 700f
    private const val RAIL_EFFECTIVE_WIDTH_DP = 650f

    fun resolve(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float
    ): ResponsiveLayoutSpec {
        require(widthDp > 0 && heightDp > 0) { "窗口尺寸必须为正数" }
        require(fontScale.isFinite() && fontScale > 0f) { "字体缩放必须为正数" }

        val widthClass = when {
            widthDp >= EXPANDED_WIDTH_DP -> WindowWidthClass.EXPANDED
            widthDp >= MEDIUM_WIDTH_DP -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.COMPACT
        }
        val heightClass = when {
            heightDp >= EXPANDED_HEIGHT_DP -> WindowHeightClass.EXPANDED
            heightDp >= MEDIUM_HEIGHT_DP -> WindowHeightClass.MEDIUM
            else -> WindowHeightClass.COMPACT
        }
        val isLandscape = widthDp > heightDp
        val isLargeFont = fontScale >= LARGE_FONT_SCALE
        // 用“可承载文字的有效宽度”决定分栏，避免平板在 2 倍字体下仍被强行切成窄栏。
        val effectiveWidthDp = widthDp / fontScale.coerceAtLeast(1f)
        val useTwoPane = effectiveWidthDp >= TWO_PANE_EFFECTIVE_WIDTH_DP &&
            (isLandscape || widthClass == WindowWidthClass.EXPANDED)
        val useNavigationRail = widthDp >= EXPANDED_WIDTH_DP &&
            effectiveWidthDp >= RAIL_EFFECTIVE_WIDTH_DP
        val preferredContentMaxWidthDp = when (widthClass) {
            WindowWidthClass.COMPACT -> 600
            WindowWidthClass.MEDIUM -> 840
            WindowWidthClass.EXPANDED -> 1_120
        }
        val pageHorizontalPaddingDp = when {
            isLargeFont -> 16
            widthClass == WindowWidthClass.EXPANDED -> 32
            widthClass == WindowWidthClass.MEDIUM -> 24
            else -> 18
        }
        return ResponsiveLayoutSpec(
            widthClass = widthClass,
            heightClass = heightClass,
            isLandscape = isLandscape,
            isLargeFont = isLargeFont,
            useTwoPane = useTwoPane,
            useNavigationRail = useNavigationRail,
            preferredContentMaxWidthDp = preferredContentMaxWidthDp,
            pageHorizontalPaddingDp = pageHorizontalPaddingDp
        )
    }
}

@Composable
fun rememberResponsiveLayoutSpec(): ResponsiveLayoutSpec {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        fontScale
    ) {
        ResponsiveLayoutPolicy.resolve(
            widthDp = configuration.screenWidthDp.coerceAtLeast(1),
            heightDp = configuration.screenHeightDp.coerceAtLeast(1),
            fontScale = fontScale
        )
    }
}
