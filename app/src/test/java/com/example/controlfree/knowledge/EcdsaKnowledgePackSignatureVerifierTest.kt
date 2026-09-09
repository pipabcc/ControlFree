package com.example.controlfree.knowledge

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcdsaKnowledgePackSignatureVerifierTest {
    @Test
    fun `只接受已配置公钥对原始载荷生成的签名`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val verifier = EcdsaKnowledgePackSignatureVerifier(
            mapOf("test-key" to Base64.getEncoder().encodeToString(keyPair.public.encoded))
        )
        val payload = "signed-question-pack".toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signature = Base64.getEncoder().encodeToString(signer.sign())

        assertTrue(verifier.verify("test-key", payload, signature))
        assertFalse(verifier.verify("test-key", "changed".toByteArray(), signature))
        assertFalse(verifier.verify("unknown", payload, signature))
        assertFalse(verifier.verify("test-key", payload, "not-base64"))
    }
}
