package com.example.controlfree.knowledge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgePackJsonParserTest {
    private val acceptingVerifier = KnowledgePackSignatureVerifier { _, _, _ -> true }
    private val parser = KnowledgePackJsonParser(
        currentAppVersionCode = 17,
        signatureVerifier = acceptingVerifier
    )

    @Test
    fun `100道英语词汇示例包结构完整且无重复`() {
        val result = parser.parse(sampleJson(), KnowledgePackTrust.BUNDLED_SAMPLE)
        val pack = (result as KnowledgePackParseResult.Accepted).pack

        assertEquals(100, pack.facts.size)
        assertEquals("英语词汇测试示例包", pack.metadata.title)
        assertEquals(listOf("english_vocabulary"), pack.metadata.contentTypes)
        assertEquals(100, pack.facts.map(KnowledgeFact::id).toSet().size)
        assertEquals(100, pack.facts.map { it.localStem }.toSet().size)
        assertTrue(pack.facts.all { it.category in KnowledgeCategory.entries })
        assertEquals(
            KnowledgePackJsonParser.BUNDLED_SAMPLE_CONTENT_SHA256,
            pack.metadata.contentSha256
        )
    }

    @Test
    fun `未知分类会让整个题包失败`() {
        val broken = sampleJson().replaceFirst(
            "\"category\": \"literature_art\"",
            "\"category\": \"language_learning\""
        )

        assertRejected(broken, KnowledgePackRejectionReason.INVALID_FACT)
    }

    @Test
    fun `重复事实ID会让整个题包失败`() {
        val broken = sampleJson().replaceFirst(
            "\"id\": \"en_vocab_002_ability\"",
            "\"id\": \"en_vocab_001_abandon\""
        )

        assertRejected(broken, KnowledgePackRejectionReason.DUPLICATE_FACT_ID)
    }

    @Test
    fun `与内置题干冲突时拒绝安装`() {
        val broken = sampleJson().replaceFirst(
            "英文单词“abandon”最接近以下哪个中文意思？",
            "水的化学式是什么？"
        )

        assertRejected(broken, KnowledgePackRejectionReason.CONFLICTS_WITH_BUILT_IN)
    }

    @Test
    fun `在线题包必须通过生产签名验证`() {
        val rejectingParser = KnowledgePackJsonParser(
            currentAppVersionCode = 17,
            signatureVerifier = RejectingKnowledgePackSignatureVerifier
        )

        val result = rejectingParser.parse(
            sampleJson(),
            KnowledgePackTrust.PRODUCTION_SIGNED
        )

        assertEquals(
            KnowledgePackRejectionReason.SIGNATURE_INVALID,
            (result as KnowledgePackParseResult.Rejected).reason
        )
    }

    @Test
    fun `题包声明的最低应用版本过高时拒绝安装`() {
        val broken = sampleJson().replaceFirst(
            "\"minimum_app_version_code\": 17",
            "\"minimum_app_version_code\": 999"
        )

        assertRejected(broken, KnowledgePackRejectionReason.INCOMPATIBLE_APP_VERSION)
    }

    @Test
    fun `示例信任通道不能安装任意外部包`() {
        val broken = sampleJson().replaceFirst(
            "\"package_id\": \"sample_english_vocabulary_100\"",
            "\"package_id\": \"untrusted_download\""
        )

        assertRejected(broken, KnowledgePackRejectionReason.SIGNATURE_INVALID)
    }

    @Test
    fun `示例题包内容或版本被改写后固定哈希校验失败`() {
        val changedAnswer = sampleJson().replaceFirst(
            "\"answer\": \"放弃\"",
            "\"answer\": \"抛弃\""
        )
        val changedVersion = sampleJson().replaceFirst("\"version\": 1", "\"version\": 2")

        assertRejected(changedAnswer, KnowledgePackRejectionReason.SIGNATURE_INVALID)
        assertRejected(changedVersion, KnowledgePackRejectionReason.SIGNATURE_INVALID)
    }

    @Test
    fun `六个中文百科五百题包及英语千题包均通过固定哈希且内容不重复`() {
        val packs = AndroidKnowledgeBankRepository.BUNDLED_PACKS.map { item ->
            val raw = File("src/main/assets/${item.assetPath}").readText(Charsets.UTF_8)
            val result = parser.parse(raw, KnowledgePackTrust.BUNDLED_SAMPLE, item.expectation)
            (result as KnowledgePackParseResult.Accepted).pack
        }
        val englishPack = packs.single {
            it.metadata.packageId == "local_english_vocabulary_1000_v1"
        }
        val chinesePacks = packs - englishPack

        assertEquals(6, chinesePacks.size)
        assertEquals(
            KnowledgeCategory.entries.toSet(),
            chinesePacks.map { it.facts.singleCategory() }.toSet()
        )
        assertTrue(chinesePacks.all { it.facts.size == 500 })
        assertTrue(chinesePacks.all { it.metadata.questionCount == 500 })
        assertTrue(chinesePacks.all { it.metadata.title.endsWith("500 题") })
        assertTrue(chinesePacks.all { "knowledge_quiz" in it.metadata.contentTypes })
        assertTrue(chinesePacks.all { "english_vocabulary" !in it.metadata.contentTypes })
        assertTrue(chinesePacks.all { pack ->
            pack.facts.all { fact ->
                "英文单词" !in fact.localStem &&
                    fact.localStem.any { character -> character in '\u3400'..'\u9fff' }
            }
        })

        assertEquals(1_000, englishPack.facts.size)
        assertEquals("英语核心词汇考察 1000 题", englishPack.metadata.title)
        assertTrue("english_vocabulary" in englishPack.metadata.contentTypes)

        val allFacts = packs.flatMap { it.facts }
        assertEquals(4_000, allFacts.size)
        assertEquals(4_000, allFacts.map(KnowledgeFact::id).toSet().size)
        assertEquals(4_000, allFacts.map { it.localStem }.toSet().size)
        assertFalse(chinesePacks.any { it.metadata.title.contains("目录词汇") })
    }

    @Test
    fun `三千题逻辑包在严格结构和签名验证下可解析`() {
        val result = parser.parse(largePackJson(3_000), KnowledgePackTrust.PRODUCTION_SIGNED)

        assertEquals(3_000, (result as KnowledgePackParseResult.Accepted).pack.facts.size)
    }

    private fun assertRejected(raw: String, expected: KnowledgePackRejectionReason) {
        val result = parser.parse(raw, KnowledgePackTrust.BUNDLED_SAMPLE)
        assertEquals(expected, (result as KnowledgePackParseResult.Rejected).reason)
    }

    private fun sampleJson(): String = File(
        "src/main/assets/${KnowledgePackJsonParser.BUNDLED_SAMPLE_ASSET_PATH}"
    ).readText(Charsets.UTF_8)

    private fun List<KnowledgeFact>.singleCategory(): KnowledgeCategory =
        map(KnowledgeFact::category).toSet().single()

    private fun largePackJson(count: Int): String = buildString(count * 340) {
        append("{\"schema_version\":1,\"metadata\":{")
        append("\"package_id\":\"scale_pack_3000\",")
        append("\"title\":\"三千题规模验证包\",\"version\":1,\"locale\":\"zh-CN\",")
        append("\"published_at_epoch_seconds\":1784505600,")
        append("\"minimum_app_version_code\":17,\"question_count\":").append(count).append(',')
        append("\"content_types\":[\"scale_validation\"],")
        append("\"signing_key_id\":\"production_test_key\"},\"facts\":[")
        repeat(count) { index ->
            if (index > 0) append(',')
            val suffix = index.toString().padStart(4, '0')
            append("{\"id\":\"scale_fact_").append(suffix).append("\",")
            append("\"category\":\"technology\",\"difficulty\":2,")
            append("\"stem\":\"规模测试题目 ").append(suffix).append(" 的正确答案是什么？\",")
            append("\"answer\":\"答案").append(suffix).append("\",\"accepted_aliases\":[],")
            append("\"distractors\":[\"干扰甲").append(suffix).append("\",\"干扰乙")
                .append(suffix).append("\",\"干扰丙").append(suffix).append("\"],")
            append("\"explanation\":\"用于验证三千题逻辑包的安全解析能力。\"}")
        }
        append("],\"signature\":\"PRODUCTION_TEST_SIGNATURE\"}")
    }
}
