package com.example.controlfree.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.data.LockUnlockFeaturePreferences
import com.example.controlfree.data.canEnableUnlockAuthentication
import com.example.controlfree.knowledge.AndroidKnowledgeBankRepository
import com.example.controlfree.knowledge.BundledKnowledgePackCatalogItem
import com.example.controlfree.knowledge.KnowledgeBankInstallResult
import com.example.controlfree.knowledge.KnowledgeBankRemovalResult
import com.example.controlfree.knowledge.KnowledgeBankSummary
import com.example.controlfree.knowledge.KnowledgePackMetadata
import com.example.controlfree.knowledge.KnowledgePackToggleResult
import com.example.controlfree.knowledge.QuestionBankManagementPassword
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.theme.BrandColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingPackageAction(
    val item: BundledKnowledgePackCatalogItem,
    val operation: PackageManagementOperation
)

private enum class PackageManagementOperation {
    INSTALL,
    UPDATE,
    REMOVE
}

internal data class BundledPackPresentation(
    val displayedQuestionCount: Int,
    val updateAvailable: Boolean
)

internal fun bundledPackPresentation(
    item: BundledKnowledgePackCatalogItem,
    installedMetadata: KnowledgePackMetadata?
): BundledPackPresentation = BundledPackPresentation(
    displayedQuestionCount = installedMetadata?.questionCount ?: item.questionCount,
    updateAvailable = installedMetadata != null && (
        installedMetadata.contentSha256 != item.expectation.contentSha256 ||
            installedMetadata.questionCount != item.questionCount
        )
)

private sealed interface QuestionBankSummaryLoadState {
    data object Loading : QuestionBankSummaryLoadState
    data class Loaded(val summary: KnowledgeBankSummary) : QuestionBankSummaryLoadState
    data class Failed(val message: String) : QuestionBankSummaryLoadState
}

