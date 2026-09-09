package com.example.controlfree.knowledge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBankRepositoryTest {
    private val testDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        testDirectories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `安装示例包后摘要为内置36加增量100`() {
        val repository = repository()

        val result = repository.installBundledSample(sampleJson())
        val summary = repository.summary()

        assertTrue(result is KnowledgeBankInstallResult.Installed)
        assertEquals(36, summary.builtInCount)
        assertEquals(100, summary.incrementalCount)
        assertEquals(136, summary.totalCount)
        assertEquals(106, summary.categoryCounts.getValue(KnowledgeCategory.LITERATURE_ART))
        assertEquals(46, summary.difficultyCounts.getValue(KnowledgeDifficulty.EASY))
        assertEquals(listOf("english_vocabulary"), summary.contentTypes)
        assertEquals(1, summary.activeVersion)
    }

    @Test
    fun `同一题包版本不得降级或重复覆盖`() {
        val repository = repository()
        repository.installBundledSample(sampleJson())

        val repeated = repository.installBundledSample(sampleJson())

        assertEquals(
            KnowledgeBankInstallResult.VersionNotNewer(1, 1),
            repeated
        )
    }

    @Test
    fun `移除增量后立即回到不可删除的内置题库`() {
        val directory = newDirectory()
        val repository = repository(directory)
        repository.installBundledSample(sampleJson())

        assertEquals(KnowledgeBankRemovalResult.Removed, repository.removeIncremental())
        assertFalse(directory.exists())
        assertEquals(36, repository.snapshot().totalCount)
        assertEquals(
            KnowledgeBankRemovalResult.AlreadyBuiltInOnly,
            repository.removeIncremental()
        )
    }

    @Test
    fun `新版本激活后可以原子回退上一版本`() {
        val repository = repository()
        repository.installBundledSample(sampleJson())
        repository.installSigned(versionTwoJson())

        val restored = repository.rollback()

        assertTrue(restored is KnowledgeBankRollbackResult.Restored)
        assertEquals(1, repository.summary().activeVersion)
    }

    @Test
    fun `活动文件损坏时使用上一有效版本并替换损坏文件`() {
        val directory = newDirectory()
        val repository = repository(directory)
        repository.installBundledSample(sampleJson())
        repository.installSigned(versionTwoJson())
        File(directory, "active.kpack").writeText("broken", Charsets.UTF_8)

        assertEquals(1, repository.summary().activeVersion)

        val reloaded = repository(directory)
        assertEquals(1, reloaded.summary().activeVersion)
        assertFalse(File(directory, "active.kpack").readText().startsWith("broken"))
    }

    @Test
    fun `挑战快照在后续安装或删除题包时保持不变`() {
        val repository = repository()
        repository.installBundledSample(sampleJson())
        val challengeSnapshot = repository.snapshot()

        repository.removeIncremental()

        assertEquals(136, challengeSnapshot.totalCount)
        assertEquals(36, repository.snapshot().totalCount)
        assertTrue(challengeSnapshot.facts.none { it.id == "later_mutation" })
    }

    @Test
    fun `多个本地分类包可同时安装并独立启停卸载`() {
        val directory = newDirectory()
        val repository = catalogRepository(directory)
        val science = AndroidKnowledgeBankRepository.BUNDLED_PACKS[0]
        val history = AndroidKnowledgeBankRepository.BUNDLED_PACKS[1]

        assertTrue(
            repository.installBundledPack(catalogJson(science), science.expectation) is
                KnowledgeBankInstallResult.Installed
        )
        assertTrue(
            repository.installBundledPack(catalogJson(history), history.expectation) is
                KnowledgeBankInstallResult.Installed
        )
        assertEquals(1_036, repository.snapshot().totalCount)
        assertEquals(2, repository.summary().installedPacks.size)

        assertEquals(
            KnowledgePackToggleResult.Updated,
            repository.setPackEnabled(science.packageId, false)
        )
        assertEquals(536, repository.snapshot().totalCount)
        assertFalse(repository.summary().installedPacks.first {
            it.metadata.packageId == science.packageId
        }.enabled)

        assertEquals(KnowledgeBankRemovalResult.Removed, repository.removePack(history.packageId))
        assertEquals(36, repository.snapshot().totalCount)
        assertEquals(1, repository.summary().installedPacks.size)
    }

    @Test
    fun `七个本地题包依次安装后共有4036道可用题`() {
        val repository = catalogRepository(newDirectory())

        AndroidKnowledgeBankRepository.BUNDLED_PACKS.forEach { item ->
            assertTrue(
                "${item.title} 应安装成功",
                repository.installBundledPack(catalogJson(item), item.expectation) is
                    KnowledgeBankInstallResult.Installed
            )
        }

        val summary = repository.summary()
        assertEquals(7, summary.installedPacks.size)
        assertEquals(4_000, summary.incrementalCount)
        assertEquals(4_036, summary.totalCount)
    }

    @Test
    fun `新目录题包失败时保留旧包成功后才清理`() {
        val repository = catalogRepository(newDirectory())
        val firstCatalogItem = AndroidKnowledgeBankRepository.BUNDLED_PACKS.first()
        assertTrue(repository.installBundledSample(sampleJson()) is KnowledgeBankInstallResult.Installed)

        val rejected = installBundledPackReplacingLegacy(
            repository = repository,
            rawJson = "{}",
            expectation = firstCatalogItem.expectation
        )

        assertTrue(rejected is KnowledgeBankInstallResult.Rejected)
        assertEquals(136, repository.snapshot().totalCount)

        val installed = installBundledPackReplacingLegacy(
            repository = repository,
            rawJson = catalogJson(firstCatalogItem),
            expectation = firstCatalogItem.expectation
        )

        assertTrue(installed is KnowledgeBankInstallResult.Installed)
        assertEquals(536, repository.snapshot().totalCount)
        assertEquals(firstCatalogItem.packageId, repository.summary().activePackageId)
    }

    @Test
    fun `旧包物理清理失败时退休标记仍阻止旧包重新参与出题`() {
        val directory = newDirectory()
        val repository = catalogRepository(directory)
        val firstCatalogItem = AndroidKnowledgeBankRepository.BUNDLED_PACKS.first()
        assertTrue(repository.installBundledSample(sampleJson()) is KnowledgeBankInstallResult.Installed)

        val installed = installBundledPackReplacingLegacy(
            repository = repository,
            rawJson = catalogJson(firstCatalogItem),
            expectation = firstCatalogItem.expectation,
            cleanupLegacyPackFiles = { Unit }
        )

        assertTrue(installed is KnowledgeBankInstallResult.Installed)
        assertTrue(File(directory, "active.kpack").isFile)
        assertEquals(536, repository.snapshot().totalCount)
        assertEquals(536, catalogRepository(directory).snapshot().totalCount)
    }

    @Test
    fun `退休标记保存失败时回滚新题包并保留旧包`() {
        val repository = catalogRepository(newDirectory())
        val firstCatalogItem = AndroidKnowledgeBankRepository.BUNDLED_PACKS.first()
        assertTrue(repository.installBundledSample(sampleJson()) is KnowledgeBankInstallResult.Installed)

        val result = installBundledPackReplacingLegacy(
            repository = repository,
            rawJson = catalogJson(firstCatalogItem),
            expectation = firstCatalogItem.expectation,
            retireLegacyPack = { false }
        )

        assertEquals(KnowledgeBankInstallResult.StorageUnavailable, result)
        assertTrue(repository.summary().installedPacks.isEmpty())
        assertEquals(136, repository.snapshot().totalCount)
    }

    @Test
    fun `同版本本地包可重试并完成旧包退休迁移`() {
        val directory = newDirectory()
        val repository = catalogRepository(directory)
        val firstCatalogItem = AndroidKnowledgeBankRepository.BUNDLED_PACKS.first()
        assertTrue(repository.installBundledSample(sampleJson()) is KnowledgeBankInstallResult.Installed)
        assertTrue(
            repository.installBundledPack(
                catalogJson(firstCatalogItem),
                firstCatalogItem.expectation
            ) is KnowledgeBankInstallResult.Installed
        )
        assertEquals(636, repository.snapshot().totalCount)

        val retried = installBundledPackReplacingLegacy(
            repository = repository,
            rawJson = catalogJson(firstCatalogItem),
            expectation = firstCatalogItem.expectation,
            cleanupLegacyPackFiles = { Unit }
        )

        assertTrue(retried is KnowledgeBankInstallResult.VersionNotNewer)
        assertEquals(536, repository.snapshot().totalCount)
    }

    @Test
    fun `内置36题使用单一开关且启停状态持久化`() {
        val directory = newDirectory()
        val repository = catalogRepository(directory)

        assertTrue(repository.summary().builtInEnabled)
        assertTrue(repository.setBuiltInEnabled(false))

        val disabled = catalogRepository(directory)
        assertFalse(disabled.summary().builtInEnabled)
        assertEquals(0, disabled.summary().builtInCount)
        assertEquals(36, disabled.summary().builtInTotalCount)
        assertEquals(0, disabled.snapshot().totalCount)

        assertTrue(disabled.setBuiltInEnabled(true))
        val enabled = catalogRepository(directory)
        assertTrue(enabled.summary().builtInEnabled)
        assertEquals(36, enabled.summary().builtInCount)
        assertEquals(36, enabled.snapshot().totalCount)
    }

    @Test
    fun `旧版部分隐藏配置迁移为内置36题全部启用`() {
        val directory = newDirectory()
        val hiddenFact = OfflineKnowledgeBank.facts.first()
        writeLegacySettings(directory, setOf(hiddenFact.id))

        val migrated = catalogRepository(directory)

        assertTrue(migrated.summary().builtInEnabled)
        assertEquals(36, migrated.summary().builtInCount)
        assertEquals(36, migrated.snapshot().totalCount)
        assertTrue(migrated.snapshot().facts.any { it.id == hiddenFact.id })
    }

    @Test
    fun `旧版全部隐藏配置迁移为内置题库停用`() {
        val directory = newDirectory()
        writeLegacySettings(directory, OfflineKnowledgeBank.facts.mapTo(mutableSetOf()) { it.id })

        val migrated = catalogRepository(directory)

        assertFalse(migrated.summary().builtInEnabled)
        assertEquals(0, migrated.summary().builtInCount)
        assertEquals(36, migrated.summary().builtInTotalCount)
        assertEquals(0, migrated.snapshot().totalCount)
    }

    private fun repository(directory: File = newDirectory()) = KnowledgeBankRepository(
        storageDirectory = directory,
        currentAppVersionCode = 17,
        signatureVerifier = KnowledgePackSignatureVerifier { _, _, _ -> true }
    )

    private fun catalogRepository(directory: File) = KnowledgeBankRepository(
        storageDirectory = directory,
        currentAppVersionCode = 17,
        signatureVerifier = KnowledgePackSignatureVerifier { _, _, _ -> true },
        bundledExpectations = listOf(KnowledgePackJsonParser.LEGACY_BUNDLED_EXPECTATION) +
            AndroidKnowledgeBankRepository.BUNDLED_PACKS.map { it.expectation }
    )

    private fun catalogJson(item: BundledKnowledgePackCatalogItem): String =
        File("src/main/assets/${item.assetPath}").readText(Charsets.UTF_8)

    private fun newDirectory(): File = Files.createTempDirectory("knowledge-pack-test").toFile()
        .also(testDirectories::add)

    private fun writeLegacySettings(directory: File, hiddenIds: Set<String>) {
        File(directory, "bank-settings.v1").writeText(
            buildString {
                appendLine("CONTROLFREE_KNOWLEDGE_SETTINGS_V1")
                append("hidden=").appendLine(hiddenIds.sorted().joinToString(","))
                append("disabled=")
            },
            Charsets.UTF_8
        )
    }

    private fun sampleJson(): String = File(
        "src/main/assets/${KnowledgePackJsonParser.BUNDLED_SAMPLE_ASSET_PATH}"
    ).readText(Charsets.UTF_8)

    private fun versionTwoJson(): String = sampleJson().replaceFirst(
        "\"version\": 1",
        "\"version\": 2"
    )
}
