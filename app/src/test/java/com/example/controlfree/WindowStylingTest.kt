package com.example.controlfree

import com.example.controlfree.theme.BrandColorInts
import com.example.controlfree.theme.LightBrandColorInts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowStylingTest {
    @Test
    fun `深色模式使用深色画布与浅色系统图标`() {
        val appearance = appSystemBarAppearance(darkTheme = true)

        assertEquals(BrandColorInts.Canvas, appearance.backgroundColor)
        assertTrue(appearance.useLightIcons)
    }

    @Test
    fun `亮色模式使用白色表面与深色系统图标`() {
        val appearance = appSystemBarAppearance(darkTheme = false)

        assertEquals(LightBrandColorInts.Surface, appearance.backgroundColor)
        assertFalse(appearance.useLightIcons)
    }

    @Test
    fun `主题持久化成功后先同步窗口外观再更新Compose状态`() {
        val calls = mutableListOf<String>()

        val applied = applyPersistedThemeChange(
            darkTheme = true,
            persistTheme = { darkTheme ->
                calls += "persist:$darkTheme"
                true
            },
            applyWindowAppearance = { darkTheme -> calls += "window:$darkTheme" },
            updateComposeTheme = { darkTheme -> calls += "compose:$darkTheme" }
        )

        assertTrue(applied)
        assertEquals(
            listOf("persist:true", "window:true", "compose:true"),
            calls
        )
    }

    @Test
    fun `主题持久化失败时不改变窗口和Compose状态`() {
        val calls = mutableListOf<String>()

        val applied = applyPersistedThemeChange(
            darkTheme = false,
            persistTheme = { darkTheme ->
                calls += "persist:$darkTheme"
                false
            },
            applyWindowAppearance = { darkTheme -> calls += "window:$darkTheme" },
            updateComposeTheme = { darkTheme -> calls += "compose:$darkTheme" }
        )

        assertFalse(applied)
        assertEquals(listOf("persist:false"), calls)
    }
}
