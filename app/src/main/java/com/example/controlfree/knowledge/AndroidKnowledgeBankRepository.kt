package com.example.controlfree.knowledge

import android.content.Context
import android.os.Build
import java.io.File

internal data class BundledKnowledgePackCatalogItem(
    val category: KnowledgeCategory,
    val displayCategoryName: String,
    val packageId: String,
    val title: String,
    val assetPath: String,
    val questionCount: Int,
    val expectation: BundledKnowledgePackExpectation
)

internal object QuestionBankManagementPassword {
    const val LENGTH = 6
    private const val PASSWORD = "666888"

    fun verify(candidate: String): Boolean = candidate == PASSWORD
}

/**
 * 先完成新目录题包的校验与持久化，再原子写入旧包退休标记。
 * 物理清理失败不会影响读取语义；退休标记失败则回滚新包并继续保留旧包。
 */
internal fun installBundledPackReplacingLegacy(
    repository: KnowledgeBankRepository,
    rawJson: String,
    expectation: BundledKnowledgePackExpectation,
    retireLegacyPack: () -> Boolean = repository::retireLegacyPack,
    cleanupLegacyPackFiles: () -> Unit = { repository.clearLegacyPackFiles() }
): KnowledgeBankInstallResult {
    val result = repository.installBundledPack(rawJson, expectation)
    if (
        result is KnowledgeBankInstallResult.Installed ||
        result is KnowledgeBankInstallResult.VersionNotNewer
    ) {
        if (!retireLegacyPack()) {
            if (result is KnowledgeBankInstallResult.Installed) {
                repository.removePack(result.metadata.packageId)
            }
            return KnowledgeBankInstallResult.StorageUnavailable
        }
        // 退休标记是读取语义的权威状态；物理清理由失败可重试的最佳努力完成。
        runCatching { cleanupLegacyPackFiles() }
    }
    return result
}

