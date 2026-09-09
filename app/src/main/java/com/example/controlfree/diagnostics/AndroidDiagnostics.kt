package com.example.controlfree.diagnostics

import android.content.Context
import java.io.File
import java.util.concurrent.CompletableFuture

object AndroidDiagnostics {
    @Volatile
    private var instance: DiagnosticLogStore? = null

    fun getLogStore(context: Context): DiagnosticLogStore {
        reusableInstance()?.let { return it }
        return synchronized(this) {
            val existing = reusableInstance()
            if (existing != null) {
                existing
            } else {
                createLogStore(context.applicationContext).also { newStore ->
                    instance = newStore
                }
            }
        }
    }

    fun record(
        context: Context,
        type: DiagnosticEventType,
        packageName: String? = null,
        occurredAtEpochMillis: Long = System.currentTimeMillis()
    ): CompletableFuture<DiagnosticWriteResult> = try {
        getLogStore(context).record(type, packageName, occurredAtEpochMillis)
    } catch (_: RuntimeException) {
        CompletableFuture.completedFuture(DiagnosticWriteResult.STORAGE_FAILURE)
    }

    fun read(context: Context): CompletableFuture<DiagnosticReadResult> = try {
        getLogStore(context).read()
    } catch (_: RuntimeException) {
        CompletableFuture.completedFuture(
            DiagnosticReadResult(emptyList(), 0, 1)
        )
    }

    fun clear(context: Context): CompletableFuture<DiagnosticClearResult> = try {
        getLogStore(context).clear()
    } catch (_: RuntimeException) {
        CompletableFuture.completedFuture(DiagnosticClearResult.STORAGE_FAILURE)
    }

    private fun reusableInstance(): DiagnosticLogStore? {
        val current = instance ?: return null
        if (!current.isClosedForFactory()) return current
        if (!current.isTerminatedForFactory()) {
            throw IllegalStateException("diagnostic store is closing")
        }
        return null
    }

    private fun createLogStore(context: Context): DiagnosticLogStore =
        DiagnosticLogStore.create(
            directory = File(context.noBackupFilesDir, DIAGNOSTICS_DIRECTORY_NAME),
            keyFile = File(
                File(context.noBackupFilesDir, DIAGNOSTIC_SECRETS_DIRECTORY_NAME),
                DiagnosticLogStore.HMAC_KEY_FILE_NAME
            )
        )

    private const val DIAGNOSTICS_DIRECTORY_NAME = "diagnostics"
    private const val DIAGNOSTIC_SECRETS_DIRECTORY_NAME = "diagnostic-secrets"
}
