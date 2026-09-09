package com.example.controlfree.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenIntentPermissionPolicyTest {
    @Test
    fun `android 13 and earlier do not require the special access`() {
        assertFalse(
            shouldRequestFullScreenIntentAccess(
                sdkInt = 33,
                canUseFullScreenIntent = false
            )
        )
    }

    @Test
    fun `android 14 requests access when it is unavailable`() {
        assertTrue(
            shouldRequestFullScreenIntentAccess(
                sdkInt = 34,
                canUseFullScreenIntent = false
            )
        )
    }

    @Test
    fun `android 14 does not request access after it is granted`() {
        assertFalse(
            shouldRequestFullScreenIntentAccess(
                sdkInt = 34,
                canUseFullScreenIntent = true
            )
        )
    }
}
