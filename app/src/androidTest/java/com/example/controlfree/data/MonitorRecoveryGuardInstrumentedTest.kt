package com.example.controlfree.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.MonitorCycleSnapshot
import com.example.controlfree.MonitorPhase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorRecoveryGuardInstrumentedTest {
    private lateinit var guard: MonitorRecoveryGuard
    private lateinit var preferences: PreferenceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        guard = MonitorRecoveryGuard(context)
        preferences = PreferenceManager(context)
        preferences.clearMonitorProgress()
        guard.clear()
    }

    @After
    fun tearDown() {
        guard.clear()
        preferences.clearMonitorProgress()
    }

    @Test
    fun 恢复标记同步写入并可清除() {
        assertFalse(guard.requiresLock())

        assertTrue(guard.markLockRequired())
        assertTrue(guard.requiresLock())

        assertTrue(guard.clear())
        assertFalse(guard.requiresLock())
    }

    @Test
    fun 已提交且匹配的检查点不会把玩机快照恢复成完整锁定() {
        val snapshot = MonitorCycleSnapshot(
            phase = MonitorPhase.USAGE,
            remainingMillis = 10 * 60_000L,
            checkpointElapsedMillis = 123_456L,
            bootCount = 8,
            isInteractive = true
        )

        assertTrue(guard.prepare(snapshot))
        assertTrue(guard.requiresLock(snapshot))
        assertTrue(preferences.saveMonitorProgress(snapshot))
        assertTrue(guard.commit(snapshot))

        assertFalse(guard.requiresLock())
        assertTrue(guard.requiresLock(snapshot.copy(checkpointElapsedMillis = 123_457L)))
    }
}
