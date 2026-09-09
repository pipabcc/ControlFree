package com.example.controlfree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalResultExpectationTest {
    @Before
    fun reset() {
        ExternalResultExpectation.clear()
    }

    @Test
    fun `未登记预期时不豁免`() {
        assertFalse(ExternalResultExpectation.isActive(nowElapsedMillis = 1_000L))
    }

    @Test
    fun `窗口内返回豁免生效`() {
        ExternalResultExpectation.expect(nowElapsedMillis = 10_000L)
        assertTrue(ExternalResultExpectation.isActive(nowElapsedMillis = 10_000L + 9L * 60_000L))
    }

    @Test
    fun `超过窗口后不再豁免`() {
        ExternalResultExpectation.expect(nowElapsedMillis = 10_000L)
        assertFalse(ExternalResultExpectation.isActive(nowElapsedMillis = 10_000L + 11L * 60_000L))
    }

    @Test
    fun `时间回拨时不豁免`() {
        ExternalResultExpectation.expect(nowElapsedMillis = 10_000L)
        assertFalse(ExternalResultExpectation.isActive(nowElapsedMillis = 5_000L))
    }

    @Test
    fun `清除后立即失效`() {
        ExternalResultExpectation.expect(nowElapsedMillis = 10_000L)
        ExternalResultExpectation.clear()
        assertFalse(ExternalResultExpectation.isActive(nowElapsedMillis = 10_001L))
    }
}
