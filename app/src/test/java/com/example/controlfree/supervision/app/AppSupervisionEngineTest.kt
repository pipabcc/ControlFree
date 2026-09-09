package com.example.controlfree.supervision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSupervisionEngineTest {
    @Test
    fun `只在目标App前台且亮屏时扣减额度`() {
        val rule = rule("video", "example.video")
        val initial = tick(listOf(rule), emptyList(), "example.video", interactive = true, wall = 1_000L, elapsed = 1_000L)
        val foreground = tick(listOf(rule), initial.states, "example.video", true, 2_000L, 2_000L)
        val otherApp = tick(listOf(rule), foreground.states, "example.chat", true, 3_000L, 3_000L)
        val screenOff = tick(listOf(rule), otherApp.states, "example.video", false, 4_000L, 4_000L)
        val afterScreenOff = tick(listOf(rule), screenOff.states, "example.video", false, 5_000L, 5_000L)

        assertEquals(rule.usageAllowanceMillis - 1_000L, foreground.states.single().remainingAllowanceMillis)
        assertEquals(rule.usageAllowanceMillis - 2_000L, otherApp.states.single().remainingAllowanceMillis)
        assertEquals(otherApp.states.single().remainingAllowanceMillis, screenOff.states.single().remainingAllowanceMillis)
        assertEquals(screenOff.states.single().remainingAllowanceMillis, afterScreenOff.states.single().remainingAllowanceMillis)
    }

    @Test
    fun `额度耗尽后只阻止对应App并按墙钟完成休息`() {
        val video = rule("video", "example.video", allowance = 60_000L, rest = 120_000L)
        val game = rule("game", "example.game", allowance = 60_000L, rest = 60_000L)
        val initial = tick(listOf(video, game), emptyList(), "example.video", true, 1_000L, 1_000L)
        val exhausted = tick(listOf(video, game), initial.states, "example.video", true, 61_000L, 61_000L)

        assertEquals(AppSupervisionPhase.REST, exhausted.states.first { it.planId == "video" }.phase)
        assertEquals("example.video", exhausted.blocked?.rule?.packageName)
        assertEquals(120_000L, exhausted.blocked?.remainingRestMillis)

        val otherApp = tick(listOf(video, game), exhausted.states, "example.game", true, 62_000L, 62_000L)
        assertNull(otherApp.blocked)

        val rested = tick(listOf(video, game), otherApp.states, "example.video", true, 181_000L, 181_000L)
        assertEquals(AppSupervisionPhase.ALLOWANCE, rested.states.first { it.planId == "video" }.phase)
        assertNull(rested.blocked)
    }

    @Test
    fun `多个App拥有互不影响的独立状态`() {
        val video = rule("video", "example.video", allowance = 60_000L)
        val game = rule("game", "example.game", allowance = 120_000L)
        val initial = tick(listOf(video, game), emptyList(), "example.video", true, 1_000L, 1_000L)
        val videoUsed = tick(listOf(video, game), initial.states, "example.video", true, 31_000L, 31_000L)
        val switched = tick(listOf(video, game), videoUsed.states, "example.game", true, 61_000L, 61_000L)
        val gameUsed = tick(listOf(video, game), switched.states, "example.game", true, 91_000L, 91_000L)

        assertEquals(0L, gameUsed.states.first { it.planId == "video" }.remainingAllowanceMillis)
        assertEquals(90_000L, gameUsed.states.first { it.planId == "game" }.remainingAllowanceMillis)
        assertEquals(AppSupervisionPhase.REST, gameUsed.states.first { it.planId == "video" }.phase)
        assertEquals(AppSupervisionPhase.ALLOWANCE, gameUsed.states.first { it.planId == "game" }.phase)
    }

    @Test
    fun `新时段或新版本会重置该规则额度`() {
        val first = rule("video", "example.video", version = 1L, occurrenceEnd = 100_000L)
        val initial = tick(listOf(first), emptyList(), "example.video", true, 1_000L, 1_000L)
        val used = tick(listOf(first), initial.states, "example.video", true, 31_000L, 31_000L)

        val nextOccurrence = first.copy(occurrenceEndEpochMillis = 200_000L)
        val resetByOccurrence = tick(listOf(nextOccurrence), used.states, null, true, 110_000L, 40_000L)
        assertEquals(first.usageAllowanceMillis, resetByOccurrence.states.single().remainingAllowanceMillis)

        val edited = nextOccurrence.copy(planUpdatedAtEpochMillis = 2L)
        val resetByVersion = tick(listOf(edited), resetByOccurrence.states, null, true, 111_000L, 41_000L)
        assertEquals(first.usageAllowanceMillis, resetByVersion.states.single().remainingAllowanceMillis)
    }

    @Test
    fun `时段结束会移除状态且不可再阻止App`() {
        val rule = rule("video", "example.video", occurrenceEnd = 10_000L)
        val initial = tick(listOf(rule), emptyList(), "example.video", true, 1_000L, 1_000L)
        val ended = tick(listOf(rule), initial.states, "example.video", true, 10_000L, 10_000L)

        assertTrue(ended.states.isEmpty())
        assertNull(ended.blocked)
    }

    @Test
    fun `重启后不使用失效的单调时钟差值`() {
        val rule = rule("video", "example.video")
        val initial = tick(
            listOf(rule),
            emptyList(),
            "example.video",
            true,
            wall = 1_000L,
            elapsed = 100_000L,
            bootCount = 1
        )
        val afterReboot = tick(
            listOf(rule),
            initial.states,
            "example.video",
            true,
            wall = 20_000L,
            elapsed = 200_000L,
            bootCount = 2
        )

        assertEquals(rule.usageAllowanceMillis, afterReboot.states.single().remainingAllowanceMillis)
        assertTrue(afterReboot.states.single().wasInteractive)
    }

    @Test
    fun `同机进程恢复会切断未知前台时长避免误扣额度`() {
        val rule = rule("video", "example.video")
        val persisted = state(rule, AppSupervisionPhase.ALLOWANCE).copy(
            remainingAllowanceMillis = 45_000L,
            checkpointElapsedMillis = 1_000L,
            bootCount = 1,
            wasTargetForeground = true,
            wasInteractive = true
        )
        val recovered = resetAppSupervisionObservationContinuity(
            states = listOf(persisted),
            nowElapsedMillis = 100_000L,
            bootCount = 1,
            isInteractive = true
        )

        val firstObservation = tick(
            rules = listOf(rule),
            states = recovered,
            foreground = "example.video",
            interactive = true,
            wall = 100_000L,
            elapsed = 100_000L,
            bootCount = 1
        )

        assertEquals(45_000L, firstObservation.states.single().remainingAllowanceMillis)
        assertTrue(firstObservation.states.single().wasTargetForeground)
    }

    @Test
    fun `前台观察不可用时不误阻止其他App`() {
        val rule = rule("video", "example.video")
        val restState = state(rule, AppSupervisionPhase.REST, restUntil = 100_000L)

        val result = AppSupervisionEngine.tick(
            rules = listOf(rule),
            previousStates = listOf(restState),
            observation = AppSupervisionObservation(false, null, true),
            nowWallEpochMillis = 10_000L,
            nowElapsedMillis = 10_000L,
            bootCount = 1
        )

        assertNull(result.blocked)
        assertEquals(AppSupervisionPhase.REST, result.states.single().phase)
    }

    @Test
    fun `前台观察故障期间不沿用旧前台标记扣减额度`() {
        val rule = rule("video", "example.video", allowance = 60_000L)
        val previous = state(rule, AppSupervisionPhase.ALLOWANCE).copy(
            remainingAllowanceMillis = 60_000L,
            checkpointElapsedMillis = 1_000L,
            wasTargetForeground = true,
            wasInteractive = true
        )

        val result = AppSupervisionEngine.tick(
            rules = listOf(rule),
            previousStates = listOf(previous),
            observation = AppSupervisionObservation(false, null, true),
            nowWallEpochMillis = 500_000L,
            nowElapsedMillis = 500_000L,
            bootCount = 1
        )

        assertEquals(AppSupervisionPhase.ALLOWANCE, result.states.single().phase)
        assertEquals(60_000L, result.states.single().remainingAllowanceMillis)
        assertNull(result.blocked)
    }

    @Test
    fun `未被端点采样覆盖的完整短会话会扣减对应App额度`() {
        val rule = rule("video", "example.video", allowance = 60_000L)
        val initial = AppSupervisionEngine.tick(
            rules = listOf(rule),
            previousStates = emptyList(),
            observation = AppSupervisionObservation(
                isAvailable = true,
                foregroundPackage = "example.home",
                isInteractive = true
            ),
            nowWallEpochMillis = 1_000L,
            nowElapsedMillis = 1_000L,
            bootCount = 1
        )
        val result = AppSupervisionEngine.tick(
            rules = listOf(rule),
            previousStates = initial.states,
            observation = AppSupervisionObservation(
                isAvailable = true,
                foregroundPackage = "example.home",
                isInteractive = true,
                unobservedForegroundMillisByPackage = mapOf("example.video" to 15_000L)
            ),
            nowWallEpochMillis = 2_000L,
            nowElapsedMillis = 2_000L,
            bootCount = 1
        )

        assertEquals(45_000L, result.states.single().remainingAllowanceMillis)
    }

    @Test
    fun `端点计时和额外短会话扣减采用饱和合并并可进入休息`() {
        val rule = rule("video", "example.video", allowance = 60_000L, rest = 60_000L)
        val initial = tick(
            rules = listOf(rule),
            states = emptyList(),
            foreground = "example.video",
            interactive = true,
            wall = 1_000L,
            elapsed = 1_000L
        )
        val result = AppSupervisionEngine.tick(
            rules = listOf(rule),
            previousStates = initial.states,
            observation = AppSupervisionObservation(
                isAvailable = true,
                foregroundPackage = "example.video",
                isInteractive = true,
                unobservedForegroundMillisByPackage = mapOf(
                    "example.video" to Long.MAX_VALUE
                )
            ),
            nowWallEpochMillis = 2_000L,
            nowElapsedMillis = 2_000L,
            bootCount = 1
        )

        assertEquals(AppSupervisionPhase.REST, result.states.single().phase)
        assertEquals(0L, result.states.single().remainingAllowanceMillis)
        assertEquals(AppSupervisionBlockReason.DAILY_LIMIT, result.blocked?.reason)
        assertEquals(998_000L, result.blocked?.remainingRestMillis)
    }

    @Test
    fun `首次建立状态时不会把没有前一端点的额外时长计入额度`() {
        val rule = rule("video", "example.video")
        val result = AppSupervisionEngine.tick(
            rules = listOf(rule),
            previousStates = emptyList(),
            observation = AppSupervisionObservation(
                isAvailable = true,
                foregroundPackage = "example.home",
                isInteractive = true,
                unobservedForegroundMillisByPackage = mapOf("example.video" to 30_000L)
            ),
            nowWallEpochMillis = 1_000L,
            nowElapsedMillis = 1_000L,
            bootCount = 1
        )

        assertEquals(rule.usageAllowanceMillis, result.states.single().remainingAllowanceMillis)
    }

    @Test
    fun `多个执行时段沿用同一天每日累计额度`() {
        val first = rule(
            "video",
            "example.video",
            occurrenceEnd = 100_000L,
            allowance = 120_000L
        ).copy(dailyUsageLimitMillis = 60_000L, dailyUsageDateEpochDay = 10L)
        val initial = AppSupervisionEngine.tick(
            rules = listOf(first),
            previousStates = emptyList(),
            previousDailyUsageStates = emptyList(),
            observation = AppSupervisionObservation(true, "example.video", true),
            nowWallEpochMillis = 1_000L,
            nowElapsedMillis = 1_000L,
            bootCount = 1
        )
        val used = AppSupervisionEngine.tick(
            rules = listOf(first),
            previousStates = initial.states,
            previousDailyUsageStates = initial.dailyUsageStates,
            observation = AppSupervisionObservation(true, "example.video", true),
            nowWallEpochMillis = 31_000L,
            nowElapsedMillis = 31_000L,
            bootCount = 1
        )
        val nextOccurrence = first.copy(
            occurrenceEndEpochMillis = 200_000L
        )
        val resumed = AppSupervisionEngine.tick(
            rules = listOf(nextOccurrence),
            previousStates = used.states,
            previousDailyUsageStates = used.dailyUsageStates,
            observation = AppSupervisionObservation(true, null, true),
            nowWallEpochMillis = 110_000L,
            nowElapsedMillis = 40_000L,
            bootCount = 1
        )

        assertEquals(30_000L, resumed.dailyUsageStates.single().remainingUsageMillis)
        assertEquals(nextOccurrence.usageAllowanceMillis, resumed.states.single().remainingAllowanceMillis)
    }

    @Test
    fun `本地日期变化重置每日累计额度`() {
        val today = rule("video", "example.video", allowance = 120_000L).copy(
            dailyUsageLimitMillis = 60_000L,
            dailyUsageDateEpochDay = 10L
        )
        val yesterdayState = AppSupervisionDailyUsageState(
            planId = today.planId,
            planUpdatedAtEpochMillis = today.planUpdatedAtEpochMillis,
            localDateEpochDay = 9L,
            remainingUsageMillis = 5_000L
        )

        val result = AppSupervisionEngine.tick(
            rules = listOf(today),
            previousStates = emptyList(),
            previousDailyUsageStates = listOf(yesterdayState),
            observation = AppSupervisionObservation(true, null, true),
            nowWallEpochMillis = 1_000L,
            nowElapsedMillis = 1_000L,
            bootCount = 1
        )

        assertEquals(60_000L, result.dailyUsageStates.single().remainingUsageMillis)
    }

    @Test
    fun `禁用时段优先于每日上限且不继续累计用量`() {
        val disabled = rule("video", "example.video", allowance = 120_000L).copy(
            occurrenceEndEpochMillis = 200_000L,
            dailyUsageLimitMillis = 60_000L,
            dailyUsageResetAtEpochMillis = 180_000L,
            disabledUntilEpochMillis = 100_000L
        )
        val daily = AppSupervisionDailyUsageState(
            disabled.planId,
            disabled.planUpdatedAtEpochMillis,
            disabled.dailyUsageDateEpochDay,
            0L
        )
        val result = AppSupervisionEngine.tick(
            rules = listOf(disabled),
            previousStates = listOf(state(disabled, AppSupervisionPhase.ALLOWANCE)),
            previousDailyUsageStates = listOf(daily),
            observation = AppSupervisionObservation(true, disabled.packageName, true),
            nowWallEpochMillis = 10_000L,
            nowElapsedMillis = 10_000L,
            bootCount = 1
        )

        assertEquals(AppSupervisionBlockReason.DISABLED_TIME, result.blocked?.reason)
        assertEquals(0L, result.dailyUsageStates.single().remainingUsageMillis)
    }

    private fun tick(
        rules: List<AppSupervisionRule>,
        states: List<AppSupervisionRuntimeState>,
        foreground: String?,
        interactive: Boolean,
        wall: Long,
        elapsed: Long,
        bootCount: Int = 1
    ) = AppSupervisionEngine.tick(
        rules = rules,
        previousStates = states,
        observation = AppSupervisionObservation(true, foreground, interactive),
        nowWallEpochMillis = wall,
        nowElapsedMillis = elapsed,
        bootCount = bootCount
    )

    private fun rule(
        id: String,
        packageName: String,
        version: Long = 1L,
        occurrenceEnd: Long = 1_000_000L,
        allowance: Long = 60_000L,
        rest: Long = 60_000L
    ) = AppSupervisionRule(
        planId = id,
        planUpdatedAtEpochMillis = version,
        planName = id,
        packageName = packageName,
        occurrenceEndEpochMillis = occurrenceEnd,
        usageAllowanceMillis = allowance,
        restDurationMillis = rest
    )

    private fun state(
        rule: AppSupervisionRule,
        phase: AppSupervisionPhase,
        restUntil: Long = 0L
    ) = AppSupervisionRuntimeState(
        planId = rule.planId,
        planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
        occurrenceEndEpochMillis = rule.occurrenceEndEpochMillis,
        phase = phase,
        remainingAllowanceMillis = if (phase == AppSupervisionPhase.REST) 0L else rule.usageAllowanceMillis,
        restUntilEpochMillis = restUntil,
        checkpointElapsedMillis = 1_000L,
        bootCount = 1,
        wasTargetForeground = true,
        wasInteractive = true
    )
}
