package com.example.controlfree.knowledge

import java.security.MessageDigest
import java.util.Collections

/**
 * 增量题包元数据。题包只补充内置题库，不得覆盖内置事实。
 */
internal data class KnowledgePackMetadata(
    val packageId: String,
    val title: String,
    val version: Int,
    val locale: String,
    val publishedAtEpochSeconds: Long,
    val minimumAppVersionCode: Int,
    val questionCount: Int,
    val contentTypes: List<String>,
    val signingKeyId: String,
    val contentSha256: String
)

internal class KnowledgeIncrementPack internal constructor(
    val metadata: KnowledgePackMetadata,
    facts: List<KnowledgeFact>,
    val signature: String
) {
    val facts: List<KnowledgeFact> = immutableList(facts)
}

/** 创建挑战前读取一次；随后即使题包被替换，本次挑战也继续使用该不可变快照。 */
internal class KnowledgeBankSnapshot internal constructor(
    val builtInCount: Int,
    activePacks: List<KnowledgePackMetadata>,
    facts: List<KnowledgeFact>
) {
    val activePacks: List<KnowledgePackMetadata> = immutableList(activePacks)
    val activePack: KnowledgePackMetadata? = this.activePacks.firstOrNull()
    val facts: List<KnowledgeFact> = immutableList(facts)
    val incrementalCount: Int = this.activePacks.sumOf(KnowledgePackMetadata::questionCount)
    val totalCount: Int = this.facts.size
    val categoryCounts: Map<KnowledgeCategory, Int> = Collections.unmodifiableMap(
        KnowledgeCategory.entries.associateWith { category ->
            this.facts.count { it.category == category }
        }
    )
    val difficultyCounts: Map<KnowledgeDifficulty, Int> = Collections.unmodifiableMap(
        KnowledgeDifficulty.entries.associateWith { difficulty ->
            this.facts.count { it.difficulty == difficulty }
        }
    )
    val contentTypes: List<String> = immutableList(
        this.activePacks.flatMap(KnowledgePackMetadata::contentTypes).distinct()
    )

    init {
        require(builtInCount >= 0)
        require(totalCount == builtInCount + incrementalCount)
    }
}

internal data class BundledKnowledgePackExpectation(
    val packageId: String,
    val signingKeyId: String,
    val signature: String,
    val contentSha256: String
)

internal fun interface KnowledgeBankSnapshotProvider {
    fun snapshot(): KnowledgeBankSnapshot
}

internal fun interface KnowledgePackSignatureVerifier {
    /**
     * 验证服务端签名。payload 由 [KnowledgePackCanonicalizer] 确定性生成；生产实现只需
     * 内置公钥，签名私钥必须始终留在发布端。
     */
    fun verify(signingKeyId: String, payload: ByteArray, signature: String): Boolean
}

internal object RejectingKnowledgePackSignatureVerifier : KnowledgePackSignatureVerifier {
    override fun verify(signingKeyId: String, payload: ByteArray, signature: String): Boolean = false
}

internal enum class KnowledgePackTrust {
    /** 在线或外部题包，必须通过生产公钥验签。 */
    PRODUCTION_SIGNED,

    /** 仅允许安装随 APK assets 交付的演示包，不得用于网络响应。 */
    BUNDLED_SAMPLE
}

internal object KnowledgePackCanonicalizer {
    fun canonicalPayload(
        packageId: String,
        title: String,
        version: Int,
        locale: String,
        publishedAtEpochSeconds: Long,
        minimumAppVersionCode: Int,
        contentTypes: List<String>,
        facts: List<KnowledgeFact>
    ): ByteArray = buildString(facts.size * 240) {
        appendField("schema", KnowledgePackJsonParser.SCHEMA_VERSION.toString())
        appendField("package_id", packageId)
        appendField("title", title)
        appendField("version", version.toString())
        appendField("locale", locale)
        appendField("published_at", publishedAtEpochSeconds.toString())
        appendField("minimum_app_version", minimumAppVersionCode.toString())
        contentTypes.sorted().forEach { appendField("content_type", it) }
        facts.sortedBy(KnowledgeFact::id).forEach { fact ->
            appendField("id", fact.id)
            appendField("category", fact.category.wireValue)
            appendField("difficulty", fact.difficulty.level.toString())
            appendField("stem", fact.localStem)
            appendField("answer", fact.canonicalAnswer)
            fact.acceptedAliases.sorted().forEach { appendField("alias", it) }
            fact.localDistractors.forEach { appendField("distractor", it) }
            appendField("explanation", fact.localExplanation)
        }
    }.toByteArray(Charsets.UTF_8)

    fun sha256Hex(payload: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(name.length).append(':').append(name)
        append('=').append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
        append('\n')
    }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(values.toList())
