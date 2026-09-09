package com.example.controlfree.productivity.quicknote

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChineseQuickNoteParserTest {
    private val parser = LocalChineseQuickNoteParser()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, zone)

    @Test
    fun `解析明天下午时间和分钟时长`() {
        val result = parser.parse("明天下午3点跑步30分钟", now)

        assertEquals("跑步", result.title)
        assertEquals(QuickNoteIntent.TODO, result.intent)
        assertEquals(30, result.estimatedDurationMinutes)
        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 15, 0, 0, 0, zone).toInstant().toEpochMilli(),
            result.startAtEpochMillis
        )
    }

    @Test
    fun `带冒号的下午时间按二十四小时制解析`() {
        val result = parser.parse("明天下午3:30跑步30分钟", now)

        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 15, 30, 0, 0, zone).toInstant().toEpochMilli(),
            result.startAtEpochMillis
        )
    }

    @Test
    fun `本周和下周按自然周定位而普通周几保持下次语义`() {
        val thisWeekMonday = parser.parse("本周一开会", now)
        val nextWeekMonday = parser.parse("下周一开会", now)
        val nextMonday = parser.parse("周一开会", now)
        val thisWednesday = parser.parse("星期三开会", now)

        assertEquals(
            ZonedDateTime.of(2026, 7, 20, 23, 59, 0, 0, zone).toInstant().toEpochMilli(),
            thisWeekMonday.dueAtEpochMillis
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 27, 23, 59, 0, 0, zone).toInstant().toEpochMilli(),
            nextWeekMonday.dueAtEpochMillis
        )
        assertEquals(nextWeekMonday.dueAtEpochMillis, nextMonday.dueAtEpochMillis)
        assertEquals(
            ZonedDateTime.of(2026, 7, 22, 23, 59, 0, 0, zone).toInstant().toEpochMilli(),
            thisWednesday.dueAtEpochMillis
        )
    }

    @Test
    fun `晚上十二点显式日期跨到次日零点`() {
        val result = parser.parse("明天晚上12点跑步30分钟", now)

        assertEquals(
            ZonedDateTime.of(2026, 7, 24, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            result.startAtEpochMillis
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 24, 0, 30, 0, 0, zone).toInstant().toEpochMilli(),
            result.dueAtEpochMillis
        )
    }

    @Test
    fun `晚上十二点半隐式日期跨到明天零点半`() {
        val result = parser.parse("晚上12:30睡觉", now)

        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 0, 30, 0, 0, zone).toInstant().toEpochMilli(),
            result.startAtEpochMillis
        )
    }

    @Test
    fun `每天行为识别为习惯`() {
        val result = parser.parse("每天早上7点背单词20分钟", now)

        assertEquals(QuickNoteIntent.HABIT, result.intent)
        assertEquals(QuickNoteRecurrence.DAILY, result.recurrence)
        assertEquals(20, result.estimatedDurationMinutes)
    }

    @Test
    fun `无日期且时间已过按明天解析`() {
        val result = parser.parse("上午9点提交报告", now)

        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            result.startAtEpochMillis
        )
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `普通文本保留为闪念`() {
        val result = parser.parse("也许可以把首页的信息层级再简化", now)

        assertEquals(QuickNoteIntent.NOTE, result.intent)
        assertEquals(null, result.startAtEpochMillis)
    }

    @Test
    fun `解析大后天并从标题中移除日期词`() {
        val result = parser.parse("大后天下午3点产品评审", now)

        assertEquals("产品评审", result.title)
        assertEquals(
            ZonedDateTime.of(2026, 7, 25, 15, 0, 0, 0, zone).toInstant().toEpochMilli(),
            result.startAtEpochMillis
        )
    }
}
