package com.example.controlfree.ui.history

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryResumeRefreshPolicyTest {
    @Test
    fun `首次注册到前台生命周期时不重复刷新`() {
        val policy = HistoryResumeRefreshPolicy()

        assertFalse(policy.onLifecycleEvent(Lifecycle.Event.ON_CREATE))
        assertFalse(policy.onLifecycleEvent(Lifecycle.Event.ON_START))
        assertFalse(policy.onLifecycleEvent(Lifecycle.Event.ON_RESUME))
    }

    @Test
    fun `仅在真正离开前台后恢复时刷新一次`() {
        val policy = HistoryResumeRefreshPolicy()

        assertFalse(policy.onLifecycleEvent(Lifecycle.Event.ON_PAUSE))
        assertFalse(policy.onLifecycleEvent(Lifecycle.Event.ON_STOP))
        assertTrue(policy.onLifecycleEvent(Lifecycle.Event.ON_RESUME))
        assertFalse(policy.onLifecycleEvent(Lifecycle.Event.ON_RESUME))
    }
}
