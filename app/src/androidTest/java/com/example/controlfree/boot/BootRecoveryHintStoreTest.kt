package com.example.controlfree.boot

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootRecoveryHintStoreTest {
    private lateinit var store: BootRecoveryHintStore

    @Before
    fun setUp() {
        store = BootRecoveryHintStore(ApplicationProvider.getApplicationContext())
        store.replaceRuntimeHints(
            monitorRecoveryRequired = false,
            appSupervisionRecoveryRequired = false
        )
    }

    @After
    fun tearDown() {
        store.replaceRuntimeHints(
            monitorRecoveryRequired = false,
            appSupervisionRecoveryRequired = false
        )
    }

    @Test
    fun `监督和App监督提示可以独立更新`() {
        assertTrue(store.setMonitorRecoveryRequired(true))
        var hint = store.read()
        assertTrue(hint.monitorRecoveryRequired)
        assertFalse(hint.appSupervisionRecoveryRequired)

        assertTrue(store.setAppSupervisionRecoveryRequired(true))
        assertTrue(store.setMonitorRecoveryRequired(false))
        hint = store.read()
        assertFalse(hint.monitorRecoveryRequired)
        assertTrue(hint.appSupervisionRecoveryRequired)
    }
}
