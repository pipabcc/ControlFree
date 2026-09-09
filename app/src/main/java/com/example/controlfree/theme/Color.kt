package com.example.controlfree.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 锁定 Activity、原生悬浮锁层等非 Compose 界面固定使用的深色品牌色。
 *
 * 主应用界面应通过 [BrandColors] 读取当前主题的语义颜色，避免亮色模式下仍冻结为深色。
 */
object BrandColorInts {
    val Canvas = 0xFF0E1310.toInt()
    val Surface = 0xFF121815.toInt()
    val SurfaceCard = 0xFF18221D.toInt() // 降低与Canvas的反差，调和暗色模式下的绿莹荧感
    val SurfaceRaised = 0xFF22312A.toInt() // 同步平滑调优
    val SurfaceMuted = 0xFF2D4037.toInt() // 同步平滑调优
    val Primary = 0xFF21C76A.toInt()
    val PrimaryBright = 0xFF55E58F.toInt()
    val PrimaryContainer = 0xFF0C2D1A.toInt()
    val OnPrimary = 0xFF001B0B.toInt()
    val Secondary = 0xFF5AD7E6.toInt()
    val SecondaryContainer = 0xFF0B292F.toInt()
    val Success = 0xFF38A169.toInt()
    val SuccessContainer = 0xFF0D2B1D.toInt()
    val AppAccent = 0xFF91A7FF.toInt()
    val Warning = 0xFFF5C76B.toInt()
    val WarningContainer = 0xFF2B210D.toInt()
    val Danger = 0xFFFF6B72.toInt()
    val DangerContainer = 0xFF35161B.toInt()
    val TextPrimary = 0xFFF4F7FA.toInt()
    val TextSecondary = 0xFFAAB6C5.toInt()
    val TextTertiary = 0xFF748296.toInt()
    val Outline = 0xFF304239.toInt()
    val OutlineSoft = 0xFF202E26.toInt()
}

/** 亮色模式的纯色值，独立保留便于对比度回归测试。 */
object LightBrandColorInts {
    val Canvas = 0xFFF3F7F5.toInt() // 调亮底色为淡雅白绿，降低与白色卡片的对比度，呈现主流柔和对比度
    val Surface = 0xFFFFFFFF.toInt()
    val SurfaceCard = 0xFFFFFFFF.toInt()
    val SurfaceRaised = 0xFFDFEAE3.toInt() // 改为更淡雅清澈的绿灰，去除原本沉重感
    val SurfaceMuted = 0xFFD2E2D7.toInt() // 改为更清透的绿灰
    val Primary = 0xFF1B8253.toInt() // 升级为年轻大气、带有一丝松石调的高档莫兰迪雅绿
    val PrimaryBright = 0xFF2A9E6C.toInt() // 升级为清澈翠绿
    val PrimaryContainer = 0xFFE5F5EC.toInt() // 升级为清透水灵的微绿底，绝不发脏
    val OnPrimary = 0xFFFFFFFF.toInt()
    val Secondary = 0xFF0E7490.toInt()
    val SecondaryContainer = 0xFFE0F2FE.toInt()
    val Success = 0xFF1B8253.toInt()
    val SuccessContainer = 0xFFE5F5EC.toInt()
    val AppAccent = 0xFF465CC7.toInt()
    val Warning = 0xFF805600.toInt()
    val WarningContainer = 0xFFFFF1CE.toInt()
    val Danger = 0xFFB3261E.toInt()
    val DangerContainer = 0xFFF9DEDC.toInt()
    val TextPrimary = 0xFF17201A.toInt()
    val TextSecondary = 0xFF405045.toInt()
    val TextTertiary = 0xFF4C5B50.toInt()
    val Outline = 0xFF708077.toInt()
    val OutlineSoft = 0xFFD4E0D7.toInt()
}

