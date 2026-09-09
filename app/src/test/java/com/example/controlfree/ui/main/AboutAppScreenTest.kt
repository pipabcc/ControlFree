package com.example.controlfree.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutAppScreenTest {
    @Test
    fun `版本标签保留安装包中的真实版本号`() {
        assertEquals("版本 3.2", formatVersionLabel("3.2"))
        assertEquals("版本 3.2-beta1", formatVersionLabel(" 3.2-beta1 "))
    }

    @Test
    fun `版本号缺失时提供稳定降级文案`() {
        assertEquals("版本 未知", formatVersionLabel(null))
        assertEquals("版本 未知", formatVersionLabel("   "))
    }
}
