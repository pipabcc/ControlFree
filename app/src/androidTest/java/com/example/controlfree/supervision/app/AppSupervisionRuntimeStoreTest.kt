package com.example.controlfree.supervision.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSupervisionRuntimeStoreTest {
    private lateinit var context: Context
    private lateinit var store: AppSupervisionRuntimeStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPreferences().edit().clear().commit()
        store = AppSupervisionRuntimeStore(context)
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
    }

    @Test
    fun 活动快照可以完整往返并要求进入App时验证() {
        val rule = rule(occurrenceEndEpochMillis = 100_000L)
        val snapshot = AppSupervisionRuntimeSnapshot(
            rules = listOf(rule),
            states = listOf(state(rule)),
            savedAtEpochMillis = 10_000L
        )

        assertTrue(store.save(snapshot))
        assertEquals(AppSupervisionSnapshotReadResult.Available(snapshot), store.read())
        assertTrue(store.requiresAppAuthentication(50_000L))
        assertFalse(store.requiresAppAuthentication(100_000L))
    }

    @Test
    fun 仅App触发规则的快照可以完整往返但不要求进入应用时验证() {
        val triggerRule = AppTriggerRule(
            planId = "global-trigger",
            planUpdatedAtEpochMillis = 7L,
            planName = "应用触发监督",
            packageNames = setOf("example.video", "example.music"),
            occurrenceEndEpochMillis = 100_000L
        )
        val snapshot = AppSupervisionRuntimeSnapshot(
            rules = emptyList(),
            states = emptyList(),
            savedAtEpochMillis = 10_000L,
            triggerRules = listOf(triggerRule)
        )

        assertTrue(store.save(snapshot))
        assertEquals(AppSupervisionSnapshotReadResult.Available(snapshot), store.read())
        assertFalse(store.requiresAppAuthentication(50_000L))
    }

    @Test
    fun 损坏快照拒绝读取且按仍有监督要求验证() {
        assertTrue(
            rawPreferences().edit()
                .putString(KEY_RUNTIME_SNAPSHOT, "not-valid-base64")
                .commit()
        )

        assertEquals(AppSupervisionSnapshotReadResult.Corrupted, store.read())
        assertTrue(store.requiresAppAuthentication(Long.MAX_VALUE))
    }

    @Test
    fun 清除后不再保留运行状态() {
        val rule = rule(occurrenceEndEpochMillis = Long.MAX_VALUE)
        assertTrue(
            store.save(
                AppSupervisionRuntimeSnapshot(
                    rules = listOf(rule),
                    states = emptyList(),
                    savedAtEpochMillis = 1L
                )
            )
        )

        assertTrue(store.clear())
        assertEquals(AppSupervisionSnapshotReadResult.None, store.read())
        assertFalse(store.requiresAppAuthentication(1L))
    }

    @Test
    fun 清除运行快照后仍保留当天累计使用状态() {
        val rule = rule(
            occurrenceEndEpochMillis = Long.MAX_VALUE,
            dailyUsageDateEpochDay = 20_000L
        )
        val dailyState = AppSupervisionDailyUsageState(
            planId = rule.planId,
            planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
            localDateEpochDay = rule.dailyUsageDateEpochDay,
            remainingUsageMillis = 45L * 60_000L
        )
        assertTrue(
            store.save(
                AppSupervisionRuntimeSnapshot(
                    rules = listOf(rule),
                    states = emptyList(),
                    savedAtEpochMillis = 1L,
                    dailyUsageStates = listOf(dailyState)
                )
            )
        )

        assertTrue(store.clear())

        assertEquals(AppSupervisionSnapshotReadResult.None, store.read())
        assertEquals(
            AppSupervisionDailyUsageReadResult.Available(listOf(dailyState)),
            store.readDailyUsageStates()
        )
    }

    private fun rawPreferences() = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    private fun rule(
        occurrenceEndEpochMillis: Long,
        dailyUsageDateEpochDay: Long = 0L
    ) = AppSupervisionRule(
        planId = "video-plan",
        planUpdatedAtEpochMillis = 7L,
        planName = "视频监督",
        packageName = "example.video",
        occurrenceEndEpochMillis = occurrenceEndEpochMillis,
        usageAllowanceMillis = 30L * 60_000L,
        restDurationMillis = 10L * 60_000L,
        dailyUsageLimitMillis = 120L * 60_000L,
        dailyUsageDateEpochDay = dailyUsageDateEpochDay
    )

    private fun state(rule: AppSupervisionRule) = AppSupervisionRuntimeState(
        planId = rule.planId,
        planUpdatedAtEpochMillis = rule.planUpdatedAtEpochMillis,
        occurrenceEndEpochMillis = rule.occurrenceEndEpochMillis,
        phase = AppSupervisionPhase.ALLOWANCE,
        remainingAllowanceMillis = 20L * 60_000L,
        restUntilEpochMillis = 0L,
        checkpointElapsedMillis = 5_000L,
        bootCount = 3,
        wasTargetForeground = true,
        wasInteractive = true
    )

    private companion object {
        const val PREFERENCES_NAME = "app_supervision_runtime"
        const val KEY_RUNTIME_SNAPSHOT = "runtime_snapshot_v1"
    }
}
