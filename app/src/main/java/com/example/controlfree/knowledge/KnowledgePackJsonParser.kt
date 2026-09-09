package com.example.controlfree.knowledge

internal enum class KnowledgePackRejectionReason {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    INVALID_METADATA,
    INCOMPATIBLE_APP_VERSION,
    INVALID_FACT,
    QUESTION_COUNT_MISMATCH,
    DUPLICATE_FACT_ID,
    DUPLICATE_CONTENT,
    CONFLICTS_WITH_BUILT_IN,
    SIGNATURE_REQUIRED,
    SIGNATURE_INVALID
}

internal sealed interface KnowledgePackParseResult {
    data class Accepted(val pack: KnowledgeIncrementPack) : KnowledgePackParseResult
    data class Rejected(val reason: KnowledgePackRejectionReason) : KnowledgePackParseResult
}

/** 严格题包解析器：拒绝未知字段、重复字段、越界结构和任何局部脏数据。 */
internal class KnowledgePackJsonParser(
    private val currentAppVersionCode: Int,
    builtInFacts: List<KnowledgeFact> = OfflineKnowledgeBank.facts,
    private val signatureVerifier: KnowledgePackSignatureVerifier =
        RejectingKnowledgePackSignatureVerifier
) {
    private val builtInIds = builtInFacts.map(KnowledgeFact::id).toSet()
    private val builtInContent = builtInFacts.map(::contentFingerprint).toSet()

    fun parse(
        rawJson: String,
        trust: KnowledgePackTrust,
        bundledExpectation: BundledKnowledgePackExpectation? = null
    ): KnowledgePackParseResult {
        val root = StrictKnowledgeJson.parseObject(rawJson, PACK_LIMITS)
            ?: return rejected(KnowledgePackRejectionReason.MALFORMED_JSON)
        if (root.keys != ROOT_KEYS) return rejected(KnowledgePackRejectionReason.MALFORMED_JSON)
        if (root.strictInt("schema_version") != SCHEMA_VERSION) {
            return rejected(KnowledgePackRejectionReason.UNSUPPORTED_SCHEMA)
        }
        val metadata = root["metadata"]?.strictObject()
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        if (metadata.keys != METADATA_KEYS) {
            return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        }

        val packageId = metadata.strictString("package_id")
            ?.takeIf(PACKAGE_ID_PATTERN::matches)
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val title = metadata.strictString("title")
            ?.takeIf { isCleanText(it, 2, 48) }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val version = metadata.strictInt("version")
            ?.takeIf { it in 1..MAX_VERSION }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val locale = metadata.strictString("locale")
            ?.takeIf { it == SUPPORTED_LOCALE }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val publishedAt = metadata.strictLong("published_at_epoch_seconds")
            ?.takeIf { it > 0L }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val minimumAppVersion = metadata.strictInt("minimum_app_version_code")
            ?.takeIf { it > 0 }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        if (minimumAppVersion > currentAppVersionCode) {
            return rejected(KnowledgePackRejectionReason.INCOMPATIBLE_APP_VERSION)
        }
        val declaredCount = metadata.strictInt("question_count")
            ?.takeIf { it in 1..MAX_FACTS }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val contentTypes = parseStringArray(metadata["content_types"])
            ?.takeIf { types ->
                types.isNotEmpty() &&
                    types.size <= MAX_CONTENT_TYPES &&
                    types.toSet().size == types.size &&
                    types.all(CONTENT_TYPE_PATTERN::matches)
            }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val signingKeyId = metadata.strictString("signing_key_id")
            ?.takeIf { SIGNING_KEY_PATTERN.matches(it) }
            ?: return rejected(KnowledgePackRejectionReason.INVALID_METADATA)
        val signature = root.strictString("signature")
            ?.takeIf { it.length in 8..MAX_SIGNATURE_CHARS }
            ?: return rejected(KnowledgePackRejectionReason.SIGNATURE_REQUIRED)

        val factValues = root.strictArray("facts")
            ?: return rejected(KnowledgePackRejectionReason.MALFORMED_JSON)
        if (factValues.size != declaredCount) {
            return rejected(KnowledgePackRejectionReason.QUESTION_COUNT_MISMATCH)
        }
        val facts = ArrayList<KnowledgeFact>(factValues.size)
        for (value in factValues) {
            val fact = parseFact(value.strictObject())
                ?: return rejected(KnowledgePackRejectionReason.INVALID_FACT)
            facts += fact
        }
        if (facts.map(KnowledgeFact::id).toSet().size != facts.size) {
            return rejected(KnowledgePackRejectionReason.DUPLICATE_FACT_ID)
        }
        if (facts.map(::contentFingerprint).toSet().size != facts.size) {
            return rejected(KnowledgePackRejectionReason.DUPLICATE_CONTENT)
        }
        if (facts.any { it.id in builtInIds || contentFingerprint(it) in builtInContent }) {
            return rejected(KnowledgePackRejectionReason.CONFLICTS_WITH_BUILT_IN)
        }

        val payload = KnowledgePackCanonicalizer.canonicalPayload(
            packageId = packageId,
            title = title,
            version = version,
            locale = locale,
            publishedAtEpochSeconds = publishedAt,
            minimumAppVersionCode = minimumAppVersion,
            contentTypes = contentTypes,
            facts = facts
        )
        when (trust) {
            KnowledgePackTrust.PRODUCTION_SIGNED -> if (
                !signatureVerifier.verify(signingKeyId, payload, signature)
            ) {
                return rejected(KnowledgePackRejectionReason.SIGNATURE_INVALID)
            }
            KnowledgePackTrust.BUNDLED_SAMPLE -> {
                val expected = bundledExpectation ?: LEGACY_BUNDLED_EXPECTATION
                if (
                    packageId != expected.packageId ||
                    signingKeyId != expected.signingKeyId ||
                    signature != expected.signature ||
                    KnowledgePackCanonicalizer.sha256Hex(payload) != expected.contentSha256
                ) {
                    return rejected(KnowledgePackRejectionReason.SIGNATURE_INVALID)
                }
            }
        }
        val packMetadata = KnowledgePackMetadata(
            packageId = packageId,
            title = title,
            version = version,
            locale = locale,
            publishedAtEpochSeconds = publishedAt,
            minimumAppVersionCode = minimumAppVersion,
            questionCount = facts.size,
            contentTypes = contentTypes.toList(),
            signingKeyId = signingKeyId,
            contentSha256 = KnowledgePackCanonicalizer.sha256Hex(payload)
        )
        return KnowledgePackParseResult.Accepted(
            KnowledgeIncrementPack(packMetadata, facts, signature)
        )
    }

    private fun parseFact(values: Map<String, StrictKnowledgeJson.Value>?): KnowledgeFact? {
        if (values == null || values.keys != FACT_KEYS) return null
        val id = values.strictString("id") ?: return null
        val categoryWire = values.strictString("category") ?: return null
        val category = KnowledgeCategory.entries.firstOrNull { it.wireValue == categoryWire }
            ?: return null
        val difficultyLevel = values.strictInt("difficulty") ?: return null
        val difficulty = KnowledgeDifficulty.entries.firstOrNull { it.level == difficultyLevel }
            ?: return null
        val aliases = parseStringArray(values["accepted_aliases"]) ?: return null
        if (aliases.size > MAX_ALIASES || aliases.toSet().size != aliases.size) return null
        val distractors = parseStringArray(values["distractors"]) ?: return null
        return try {
            KnowledgeFact(
                id = id,
                category = category,
                difficulty = difficulty,
                localStem = values.strictString("stem") ?: return null,
                canonicalAnswer = values.strictString("answer") ?: return null,
                acceptedAliases = aliases.toSet(),
                localDistractors = distractors,
                localExplanation = values.strictString("explanation") ?: return null
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseStringArray(value: StrictKnowledgeJson.Value?): List<String>? =
        (value as? StrictKnowledgeJson.Value.ArrayValue)?.values?.map { item ->
            (item as? StrictKnowledgeJson.Value.StringValue)?.value ?: return null
        }

    private fun isCleanText(value: String, minLength: Int, maxLength: Int): Boolean =
        value == KnowledgeContentPolicy.sanitizeDisplayText(value) &&
            value.codePointCount(0, value.length) in minLength..maxLength

    private fun contentFingerprint(fact: KnowledgeFact): String =
        KnowledgeContentPolicy.comparisonKey(fact.localStem)

    private fun rejected(reason: KnowledgePackRejectionReason) =
        KnowledgePackParseResult.Rejected(reason)

    internal companion object {
        const val SCHEMA_VERSION = 1
        const val BUNDLED_SAMPLE_ASSET_PATH = "knowledge_packs/english_vocabulary_100_v1.json"
        const val BUNDLED_SAMPLE_PACKAGE_ID = "sample_english_vocabulary_100"
        const val BUNDLED_SAMPLE_KEY_ID = "bundled_sample_test_v1"
        const val BUNDLED_SAMPLE_SIGNATURE = "BUNDLED_SAMPLE_ONLY_DO_NOT_DISTRIBUTE"
        const val BUNDLED_SAMPLE_CONTENT_SHA256 =
            "e715f69e782f57a2bc4458f0dd936c842bdebedad7a633b59ad98fa92cbea66a"

        val LEGACY_BUNDLED_EXPECTATION = BundledKnowledgePackExpectation(
            packageId = BUNDLED_SAMPLE_PACKAGE_ID,
            signingKeyId = BUNDLED_SAMPLE_KEY_ID,
            signature = BUNDLED_SAMPLE_SIGNATURE,
            contentSha256 = BUNDLED_SAMPLE_CONTENT_SHA256
        )

        private const val SUPPORTED_LOCALE = "zh-CN"
        // 单个逻辑题包允许覆盖 3000 题规模；严格字符数、深度、字段和哈希校验仍生效。
        private const val MAX_FACTS = 3_500
        private const val MAX_ALIASES = 8
        private const val MAX_CONTENT_TYPES = 16
        private const val MAX_VERSION = 1_000_000_000
        private const val MAX_SIGNATURE_CHARS = 2_048
        private val PACKAGE_ID_PATTERN = Regex("[a-z][a-z0-9_]{2,63}")
        private val CONTENT_TYPE_PATTERN = Regex("[a-z][a-z0-9_]{2,47}")
        private val SIGNING_KEY_PATTERN = Regex("[a-z][a-z0-9_]{2,63}")
        private val ROOT_KEYS = setOf("schema_version", "metadata", "facts", "signature")
        private val METADATA_KEYS = setOf(
            "package_id",
            "title",
            "version",
            "locale",
            "published_at_epoch_seconds",
            "minimum_app_version_code",
            "question_count",
            "content_types",
            "signing_key_id"
        )
        private val FACT_KEYS = setOf(
            "id",
            "category",
            "difficulty",
            "stem",
            "answer",
            "accepted_aliases",
            "distractors",
            "explanation"
        )
        private val PACK_LIMITS = StrictKnowledgeJson.Limits(
            maxJsonChars = 4_000_000,
            maxStringChars = 2_048,
            maxArrayItems = MAX_FACTS,
            maxDepth = 12
        )
    }
}

private fun Map<String, StrictKnowledgeJson.Value>.strictLong(name: String): Long? =
    (this[name] as? StrictKnowledgeJson.Value.NumberValue)?.raw?.toLongOrNull()