@Immutable
data class BrandPalette(
    val canvas: Color,
    val surface: Color,
    val surfaceCard: Color,
    val surfaceRaised: Color,
    val surfaceMuted: Color,
    val primary: Color,
    val primaryBright: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val success: Color,
    val successContainer: Color,
    val appAccent: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val outline: Color,
    val outlineSoft: Color
)

/**
 * 直接绘制在应用背景上的前景色。
 *
 * 卡片内部继续使用 [BrandPalette] 的表面语义色；顶部标题、导航、Tab 和卡片外文字
 * 使用本调色板，避免自定义浅色背景配合暗色主题时仍显示浅色文字。
 */
@Immutable
data class BackgroundContentPalette(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentContainer: Color,
    val divider: Color,
    val chromeContainer: Color,
    val usesDarkForeground: Boolean
)

internal val DarkBrandPalette = BrandPalette(
    canvas = Color(BrandColorInts.Canvas),
    surface = Color(BrandColorInts.Surface),
    surfaceCard = Color(BrandColorInts.SurfaceCard),
    surfaceRaised = Color(BrandColorInts.SurfaceRaised),
    surfaceMuted = Color(BrandColorInts.SurfaceMuted),
    primary = Color(BrandColorInts.Primary),
    primaryBright = Color(BrandColorInts.PrimaryBright),
    primaryContainer = Color(BrandColorInts.PrimaryContainer),
    onPrimary = Color(BrandColorInts.OnPrimary),
    secondary = Color(BrandColorInts.Secondary),
    secondaryContainer = Color(BrandColorInts.SecondaryContainer),
    success = Color(BrandColorInts.Success),
    successContainer = Color(BrandColorInts.SuccessContainer),
    appAccent = Color(BrandColorInts.AppAccent),
    warning = Color(BrandColorInts.Warning),
    warningContainer = Color(BrandColorInts.WarningContainer),
    danger = Color(BrandColorInts.Danger),
    dangerContainer = Color(BrandColorInts.DangerContainer),
    textPrimary = Color(BrandColorInts.TextPrimary),
    textSecondary = Color(BrandColorInts.TextSecondary),
    textTertiary = Color(BrandColorInts.TextTertiary),
    outline = Color(BrandColorInts.Outline),
    outlineSoft = Color(BrandColorInts.OutlineSoft)
)

internal val LightBrandPalette = BrandPalette(
    canvas = Color(LightBrandColorInts.Canvas),
    surface = Color(LightBrandColorInts.Surface),
    surfaceCard = Color(LightBrandColorInts.SurfaceCard),
    surfaceRaised = Color(LightBrandColorInts.SurfaceRaised),
    surfaceMuted = Color(LightBrandColorInts.SurfaceMuted),
    primary = Color(LightBrandColorInts.Primary),
    primaryBright = Color(LightBrandColorInts.PrimaryBright),
    primaryContainer = Color(LightBrandColorInts.PrimaryContainer),
    onPrimary = Color(LightBrandColorInts.OnPrimary),
    secondary = Color(LightBrandColorInts.Secondary),
    secondaryContainer = Color(LightBrandColorInts.SecondaryContainer),
    success = Color(LightBrandColorInts.Success),
    successContainer = Color(LightBrandColorInts.SuccessContainer),
    appAccent = Color(LightBrandColorInts.AppAccent),
    warning = Color(LightBrandColorInts.Warning),
    warningContainer = Color(LightBrandColorInts.WarningContainer),
    danger = Color(LightBrandColorInts.Danger),
    dangerContainer = Color(LightBrandColorInts.DangerContainer),
    textPrimary = Color(LightBrandColorInts.TextPrimary),
    textSecondary = Color(LightBrandColorInts.TextSecondary),
    textTertiary = Color(LightBrandColorInts.TextTertiary),
    outline = Color(LightBrandColorInts.Outline),
    outlineSoft = Color(LightBrandColorInts.OutlineSoft)
)

