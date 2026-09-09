package com.example.controlfree.supervision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSupervisionEnforcementPolicyTest {
    @Test
    fun `只有正向确认的受保护包才能绕过监督`() {
        assertTrue(AppSupervisionEnforcementPolicy.isProtectedPackageBypassAllowed(true))
        assertFalse(AppSupervisionEnforcementPolicy.isProtectedPackageBypassAllowed(false))
        assertFalse(AppSupervisionEnforcementPolicy.isProtectedPackageBypassAllowed(null))
    }

    @Test
    fun `安全恢复请求后在可信休息截止前保留阻止`() {
        val trusted = trustedState()

        val active = AppSupervisionEnforcementPolicy.recoveryFallbackBlock(
            state = trusted,
            nowWallEpochMillis = 59_999L
        )
        val expired = AppSupervisionEnforcementPolicy.recoveryFallbackBlock(
            state = trusted,
            nowWallEpochMillis = 60_000L
        )

        assertEquals(1L, active?.remainingRestMillis)
        assertNull(expired)
    }

    @Test
    fun `TIMEOUT 在宽限期内保留最近可信阻止`() {
        val trusted = trustedState()

        val decision = reduce(
            previous = trusted,
            status = AppSupervisionObservationStatus.TIMEOUT,
            wall = 10_000L,
            elapsed = 10_000L
        )

        assertEquals("example.video", decision.blocked?.rule?.packageName)
        assertEquals(50_000L, decision.blocked?.remainingRestMillis)
        assertTrue(decision.observationDegraded)
        assertFalse(decision.requestSafeRecovery)
    }

    @Test
    fun `CIRCUIT_OPEN 在宽限边界内保留阻止且过期后请求安全回退`() {
        val trusted = trustedState()
        val grace = 100L

        val atBoundary = reduce(
            previous = trusted,
            status = AppSupervisionObservationStatus.CIRCUIT_OPEN,
            wall = 40_000L,
            elapsed = grace,
            grace = grace
        )
        assertEquals("example.video", atBoundary.blocked?.rule?.packageName)
        assertFalse(atBoundary.requestSafeRecovery)

        val expired = reduce(
            previous = atBoundary.state,
            status = AppSupervisionObservationStatus.CIRCUIT_OPEN,
            wall = 40_001L,
            elapsed = grace + 1L,
            grace = grace
        )
        assertNull(expired.blocked)
        assertTrue(expired.requestSafeRecovery)

        val repeated = reduce(
            previous = expired.state,
            status = AppSupervisionObservationStatus.CIRCUIT_OPEN,
            wall = 40_002L,
            elapsed = grace + 2L,
            grace = grace
        )
        assertNull(repeated.blocked)
        assertFalse(repeated.requestSafeRecovery)
    }

    @Test
    fun `QUERY_FAILED 与 ACCESS_DENIED 采用同一保守窗口`() {
        val trusted = trustedState()

        listOf(
            AppSupervisionObservationStatus.QUERY_FAILED,
            AppSupervisionObservationStatus.ACCESS_DENIED
        ).forEach { status ->
            val decision = reduce(
                previous = trusted,
                status = status,
                wall = 20_000L,
                elapsed = 20_000L
            )
            assertEquals(status.name, "example.video", decision.blocked?.rule?.packageName)
            assertTrue(decision.observationDegraded)
        }
    }

    @Test
    fun `AVAILABLE 明确观察到其他App后才清除阻止`() {
        val trusted = trustedState()

        val decision = reduce(
            previous = trusted,
            status = AppSupervisionObservationStatus.AVAILABLE,
            foreground = "example.chat",
            candidateBlocked = null,
            wall = 10_000L,
            elapsed = 10_000L
        )

        assertNull(decision.blocked)
        assertNull(decision.state.trustedBlock)
        assertFalse(decision.observationDegraded)
    }

    @Test
    fun `AVAILABLE 但前台包未知时不能清除可信阻止`() {
        val decision = reduce(
            previous = trustedState(),
            status = AppSupervisionObservationStatus.AVAILABLE,
            foreground = null,
            wall = 10_000L,
            elapsed = 10_000L
        )

        assertEquals("example.video", decision.blocked?.rule?.packageName)
        assertEquals("example.video", decision.state.trustedBlock?.rule?.packageName)
        assertTrue(decision.observationDegraded)
        assertFalse(decision.requestSafeRecovery)
    }

    @Test
    fun `AVAILABLE 重新确认目标App时刷新可信阻止时间`() {
        val trusted = trustedState()

        val decision = reduce(
            previous = trusted,
            status = AppSupervisionObservationStatus.AVAILABLE,
            foreground = "example.video",
            candidateBlocked = blocked(remaining = 45_000L),
            wall = 20_000L,
            elapsed = 20_000L
        )

        assertEquals("example.video", decision.blocked?.rule?.packageName)
        assertEquals(20_000L, decision.state.lastConfirmedElapsedMillis)
        assertEquals(65_000L, decision.state.validUntilEpochMillis)
        assertFalse(decision.observationDegraded)
    }

    @Test
    fun `休息墙钟结束后即使观察异常也不继续阻止`() {
        val trusted = trustedState()

        val decision = reduce(
            previous = trusted,
            status = AppSupervisionObservationStatus.TIMEOUT,
            wall = 60_001L,
            elapsed = 10_000L
        )

        assertNull(decision.blocked)
        assertNull(decision.state.trustedBlock)
        assertFalse(decision.requestSafeRecovery)
    }

    @Test
    fun `熄屏不清除可信阻止，亮屏后由观察结果决定`() {
        val trusted = trustedState()

        val screenOff = reduce(
            previous = trusted,
            status = AppSupervisionObservationStatus.AVAILABLE,
            interactive = false,
            foreground = "example.video",
            candidateBlocked = null,
            wall = 10_000L,
            elapsed = 10_000L
        )
        assertNull(screenOff.blocked)
        assertEquals("example.video", screenOff.state.trustedBlock?.rule?.packageName)

        val screenOn = reduce(
            previous = screenOff.state,
            status = AppSupervisionObservationStatus.AVAILABLE,
            interactive = true,
            foreground = "example.chat",
            candidateBlocked = null,
            wall = 11_000L,
            elapsed = 11_000L
        )
        assertNull(screenOn.state.trustedBlock)
    }

    @Test
    fun `单调时钟回退不延长旧阻止而触发安全恢复`() {
        val decision = reduce(
            previous = trustedState().copy(lastConfirmedElapsedMillis = 1_000L),
            status = AppSupervisionObservationStatus.TIMEOUT,
            wall = 10_000L,
            elapsed = 0L
        )

        assertNull(decision.blocked)
        assertTrue(decision.requestSafeRecovery)
    }

    @Test
    fun `从未获得可信前台结果时持续不可用也触发一次安全恢复`() {
        val first = reduce(
            previous = AppSupervisionEnforcementState(),
            status = AppSupervisionObservationStatus.QUERY_FAILED,
            wall = 1_000L,
            elapsed = 1_000L
        )
        assertFalse(first.requestSafeRecovery)
        assertTrue(first.observationDegraded)

        val expired = reduce(
            previous = first.state,
            status = AppSupervisionObservationStatus.CIRCUIT_OPEN,
            wall = 31_001L,
            elapsed = 31_001L
        )
        assertNull(expired.blocked)
        assertTrue(expired.requestSafeRecovery)
        assertNull(expired.recoveryRule)

        val repeated = reduce(
            previous = expired.state,
            status = AppSupervisionObservationStatus.TIMEOUT,
            wall = 31_002L,
            elapsed = 31_002L
        )
        assertFalse(repeated.requestSafeRecovery)
    }

    @Test
    fun `同一开机周期恢复可信阻止并按墙钟重算剩余时间`() {
        val rule = blocked(60_000L).rule
        val persisted = AppSupervisionEnforcementState(
            trustedBlock = BlockedAppSupervision(rule, 55_000L),
            validUntilEpochMillis = 60_000L,
            lastConfirmedElapsedMillis = 4_000L,
            unavailableSinceElapsedMillis = 5_000L,
            recoveryRequested = true
        )

        val restored = AppSupervisionEnforcementPolicy.restore(
            persisted = persisted,
            rules = listOf(rule),
            states = listOf(restState(rule, bootCount = 7)),
            nowWallEpochMillis = 20_000L,
            nowElapsedMillis = 10_000L,
            currentBootCount = 7
        )

        assertEquals(40_000L, restored.trustedBlock?.remainingRestMillis)
        assertEquals(4_000L, restored.lastConfirmedElapsedMillis)
        assertEquals(5_000L, restored.unavailableSinceElapsedMillis)
        assertFalse(restored.recoveryRequested)
    }

    @Test
    fun `跨开机周期恢复时重新建立单调时钟基线`() {
        val rule = blocked(60_000L).rule
        val restored = AppSupervisionEnforcementPolicy.restore(
            persisted = trustedState().copy(recoveryRequested = true),
            rules = listOf(rule),
            states = listOf(restState(rule, bootCount = 6)),
            nowWallEpochMillis = 20_000L,
            nowElapsedMillis = 2_000L,
            currentBootCount = 7
        )

        assertEquals(40_000L, restored.trustedBlock?.remainingRestMillis)
        assertEquals(2_000L, restored.lastConfirmedElapsedMillis)
        assertFalse(restored.recoveryRequested)
    }

    @Test
    fun `旧快照仅从最后确认在前台的休息状态推导阻止`() {
        val rule = blocked(60_000L).rule
        val restored = AppSupervisionEnforcementPolicy.restore(
            persisted = null,
            rules = listOf(rule),
            states = listOf(restState(rule, bootCount = 7)),
            nowWallEpochMillis = 20_000L,
            nowElapsedMillis = 10_000L,
            currentBootCount = 7
        )

        assertEquals("example.video", restored.trustedBlock?.rule?.packageName)
        assertEquals(40_000L, restored.trustedBlock?.remainingRestMillis)
        assertEquals(10_000L, restored.lastConfirmedElapsedMillis)
    }

    @Test
    fun `新快照明确为空时不从休息状态推导旧阻止`() {
        val rule = blocked(60_000L).rule
        val restored = AppSupervisionEnforcementPolicy.restore(
            persisted = AppSupervisionEnforcementState(),
            rules = listOf(rule),
            states = listOf(restState(rule, bootCount = 7)),
            nowWallEpochMillis = 20_000L,
            nowElapsedMillis = 10_000L,
            currentBootCount = 7
        )

        assertNull(restored.trustedBlock)
    }

    private fun reduce(
        previous: AppSupervisionEnforcementState,
        status: AppSupervisionObservationStatus,
        foreground: String? = null,
        interactive: Boolean = true,
        candidateBlocked: BlockedAppSupervision? = null,
        wall: Long,
        elapsed: Long,
        grace: Long = AppSupervisionEnforcementPolicy.STALE_BLOCK_GRACE_MILLIS
    ) = AppSupervisionEnforcementPolicy.reduce(
        previous = previous,
        candidateBlocked = candidateBlocked,
        observation = AppSupervisionObservation(
            isAvailable = status == AppSupervisionObservationStatus.AVAILABLE,
            foregroundPackage = foreground,
            isInteractive = interactive,
            status = status
        ),
        nowWallEpochMillis = wall,
        nowElapsedMillis = elapsed,
        staleBlockGraceMillis = grace
    )

    private fun trustedState(): AppSupervisionEnforcementState {
        val block = blocked(remaining = 60_000L)
        return AppSupervisionEnforcementState(
            trustedBlock = block,
            validUntilEpochMillis = 60_000L,
            lastConfirmedElapsedMillis = 0L
        )
    }

    private fun blocked(remaining: Long) = BlockedAppSupervision(
        rule = AppSupervisionRule(
            planId = "video",
            planUpdatedAtEpochMillis = 1L,
            planName = "video",
            packageName = "example.video",
            occurrenceEndEpochMillis = 120_000L,
            usageAllowanceMillis = 60_000L,
            restDurationMillis = 60_000L
        ),
        remainingRestMillis = remaining
    )

    private fun restState(
        rule: AppSupervisionRule,
        bootCount: Int
    ) = AppSupervisionRuntimeState(
        planId = rule.planId,
        planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
        occurrenceEndEpochMillis = rule.occurrenceEndEpochMillis,
        phase = AppSupervisionPhase.REST,
        remainingAllowanceMillis = 0L,
        restUntilEpochMillis = 60_000L,
        checkpointElapsedMillis = 5_000L,
        bootCount = bootCount,
        wasTargetForeground = true,
        wasInteractive = true
    )
}
