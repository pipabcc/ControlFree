package com.example.controlfree.ui.main

import com.example.controlfree.ui.todo.search.SuperSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabTest {
    @Test
    fun `底部五个入口顺序稳定且清单位于中间`() {
        assertEquals(
            listOf("监督", "专注", "清单", "统计", "我的"),
            MainTab.entries.map(MainTab::displayName)
        )
        assertEquals(MainTab.MONITOR, MainTab.entries.first())
        assertEquals(MainTab.TODO, MainTab.entries[MainTab.entries.size / 2])
        assertEquals(64, MAIN_BOTTOM_BAR_MIN_HEIGHT_DP)
        assertEquals(4, MAIN_BOTTOM_BAR_CONTENT_OFFSET_DP)
    }

    @Test
    fun `底部入口使用统一线性图标语义`() {
        assertEquals(
            listOf(
                MainNavigationGlyph.SHIELD,
                MainNavigationGlyph.TIMER,
                MainNavigationGlyph.CHECKLIST,
                MainNavigationGlyph.CHART,
                MainNavigationGlyph.PERSON
            ),
            MainTab.entries.map(MainTab::navigationGlyph)
        )
    }

    @Test
    fun `输入法显示或使用侧边导航时不保留底栏空间`() {
        assertTrue(
            shouldShowMainBottomBar(
                useNavigationRail = false,
                isImeVisible = false
            )
        )
        assertFalse(
            shouldShowMainBottomBar(
                useNavigationRail = false,
                isImeVisible = true
            )
        )
        assertFalse(
            shouldShowMainBottomBar(
                useNavigationRail = true,
                isImeVisible = false
            )
        )
    }

    @Test
    fun `所有超级搜索结果都可跳转到对应清单标签`() {
        assertTrue(shouldNavigateFromSuperSearch(SuperSearchSource.CALENDAR))
        assertTrue(shouldNavigateFromSuperSearch(SuperSearchSource.TODO))
        assertTrue(shouldNavigateFromSuperSearch(SuperSearchSource.ANNIVERSARY))
    }
}
