package com.example.controlfree.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTimelineAggregatorTest {
    @Test
    fun `跨午夜会话严格拆分到两个自然日`() {
        val ranges = twoDays()
        val midnight = ranges[1].startMillis

        val result = aggregate(
            ranges,
            event(APP_A, "Main", midnight - 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", midnight + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(10 * MINUTE, foreground(result, APP_A, ranges[0].date))
        assertEquals(10 * MINUTE, foreground(result, APP_A, ranges[1].date))
        assertEquals(
            listOf(UsageSessionSlice(midnight - 10 * MINUTE, midnight)),
            sessions(result, APP_A, ranges[0].date)
        )
        assertEquals(
            listOf(UsageSessionSlice(midnight, midnight + 10 * MINUTE)),
            sessions(result, APP_A, ranges[1].date)
        )
        assertDurationInvariant(result, APP_A, ranges[0].date)
        assertDurationInvariant(result, APP_A, ranges[1].date)
    }

    @Test
    fun `窗口前恢复的会话只统计与首日相交部分`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE - 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(10 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `完全重复的系统事件不会重复累计`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)
        val resume = event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED)
        val pause = event(APP_A, "Main", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)

        val result = aggregate(listOf(range), resume, resume, pause, pause)

        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `乱序切换事件按时间重排且只保留一个焦点包`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_B, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Main", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_B, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED)
        )

        assertEquals(10 * MINUTE, foreground(result, APP_A, DAY_ONE))
        assertEquals(20 * MINUTE, foreground(result, APP_B, DAY_ONE))
    }

    @Test
    fun `旧应用迟到的后台事件不会关闭当前前台应用`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_B, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_B, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(10 * MINUTE, foreground(result, APP_A, DAY_ONE))
        assertEquals(20 * MINUTE, foreground(result, APP_B, DAY_ONE))
    }

    @Test
    fun `不可展示的系统包仍参与焦点切换并截断普通应用`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(SYSTEM_UI, "Shade", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(SYSTEM_UI, "Shade", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(10 * MINUTE, foreground(result, APP_A, DAY_ONE))
        assertEquals(10 * MINUTE, foreground(result, SYSTEM_UI, DAY_ONE))
    }

    @Test
    fun `同包多 Activity 切换不会产生空洞或双重计时`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Detail", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 11 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Detail", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `熄屏期间停止计时且亮屏解锁后恢复已有焦点`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            systemEvent(BASE + 10 * MINUTE, UsageTimelineEventType.SCREEN_NON_INTERACTIVE),
            systemEvent(BASE + 20 * MINUTE, UsageTimelineEventType.SCREEN_INTERACTIVE),
            systemEvent(BASE + 20 * MINUTE, UsageTimelineEventType.KEYGUARD_HIDDEN),
            event(APP_A, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
        assertEquals(
            listOf(
                UsageSessionSlice(BASE, BASE + 10 * MINUTE),
                UsageSessionSlice(BASE + 20 * MINUTE, BASE + 30 * MINUTE)
            ),
            sessions(result, APP_A, DAY_ONE)
        )
    }

    @Test
    fun `同包同日首尾相接的片段合并为一次会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.USER_INTERACTION),
            event(APP_A, "Main", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(UsageSessionSlice(BASE, BASE + 20 * MINUTE)),
            sessions(result, APP_A, DAY_ONE)
        )
        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `同包同一显示秒内的生命周期间隙递归合并`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 1_100L, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Detail", BASE + 1_700L, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Detail", BASE + 2_100L, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Settings", BASE + 2_600L, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Settings", BASE + 3_000L, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(UsageSessionSlice(BASE, BASE + 3_000L)),
            sessions(result, APP_A, DAY_ONE)
        )
        assertEquals(3_000L, foreground(result, APP_A, DAY_ONE))
        assertDurationInvariant(result, APP_A, DAY_ONE)
    }

    @Test
    fun `毫秒间隙跨越显示秒时仍拆分会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 1_900L, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Detail", BASE + 2_000L, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Detail", BASE + 3_000L, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(
                UsageSessionSlice(BASE, BASE + 1_900L),
                UsageSessionSlice(BASE + 2_000L, BASE + 3_000L)
            ),
            sessions(result, APP_A, DAY_ONE)
        )
        assertEquals(2_900L, foreground(result, APP_A, DAY_ONE))
        assertDurationInvariant(result, APP_A, DAY_ONE)
    }

    @Test
    fun `同一显示秒内切换其他App会阻断原App会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 1_100L, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_B, "Main", BASE + 1_500L, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_B, "Main", BASE + 1_600L, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Detail", BASE + 1_700L, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Detail", BASE + 2_500L, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(
                UsageSessionSlice(BASE, BASE + 1_100L),
                UsageSessionSlice(BASE + 1_700L, BASE + 2_500L)
            ),
            sessions(result, APP_A, DAY_ONE)
        )
        assertEquals(
            listOf(UsageSessionSlice(BASE + 1_500L, BASE + 1_600L)),
            sessions(result, APP_B, DAY_ONE)
        )
        assertDurationInvariant(result, APP_A, DAY_ONE)
        assertDurationInvariant(result, APP_B, DAY_ONE)
    }

    @Test
    fun `其他App零时长切入切出仍会阻断原App会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)
        val switchMillis = BASE + 1_500L

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_B, "Main", switchMillis, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", switchMillis, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 2_500L, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(
                UsageSessionSlice(BASE, switchMillis),
                UsageSessionSlice(switchMillis, BASE + 2_500L)
            ),
            sessions(result, APP_A, DAY_ONE)
        )
        assertTrue(sessions(result, APP_B, DAY_ONE).isEmpty())
        assertDurationInvariant(result, APP_A, DAY_ONE)
    }

    @Test
    fun `熄屏和锁屏事件会阻断同一显示秒内的会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)
        val barriers = listOf(
            "熄屏" to listOf(
                UsageTimelineEventType.SCREEN_NON_INTERACTIVE,
                UsageTimelineEventType.SCREEN_INTERACTIVE
            ),
            "锁屏" to listOf(
                UsageTimelineEventType.KEYGUARD_SHOWN,
                UsageTimelineEventType.KEYGUARD_HIDDEN
            )
        )

        barriers.forEach { (label, events) ->
            val result = aggregate(
                listOf(range),
                event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
                systemEvent(BASE + 1_100L, events[0]),
                systemEvent(BASE + 1_500L, events[1]),
                event(APP_A, "Main", BASE + 2_500L, UsageTimelineEventType.ACTIVITY_PAUSED)
            )

            assertEquals(
                label,
                listOf(
                    UsageSessionSlice(BASE, BASE + 1_100L),
                    UsageSessionSlice(BASE + 1_500L, BASE + 2_500L)
                ),
                sessions(result, APP_A, DAY_ONE)
            )
            assertDurationInvariant(result, APP_A, DAY_ONE)
        }
    }

    @Test
    fun `系统状态同一毫秒关闭并恢复仍会阻断会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)
        val barrierMillis = BASE + 1_500L
        val barriers = listOf(
            "熄屏" to listOf(
                UsageTimelineEventType.SCREEN_NON_INTERACTIVE,
                UsageTimelineEventType.SCREEN_INTERACTIVE
            ),
            "锁屏" to listOf(
                UsageTimelineEventType.KEYGUARD_SHOWN,
                UsageTimelineEventType.KEYGUARD_HIDDEN
            )
        )

        barriers.forEach { (label, events) ->
            val result = aggregate(
                listOf(range),
                event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
                systemEvent(barrierMillis, events[0]),
                systemEvent(barrierMillis, events[1]),
                event(APP_A, "Main", BASE + 2_500L, UsageTimelineEventType.ACTIVITY_PAUSED)
            )

            assertEquals(
                label,
                listOf(
                    UsageSessionSlice(BASE, barrierMillis),
                    UsageSessionSlice(barrierMillis, BASE + 2_500L)
                ),
                sessions(result, APP_A, DAY_ONE)
            )
            assertDurationInvariant(result, APP_A, DAY_ONE)
        }
    }

    @Test
    fun `关机和启动会阻断同一显示秒内的会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            systemEvent(BASE + 1_100L, UsageTimelineEventType.DEVICE_SHUTDOWN),
            systemEvent(BASE + 1_300L, UsageTimelineEventType.DEVICE_STARTUP),
            systemEvent(BASE + 1_400L, UsageTimelineEventType.SCREEN_INTERACTIVE),
            systemEvent(BASE + 1_500L, UsageTimelineEventType.KEYGUARD_HIDDEN),
            event(APP_A, "Main", BASE + 1_600L, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 2_500L, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(
                UsageSessionSlice(BASE, BASE + 1_100L),
                UsageSessionSlice(BASE + 1_600L, BASE + 2_500L)
            ),
            sessions(result, APP_A, DAY_ONE)
        )
        assertDurationInvariant(result, APP_A, DAY_ONE)
    }

    @Test
    fun `离开前台后再次打开同一应用会拆分为两次会话`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED),
            event(APP_A, "Main", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(
            listOf(
                UsageSessionSlice(BASE, BASE + 10 * MINUTE),
                UsageSessionSlice(BASE + 20 * MINUTE, BASE + 30 * MINUTE)
            ),
            sessions(result, APP_A, DAY_ONE)
        )
        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `解锁和亮屏事件顺序颠倒时仍在条件齐备后恢复计时`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            systemEvent(BASE + 10 * MINUTE, UsageTimelineEventType.SCREEN_NON_INTERACTIVE),
            systemEvent(BASE + 20 * MINUTE, UsageTimelineEventType.KEYGUARD_HIDDEN),
            systemEvent(BASE + 21 * MINUTE, UsageTimelineEventType.SCREEN_INTERACTIVE),
            event(APP_A, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(19 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `解锁后恢复焦点且用户交互不会重复计时`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            systemEvent(BASE + 10 * MINUTE, UsageTimelineEventType.KEYGUARD_SHOWN),
            systemEvent(BASE + 20 * MINUTE, UsageTimelineEventType.KEYGUARD_HIDDEN),
            event(APP_A, null, BASE + 25 * MINUTE, UsageTimelineEventType.USER_INTERACTION),
            event(APP_A, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `关机清空旧会话且开机后必须重新确认`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            systemEvent(BASE + 10 * MINUTE, UsageTimelineEventType.DEVICE_SHUTDOWN),
            systemEvent(BASE + 20 * MINUTE, UsageTimelineEventType.DEVICE_STARTUP),
            systemEvent(BASE + 25 * MINUTE, UsageTimelineEventType.SCREEN_INTERACTIVE),
            systemEvent(BASE + 26 * MINUTE, UsageTimelineEventType.KEYGUARD_HIDDEN),
            event(APP_A, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 40 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(20 * MINUTE, foreground(result, APP_A, DAY_ONE))
    }

    @Test
    fun `未闭合前台会话结算到当前窗口终点`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED)
        )

        assertEquals(50 * MINUTE, foreground(result, APP_A, DAY_ONE))
        assertEquals(BASE + HOUR, lastUsed(result, APP_A, DAY_ONE))
    }

    @Test
    fun `空事件和非法事件返回空统计而不是沿用旧值`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)
        val invalidEvents = listOf(
            event(" ", "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", -1L, UsageTimelineEventType.ACTIVITY_RESUMED),
            UsageTimelineEvent(null, null, BASE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertTrue(UsageTimelineAggregator.aggregate(emptyList(), listOf(range)).packages.isEmpty())
        assertTrue(UsageTimelineAggregator.aggregate(invalidEvents, listOf(range)).packages.isEmpty())
    }

    @Test
    fun `跨日结束和继续事件保持会话连续但不越过日界线`() {
        val ranges = twoDays()
        val midnight = ranges[1].startMillis

        val result = aggregate(
            ranges,
            event(APP_A, "Main", midnight - 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, null, midnight, UsageTimelineEventType.END_OF_DAY),
            event(APP_A, null, midnight, UsageTimelineEventType.CONTINUE_PREVIOUS_DAY),
            event(APP_A, null, midnight + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_PAUSED)
        )

        assertEquals(10 * MINUTE, foreground(result, APP_A, ranges[0].date))
        assertEquals(10 * MINUTE, foreground(result, APP_A, ranges[1].date))
        assertEquals(1, sessions(result, APP_A, ranges[0].date).size)
        assertEquals(1, sessions(result, APP_A, ranges[1].date).size)
        assertDurationInvariant(result, APP_A, ranges[0].date)
        assertDurationInvariant(result, APP_A, ranges[1].date)
    }

    @Test
    fun `频繁切换时所有包合计不超过统计区间`() {
        val range = dayRange(DAY_ONE, BASE, BASE + HOUR)

        val result = aggregate(
            listOf(range),
            event(APP_A, "Main", BASE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_B, "Main", BASE + 10 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_A, "Main", BASE + 20 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED),
            event(APP_B, "Main", BASE + 30 * MINUTE, UsageTimelineEventType.ACTIVITY_RESUMED)
        )
        val total = result.packages.values.sumOf { timeline ->
            timeline.days[DAY_ONE]?.foregroundMillis ?: 0L
        }

        assertEquals(HOUR, total)
        assertTrue(total <= range.durationMillis)
    }

    @Test
    fun `Android 旧别名和隐藏跨日事件映射稳定`() {
        assertEquals(
            UsageTimelineEventType.ACTIVITY_RESUMED,
            UsageTimelineEventType.fromAndroidEventType(1)
        )
        assertEquals(
            UsageTimelineEventType.ACTIVITY_PAUSED,
            UsageTimelineEventType.fromAndroidEventType(2)
        )
        assertEquals(
            UsageTimelineEventType.END_OF_DAY,
            UsageTimelineEventType.fromAndroidEventType(3)
        )
        assertEquals(
            UsageTimelineEventType.CONTINUE_PREVIOUS_DAY,
            UsageTimelineEventType.fromAndroidEventType(4)
        )
        // 无公开 instanceId 时，STOPPED/DESTROYED 可能属于已被同类新 Activity 替代的旧实例。
        assertNull(UsageTimelineEventType.fromAndroidEventType(23))
        assertNull(UsageTimelineEventType.fromAndroidEventType(24))
    }

    private fun aggregate(
        ranges: List<UsageDayRange>,
        vararg events: UsageTimelineEvent
    ): UsageTimelineAggregation = UsageTimelineAggregator.aggregate(events.toList(), ranges)

    private fun foreground(
        result: UsageTimelineAggregation,
        packageName: String,
        date: LocalDate
    ): Long = result.packages[packageName]?.days?.get(date)?.foregroundMillis ?: 0L

    private fun lastUsed(
        result: UsageTimelineAggregation,
        packageName: String,
        date: LocalDate
    ): Long = result.packages[packageName]?.days?.get(date)?.lastTimeUsedMillis ?: 0L

    private fun sessions(
        result: UsageTimelineAggregation,
        packageName: String,
        date: LocalDate
    ): List<UsageSessionSlice> = result.packages[packageName]
        ?.days
        ?.get(date)
        ?.sessions
        .orEmpty()

    private fun assertDurationInvariant(
        result: UsageTimelineAggregation,
        packageName: String,
        date: LocalDate
    ) {
        assertEquals(
            foreground(result, packageName, date),
            sessions(result, packageName, date).sumOf(UsageSessionSlice::durationMillis)
        )
    }

    private fun event(
        packageName: String,
        className: String?,
        timestampMillis: Long,
        type: UsageTimelineEventType
    ) = UsageTimelineEvent(packageName, className, timestampMillis, type)

    private fun systemEvent(
        timestampMillis: Long,
        type: UsageTimelineEventType
    ) = UsageTimelineEvent(null, null, timestampMillis, type)

    private fun dayRange(date: LocalDate, startMillis: Long, endMillis: Long) =
        UsageDayRange(date, startMillis, endMillis)

    private fun twoDays(): List<UsageDayRange> = listOf(
        dayRange(DAY_ONE, BASE, BASE + DAY),
        dayRange(DAY_ONE.plusDays(1L), BASE + DAY, BASE + 2L * DAY)
    )

    private companion object {
        val DAY_ONE: LocalDate = LocalDate.of(2026, 1, 1)
        const val APP_A = "example.a"
        const val APP_B = "example.b"
        const val SYSTEM_UI = "com.android.systemui"
        const val BASE = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }
}
