package com.example.controlfree.security

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialMethodTest {
    @Test
    fun `设置手势后优先手势并保留数字密码入口`() {
        assertEquals(
            listOf(CredentialMethod.GESTURE, CredentialMethod.PASSWORD),
            preferredCredentialMethods(hasPassword = true, hasGesture = true)
        )
    }

    @Test
    fun `未设置手势时直接使用数字密码`() {
        assertEquals(
            listOf(CredentialMethod.PASSWORD),
            preferredCredentialMethods(hasPassword = true, hasGesture = false)
        )
    }

    @Test
    fun `只设置手势时不显示数字密码入口`() {
        assertEquals(
            listOf(CredentialMethod.GESTURE),
            preferredCredentialMethods(hasPassword = false, hasGesture = true)
        )
    }

    @Test
    fun `没有凭据时没有可验证方式`() {
        assertEquals(
            emptyList<CredentialMethod>(),
            preferredCredentialMethods(hasPassword = false, hasGesture = false)
        )
    }
}
