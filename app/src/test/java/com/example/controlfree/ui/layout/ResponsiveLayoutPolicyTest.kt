package com.example.controlfree.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutPolicyTest {
    @Test
    fun `手机竖屏使用紧凑单栏和底部导航`() {
        val spec = ResponsiveLayoutPolicy.resolve(360, 800, 1f)

        assertEquals(WindowWidthClass.COMPACT, spec.widthClass)
        assertFalse(spec.isLandscape)
        assertFalse(spec.useTwoPane)
        assertFalse(spec.useNavigationRail)
    }

    @Test
    fun `手机横屏有足够有效宽度时使用双栏`() {
        val spec = ResponsiveLayoutPolicy.resolve(800, 360, 1f)

        assertEquals(WindowWidthClass.MEDIUM, spec.widthClass)
        assertEquals(WindowHeightClass.COMPACT, spec.heightClass)
        assertTrue(spec.isLandscape)
        assertTrue(spec.useTwoPane)
        assertFalse(spec.useNavigationRail)
    }

    @Test
    fun `展开平板使用双栏和侧边导航`() {
        val spec = ResponsiveLayoutPolicy.resolve(1_280, 800, 1f)

        assertEquals(WindowWidthClass.EXPANDED, spec.widthClass)
        assertTrue(spec.useTwoPane)
        assertTrue(spec.useNavigationRail)
        assertEquals(1_120, spec.preferredContentMaxWidthDp)
    }

    @Test
    fun `超大字体降低有效宽度并主动回退单栏`() {
        val spec = ResponsiveLayoutPolicy.resolve(1_280, 800, 2f)

        assertTrue(spec.isLargeFont)
        assertFalse(spec.useTwoPane)
        assertFalse(spec.useNavigationRail)
        assertEquals(16, spec.pageHorizontalPaddingDp)
    }
}
