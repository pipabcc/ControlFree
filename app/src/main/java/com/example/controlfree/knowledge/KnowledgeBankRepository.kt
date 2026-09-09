package com.example.controlfree.knowledge

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class KnowledgeBankSummary(
    val builtInCount: Int,
    val builtInTotalCount: Int,
    val builtInEnabled: Boolean,
    val incrementalCount: Int,
    val totalCount: Int,
    val categoryCounts: Map<KnowledgeCategory, Int>,
    val difficultyCounts: Map<KnowledgeDifficulty, Int>,
    val contentTypes: List<String>,
    val activePackageId: String?,
    val activePackageTitle: String?,
    val activeVersion: Int?,
    val contentSha256: String?,
    val installedPacks: List<InstalledKnowledgePackSummary>
)

internal data class InstalledKnowledgePackSummary(
    val metadata: KnowledgePackMetadata,
    val category: KnowledgeCategory,
    val enabled: Boolean
)

internal sealed interface KnowledgeBankInstallResult {
    data class Installed(val metadata: KnowledgePackMetadata) : KnowledgeBankInstallResult
    data class Rejected(val reason: KnowledgePackRejectionReason) : KnowledgeBankInstallResult
    data class VersionNotNewer(
        val installedVersion: Int,
        val offeredVersion: Int
    ) : KnowledgeBankInstallResult
    data object BundledSampleUnavailable : KnowledgeBankInstallResult
    data object PasswordInvalid : KnowledgeBankInstallResult
    data object StorageUnavailable : KnowledgeBankInstallResult
}

internal sealed interface KnowledgeBankRemovalResult {
    data object Removed : KnowledgeBankRemovalResult
    data object AlreadyBuiltInOnly : KnowledgeBankRemovalResult
    data object PasswordInvalid : KnowledgeBankRemovalResult
    data object StorageUnavailable : KnowledgeBankRemovalResult
}

internal sealed interface KnowledgePackToggleResult {
    data object Updated : KnowledgePackToggleResult
    data object NotFound : KnowledgePackToggleResult
    data object StorageUnavailable : KnowledgePackToggleResult
}

internal sealed interface KnowledgeBankRollbackResult {
    data class Restored(val metadata: KnowledgePackMetadata) : KnowledgeBankRollbackResult
    data object NoPreviousVersion : KnowledgeBankRollbackResult
    data object StorageUnavailable : KnowledgeBankRollbackResult
}

/**
 * 内置题库与多个增量题包的统一入口。每次 [snapshot] 都重新验证磁盘内容，避免
 * 内存状态与原子切换后的文件不一致。
 */
