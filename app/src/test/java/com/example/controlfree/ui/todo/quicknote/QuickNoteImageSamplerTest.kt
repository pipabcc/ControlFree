package com.example.controlfree.ui.todo.quicknote

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickNoteImageSamplerTest {
    @Test
    fun `大图按二次幂采样到接近目标尺寸`() {
        assertEquals(4, QuickNoteImageSampler.calculateSampleSize(4_000, 3_000, 640, 640))
        assertEquals(8, QuickNoteImageSampler.calculateSampleSize(8_000, 8_000, 640, 640))
        assertEquals(64, QuickNoteImageSampler.calculateSampleSize(50_000, 1_000, 640, 640))
    }

    @Test
    fun `无效尺寸和小图不放大`() {
        assertEquals(1, QuickNoteImageSampler.calculateSampleSize(0, 800, 640, 640))
        assertEquals(1, QuickNoteImageSampler.calculateSampleSize(320, 240, 640, 640))
    }
}
