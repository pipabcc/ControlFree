package com.example.controlfree.knowledge

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 生产题包验签器。App 只保存 X.509 公钥，发布私钥必须留在离线发布环境或受控 CI。
 */
internal class EcdsaKnowledgePackSignatureVerifier(
    x509PublicKeysById: Map<String, String>
) : KnowledgePackSignatureVerifier {
    private val publicKeysById = x509PublicKeysById.mapValues { (_, encoded) ->
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(encoded))
        )
    }

    override fun verify(signingKeyId: String, payload: ByteArray, signature: String): Boolean {
        val publicKey = publicKeysById[signingKeyId] ?: return false
        return try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(payload)
            verifier.verify(Base64.getDecoder().decode(signature))
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: RuntimeException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    private companion object {
        const val KEY_ALGORITHM = "EC"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
