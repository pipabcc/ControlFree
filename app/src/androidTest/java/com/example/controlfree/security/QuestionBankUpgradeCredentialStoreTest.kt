package com.example.controlfree.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionBankUpgradeCredentialStoreTest {
    private lateinit var preferencesName: String
    private lateinit var context: Context
    private lateinit var store: QuestionBankUpgradeCredentialStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "question_bank_credential_test_${UUID.randomUUID()}"
        store = QuestionBankUpgradeCredentialStore(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun 独立密码只保存哈希并可验证() {
        store.setPassword("246810")

        assertTrue(store.isConfigured())
        assertEquals(6, store.passwordLength())
        assertEquals(VerificationStatus.SUCCESS, store.verifyPassword("246810").status)
        assertEquals(VerificationStatus.FAILURE, store.verifyPassword("135790").status)
        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all.values
        assertFalse(raw.contains("246810"))
    }

    @Test
    fun 连续失败五次后独立锁定三十秒() {
        store.setPassword("246810")
        repeat(4) {
            assertEquals(
                VerificationStatus.FAILURE,
                store.verifyPassword("000000", nowEpochMillis = 1_000L).status
            )
        }

        val locked = store.verifyPassword("000000", nowEpochMillis = 1_000L)
        assertEquals(VerificationStatus.LOCKED, locked.status)
        assertEquals(30, locked.retryAfterSeconds)
        assertEquals(30, store.lockoutRemainingSeconds(nowEpochMillis = 1_000L))
        assertEquals(
            VerificationStatus.SUCCESS,
            store.verifyPassword("246810", nowEpochMillis = 31_001L).status
        )
    }

    @Test
    fun 清除后题库凭据不影响其他凭据仓() {
        store.setPassword("246810")
        store.clear()

        assertFalse(store.isConfigured())
        assertNull(store.passwordLength())
    }
}
