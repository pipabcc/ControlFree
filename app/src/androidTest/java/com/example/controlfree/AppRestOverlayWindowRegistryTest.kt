package com.example.controlfree

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRestOverlayWindowRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val windowManager = context.getSystemService(WindowManager::class.java)

    @After
    fun tearDown() {
        runOnMain { AppRestOverlayWindowRegistry.hideAny() }
        waitUntil { AppRestOverlayWindowRegistry.registeredWindowCount() == 0 }
    }

    @Test
    fun serviceRecreationAdoptsOneWindowAndHomeRemovalReleasesIt() {
        assumeTrue(hasOverlayPermission())
        val firstOwner = Any()
        val secondOwner = Any()
        var createdWindowCount = 0

        assertEquals(
            OverlayShowResult.SHOWN,
            runOnMain { showWindow(firstOwner) { createdWindowCount++ } }
        )
        assertEquals(1, AppRestOverlayWindowRegistry.registeredWindowCount())

        assertEquals(
            OverlayShowResult.SHOWN,
            runOnMain { showWindow(secondOwner) { createdWindowCount++ } }
        )
        assertEquals(1, createdWindowCount)
        assertEquals(1, AppRestOverlayWindowRegistry.registeredWindowCount())

        runOnMain { AppRestOverlayWindowRegistry.hide(secondOwner) }
        assertTrue(waitUntil { AppRestOverlayWindowRegistry.registeredWindowCount() == 0 })
    }

    @Test
    fun removalSettlementDefersSecondWindowUntilTheFirstIsFullyReleased() {
        assumeTrue(hasOverlayPermission())
        val firstOwner = Any()
        val secondOwner = Any()
        var createdWindowCount = 0

        assertEquals(
            OverlayShowResult.SHOWN,
            runOnMain { showWindow(firstOwner) { createdWindowCount++ } }
        )
        runOnMain { AppRestOverlayWindowRegistry.hide(firstOwner) }

        assertEquals(
            OverlayShowResult.DEFERRED,
            runOnMain { showWindow(secondOwner) { createdWindowCount++ } }
        )
        assertEquals(1, createdWindowCount)
        assertTrue(waitUntil { AppRestOverlayWindowRegistry.registeredWindowCount() == 0 })

        assertEquals(
            OverlayShowResult.SHOWN,
            runOnMain { showWindow(secondOwner) { createdWindowCount++ } }
        )
        assertEquals(2, createdWindowCount)
        assertEquals(1, AppRestOverlayWindowRegistry.registeredWindowCount())
    }

    private fun showWindow(owner: Any, onCreated: () -> Unit): OverlayShowResult =
        AppRestOverlayWindowRegistry.show(
            owner = owner,
            windowManager = windowManager,
            createWindow = {
                onCreated()
                AppRestOverlayWindowSpec(
                    handle = AppRestOverlayWindowHandle(FrameLayout(context)),
                    layoutParams = WindowManager.LayoutParams(
                        32,
                        32,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    )
                )
            },
            canAdoptWindow = { true },
            updateWindow = { _: AppRestOverlayWindowHandle -> }
        )

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(context) ||
            context.getSystemService(android.app.AppOpsManager::class.java).checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                context.applicationInfo.uid,
                context.packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED

    private fun waitUntil(
        timeoutMillis: Long = 3_000L,
        condition: () -> Boolean
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    private fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = java.util.concurrent.CompletableFuture<T>()
        Handler(Looper.getMainLooper()).post {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result.get(5L, java.util.concurrent.TimeUnit.SECONDS)
    }
}
