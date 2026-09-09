package com.example.controlfree.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackgroundTest {

    @Test
    fun `渐变解析支持六位和八位十六进制颜色`() {
        assertEquals(
            listOf(Color(0xFFE8C5C8), Color(0x80C6D5E5)),
            parseBackgroundGradient(" #E8C5C8, #80C6D5E5 ")
        )
    }

    @Test
    fun `渐变解析忽略非法颜色项`() {
        assertEquals(
            listOf(Color(0xFFC7D3C6), Color(0xFFDACAE5)),
            parseBackgroundGradient("invalid,#C7D3C6,#12345G,#DACAE5")
        )
    }
}