@Composable
internal fun QuestionBankSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { AndroidKnowledgeBankRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val catalog = remember(repository) { repository.bundledCatalog() }
    var refreshToken by remember { mutableIntStateOf(0) }
    val summaryState by produceState<QuestionBankSummaryLoadState>(
        QuestionBankSummaryLoadState.Loading,
        repository,
        refreshToken
    ) {
        value = QuestionBankSummaryLoadState.Loading
        value = withContext(Dispatchers.IO) {
            runCatching(repository::summary).fold(
                onSuccess = QuestionBankSummaryLoadState::Loaded,
                onFailure = {
                    QuestionBankSummaryLoadState.Failed("题库状态读取失败，请重试")
                }
            )
        }
    }
    val summary = (summaryState as? QuestionBankSummaryLoadState.Loaded)?.summary
    val summaryAvailable = summary != null
    var pendingAction by remember { mutableStateOf<PendingPackageAction?>(null) }
    var operationInProgress by remember { mutableStateOf(false) }
    val controlsEnabled = summaryAvailable && !operationInProgress
    var statusMessage by remember {
        mutableStateOf("六个领域题包及英语词汇题包随 App 提供；安装和卸载使用固定管理密码 666888。")
    }

    fun refresh(message: String) {
        statusMessage = message
        refreshToken++
    }

    fun runPackageAction(action: PendingPackageAction, password: String) {
        if (operationInProgress || !summaryAvailable) return
        operationInProgress = true
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                when (action.operation) {
                    PackageManagementOperation.INSTALL -> installResultMessage(
                        repository.installBundledPack(action.item.packageId, password)
                    )
                    PackageManagementOperation.UPDATE -> installResultMessage(
                        repository.installBundledPack(action.item.packageId, password),
                        successVerb = "已更新"
                    )
                    PackageManagementOperation.REMOVE -> removalResultMessage(
                        repository.removePack(action.item.packageId, password)
                    )
                }
            }
            operationInProgress = false
            refresh(message)
        }
    }

    fun togglePack(packageId: String, enabled: Boolean) {
        if (operationInProgress || !summaryAvailable) return
        operationInProgress = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.setPackEnabled(packageId, enabled)
            }
            operationInProgress = false
            refresh(
                when (result) {
                    KnowledgePackToggleResult.Updated -> if (enabled) "题包已启用" else "题包已停用"
                    KnowledgePackToggleResult.NotFound -> "题包尚未安装"
                    KnowledgePackToggleResult.StorageUnavailable -> "题包状态保存失败"
                }
            )
        }
    }

    fun toggleBuiltIn(enabled: Boolean) {
        if (operationInProgress || !summaryAvailable) return
        operationInProgress = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                repository.setBuiltInEnabled(enabled)
            }
            operationInProgress = false
            refresh(
                if (!saved) {
                    "内置题库状态保存失败"
                } else if (enabled) {
                    "内置 36 题已启用"
                } else {
                    "内置 36 题已停用"
                }
            )
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = BrandColors.Primary
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("百科题库", color = BrandColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (val state = summaryState) {
                            QuestionBankSummaryLoadState.Loading -> "正在读取题库…"
                            is QuestionBankSummaryLoadState.Loaded -> with(state.summary) {
                                "可用 $totalCount 题 · 内置 $builtInCount/$builtInTotalCount · " +
                                    "启用扩展 $incrementalCount 题"
                            }
                            is QuestionBankSummaryLoadState.Failed -> state.message
                        },
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            if (summaryState is QuestionBankSummaryLoadState.Loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在载入安装和显隐状态，完成前暂不可操作。",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            if (summaryState is QuestionBankSummaryLoadState.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "状态不可用，题包和显隐操作已暂停；重试不会清除本机设置。",
                        modifier = Modifier.weight(1f),
                        color = BrandColors.Warning,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = { refreshToken++ }) { Text("重试") }
                }
            }

            summary?.takeIf { it.totalCount < 6 }?.let { current ->
                Text(
                    "当前仅 ${current.totalCount} 道可用题，百科挑战至少需要 6 道，请启用更多题目。",
                    color = BrandColors.Warning,
                    fontSize = 12.sp
                )
            }

            Text(statusMessage, color = BrandColors.TextSecondary, fontSize = 12.sp)
            if (operationInProgress) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在处理题包…", color = BrandColors.TextSecondary, fontSize = 12.sp)
                }
            }

            Text("本地题包", color = BrandColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            catalog.forEach { item ->
                val installedPack = summary?.installedPacks?.firstOrNull {
                    it.metadata.packageId == item.packageId
                }
                val presentation = bundledPackPresentation(item, installedPack?.metadata)
                QuestionBankPackRow(
                    item = item,
                    installed = summary?.let { installedPack != null },
                    enabled = installedPack?.enabled == true,
                    displayedQuestionCount = presentation.displayedQuestionCount,
                    updateAvailable = presentation.updateAvailable,
                    controlsEnabled = controlsEnabled,
                    unavailableActionLabel = when (summaryState) {
                        QuestionBankSummaryLoadState.Loading -> "读取中"
                        is QuestionBankSummaryLoadState.Failed -> "暂不可用"
                        is QuestionBankSummaryLoadState.Loaded -> "安装"
                    },
                    onToggle = { togglePack(item.packageId, it) },
                    onInstallOrUpdate = {
                        val operation = if (installedPack == null) {
                            PackageManagementOperation.INSTALL
                        } else {
                            PackageManagementOperation.UPDATE
                        }
                        pendingAction = PendingPackageAction(item, operation)
                    },
                    onRemove = {
                        pendingAction = PendingPackageAction(
                            item,
                            PackageManagementOperation.REMOVE
                        )
                    }
                )
                HorizontalDivider(color = BrandColors.OutlineSoft)
            }

            Text("内置题库", color = BrandColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "内置百科基础题",
                        color = BrandColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (val state = summaryState) {
                            QuestionBankSummaryLoadState.Loading -> "36 道本地题目 · 正在读取状态"
                            is QuestionBankSummaryLoadState.Failed -> "36 道本地题目 · 状态不可用"
                            is QuestionBankSummaryLoadState.Loaded -> if (state.summary.builtInEnabled) {
                                "36 道本地题目 · 已启用"
                            } else {
                                "36 道本地题目 · 已停用"
                            }
                        },
                        color = BrandColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = summary?.builtInEnabled == true,
                    onCheckedChange = ::toggleBuiltIn,
                    enabled = controlsEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandColors.Primary,
                        uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                        uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            HorizontalDivider(color = BrandColors.OutlineSoft)
        }
    }

    pendingAction?.let { action ->
        QuestionBankManagementPasswordDialog(
            title = when (action.operation) {
                PackageManagementOperation.INSTALL -> "安装${action.item.title}"
                PackageManagementOperation.UPDATE -> "更新${action.item.title}"
                PackageManagementOperation.REMOVE -> "卸载${action.item.title}"
            },
            isWorking = operationInProgress,
            onDismiss = { if (!operationInProgress) pendingAction = null },
            onConfirm = { password ->
                pendingAction = null
                runPackageAction(action, password)
            }
        )
    }
}

