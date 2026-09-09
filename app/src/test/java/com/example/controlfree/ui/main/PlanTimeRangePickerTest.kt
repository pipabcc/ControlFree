package com.example.controlfree.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanTimeRangePickerTest {

    @Test
    fun `结束边界的二十四点以零点显示并保存回二十四点`() {
        assertEquals(0, normalizePlanTimePickerMinute(24 * 60))
        assertEquals(
            24 * 60,
            planMinuteFromTimePicker(TimeRangeBoundary.END, hour = 0, minute = 0)
        )
    }

    @Test
    fun `开始边界的零点保持为当天零点`() {
        assertEquals(
            0,
            planMinuteFromTimePicker(TimeRangeBoundary.START, hour = 0, minute = 0)
        )
    }

    @Test
    fun `普通时间不因边界类型改变`() {
        assertEquals(
            8 * 60 + 35,
            planMinuteFromTimePicker(TimeRangeBoundary.START, hour = 8, minute = 35)
        )
        assertEquals(
            18 * 60 + 20,
            planMinuteFromTimePicker(TimeRangeBoundary.END, hour = 18, minute = 20)
        )
    }

    @Test
    fun `非法分钟输入快速失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizePlanTimePickerMinute(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizePlanTimePickerMinute(24 * 60 + 1)
        }
    }
}