/** 设置页和挑战协调器共用的 Android 入口。安装与卸载在本层强制验证固定管理密码。 */
internal class AndroidKnowledgeBankRepository private constructor(
    private val appContext: Context,
    signatureVerifier: KnowledgePackSignatureVerifier
) : KnowledgeBankSnapshotProvider {
    private val delegate = KnowledgeBankRepository(
        storageDirectory = File(appContext.noBackupFilesDir, STORAGE_DIRECTORY),
        currentAppVersionCode = appVersionCode(appContext),
        signatureVerifier = signatureVerifier,
        bundledExpectations = listOf(KnowledgePackJsonParser.LEGACY_BUNDLED_EXPECTATION) +
            BUNDLED_PACK_EXPECTATIONS
    )

    override fun snapshot(): KnowledgeBankSnapshot = delegate.snapshot()

    fun summary(): KnowledgeBankSummary = delegate.summary()

    fun bundledCatalog(): List<BundledKnowledgePackCatalogItem> = BUNDLED_PACKS

    fun installBundledPack(packageId: String, password: String): KnowledgeBankInstallResult {
        if (!QuestionBankManagementPassword.verify(password)) {
            return KnowledgeBankInstallResult.PasswordInvalid
        }
        val item = BUNDLED_PACKS.firstOrNull { it.packageId == packageId }
            ?: return KnowledgeBankInstallResult.BundledSampleUnavailable
        val rawJson = try {
            appContext.assets.open(item.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            return KnowledgeBankInstallResult.BundledSampleUnavailable
        }
        return installBundledPackReplacingLegacy(delegate, rawJson, item.expectation)
    }

    fun removePack(packageId: String, password: String): KnowledgeBankRemovalResult {
        if (!QuestionBankManagementPassword.verify(password)) {
            return KnowledgeBankRemovalResult.PasswordInvalid
        }
        if (BUNDLED_PACKS.none { it.packageId == packageId }) {
            return KnowledgeBankRemovalResult.AlreadyBuiltInOnly
        }
        return delegate.removePack(packageId)
    }

    fun setPackEnabled(packageId: String, enabled: Boolean): KnowledgePackToggleResult =
        delegate.setPackEnabled(packageId, enabled)

    fun setBuiltInEnabled(enabled: Boolean): Boolean = delegate.setBuiltInEnabled(enabled)

    internal companion object {
        private const val STORAGE_DIRECTORY = "knowledge_packs"

        private const val LOCAL_KEY_ID = "bundled_local_v1"
        private const val LOCAL_SIGNATURE = "CONTROLFREE_BUNDLED_LOCAL_V1"

        val BUNDLED_PACKS: List<BundledKnowledgePackCatalogItem> = listOf(
            bundledPack(
                KnowledgeCategory.NATURAL_SCIENCE, "local_natural_science_v1",
                "自然科学百科 500 题", 500,
                "04805985f80fa17af936ffc7afc850872cc10610053ae22d065f924092d9b5a1"
            ),
            bundledPack(
                KnowledgeCategory.HISTORY_CULTURE, "local_history_culture_v1",
                "历史文化百科 500 题", 500,
                "c7cdf83336dc240799e7d33cf6e051aee560dde14705cd5046223a46b4003d36"
            ),
            bundledPack(
                KnowledgeCategory.GEOGRAPHY, "local_geography_v1",
                "地理百科 500 题", 500,
                "5e283c97d82d2eea2617033692393667220449199760d3f1b4cf490795bc9406"
            ),
            bundledPack(
                KnowledgeCategory.LITERATURE_ART, "local_literature_art_v1",
                "文学艺术百科 500 题", 500,
                "4ace88a55edf030293d8a19513b8d5286a55f94142f2d1506f200000639e5a71"
            ),
            bundledPack(
                KnowledgeCategory.LIFE_KNOWLEDGE, "local_life_knowledge_v1",
                "生活常识百科 500 题", 500,
                "eab40eb2f40efa0e981db2da358a2c99109209a75cae1b1e469d87306c8f9d79"
            ),
            bundledPack(
                KnowledgeCategory.TECHNOLOGY, "local_technology_v1",
                "科技百科 500 题", 500,
                "63caa84d14b6799b8142b127bda6a7728ef24ba686de84f40672477a88f81f84"
            ),
            bundledPack(
                KnowledgeCategory.LITERATURE_ART, "local_english_vocabulary_1000_v1",
                "英语核心词汇考察 1000 题", 1_000,
                "064d9bb26de3536b274211ba7d1c44c5a6572f1ed30342e45776d2adc2f9bd2f",
                assetPath = "knowledge_packs/local/english_vocabulary/pack.json",
                displayCategoryName = "英语核心词汇"
            )
        )

        /** 保留旧目录词汇包哈希，使已安装 v1 包可见并能在设置页升级为 v2。 */
        val BUNDLED_PACK_EXPECTATIONS: List<BundledKnowledgePackExpectation> =
            BUNDLED_PACKS.map(BundledKnowledgePackCatalogItem::expectation) + listOf(
                legacyExpectation(
                    "local_natural_science_v1",
                    "34630c910cc9ce0857b4323215d3a8f6f7c6ea676c7feaf90a29915c1f3fc547"
                ),
                legacyExpectation(
                    "local_history_culture_v1",
                    "0fc8e58e850d2e7811d6d4409df64ebd6e904bcd37a8ef57149dbb4a78dbdff2"
                ),
                legacyExpectation(
                    "local_geography_v1",
                    "d5ef86116a78425a1ff9d011bb417259cffcd8f3d60c9c3867678fc7af39298b"
                ),
                legacyExpectation(
                    "local_literature_art_v1",
                    "1892abbf448c3d1e16041819099caf731fd57f9def8874b2afdabe9b447bdf0b"
                ),
                legacyExpectation(
                    "local_life_knowledge_v1",
                    "89d5daae208fdea984dc6efbd61cfba3ad51aa5efc948a57a7dd79d5308dbed5"
                ),
                legacyExpectation(
                    "local_technology_v1",
                    "aa2b84ce662ad558aae0f1a92104b558f29c76836bc7759ec1a9dfa7a64a00cc"
                )
            )

        private fun bundledPack(
            category: KnowledgeCategory,
            packageId: String,
            title: String,
            questionCount: Int,
            sha256: String,
            assetPath: String = "knowledge_packs/local/${category.wireValue}/pack.json",
            displayCategoryName: String = category.displayName
        ) = BundledKnowledgePackCatalogItem(
            category = category,
            displayCategoryName = displayCategoryName,
            packageId = packageId,
            title = title,
            assetPath = assetPath,
            questionCount = questionCount,
            expectation = BundledKnowledgePackExpectation(
                packageId = packageId,
                signingKeyId = LOCAL_KEY_ID,
                signature = LOCAL_SIGNATURE,
                contentSha256 = sha256
            )
        )

        private fun legacyExpectation(
            packageId: String,
            sha256: String
        ) = BundledKnowledgePackExpectation(
            packageId = packageId,
            signingKeyId = LOCAL_KEY_ID,
            signature = LOCAL_SIGNATURE,
            contentSha256 = sha256
        )

        @Volatile
        private var instance: AndroidKnowledgeBankRepository? = null

        fun getInstance(context: Context): AndroidKnowledgeBankRepository =
            instance ?: synchronized(this) {
                instance ?: AndroidKnowledgeBankRepository(
                    appContext = context.applicationContext,
                    signatureVerifier = RejectingKnowledgePackSignatureVerifier
                ).also { instance = it }
            }

        /**
         * 测试或宿主可在首次创建实例前注入只含公钥的验证器。Android 管理入口仅支持
         * 随 APK 提供的本地题包，不暴露在线安装、无密码卸载或回退能力。
         */
        fun initialize(
            context: Context,
            signatureVerifier: KnowledgePackSignatureVerifier
        ): AndroidKnowledgeBankRepository = synchronized(this) {
            instance ?: AndroidKnowledgeBankRepository(
                appContext = context.applicationContext,
                signatureVerifier = signatureVerifier
            ).also { instance = it }
        }

        @Suppress("DEPRECATION")
        private fun appVersionCode(context: Context): Int {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            return code.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