@Composable
private fun QuestionBankPackRow(
    item: BundledKnowledgePackCatalogItem,
    installed: Boolean?,
    enabled: Boolean,
    displayedQuestionCount: Int,
    updateAvailable: Boolean,
    controlsEnabled: Boolean,
    unavailableActionLabel: String,
    onToggle: (Boolean) -> Unit,
    onInstallOrUpdate: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, color = BrandColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${item.displayCategoryName} · $displayedQuestionCount 道本地题目 · " +
                    when (installed) {
                        true -> (if (enabled) "已启用" else "已停用") +
                            if (updateAvailable) " · 可更新" else ""
                        false -> "未安装"
                        null -> if (unavailableActionLabel == "读取中") "正在读取状态" else "状态不可用"
                    },
                color = BrandColors.TextSecondary,
                fontSize = 11.sp
            )
        }
        when (installed) {
            true -> {
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = controlsEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandColors.Primary,
                        uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                        uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(6.dp))
                if (updateAvailable) {
                    TextButton(onClick = onInstallOrUpdate, enabled = controlsEnabled) {
                        Text("更新")
                    }
                }
                TextButton(onClick = onRemove, enabled = controlsEnabled) { Text("卸载") }
            }
            false -> {
                OutlinedButton(onClick = onInstallOrUpdate, enabled = controlsEnabled) {
                    Text("安装")
                }
            }
            null -> {
                OutlinedButton(onClick = {}, enabled = false) { Text(unavailableActionLabel) }
            }
        }
    }
}

@Composable
private fun QuestionBankManagementPasswordDialog(
    title: String,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = BrandColors.TextTertiary)
                    Spacer(Modifier.width(8.dp))
                    Text("请输入固定 6 位题包管理密码。", color = BrandColors.TextSecondary, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { candidate ->
                        password = candidate.filter { it in '0'..'9' }
                            .take(QuestionBankManagementPassword.LENGTH)
                    },
                    label = { Text("管理密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = !isWorking && password.length == QuestionBankManagementPassword.LENGTH
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") } }
    )
}

private fun installResultMessage(
    result: KnowledgeBankInstallResult,
    successVerb: String = "已安装"
): String = when (result) {
    is KnowledgeBankInstallResult.Installed ->
        "$successVerb「${result.metadata.title}」：${result.metadata.questionCount} 题"
    is KnowledgeBankInstallResult.VersionNotNewer -> "该题包已安装，版本 v${result.installedVersion}"
    is KnowledgeBankInstallResult.Rejected -> "题包校验失败：${result.reason.name}"
    KnowledgeBankInstallResult.BundledSampleUnavailable -> "未找到随 App 交付的本地题包"
    KnowledgeBankInstallResult.PasswordInvalid -> "管理密码错误，题包未改变"
    KnowledgeBankInstallResult.StorageUnavailable -> "题包存储不可用，原题库未改变"
}

private fun removalResultMessage(result: KnowledgeBankRemovalResult): String = when (result) {
    KnowledgeBankRemovalResult.Removed -> "题包已卸载"
    KnowledgeBankRemovalResult.AlreadyBuiltInOnly -> "题包尚未安装"
    KnowledgeBankRemovalResult.PasswordInvalid -> "管理密码错误，题包未改变"
    KnowledgeBankRemovalResult.StorageUnavailable -> "题包卸载失败，当前题库未改变"
}

@Composable
internal fun ChallengeUnlockSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) {
        LockUnlockFeaturePreferences(context.applicationContext)
    }
    val credentials = remember(context.applicationContext) {
        CredentialStore(context.applicationContext)
    }
    var challengeEnabled by remember { mutableStateOf(preferences.knowledgeChallengeEnabled) }
    var requireAuth by remember { mutableStateOf(preferences.requireAuthForKnowledgeChallenge) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun updateChallenge(enabled: Boolean) {
        runCatching { preferences.knowledgeChallengeEnabled = enabled }
            .onSuccess {
                challengeEnabled = enabled
                statusMessage = null
            }
            .onFailure { statusMessage = "百科挑战开关保存失败" }
    }

    fun updateRequireAuth(enabled: Boolean) {
        if (!canEnableUnlockAuthentication(enabled, credentials.hasAnyCredential())) {
            statusMessage = "请先设置数字密码或手势密码"
            return
        }
        runCatching { preferences.requireAuthForKnowledgeChallenge = enabled }
            .onSuccess {
                requireAuth = enabled
                statusMessage = null
            }
            .onFailure { statusMessage = "密码验证开关保存失败" }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            LockUnlockFeatureToggle(
                title = "百科挑战解锁",
                description = "显示自律锁屏答题通关免成长值的入口",
                checked = challengeEnabled,
                onCheckedChange = ::updateChallenge
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = BrandColors.OutlineSoft
            )
            LockUnlockFeatureToggle(
                title = "百科挑战密码验证",
                description = "开启后答题挑战通关仍需验证数字密码或手势，关闭则直接解锁",
                checked = requireAuth,
                onCheckedChange = ::updateRequireAuth
            )
            statusMessage?.let {
                Text(
                    it,
                    color = BrandColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
