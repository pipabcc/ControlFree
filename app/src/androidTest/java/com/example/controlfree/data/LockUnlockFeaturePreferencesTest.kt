package com.example.controlfree.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LockUnlockFeaturePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(
            LockUnlockFeaturePreferences.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun 两个开关默认开启且独立持久化() {
        val preferences = LockUnlockFeaturePreferences(context)
        assertTrue(preferences.growthUnlockEnabled)
        assertTrue(preferences.knowledgeChallengeEnabled)

        preferences.growthUnlockEnabled = false
        assertFalse(LockUnlockFeaturePreferences(context).growthUnlockEnabled)
        assertTrue(LockUnlockFeaturePreferences(context).knowledgeChallengeEnabled)

        preferences.knowledgeChallengeEnabled = false
        assertFalse(LockUnlockFeaturePreferences(context).growthUnlockEnabled)
        assertFalse(LockUnlockFeaturePreferences(context).knowledgeChallengeEnabled)
    }
}
