package com.example.controlfree.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAccessSettingsNavigatorTest {
    @Test
    fun `优先打开当前应用专属设置`() {
        val attempts = mutableListOf<UsageAccessSettingsAttempt>()

        val destination = UsageAccessSettingsNavigator.openWithFallback { attempt ->
            attempts += attempt
            true
        }

        assertEquals(UsageAccessSettingsDestination.APP_SPECIFIC, destination)
        assertEquals(listOf(UsageAccessSettingsAttempt.APP_SPECIFIC), attempts)
    }

    @Test
    fun `专属设置不可用时回退应用列表`() {
        val attempts = mutableListOf<UsageAccessSettingsAttempt>()

        val destination = UsageAccessSettingsNavigator.openWithFallback { attempt ->
            attempts += attempt
            attempt == UsageAccessSettingsAttempt.APP_LIST
        }

        assertEquals(UsageAccessSettingsDestination.APP_LIST, destination)
        assertEquals(
            listOf(
                UsageAccessSettingsAttempt.APP_SPECIFIC,
                UsageAccessSettingsAttempt.APP_LIST
            ),
            attempts
        )
    }

    @Test
    fun `所有入口不可用时返回不可用`() {
        val attempts = mutableListOf<UsageAccessSettingsAttempt>()

        val destination = UsageAccessSettingsNavigator.openWithFallback { attempt ->
            attempts += attempt
            false
        }

        assertEquals(UsageAccessSettingsDestination.UNAVAILABLE, destination)
        assertEquals(
            listOf(
                UsageAccessSettingsAttempt.APP_SPECIFIC,
                UsageAccessSettingsAttempt.APP_LIST
            ),
            attempts
        )
    }
}
