package com.example.controlfree.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAllowlistManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var hadStoredAllowlist = false
    private var originalEntries: Set<String> = emptySet()

    @Before
    fun setUp() {
        hadStoredAllowlist = preferences.contains(CUSTOM_ALLOWLIST)
        originalEntries = preferences.getStringSet(CUSTOM_ALLOWLIST, emptySet()).orEmpty().toSet()
        assertTrue(preferences.edit().remove(CUSTOM_ALLOWLIST).commit())
    }

    @After
    fun tearDown() {
        val editor = preferences.edit()
        if (hadStoredAllowlist) {
            editor.putStringSet(CUSTOM_ALLOWLIST, originalEntries)
        } else {
            editor.remove(CUSTOM_ALLOWLIST)
        }
        editor.commit()
    }

    @Test
    fun readFiltersInvalidEntriesWithoutRewritingRawAllowlist() {
        val storedEntries = setOf("test.package.that.does.not.exist|invalid-signature")
        assertTrue(
            preferences.edit().putStringSet(CUSTOM_ALLOWLIST, storedEntries).commit()
        )

        val packages = AppAllowlistManager(context).getCustomPackages()

        assertTrue(packages.isEmpty())
        assertEquals(storedEntries, preferences.getStringSet(CUSTOM_ALLOWLIST, emptySet()))
    }

    private companion object {
        const val PREFERENCES_NAME = "control_free_prefs"
        const val CUSTOM_ALLOWLIST = "custom_app_allowlist"
    }
}
