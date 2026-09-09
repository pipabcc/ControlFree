package com.example.controlfree.knowledge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKnowledgeBankRepositoryTest {
    @Test
    fun localCatalogInstallAndRemovalBothRequireFixedPassword() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AndroidKnowledgeBankRepository.getInstance(context)
        clearCatalog(repository)
        val item = repository.bundledCatalog().first()
        val packFile = context.noBackupFilesDir.resolve(
            "knowledge_packs/installed/${item.packageId}.kpack"
        )

        assertEquals(
            KnowledgeBankInstallResult.PasswordInvalid,
            repository.installBundledPack(item.packageId, "000000")
        )
        assertFalse(packFile.exists())

        assertTrue(
            repository.installBundledPack(item.packageId, "666888") is
                KnowledgeBankInstallResult.Installed
        )
        assertTrue(packFile.isFile)
        assertEquals(
            KnowledgeBankRemovalResult.PasswordInvalid,
            repository.removePack(item.packageId, "123456")
        )
        assertTrue(packFile.isFile)
        assertEquals(
            KnowledgeBankRemovalResult.Removed,
            repository.removePack(item.packageId, "666888")
        )
        assertFalse(packFile.exists())
    }

    @Test
    fun localCatalogRequiresFixedPasswordAndAllowsMultipleEnabledPacks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AndroidKnowledgeBankRepository.getInstance(context)
        clearCatalog(repository)
        val builtInCount = repository.summary().totalCount
        val first = repository.bundledCatalog()[0]
        val second = repository.bundledCatalog()[1]

        assertEquals(
            KnowledgeBankInstallResult.PasswordInvalid,
            repository.installBundledPack(first.packageId, "000000")
        )
        assertTrue(repository.installBundledPack(first.packageId, "666888") is KnowledgeBankInstallResult.Installed)
        assertTrue(repository.installBundledPack(second.packageId, "666888") is KnowledgeBankInstallResult.Installed)
        assertEquals(builtInCount + first.questionCount + second.questionCount, repository.summary().totalCount)

        assertEquals(KnowledgePackToggleResult.Updated, repository.setPackEnabled(first.packageId, false))
        assertEquals(builtInCount + second.questionCount, repository.summary().totalCount)
        assertEquals(
            KnowledgeBankRemovalResult.PasswordInvalid,
            repository.removePack(second.packageId, "123456")
        )
        assertFalse(repository.summary().installedPacks.isEmpty())
        clearCatalog(repository)
    }

    private fun clearCatalog(repository: AndroidKnowledgeBankRepository) {
        repository.bundledCatalog().forEach { item ->
            repository.removePack(item.packageId, "666888")
        }
    }
}
