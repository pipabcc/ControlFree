package com.example.controlfree.growth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GrowthRuntimeStoreTest {
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
    }

    @After
    fun tearDown() {
        check(preferences.edit().clear().commit())
    }

    @Test
    fun 多个待结算周期跨运行和进程恢复后都可独立完成() {
        var nextRun = 1
        val store = GrowthRuntimeStore(preferences) { "run-${nextRun++}" }
        store.startNewRun()
        val first = store.beginLockCycle(1L, "global", 600L)
        assertTrue(store.markPendingOutcome(first.cycleId, GrowthCycleOutcome.SKIPPED))

        store.startNewRun()
        val second = store.beginLockCycle(2L, "app", 900L)
        assertTrue(store.markPendingOutcome(second.cycleId, GrowthCycleOutcome.CANCELLED))
        val third = store.beginLockCycle(3L, "global", 1_200L)
        assertTrue(store.markPendingOutcome(third.cycleId, GrowthCycleOutcome.TIMER_COMPLETED))

        val restoredStore = GrowthRuntimeStore(preferences) { "unused-run" }
        val restored = restoredStore.pendingSettlementCycles()
        assertEquals(setOf(first.cycleId, second.cycleId, third.cycleId), restored.map { it.cycleId }.toSet())
        assertEquals(
            GrowthCycleOutcome.SKIPPED,
            restored.single { it.cycleId == first.cycleId }.pendingOutcome
        )

        assertTrue(restoredStore.completePendingSettlement(first.cycleId))
        assertEquals(third.cycleId, restoredStore.activeCycle()?.cycleId)
        assertTrue(restoredStore.completeCycle(second.cycleId))
        assertEquals(listOf(third.cycleId), restoredStore.pendingSettlementCycles().map { it.cycleId })
        assertTrue(restoredStore.completePendingSettlement(third.cycleId))
        assertNull(restoredStore.activeCycle())
        assertTrue(restoredStore.pendingSettlementCycles().isEmpty())
        assertFalse(restoredStore.completePendingSettlement("missing-cycle"))
    }

    @Test
    fun 旧周期已移入待结算队列后仍可撤销候选结果() {
        val store = GrowthRuntimeStore(preferences) { "run-clear" }
        val first = store.beginLockCycle(10L, "global", 600L)
        assertTrue(store.markPendingOutcome(first.cycleId, GrowthCycleOutcome.SKIPPED))
        val second = store.beginLockCycle(11L, "global", 600L)

        assertTrue(store.clearPendingOutcome(first.cycleId))

        assertTrue(store.pendingSettlementCycles().isEmpty())
        assertEquals(second.cycleId, store.activeCycle()?.cycleId)
        assertNull(store.activeCycle()?.pendingOutcome)
    }

    @Test
    fun 恢复仍在锁定的周期会撤销尚未生效的候选结算() {
        val store = GrowthRuntimeStore(preferences) { "run-restore" }
        val cycle = store.beginLockCycle(20L, "global", 600L)
        assertTrue(store.markPendingOutcome(cycle.cycleId, GrowthCycleOutcome.RECOVERY_UNCERTAIN))

        val restored = store.restoreOrBeginLockCycle(21L, "global", 600L)

        assertEquals(cycle.cycleId, restored.cycleId)
        assertEquals(21L, restored.lockSessionId)
        assertNull(restored.pendingOutcome)
        assertTrue(store.pendingSettlementCycles().isEmpty())
    }

    private companion object {
        const val PREFERENCES_NAME = "growth_runtime_store_test"
    }
}