internal class KnowledgeBankRepository(
    storageDirectory: File,
    currentAppVersionCode: Int,
    builtInFacts: List<KnowledgeFact> = OfflineKnowledgeBank.facts,
    signatureVerifier: KnowledgePackSignatureVerifier = RejectingKnowledgePackSignatureVerifier,
    bundledExpectations: List<BundledKnowledgePackExpectation> = listOf(
        KnowledgePackJsonParser.LEGACY_BUNDLED_EXPECTATION
    )
) : KnowledgeBankSnapshotProvider {
    private val immutableBuiltIn = builtInFacts.toList()
    private val parser = KnowledgePackJsonParser(
        currentAppVersionCode = currentAppVersionCode,
        builtInFacts = immutableBuiltIn,
        signatureVerifier = signatureVerifier
    )
    // 同一 packageId 可以有多个历史内容哈希，升级后仍需读取旧包并提供更新入口。
    private val bundledExpectations = bundledExpectations.distinct()
    private val store = AtomicKnowledgePackStore(storageDirectory)
    private val multiPackStore = MultiKnowledgePackStore(File(storageDirectory, "installed"))
    private val settingsStore = KnowledgeBankSettingsStore(File(storageDirectory, "bank-settings.v1"))
    private val legacyRetirementStore = LegacyKnowledgePackRetirementStore(
        File(storageDirectory, "legacy-pack-retired.v1")
    )
    private val lock = Any()

    override fun snapshot(): KnowledgeBankSnapshot = synchronized(lock) {
        val settings = settingsStore.read()
        buildSnapshot(settings, loadInstalledPacks())
    }

    fun summary(): KnowledgeBankSummary = synchronized(lock) {
        val settings = settingsStore.read()
        val installed = loadInstalledPacks()
        val current = buildSnapshot(settings, installed)
        val packSummaries = installed.map { stored ->
            InstalledKnowledgePackSummary(
                metadata = stored.pack.metadata,
                category = requireNotNull(stored.pack.facts.firstOrNull()?.category),
                enabled = stored.pack.metadata.packageId !in settings.disabledPackageIds
            )
        }
        return KnowledgeBankSummary(
            builtInCount = current.builtInCount,
            builtInTotalCount = immutableBuiltIn.size,
            builtInEnabled = isBuiltInEnabled(settings),
            incrementalCount = current.incrementalCount,
            totalCount = current.totalCount,
            categoryCounts = current.categoryCounts,
            difficultyCounts = current.difficultyCounts,
            contentTypes = current.contentTypes,
            activePackageId = current.activePack?.packageId,
            activePackageTitle = current.activePack?.title,
            activeVersion = current.activePack?.version,
            contentSha256 = current.activePack?.contentSha256,
            installedPacks = packSummaries
        )
    }

    fun installSigned(rawJson: String): KnowledgeBankInstallResult =
        install(rawJson, KnowledgePackTrust.PRODUCTION_SIGNED)

    /** 只能由固定 assets 路径调用；绝不能把网络响应传入这个测试入口。 */
    fun installBundledSample(rawJson: String): KnowledgeBankInstallResult =
        install(rawJson, KnowledgePackTrust.BUNDLED_SAMPLE)

    fun installBundledPack(
        rawJson: String,
        expectation: BundledKnowledgePackExpectation
    ): KnowledgeBankInstallResult = synchronized(lock) {
        val accepted = when (
            val parsed = parser.parse(rawJson, KnowledgePackTrust.BUNDLED_SAMPLE, expectation)
        ) {
            is KnowledgePackParseResult.Accepted -> parsed.pack
            is KnowledgePackParseResult.Rejected -> {
                return@synchronized KnowledgeBankInstallResult.Rejected(parsed.reason)
            }
        }
        val existing = loadInstalledPacks()
        existing.firstOrNull { it.pack.metadata.packageId == accepted.metadata.packageId }
            ?.let { current ->
                if (accepted.metadata.version <= current.pack.metadata.version) {
                    return@synchronized KnowledgeBankInstallResult.VersionNotNewer(
                        current.pack.metadata.version,
                        accepted.metadata.version
                    )
                }
            }
        val otherFacts = existing
            .filterNot { it.pack.metadata.packageId == accepted.metadata.packageId }
            .flatMap { it.pack.facts }
        val otherIds = otherFacts.map(KnowledgeFact::id).toSet()
        if (accepted.facts.any { it.id in otherIds }) {
            return@synchronized KnowledgeBankInstallResult.Rejected(
                KnowledgePackRejectionReason.DUPLICATE_FACT_ID
            )
        }
        val otherContent = otherFacts.map(::contentFingerprint).toSet()
        if (accepted.facts.any { contentFingerprint(it) in otherContent }) {
            return@synchronized KnowledgeBankInstallResult.Rejected(
                KnowledgePackRejectionReason.DUPLICATE_CONTENT
            )
        }
        val stored = StoredKnowledgePack(KnowledgePackTrust.BUNDLED_SAMPLE, rawJson)
        if (!multiPackStore.write(accepted.metadata.packageId, stored)) {
            return@synchronized KnowledgeBankInstallResult.StorageUnavailable
        }
        val currentSettings = settingsStore.read()
        val settings = currentSettings.copy(
            disabledPackageIds = currentSettings.disabledPackageIds - accepted.metadata.packageId
        )
        if (!settingsStore.write(settings)) {
            multiPackStore.remove(accepted.metadata.packageId)
            return@synchronized KnowledgeBankInstallResult.StorageUnavailable
        }
        KnowledgeBankInstallResult.Installed(accepted.metadata)
    }

    fun removePack(packageId: String): KnowledgeBankRemovalResult = synchronized(lock) {
        if (!multiPackStore.exists(packageId)) return KnowledgeBankRemovalResult.AlreadyBuiltInOnly
        if (!multiPackStore.remove(packageId)) return KnowledgeBankRemovalResult.StorageUnavailable
        val current = settingsStore.read()
        settingsStore.write(current.copy(disabledPackageIds = current.disabledPackageIds - packageId))
        KnowledgeBankRemovalResult.Removed
    }

    fun setPackEnabled(packageId: String, enabled: Boolean): KnowledgePackToggleResult =
        synchronized(lock) {
            if (!multiPackStore.exists(packageId)) return KnowledgePackToggleResult.NotFound
            val current = settingsStore.read()
            val disabled = if (enabled) {
                current.disabledPackageIds - packageId
            } else {
                current.disabledPackageIds + packageId
            }
            if (settingsStore.write(current.copy(disabledPackageIds = disabled))) {
                KnowledgePackToggleResult.Updated
            } else {
                KnowledgePackToggleResult.StorageUnavailable
            }
        }

    fun setBuiltInEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        val hidden = if (enabled) emptySet() else immutableBuiltIn.mapTo(mutableSetOf()) { it.id }
        settingsStore.write(settingsStore.read().copy(hiddenBuiltInIds = hidden))
    }

    fun clearLegacyPackFiles(): Boolean = synchronized(lock) { store.clearPackFiles() }

    fun retireLegacyPack(): Boolean = synchronized(lock) { legacyRetirementStore.retire() }

    fun removeIncremental(): KnowledgeBankRemovalResult = synchronized(lock) {
        if (!store.hasAnyPack()) return KnowledgeBankRemovalResult.AlreadyBuiltInOnly
        if (store.clear()) {
            KnowledgeBankRemovalResult.Removed
        } else {
            KnowledgeBankRemovalResult.StorageUnavailable
        }
    }

    fun rollback(): KnowledgeBankRollbackResult = synchronized(lock) {
        val previous = store.readPrevious()?.let(::parseStored)
            ?: return KnowledgeBankRollbackResult.NoPreviousVersion
        if (!store.restorePrevious()) return KnowledgeBankRollbackResult.StorageUnavailable
        KnowledgeBankRollbackResult.Restored(previous.pack.metadata)
    }

    private fun install(rawJson: String, trust: KnowledgePackTrust): KnowledgeBankInstallResult =
        synchronized(lock) {
            val accepted = when (val parsed = parser.parse(rawJson, trust)) {
                is KnowledgePackParseResult.Accepted -> parsed.pack
                is KnowledgePackParseResult.Rejected -> {
                    return@synchronized KnowledgeBankInstallResult.Rejected(parsed.reason)
                }
            }
            val current = loadActiveWithRecovery()?.pack?.metadata
            if (
                current?.packageId == accepted.metadata.packageId &&
                accepted.metadata.version <= current.version
            ) {
                return@synchronized KnowledgeBankInstallResult.VersionNotNewer(
                    installedVersion = current.version,
                    offeredVersion = accepted.metadata.version
                )
            }
            val stored = StoredKnowledgePack(trust = trust, rawJson = rawJson)
            if (!store.activate(stored)) {
                return@synchronized KnowledgeBankInstallResult.StorageUnavailable
            }
            KnowledgeBankInstallResult.Installed(accepted.metadata)
        }

    private fun loadActiveWithRecovery(): ParsedStoredPack? {
        store.readActive()?.let(::parseStored)?.let { return it }
        val previous = store.readPrevious()?.let(::parseStored) ?: return null
        store.recoverPreviousAsActive()
        return previous
    }

    private fun parseStored(stored: StoredKnowledgePack): ParsedStoredPack? {
        if (stored.trust == KnowledgePackTrust.BUNDLED_SAMPLE) {
            bundledExpectations.forEach { expectation ->
                when (
                    val parsed = parser.parse(stored.rawJson, stored.trust, expectation)
                ) {
                    is KnowledgePackParseResult.Accepted -> return ParsedStoredPack(parsed.pack)
                    is KnowledgePackParseResult.Rejected -> Unit
                }
            }
            return null
        }
        return when (val parsed = parser.parse(stored.rawJson, stored.trust)) {
            is KnowledgePackParseResult.Accepted -> ParsedStoredPack(parsed.pack)
            is KnowledgePackParseResult.Rejected -> null
        }
    }

    private data class ParsedStoredPack(val pack: KnowledgeIncrementPack)

    private fun loadInstalledPacks(): List<ParsedStoredPack> = multiPackStore.readAll()
        .mapNotNull { (_, stored) -> parseStored(stored) }

    private fun buildSnapshot(
        settings: KnowledgeBankSettings,
        installed: List<ParsedStoredPack>
    ): KnowledgeBankSnapshot {
        val builtIn = if (isBuiltInEnabled(settings)) immutableBuiltIn else emptyList()
        val packs = loadUsablePacks(settings, installed)
        return KnowledgeBankSnapshot(
            builtInCount = builtIn.size,
            activePacks = packs.map { it.pack.metadata },
            facts = builtIn + packs.flatMap { it.pack.facts }
        )
    }

    /** 旧版只要仍有一道内置题可用，就迁移为整个内置题库启用。 */
    private fun isBuiltInEnabled(settings: KnowledgeBankSettings): Boolean {
        if (immutableBuiltIn.isEmpty()) return false
        val builtInIds = immutableBuiltIn.mapTo(mutableSetOf()) { it.id }
        return !settings.hiddenBuiltInIds.containsAll(builtInIds)
    }

    private fun loadUsablePacks(
        settings: KnowledgeBankSettings,
        installed: List<ParsedStoredPack> = loadInstalledPacks()
    ): List<ParsedStoredPack> {
        val usedIds = mutableSetOf<String>()
        val usedContent = mutableSetOf<String>()
        return buildList {
            val candidates = buildList {
                if (!legacyRetirementStore.isRetired()) {
                    loadActiveWithRecovery()?.let(::add)
                }
                addAll(installed.filterNot {
                    it.pack.metadata.packageId in settings.disabledPackageIds
                })
            }
            candidates.forEach { candidate ->
                val ids = candidate.pack.facts.map(KnowledgeFact::id)
                val content = candidate.pack.facts.map(::contentFingerprint)
                if (ids.any { it in usedIds } || content.any { it in usedContent }) return@forEach
                usedIds += ids
                usedContent += content
                add(candidate)
            }
        }
    }

    private fun contentFingerprint(fact: KnowledgeFact): String =
        KnowledgeContentPolicy.comparisonKey(fact.localStem)
}

