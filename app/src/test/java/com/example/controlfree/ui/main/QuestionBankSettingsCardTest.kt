package com.example.controlfree.ui.main

import com.example.controlfree.knowledge.AndroidKnowledgeBankRepository
import com.example.controlfree.knowledge.KnowledgePackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionBankSettingsCardTest {
    @Test
    fun `旧版小题包显示实际题数并提供更新入口`() {
        val item = AndroidKnowledgeBankRepository.BUNDLED_PACKS.first()
        val presentation = bundledPackPresentation(
            item = item,
            installedMetadata = metadata(
                packageId = item.packageId,
                version = 1,
                questionCount = 17,
                contentSha256 = "legacy-content"
            )
        )

        assertEquals(17, presentation.displayedQuestionCount)
        assertTrue(presentation.updateAvailable)
        assertEquals(
            2,
            AndroidKnowledgeBankRepository.BUNDLED_PACK_EXPECTATIONS.count {
                it.packageId == item.packageId
            }
        )
    }

    @Test
    fun `当前题包不显示更新入口`() {
        val item = AndroidKnowledgeBankRepository.BUNDLED_PACKS.first()
        val presentation = bundledPackPresentation(
            item = item,
            installedMetadata = metadata(
                packageId = item.packageId,
                version = 2,
                questionCount = item.questionCount,
                contentSha256 = item.expectation.contentSha256
            )
        )

        assertEquals(item.questionCount, presentation.displayedQuestionCount)
        assertFalse(presentation.updateAvailable)
    }

    private fun metadata(
        packageId: String,
        version: Int,
        questionCount: Int,
        contentSha256: String
    ) = KnowledgePackMetadata(
        packageId = packageId,
        title = "测试题包",
        version = version,
        locale = "zh-CN",
        publishedAtEpochSeconds = 1L,
        minimumAppVersionCode = 1,
        questionCount = questionCount,
        contentTypes = listOf("knowledge_quiz"),
        signingKeyId = "bundled_local_v1",
        contentSha256 = contentSha256
    )
}
