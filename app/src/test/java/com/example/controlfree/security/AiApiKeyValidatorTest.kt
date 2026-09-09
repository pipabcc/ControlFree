package com.example.controlfree.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiApiKeyValidatorTest {
    @Test
    fun `合法密钥允许去除复制产生的首尾空格`() {
        assertEquals(
            "sk-test-key-1234567890",
            AiApiKeyValidator.normalize("  sk-test-key-1234567890  ")
        )
    }

    @Test
    fun `拒绝非DeepSeek前缀与请求头控制字符`() {
        assertNull(AiApiKeyValidator.normalize("token-test-key-1234567890"))
        assertNull(AiApiKeyValidator.normalize("sk-test-key-1234\r\nInjected"))
        assertNull(AiApiKeyValidator.normalize("sk-short"))
    }
}