internal val LocalBrandPalette = staticCompositionLocalOf { DarkBrandPalette }
internal val LocalPageBackgroundColor = staticCompositionLocalOf { DarkBrandPalette.canvas }
internal val LocalBackgroundType = staticCompositionLocalOf { "pure" }
internal val LocalBackgroundContentPalette = staticCompositionLocalOf {
    BackgroundContentPalette(
        textPrimary = DarkBrandPalette.textPrimary,
        textSecondary = DarkBrandPalette.textSecondary,
        textTertiary = DarkBrandPalette.textTertiary,
        accent = DarkBrandPalette.primary,
        accentContainer = DarkBrandPalette.primaryContainer,
        divider = DarkBrandPalette.outlineSoft,
        chromeContainer = Color.Transparent,
        usesDarkForeground = false
    )
}

/** 当前 Compose 主题的语义色入口，保留既有 `BrandColors.X` 调用形式。 */
object BrandColors {
    val Canvas: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.canvas
    /** 普通页面根层；自定义背景启用时透明，以显示 Activity 绘制的背景。 */
    val PageBackground: Color
        @Composable @ReadOnlyComposable get() = LocalPageBackgroundColor.current
    /** 菜单、弹窗、底部面板和全屏覆盖层使用的不透明主题画布。 */
    val OverlaySurface: Color
        @Composable @ReadOnlyComposable get() = if (isCustomBackground) {
            if (UsesDarkForeground) {
                Color.White.copy(alpha = 0.92f)
            } else {
                Color.Black.copy(alpha = 0.88f)
            }
        } else {
            LocalBrandPalette.current.canvas
        }
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.surface
    val SurfaceCard: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.surfaceCard
    val SurfaceRaised: Color
        @Composable @ReadOnlyComposable get() = if (isCustomBackground) {
            if (UsesDarkForeground) {
                Color.White.copy(alpha = 0.45f)
            } else {
                Color.Black.copy(alpha = 0.35f)
            }
        } else {
            LocalBrandPalette.current.surfaceRaised
        }
    val SurfaceMuted: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.surfaceMuted
    val Primary: Color
        @Composable @ReadOnlyComposable get() = if (isCustomBackground) {
            BackgroundAccent
        } else {
            LocalBrandPalette.current.primary
        }
    val PrimaryBright: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.primaryBright
    val PrimaryContainer: Color
        @Composable @ReadOnlyComposable get() = if (isCustomBackground) {
            BackgroundAccentContainer
        } else {
            LocalBrandPalette.current.primaryContainer
        }
    val OnPrimary: Color
        @Composable @ReadOnlyComposable get() = if (isCustomBackground) {
            Color.White
        } else {
            LocalBrandPalette.current.onPrimary
        }
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.secondary
    val SecondaryContainer: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.secondaryContainer
    val Success: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.success
    val SuccessContainer: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.successContainer
    val AppAccent: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.appAccent
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.warning
    val WarningContainer: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.warningContainer
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.danger
    val DangerContainer: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.dangerContainer
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.textTertiary
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.outline
    val OutlineSoft: Color @Composable @ReadOnlyComposable get() = LocalBrandPalette.current.outlineSoft
    val BackgroundTextPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.textPrimary
    val BackgroundTextSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.textSecondary
    val BackgroundTextTertiary: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.textTertiary
    val BackgroundAccent: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.accent
    val BackgroundAccentContainer: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.accentContainer
    val BackgroundDivider: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.divider
    val BackgroundChrome: Color
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.chromeContainer
    val BackgroundType: String
        @Composable @ReadOnlyComposable get() = LocalBackgroundType.current
    val UsesDarkForeground: Boolean
        @Composable @ReadOnlyComposable get() = LocalBackgroundContentPalette.current.usesDarkForeground
    val isCustomBackground: Boolean
        @Composable @ReadOnlyComposable get() = LocalPageBackgroundColor.current == Color.Transparent
}
