package com.example.controlfree.diagnostics

import android.content.Context
import android.provider.Settings

data class BootEvidence(
    val bootCount: Int,
    val receivedAtMillis: Long
)

class BootEvidenceStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun recordBootCompleted(receivedAtMillis: Long = System.currentTimeMillis()): Boolean {
        val bootCount = try {
            Settings.Global.getInt(
                appContext.contentResolver,
                Settings.Global.BOOT_COUNT,
                UNKNOWN_BOOT_COUNT
            )
        } catch (_: RuntimeException) {
            UNKNOWN_BOOT_COUNT
        }
        return preferences.edit()
            .putInt(KEY_BOOT_COUNT, bootCount)
            .putLong(KEY_RECEIVED_AT_MILLIS, receivedAtMillis.coerceAtLeast(0L))
            .commit()
    }

    fun read(): BootEvidence? = try {
        val receivedAtMillis = preferences.getLong(KEY_RECEIVED_AT_MILLIS, 0L)
        if (receivedAtMillis <= 0L) {
            null
        } else {
            BootEvidence(
                bootCount = preferences.getInt(KEY_BOOT_COUNT, UNKNOWN_BOOT_COUNT),
                receivedAtMillis = receivedAtMillis
            )
        }
    } catch (_: RuntimeException) {
        null
    }

    companion object {
        const val UNKNOWN_BOOT_COUNT = -1
        private const val PREFERENCES_NAME = "controlfree_boot_evidence"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_RECEIVED_AT_MILLIS = "received_at_millis"
    }
}
