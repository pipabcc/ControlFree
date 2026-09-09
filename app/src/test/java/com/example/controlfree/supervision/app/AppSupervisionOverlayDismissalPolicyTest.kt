package com.example.controlfree.supervision.app

import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation
import com.example.controlfree.data.ForegroundObservationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSupervisionOverlayDismissalPolicyTest {
    private val rule = AppSupervisionRule(
        planId = "maps",
        planUpdatedAtEpochMillis = 1_000L,
        planName = "地图监督",
        packageName = "com.example.maps",
        occurrenceEndEpochMillis = 100_000L,
        usageAllowanceMillis = 60_000L,
        restDurationMillis = 60_000L
    )
    private val blocked = BlockedAppSupervision(rule, remainingRestMillis = 60_000L)
    private val dismissal = AppSupervisionOverlayDismissalPolicy.dismiss(blocked, 20_000L)

    @Test
    fun `退出后旧目标观察不会重新显示悬浮层`() {
        val reconciled = AppSupervisionOverlayDismissalPolicy.reconcile(
            dismissal,
            observation(
                packageName = rule.packageName,
                transitionTimestampMillis = 10_000L
            )
        )

        assertNotNull(reconciled)
        assertTrue(AppSupervisionOverlayDismissalPolicy.shouldSuppress(reconciled, blocked))
    }

    @Test
    fun `查询暂时不可用时继续抑制旧悬浮层`() {
        val reconciled = AppSupervisionOverlayDismissalPolicy.reconcile(
            dismissal,
            ForegroundObservation.unavailable(ForegroundObservationStatus.TIMEOUT)
        )

        assertNotNull(reconciled)
        assertTrue(AppSupervisionOverlayDismissalPolicy.shouldSuppress(reconciled, blocked))
    }

    @Test
    fun `明确识别到其他前台应用后仍防止旧目标状态重新覆盖桌面`() {
        val reconciled = AppSupervisionOverlayDismissalPolicy.reconcile(
            dismissal,
            observation(packageName = "com.example.launcher", transitionTimestampMillis = 21_000L)
        )

        assertNotNull(reconciled)
        assertTrue(AppSupervisionOverlayDismissalPolicy.shouldSuppress(reconciled, blocked))
    }

    @Test
    fun `目标应用产生新的前台事件后恢复监督悬浮层`() {
        val reconciled = AppSupervisionOverlayDismissalPolicy.reconcile(
            dismissal,
            observation(
                packageName = rule.packageName,
                transitionTimestampMillis = 21_000L,
                newEvents = listOf(
                    ForegroundAppEvent(
                        packageName = rule.packageName,
                        timestampMillis = 21_000L,
                        kind = ForegroundEventKind.FOREGROUND
                    )
                )
            )
        )

        assertNull(reconciled)
    }

    @Test
    fun `目标应用已在退出后重新前台即使本轮事件已去重也恢复监督`() {
        val reconciled = AppSupervisionOverlayDismissalPolicy.reconcile(
            dismissal,
            observation(
                packageName = rule.packageName,
                transitionTimestampMillis = 21_000L,
                newEvents = emptyList(),
                observedAtEpochMillis = 22_000L
            )
        )

        assertNull(reconciled)
    }

    @Test
    fun `退出前遗留的目标前台状态不能恢复监督悬浮层`() {
        val reconciled = AppSupervisionOverlayDismissalPolicy.reconcile(
            dismissal,
            observation(
                packageName = rule.packageName,
                transitionTimestampMillis = 19_999L,
                newEvents = emptyList(),
                observedAtEpochMillis = 22_000L
            )
        )

        assertNotNull(reconciled)
        assertTrue(AppSupervisionOverlayDismissalPolicy.shouldSuppress(reconciled, blocked))
    }

    @Test
    fun `旧退出状态不能抑制另一条规则`() {
        val otherRule = rule.copy(
            planId = "other",
            packageName = "com.example.other"
        )

        assertFalse(
            AppSupervisionOverlayDismissalPolicy.shouldSuppress(
                dismissal,
                BlockedAppSupervision(otherRule, remainingRestMillis = 60_000L)
            )
        )
    }

    @Test
    fun `悬浮层显示后明确切到桌面时应关闭悬浮层`() {
        assertTrue(
            AppSupervisionOverlayDismissalPolicy.shouldDismissVisibleOverlay(
                blocked = blocked,
                shownAtEpochMillis = 20_000L,
                observation = observation(
                    packageName = "com.example.launcher",
                    transitionTimestampMillis = 21_000L,
                    observedAtEpochMillis = 22_000L
                )
            )
        )
    }

    @Test
    fun `悬浮层显示前已经完成的旧查询不能关闭悬浮层`() {
        assertFalse(
            AppSupervisionOverlayDismissalPolicy.shouldDismissVisibleOverlay(
                blocked = blocked,
                shownAtEpochMillis = 20_000L,
                observation = observation(
                    packageName = "com.example.launcher",
                    transitionTimestampMillis = 19_000L,
                    observedAtEpochMillis = 19_500L
                )
            )
        )
    }

    @Test
    fun `前台转场早于显示但显示后新查询确认已离开时应关闭悬浮层`() {
        assertTrue(
            AppSupervisionOverlayDismissalPolicy.shouldDismissVisibleOverlay(
                blocked = blocked,
                shownAtEpochMillis = 20_000L,
                observation = observation(
                    packageName = "com.example.launcher",
                    transitionTimestampMillis = 19_000L,
                    observedAtEpochMillis = 21_000L
                )
            )
        )
    }

    @Test
    fun `目标仍在前台或查询失败时不能关闭悬浮层`() {
        assertFalse(
            AppSupervisionOverlayDismissalPolicy.shouldDismissVisibleOverlay(
                blocked = blocked,
                shownAtEpochMillis = 20_000L,
                observation = observation(
                    packageName = rule.packageName,
                    transitionTimestampMillis = 21_000L,
                    observedAtEpochMillis = 22_000L
                )
            )
        )
        assertFalse(
            AppSupervisionOverlayDismissalPolicy.shouldDismissVisibleOverlay(
                blocked = blocked,
                shownAtEpochMillis = 20_000L,
                observation = ForegroundObservation.unavailable(
                    ForegroundObservationStatus.TIMEOUT
                )
            )
        )
    }

    private fun observation(
        packageName: String?,
        transitionTimestampMillis: Long,
        newEvents: List<ForegroundAppEvent> = emptyList(),
        observedAtEpochMillis: Long = 22_000L
    ) = ForegroundObservation(
        packageName = packageName,
        transitionTimestampMillis = transitionTimestampMillis,
        newEvents = newEvents,
        status = ForegroundObservationStatus.AVAILABLE,
        observedAtEpochMillis = observedAtEpochMillis
    )
}
