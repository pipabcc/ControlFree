package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedAppOpenRequestPolicyTest {
    @Test
    fun `Home 之后到达的旧点击命令被拒绝`() {
        assertFalse(
            AllowedAppOpenRequestPolicy.isCurrent(
                requestedElapsedMillis = 1_000L,
                nowElapsedMillis = 1_100L,
                lastInvalidationElapsedMillis = 1_050L
            )
        )
    }

    @Test
    fun `Home 之后产生的新点击命令可继续校验`() {
        assertTrue(
            AllowedAppOpenRequestPolicy.isCurrent(
                requestedElapsedMillis = 1_100L,
                nowElapsedMillis = 1_120L,
                lastInvalidationElapsedMillis = 1_050L
            )
        )
    }

    @Test
    fun `缺失未来或过期命令均被拒绝`() {
        assertFalse(AllowedAppOpenRequestPolicy.isCurrent(0L, 1_000L, 0L))
        assertFalse(AllowedAppOpenRequestPolicy.isCurrent(1_001L, 1_000L, 0L))
        assertFalse(
            AllowedAppOpenRequestPolicy.isCurrent(
                requestedElapsedMillis = 1L,
                nowElapsedMillis = AllowedAppOpenRequestPolicy.MAX_REQUEST_AGE_MILLIS + 2L,
                lastInvalidationElapsedMillis = 0L
            )
        )
    }
}
