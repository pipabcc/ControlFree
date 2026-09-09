package com.example.controlfree.ui.main

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistPersistenceTest {
    @Test
    fun `保存使用独立调度器和点击时的选择快照`() {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "allowlist-save-test")
        }.asCoroutineDispatcher()
        val selectedPackages = mutableSetOf("example.one")
        var persistedPackages: Set<String>? = null
        var persistenceThreadName = ""

        try {
            runBlocking {
                persistAllowlistSelection(selectedPackages, dispatcher) { snapshot ->
                    persistenceThreadName = Thread.currentThread().name
                    persistedPackages = snapshot
                    selectedPackages += "example.two"
                }
            }
        } finally {
            dispatcher.close()
        }

        assertEquals(setOf("example.one"), persistedPackages)
        assertNotSame(selectedPackages, persistedPackages)
        assertTrue(persistenceThreadName.startsWith("allowlist-save-test"))
    }

    @Test
    fun `调用方生命周期取消后不会继续回写界面`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        var uiCallbackInvoked = false

        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                persistAllowlistSelection(setOf("example.one"), dispatcher) {
                    persistenceStarted.countDown()
                    releasePersistence.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                uiCallbackInvoked = true
            }

            assertTrue(
                persistenceStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )
            job.cancel()
            releasePersistence.countDown()
            job.cancelAndJoin()

            assertFalse(uiCallbackInvoked)
        } finally {
            releasePersistence.countDown()
            dispatcher.close()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 5L
    }
}