/** 题库迁移的逻辑提交点；标记落盘后旧单包即使删除失败也不会再次参与出题。 */
private class LegacyKnowledgePackRetirementStore(private val marker: File) {
    private val staging = File(marker.parentFile, "${marker.name}.staging")

    fun isRetired(): Boolean = try {
        marker.isFile && marker.length() <= MARKER_CONTENT.length.toLong() + 1L &&
            marker.readText(Charsets.UTF_8).trim() == MARKER_CONTENT
    } catch (_: Exception) {
        false
    }

    fun retire(): Boolean = try {
        val parent = marker.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        FileOutputStream(staging, false).use { output ->
            output.write(MARKER_CONTENT.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        moveReplacing(staging, marker)
        true
    } catch (_: Exception) {
        staging.delete()
        false
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MARKER_CONTENT = "CONTROLFREE_LEGACY_KNOWLEDGE_PACK_RETIRED_V1"
    }
}

internal data class StoredKnowledgePack(
    val trust: KnowledgePackTrust,
    val rawJson: String
)

/** active/previous/staging 都位于同一目录，切换不会暴露半写入的题包。 */
internal class AtomicKnowledgePackStore(private val directory: File) {
    private val active = File(directory, ACTIVE_FILE)
    private val previous = File(directory, PREVIOUS_FILE)
    private val staging = File(directory, STAGING_FILE)

    fun readActive(): StoredKnowledgePack? = read(active)

    fun readPrevious(): StoredKnowledgePack? = read(previous)

    fun hasAnyPack(): Boolean = active.isFile || previous.isFile || staging.isFile

    fun clearPackFiles(): Boolean = try {
        listOf(active, previous, staging).all { file -> !file.exists() || file.delete() }
    } catch (_: Exception) {
        false
    }

    fun activate(pack: StoredKnowledgePack): Boolean = try {
        ensureDirectory()
        writeDurably(staging, encode(pack))
        if (active.isFile) {
            moveReplacing(active, previous)
        }
        moveReplacing(staging, active)
        true
    } catch (_: Exception) {
        staging.delete()
        if (!active.isFile && previous.isFile) recoverPreviousAsActive()
        false
    }

    fun restorePrevious(): Boolean = try {
        if (!previous.isFile) return false
        ensureDirectory()
        Files.copy(previous.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        FileOutputStream(staging, true).use { it.fd.sync() }
        if (active.isFile) moveReplacing(active, previous)
        moveReplacing(staging, active)
        true
    } catch (_: Exception) {
        if (!active.isFile && staging.isFile) {
            try {
                moveReplacing(staging, active)
            } catch (_: Exception) {
                staging.delete()
            }
        } else {
            staging.delete()
        }
        false
    }

    fun recoverPreviousAsActive(): Boolean = try {
        if (!previous.isFile) return false
        ensureDirectory()
        Files.copy(previous.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        FileOutputStream(staging, true).use { it.fd.sync() }
        moveReplacing(staging, active)
        true
    } catch (_: Exception) {
        staging.delete()
        false
    }

    fun clear(): Boolean {
        if (!directory.exists()) return true
        val parent = directory.absoluteFile.parentFile ?: return false
        val tombstone = File(
            parent,
            ".${directory.name}.removing-${System.nanoTime()}"
        )
        return try {
            // 整个专用目录一次切走，调用方只会看到“旧题包”或“仅内置题库”，不会
            // 落入 active 已删但 previous 尚存的半清理状态。
            moveReplacing(directory, tombstone)
            runCatching { tombstone.deleteRecursively() }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun read(file: File): StoredKnowledgePack? = try {
        if (!file.isFile || file.length() > MAX_STORED_BYTES) return null
        decode(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    private fun ensureDirectory() {
        if (directory.exists()) require(directory.isDirectory)
        if (!directory.exists()) require(directory.mkdirs())
    }

    private fun writeDurably(target: File, content: String) {
        FileOutputStream(target, false).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun encode(pack: StoredKnowledgePack): String = buildString(pack.rawJson.length + 64) {
        append(STORAGE_HEADER).append('\n')
        append(pack.trust.name).append('\n')
        append(pack.rawJson)
    }

    private fun decode(raw: String): StoredKnowledgePack? {
        val firstBreak = raw.indexOf('\n')
        if (firstBreak <= 0 || raw.substring(0, firstBreak) != STORAGE_HEADER) return null
        val secondBreak = raw.indexOf('\n', firstBreak + 1)
        if (secondBreak <= firstBreak + 1) return null
        val trust = try {
            KnowledgePackTrust.valueOf(raw.substring(firstBreak + 1, secondBreak))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val json = raw.substring(secondBreak + 1)
        if (json.isBlank()) return null
        return StoredKnowledgePack(trust, json)
    }

    private companion object {
        const val STORAGE_HEADER = "CONTROLFREE_KNOWLEDGE_PACK_V1"
        const val ACTIVE_FILE = "active.kpack"
        const val PREVIOUS_FILE = "previous.kpack"
        const val STAGING_FILE = "staging.kpack"
        const val MAX_STORED_BYTES = 12_000_000L
    }
}

private data class KnowledgeBankSettings(
    val hiddenBuiltInIds: Set<String> = emptySet(),
    val disabledPackageIds: Set<String> = emptySet()
)

/** 设置文件很小，使用 staging + 原子替换避免启停过程中留下半写状态。 */
private class KnowledgeBankSettingsStore(private val file: File) {
    private val staging = File(file.parentFile, "${file.name}.staging")

    fun read(): KnowledgeBankSettings = try {
        if (!file.isFile || file.length() > MAX_SETTINGS_BYTES) return KnowledgeBankSettings()
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size != 3 || lines[0] != SETTINGS_HEADER) return KnowledgeBankSettings()
        val hidden = parseIds(lines[1], "hidden=") ?: return KnowledgeBankSettings()
        val disabled = parseIds(lines[2], "disabled=") ?: return KnowledgeBankSettings()
        KnowledgeBankSettings(hidden, disabled)
    } catch (_: Exception) {
        KnowledgeBankSettings()
    }

    fun write(settings: KnowledgeBankSettings): Boolean = try {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val content = buildString {
            appendLine(SETTINGS_HEADER)
            append("hidden=").appendLine(settings.hiddenBuiltInIds.sorted().joinToString(","))
            append("disabled=").append(settings.disabledPackageIds.sorted().joinToString(","))
        }
        FileOutputStream(staging, false).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        moveReplacing(staging, file)
        true
    } catch (_: Exception) {
        staging.delete()
        false
    }

    private fun parseIds(line: String, prefix: String): Set<String>? {
        if (!line.startsWith(prefix)) return null
        val raw = line.removePrefix(prefix)
        if (raw.isEmpty()) return emptySet()
        val ids = raw.split(',')
        return ids.takeIf { values ->
            values.size <= MAX_SETTINGS_IDS && values.toSet().size == values.size &&
                values.all(ID_PATTERN::matches)
        }?.toSet()
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val SETTINGS_HEADER = "CONTROLFREE_KNOWLEDGE_SETTINGS_V1"
        const val MAX_SETTINGS_BYTES = 256_000L
        const val MAX_SETTINGS_IDS = 10_000
        val ID_PATTERN = Regex("[a-z][a-z0-9_]{2,63}")
    }
}

/** 每个已安装包单独落盘，安装和卸载一个包不会影响其他包。 */
private class MultiKnowledgePackStore(private val directory: File) {
    fun readAll(): List<Pair<String, StoredKnowledgePack>> = try {
        if (!directory.isDirectory) return emptyList()
        directory.listFiles { file -> file.isFile && file.name.endsWith(PACK_SUFFIX) }
            .orEmpty()
            .sortedBy(File::getName)
            .mapNotNull { file ->
                val packageId = file.name.removeSuffix(PACK_SUFFIX)
                read(file)?.let { packageId to it }
            }
    } catch (_: Exception) {
        emptyList()
    }

    fun exists(packageId: String): Boolean = fileFor(packageId)?.isFile == true

    fun write(packageId: String, pack: StoredKnowledgePack): Boolean = try {
        val target = fileFor(packageId) ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val staging = File(directory, ".$packageId.staging")
        FileOutputStream(staging, false).use { output ->
            output.write(encode(pack).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        moveReplacing(staging, target)
        true
    } catch (_: Exception) {
        File(directory, ".$packageId.staging").delete()
        false
    }

    fun remove(packageId: String): Boolean = try {
        val target = fileFor(packageId) ?: return false
        !target.exists() || target.delete()
    } catch (_: Exception) {
        false
    }

    private fun fileFor(packageId: String): File? = packageId
        .takeIf(PACKAGE_ID_PATTERN::matches)
        ?.let { File(directory, "$it$PACK_SUFFIX") }

    private fun read(file: File): StoredKnowledgePack? = try {
        if (file.length() > MAX_STORED_BYTES) return null
        decode(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    private fun encode(pack: StoredKnowledgePack): String = buildString(pack.rawJson.length + 64) {
        append(STORAGE_HEADER).append('\n')
        append(pack.trust.name).append('\n')
        append(pack.rawJson)
    }

    private fun decode(raw: String): StoredKnowledgePack? {
        val firstBreak = raw.indexOf('\n')
        if (firstBreak <= 0 || raw.substring(0, firstBreak) != STORAGE_HEADER) return null
        val secondBreak = raw.indexOf('\n', firstBreak + 1)
        if (secondBreak <= firstBreak + 1) return null
        val trust = runCatching {
            KnowledgePackTrust.valueOf(raw.substring(firstBreak + 1, secondBreak))
        }.getOrNull() ?: return null
        val json = raw.substring(secondBreak + 1).takeIf(String::isNotBlank) ?: return null
        return StoredKnowledgePack(trust, json)
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val STORAGE_HEADER = "CONTROLFREE_KNOWLEDGE_PACK_V1"
        const val PACK_SUFFIX = ".kpack"
        const val MAX_STORED_BYTES = 12_000_000L
        val PACKAGE_ID_PATTERN = Regex("[a-z][a-z0-9_]{2,63}")
    }
}
