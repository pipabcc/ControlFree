package com.example.controlfree.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiApiKeyStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testId = UUID.randomUUID().toString()
    private val preferencesName = "ai_key_store_test_$testId"
    private val keyAlias = "ai_key_store_test_$testId"
    private val store = AiApiKeyStore(context, keyAlias, preferencesName)

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    @Test
    fun apiKeyRoundTripStoresOnlyCiphertext() {
        val apiKey = "sk-" + "instrumented-test-key-123456"

        assertEquals(AiApiKeyWriteResult.SAVED, store.save(apiKey))
        val readResult = store.read()

        assertTrue(readResult is AiApiKeyReadResult.Available)
        assertEquals(apiKey, (readResult as AiApiKeyReadResult.Available).apiKey)
        val storedText = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.joinToString(separator = "|")
        assertFalse(storedText.contains(apiKey))
    }

    @Test
    fun overwritingKeyUsesFreshCiphertextAndClearRemovesSecret() {
        val firstKey = "sk-" + "instrumented-first-key-12345"
        val secondKey = "sk-" + "instrumented-second-key-1234"

        assertEquals(AiApiKeyWriteResult.SAVED, store.save(firstKey))
        val firstStored = storedStringValues()
        assertEquals(AiApiKeyWriteResult.SAVED, store.save(secondKey))
        val secondStored = storedStringValues()

        assertFalse(firstStored == secondStored)
        assertEquals(
            secondKey,
            (store.read() as AiApiKeyReadResult.Available).apiKey
        )
        assertTrue(store.clear())
        assertEquals(AiApiKeyReadResult.Missing, store.read())
    }

    @Test
    fun corruptedCiphertextFailsClosedAndIsRemoved() {
        val apiKey = "sk-" + "instrumented-corrupt-key-1234"
        assertEquals(AiApiKeyWriteResult.SAVED, store.save(apiKey))
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val ciphertextEntry = preferences.all.entries
            .filter { it.value is String }
            .maxBy { (it.value as String).length }
        preferences.edit().putString(ciphertextEntry.key, "%%%not-base64%%%").commit()

        assertEquals(AiApiKeyReadResult.StorageUnavailable, store.read())
        assertFalse(store.hasSavedKey())
    }

    private fun storedStringValues(): Set<String> =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.filterIsInstance<String>().toSet()
}
