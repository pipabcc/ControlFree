package com.example.controlfree.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.ai.AiCompanionPreferences
import com.example.controlfree.ai.AiConnectionTestResult
import com.example.controlfree.ai.AiInterventionCoordinator
import com.example.controlfree.ai.AiPersonality
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import com.example.controlfree.security.AiApiKeyStore
import com.example.controlfree.security.AiApiKeyWriteResult
import com.example.controlfree.theme.BrandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AiCompanionSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val preferences = remember { AiCompanionPreferences.getInstance(context) }

    var companionEnabled by remember { mutableStateOf(preferences.isCompanionEnabled()) }
    var lockChatEnabled by remember { mutableStateOf(preferences.isLockChatEnabled()) }
    var petPersonality by remember { mutableStateOf(preferences.getPetPersonality()) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun updateCompanionEnabled(enabled: Boolean) {
        if (isBusy) return
        val previousValue = companionEnabled
        companionEnabled = enabled
        errorMessage = ""
        isBusy = true
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                preferences.setCompanionEnabled(enabled)
            }
            if (!saved) {
                companionEnabled = previousValue
                errorMessage = "设置保存失败，请稍后重试"
            }
            isBusy = false
        }
    }

    fun updateLockChatEnabled(enabled: Boolean) {
        if (isBusy) return
        val previousValue = lockChatEnabled
        lockChatEnabled = enabled
        errorMessage = ""
        isBusy = true
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                preferences.setLockChatEnabled(enabled)
            }
            if (!saved) {
                lockChatEnabled = previousValue
                errorMessage = "设置保存失败，请稍后重试"
            }
            isBusy = false
        }
    }

    fun updatePetPersonality(personality: AiPersonality) {
        if (isBusy) return
        val previousValue = petPersonality
        petPersonality = personality
        errorMessage = ""
        isBusy = true
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                preferences.setPetPersonality(personality)
            }
            if (!saved) {
                petPersonality = previousValue
                errorMessage = "设置保存失败，请稍后重试"
            }
            isBusy = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AI 建议",
                        color = BrandColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "暂停或跳过前，缓一缓。",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = companionEnabled,
                    onCheckedChange = ::updateCompanionEnabled,
                    modifier = Modifier.testTag("ai_companion_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandColors.Primary,
                        uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                        uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AI 角色聊天",
                        color = BrandColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "允许在锁定状态下进行多轮聊天交流。",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = lockChatEnabled,
                    enabled = companionEnabled,
                    onCheckedChange = ::updateLockChatEnabled,
                    modifier = Modifier.testTag("ai_lock_chat_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandColors.Primary,
                        uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                        uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "小芽性格定制",
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiPersonality.entries.forEach { personality ->
                        val isSelected = petPersonality == personality
                        val cardColor = if (isSelected) BrandColors.Primary.copy(alpha = 0.12f) else BrandColors.SurfaceCard
                        val borderColor = if (isSelected) BrandColors.Primary else BrandColors.Outline.copy(alpha = 0.25f)
                        val textColor = if (isSelected) BrandColors.Primary else BrandColors.TextSecondary
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.2.dp, borderColor, shape = CardDefaults.shape)
                                .clickable(enabled = companionEnabled && !isBusy) {
                                    updatePetPersonality(personality)
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    personality.displayName.substringBefore(" "),
                                    color = BrandColors.TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                                Text(
                                    personality.displayName.substringAfter("(").substringBefore(")"),
                                    color = textColor,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            if (errorMessage.isNotBlank()) {
                Text(
                    errorMessage,
                    color = BrandColors.Danger,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun AiApiKeySettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val apiKeyStore = remember { AiApiKeyStore.getInstance(context) }
    val coordinator = remember { AiInterventionCoordinator.getInstance(context) }

    var hasSavedKey by remember { mutableStateOf(apiKeyStore.hasSavedKey()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember {
        mutableStateOf(
            if (hasSavedKey) "API Key 已加密保存并生效中"
            else "未配置 API Key 时，系统会自动使用本地轻量建议"
        )
    }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var isApiKeyFocused by remember { mutableStateOf(false) }
    val apiKeyBringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)

    LaunchedEffect(isApiKeyFocused, imeBottom) {
        if (isApiKeyFocused && imeBottom > 0) {
            // 等待输入法动画和设置页滚动视口稳定，再请求定位；聚焦瞬间请求会过早失效。
            delay(API_KEY_KEYBOARD_SETTLE_MILLIS)
            apiKeyBringIntoViewRequester.bringIntoView()
        }
    }

    fun saveApiKey() {
        if (isBusy) return
        val candidate = apiKeyInput
        isBusy = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { apiKeyStore.save(candidate) }
            when (result) {
                AiApiKeyWriteResult.SAVED -> {
                    apiKeyInput = ""
                    showApiKey = false
                    hasSavedKey = true
                    statusMessage = "API Key 已加密保存并生效中"
                }
                AiApiKeyWriteResult.INVALID_KEY ->
                    statusMessage = "API Key 格式无效，请检查是否以 sk- 开头"
                AiApiKeyWriteResult.STORAGE_UNAVAILABLE ->
                    statusMessage = "系统安全存储暂不可用，保存密钥失败"
            }
            isBusy = false
        }
    }

    fun testConnection() {
        if (isBusy) return
        val candidate = apiKeyInput.takeIf(String::isNotBlank)
        isBusy = true
        statusMessage = "正在测试 DeepSeek 连接…"
        coroutineScope.launch {
            try {
                val result = coordinator.testConnection(candidate)
                if (candidate == null && result == AiConnectionTestResult.SECURE_STORAGE_UNAVAILABLE) {
                    hasSavedKey = false
                }
                statusMessage = connectionResultMessage(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                statusMessage = "连接测试失败，已保留本地服务能力"
            } finally {
                isBusy = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Assistant, null, tint = BrandColors.Primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "DeepSeek API Key 设置",
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (hasSavedKey) {
                    IconButton(
                        enabled = !isBusy,
                        onClick = { showClearConfirmation = true },
                        modifier = Modifier.testTag("clear_deepseek_api_key")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "清除已保存的 API Key",
                            tint = BrandColors.Danger
                        )
                    }
                }
            }

            if (!hasSavedKey) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { value ->
                        if (value.length <= MAX_API_KEY_INPUT_LENGTH) apiKeyInput = value
                    },
                    enabled = !isBusy,
                    label = { Text("API Key") },
                    placeholder = { Text("输入 sk- 密钥") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    // 仅在界面层遮罩密钥；Password 输入类型会触发部分厂商的安全键盘。
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "隐藏 API Key" else "显示 API Key"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(apiKeyBringIntoViewRequester)
                        .onFocusChanged { focusState ->
                            isApiKeyFocused = focusState.isFocused
                        }
                        .testTag("deepseek_api_key_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = ::testConnection,
                        enabled = !isBusy && (apiKeyInput.isNotBlank() || hasSavedKey),
                        modifier = Modifier.weight(1f).testTag("test_deepseek_connection")
                    ) {
                        Text("测试连接")
                    }
                    Button(
                        onClick = ::saveApiKey,
                        enabled = !isBusy && apiKeyInput.isNotBlank(),
                        modifier = Modifier.weight(1f).testTag("save_deepseek_api_key")
                    ) {
                        Text("安全保存")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    statusMessage,
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).testTag("ai_companion_status")
                )
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showClearConfirmation = false },
            title = { Text("清除 DeepSeek API Key？") },
            text = { Text("清除后将停止使用联网的大语言模型，系统会自动降级使用本地建议。") },
            confirmButton = {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        if (isBusy) return@Button
                        isBusy = true
                        coroutineScope.launch {
                            val keyCleared = withContext(Dispatchers.IO) {
                                apiKeyStore.clear()
                            }
                            if (keyCleared) {
                                hasSavedKey = false
                                apiKeyInput = ""
                                showClearConfirmation = false
                                statusMessage = "API Key 已清除，已降级为本地建议模式"
                            } else {
                                statusMessage = "API Key 清除失败，请稍后重试"
                            }
                            isBusy = false
                        }
                    }
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = { showClearConfirmation = false }
                ) { Text("取消") }
            }
        )
    }
}

internal fun connectionResultMessage(result: AiConnectionTestResult): String = when (result) {
    AiConnectionTestResult.SUCCESS -> "连接成功，DeepSeek 可用"
    AiConnectionTestResult.MISSING_API_KEY -> "请先输入或保存 API Key"
    AiConnectionTestResult.INVALID_API_KEY -> "API Key 无效或无模型访问权限"
    AiConnectionTestResult.SECURE_STORAGE_UNAVAILABLE -> "系统安全存储不可用，请重新保存密钥"
    AiConnectionTestResult.NETWORK_UNAVAILABLE -> "网络连接失败，锁屏时会自动使用本地建议"
    AiConnectionTestResult.SERVICE_BUSY -> "DeepSeek 服务繁忙或限流，请稍后再试"
    AiConnectionTestResult.INVALID_RESPONSE -> "服务响应无法识别，请稍后重试"
}

private const val MAX_API_KEY_INPUT_LENGTH = 256
private const val API_KEY_KEYBOARD_SETTLE_MILLIS = 120L
