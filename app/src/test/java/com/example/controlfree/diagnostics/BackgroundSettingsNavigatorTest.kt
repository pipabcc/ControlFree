package com.example.controlfree.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSettingsNavigatorTest {
    @Test
    fun `已知厂商返回自启动候选入口`() {
        assertEquals(
            VendorBackgroundSettingsFamily.XIAOMI,
            BackgroundSettingsNavigator.detectVendorFamily("Xiaomi")
        )
        assertEquals(
            VendorBackgroundSettingsFamily.HUAWEI,
            BackgroundSettingsNavigator.detectVendorFamily("HUAWEI")
        )
        assertEquals(
            VendorBackgroundSettingsFamily.VIVO,
            BackgroundSettingsNavigator.detectVendorFamily("vivo")
        )
    }

    @Test
    fun `未知厂商不伪造专有设置入口`() {
        assertEquals(
            VendorBackgroundSettingsFamily.UNKNOWN,
            BackgroundSettingsNavigator.detectVendorFamily("unknown")
        )
    }
}
