package com.example.controlfree.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CredentialStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("control_free_prefs", Context.MODE_PRIVATE)

    @Before
    fun clearBeforeTest() {
        preferences.edit().clear().commit()
    }

    @After
    fun clearAfterTest() {
        preferences.edit().clear().commit()
    }

    @Test
    fun setPassword_persistsLengthForAutomaticVerification() {
        val store = CredentialStore(context)

        store.setPassword("246810")

        assertEquals(6, store.getPasswordLength())
        assertTrue(store.verifyPassword("246810").isSuccess)
    }

    @Test
    fun legacyV2Hash_recordsLengthAfterFirstSuccessfulConfirmation() {
        val hashed = SecretHasher.create("135790")
        preferences.edit()
            .putString("password_salt_v2", hashed.saltBase64)
            .putString("password_hash_v2", hashed.hashBase64)
            .putBoolean("initial_password_setup_required", false)
            .commit()
        val store = CredentialStore(context)

        assertNull(store.getPasswordLength())
        assertTrue(store.verifyPassword("135790").isSuccess)
        assertEquals(6, store.getPasswordLength())
    }

    @Test
    fun legacyPlaintext_isMigratedOnlyAfterSuccessfulVerification() {
        preferences.edit()
            .putString("passcode", "864209")
            .commit()

        val store = CredentialStore(context)

        assertTrue(!store.requiresInitialPasswordSetup())
        assertEquals(6, store.getPasswordLength())
        assertTrue(preferences.contains("passcode"))
        assertTrue(store.verifyPassword("864209").isSuccess)
        assertTrue(!preferences.contains("passcode"))
        assertTrue(preferences.contains("password_hash_v2"))
        assertEquals(6, store.getPasswordLength())
    }

    @Test
    fun noCredential_requiresInitialSetupEvenWhenLegacyFlagIsFalse() {
        preferences.edit()
            .putBoolean("initial_password_setup_required", false)
            .commit()

        val store = CredentialStore(context)

        assertFalse(store.hasAnyCredential())
        assertTrue(store.requiresInitialPasswordSetup())
    }

    @Test
    fun malformedStoredHashes_areNotTreatedAsCredentials() {
        preferences.edit()
            .putString("password_salt_v2", "AA==")
            .putString("password_hash_v2", "AA==")
            .putString("gesture_salt_v2", "")
            .putString("gesture_hash_v2", "not-base64")
            .commit()

        val store = CredentialStore(context)

        assertFalse(store.hasPassword())
        assertFalse(store.hasGesture())
        assertFalse(store.hasAnyCredential())
    }

    @Test
    fun wrongPreferenceTypes_failClosedInsteadOfThrowing() {
        preferences.edit()
            .putInt("password_salt_v2", 1)
            .putBoolean("password_hash_v2", true)
            .putLong("gesture_salt_v2", 2L)
            .putInt("gesture_hash_v2", 3)
            .putString("initial_password_setup_required", "false")
            .commit()

        val store = CredentialStore(context)

        assertFalse(store.hasAnyCredential())
        assertTrue(store.requiresInitialPasswordSetup())
    }

    @Test
    fun structurallyValidPasswordAndGestureHashes_areRecognized() {
        val password = SecretHasher.create("246810")
        val gesture = SecretHasher.create("0-1-2-3")
        preferences.edit()
            .putString("password_salt_v2", password.saltBase64)
            .putString("password_hash_v2", password.hashBase64)
            .putString("gesture_salt_v2", gesture.saltBase64)
            .putString("gesture_hash_v2", gesture.hashBase64)
            .commit()

        val store = CredentialStore(context)

        assertTrue(store.hasPassword())
        assertTrue(store.hasGesture())
        assertTrue(store.hasAnyCredential())
        assertFalse(store.requiresInitialPasswordSetup())
    }

    @Test
    fun setupFlagCannotBypassAnExistingValidCredential() {
        val password = SecretHasher.create("246810")
        preferences.edit()
            .putString("password_salt_v2", password.saltBase64)
            .putString("password_hash_v2", password.hashBase64)
            .putBoolean("initial_password_setup_required", true)
            .commit()

        val store = CredentialStore(context)

        assertTrue(store.hasAnyCredential())
        assertFalse(store.requiresInitialPasswordSetup())
        assertFalse(store.verifyPassword("000000").isSuccess)
    }

    @Test
    fun onlyPassword_cannotBeRemoved() {
        val store = CredentialStore(context)
        store.setPassword("246810")

        val result = store.clearPassword()

        assertEquals(CredentialRemovalResult.LAST_CREDENTIAL, result)
        assertTrue(store.hasPassword())
        assertTrue(store.verifyPassword("246810").isSuccess)
    }

    @Test
    fun onlyGesture_cannotBeRemoved() {
        val store = CredentialStore(context)
        val pattern = listOf(0, 1, 2, 3)
        store.setGesture(pattern)

        val result = store.clearGesture()

        assertEquals(CredentialRemovalResult.LAST_CREDENTIAL, result)
        assertTrue(store.hasGesture())
        assertTrue(store.verifyGesture(pattern).isSuccess)
    }

    @Test
    fun oneOfTwoCredentials_canBeRemoved() {
        val store = CredentialStore(context)
        val pattern = listOf(0, 1, 2, 3)
        store.setPassword("246810")
        store.setGesture(pattern)

        assertEquals(CredentialRemovalResult.REMOVED, store.clearPassword())
        assertFalse(store.hasPassword())
        assertTrue(store.hasGesture())
        assertFalse(store.requiresInitialPasswordSetup())
    }

    @Test
    fun removingPassword_doesNotResetFailureCountForRemainingGesture() {
        val store = CredentialStore(context)
        store.setPassword("246810")
        store.setGesture(listOf(0, 1, 2, 3))

        repeat(4) {
            assertEquals(
                VerificationStatus.FAILURE,
                store.verifyGesture(listOf(0, 1, 2, 4)).status
            )
        }

        assertEquals(CredentialRemovalResult.REMOVED, store.clearPassword())
        assertEquals(
            VerificationStatus.LOCKED,
            store.verifyGesture(listOf(0, 1, 2, 4)).status
        )
    }

    @Test
    fun concurrentRemovalAcrossStoreInstances_preservesOneCredential() {
        val firstStore = CredentialStore(context)
        val secondStore = CredentialStore(context)
        firstStore.setPassword("246810")
        firstStore.setGesture(listOf(0, 1, 2, 3))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val passwordRemoval = executor.submit<CredentialRemovalResult> {
                start.await()
                firstStore.clearPassword()
            }
            val gestureRemoval = executor.submit<CredentialRemovalResult> {
                start.await()
                secondStore.clearGesture()
            }

            start.countDown()
            val results = setOf(
                passwordRemoval.get(5, TimeUnit.SECONDS),
                gestureRemoval.get(5, TimeUnit.SECONDS)
            )

            assertEquals(
                setOf(
                    CredentialRemovalResult.REMOVED,
                    CredentialRemovalResult.LAST_CREDENTIAL
                ),
                results
            )
            assertTrue(firstStore.hasAnyCredential())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentVerificationAcrossStoreInstances_preservesEveryFailureCount() {
        val stores = List(5) { CredentialStore(context) }
        stores.first().setPassword("246810")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(stores.size)

        try {
            val attempts = stores.map { store ->
                executor.submit<VerificationStatus> {
                    start.await()
                    store.verifyPassword("000000").status
                }
            }
            start.countDown()

            val statuses = attempts.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(4, statuses.count { it == VerificationStatus.FAILURE })
            assertEquals(1, statuses.count { it == VerificationStatus.LOCKED })
            assertEquals(
                VerificationStatus.LOCKED,
                stores.first().verifyPassword("000000").status
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
