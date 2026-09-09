package com.example.controlfree.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretHasherTest {
    @Test
    fun `正确凭据能够通过验证`() {
        val stored = SecretHasher.create("246810")

        assertTrue(SecretHasher.verify("246810", stored))
        assertFalse(SecretHasher.verify("000000", stored))
    }

    @Test
    fun `相同凭据使用随机盐生成不同结果`() {
        val first = SecretHasher.create("246810")
        val second = SecretHasher.create("246810")

        assertNotEquals(first.saltBase64, second.saltBase64)
        assertNotEquals(first.hashBase64, second.hashBase64)
    }

    @Test
    fun `空值或错误长度的持久化哈希不可视为有效凭据`() {
        val valid = SecretHasher.create("246810")

        assertTrue(SecretHasher.isStructurallyValid(valid))
        assertFalse(SecretHasher.isStructurallyValid(SecretHash("", valid.hashBase64)))
        assertFalse(SecretHasher.isStructurallyValid(SecretHash(valid.saltBase64, "AA==")))
        assertFalse(SecretHasher.isStructurallyValid(SecretHash("not-base64", valid.hashBase64)))
    }
}
