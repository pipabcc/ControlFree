package com.example.controlfree

import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.controlfree.theme.BrandColorInts
import com.example.controlfree.theme.LightBrandColorInts

fun ComponentActivity.configureBlackEdgeToEdge(hideSystemBars: Boolean = false) {
    configureThemedEdgeToEdge(darkTheme = true, hideSystemBars = hideSystemBars)
}

fun ComponentActivity.configureThemedEdgeToEdge(
    darkTheme: Boolean,
    hideSystemBars: Boolean = false
) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    )
    applyWindowAppearance(appSystemBarAppearance(darkTheme), hideSystemBars)
}

/**
 * 只同步现有 Edge-to-Edge 窗口的主题外观，不重新配置窗口布局。
 * 主题切换时应在更新 Compose 状态前调用，避免系统栏与页面分帧变色。
 */
fun ComponentActivity.applyThemedWindowAppearance(
    darkTheme: Boolean,
    hideSystemBars: Boolean = false
) {
    applyWindowAppearance(appSystemBarAppearance(darkTheme), hideSystemBars)
}

@Suppress("DEPRECATION")
private fun ComponentActivity.applyWindowAppearance(
    appearance: AppSystemBarAppearance,
    hideSystemBars: Boolean
) {
    window.setBackgroundDrawable(ColorDrawable(appearance.backgroundColor))
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !appearance.useLightIcons
        isAppearanceLightNavigationBars = !appearance.useLightIcons
        if (hideSystemBars) {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private fun AppSystemBarAppearance.toSystemBarStyle(): SystemBarStyle =
    if (useLightIcons) {
        SystemBarStyle.dark(backgroundColor)
    } else {
        SystemBarStyle.light(backgroundColor, backgroundColor)
    }

internal fun applyPersistedThemeChange(
    darkTheme: Boolean,
    persistTheme: (Boolean) -> Boolean,
    applyWindowAppearance: (Boolean) -> Unit,
    updateComposeTheme: (Boolean) -> Unit
): Boolean {
    if (!persistTheme(darkTheme)) return false
    applyWindowAppearance(darkTheme)
    updateComposeTheme(darkTheme)
    return true
}

internal data class AppSystemBarAppearance(
    val backgroundColor: Int,
    val useLightIcons: Boolean
)

internal fun appSystemBarAppearance(darkTheme: Boolean): AppSystemBarAppearance =
    if (darkTheme) {
        AppSystemBarAppearance(
            backgroundColor = BrandColorInts.Canvas,
            useLightIcons = true
        )
    } else {
        AppSystemBarAppearance(
            backgroundColor = LightBrandColorInts.Surface,
            useLightIcons = false
        )
    }
