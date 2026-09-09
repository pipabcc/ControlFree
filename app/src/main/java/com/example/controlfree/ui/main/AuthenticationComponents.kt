package com.example.controlfree.ui.main

import android.content.Context
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pattern
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.controlfree.R
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.AllowlistRepository
import com.example.controlfree.data.AllowlistRepositoryState
import com.example.controlfree.data.AllowlistSaveResult
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.security.CredentialMethod
import com.example.controlfree.security.CredentialRemovalResult
import com.example.controlfree.security.PatternLockView
import com.example.controlfree.security.VerificationResult
import com.example.controlfree.security.VerificationStatus
import com.example.controlfree.security.preferredCredentialMethods
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.layout.WindowHeightClass
import com.example.controlfree.ui.layout.rememberResponsiveLayoutSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AuthenticationGate(
    credentials: CredentialStore,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val taskExecutor = rememberCredentialTaskExecutor()
    val initialMethods = remember(credentials) {
        preferredCredentialMethods(
            hasPassword = credentials.hasPassword(),
            hasGesture = credentials.hasGesture()
        )
    }
    var mode by remember(credentials) {
        mutableStateOf(initialMethods.firstOrNull() ?: CredentialMethod.PASSWORD)
    }
    val passwordBuffer = remember(credentials) { NumericPasswordInputBuffer() }
    var password by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var isUnlocked by remember { mutableStateOf(false) }
    var setupGeneration by remember { mutableIntStateOf(0) }
    var metadataGeneration by remember { mutableIntStateOf(0) }
    val expectedPasswordLength = remember(credentials, metadataGeneration) {
        credentials.getPasswordLength()
    }
    val requiresInitialSetup = remember(credentials, metadataGeneration) {
        credentials.requiresInitialPasswordSetup()
    }
    val gestureConfigured = remember(credentials, metadataGeneration) { credentials.hasGesture() }
    val passwordConfigured = remember(credentials, metadataGeneration) { credentials.hasPassword() }
    val hasAnyCredential = remember(credentials, metadataGeneration) { credentials.hasAnyCredential() }

    LaunchedEffect(hasAnyCredential) {
        // 凭据被移除或损坏后立即撤销本次会话，必须重新完成初始化，不能自动放行。
        if (!hasAnyCredential) {
            isUnlocked = false
        }
    }

    DisposableEffect(lifecycleOwner, taskExecutor) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // 每次离开前台都使当前验证结果失效，禁止后台完成后绕过下一次进入验证。
                    taskExecutor.invalidate()
                    isWorking = false
                    isUnlocked = false
                    password = passwordBuffer.reset()
                    setupGeneration++
                }
                Lifecycle.Event.ON_START -> {
                    isUnlocked = false
                    metadataGeneration++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isUnlocked) return

    if (requiresInitialSetup || !hasAnyCredential) {
        key(setupGeneration) {
            InitialPasswordSetup(
                isSaving = isWorking,
                onSave = save@ { newPassword ->
                    if (isWorking) return@save
                    val accepted = taskExecutor.submit(
                        task = { credentials.setPassword(newPassword) },
                        onResult = { result ->
                            isWorking = false
                            result.fold(
                                onSuccess = {
                                    isUnlocked = true
                                    metadataGeneration++
                                    onUnlocked()
                                },
                                onFailure = {
                                    setupGeneration++
                                    showCredentialOperationFailureToast(context)
                                }
                            )
                        }
                    )
                    if (accepted) {
                        isWorking = true
                    } else {
                        setupGeneration++
                        showCredentialOperationFailureToast(context)
                    }
                }
            )
        }
        return
    }

    fun resetEnteredPassword() {
        password = passwordBuffer.reset()
    }

    fun verifyEnteredPassword(candidate: String = passwordBuffer.value) {
        if (isWorking || isUnlocked) return
        val passwordSnapshot = candidate
        val accepted = taskExecutor.submit(
            task = { credentials.verifyPassword(passwordSnapshot) },
            onResult = { operation ->
                isWorking = false
                operation.fold(
                    onSuccess = { result ->
                        if (result.isSuccess) {
                            isUnlocked = true
                            resetEnteredPassword()
                            onUnlocked()
                        } else {
                            resetEnteredPassword()
                        }
                        showVerificationToast(context, result)
                    },
                    onFailure = {
                        resetEnteredPassword()
                        showCredentialOperationFailureToast(context)
                    }
                )
            }
        )
        if (accepted) {
            isWorking = true
        } else {
            resetEnteredPassword()
            showCredentialOperationFailureToast(context)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.OverlaySurface)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        val keySize = when {
            maxHeight < 420.dp -> 46.dp
            maxHeight < 620.dp -> 54.dp
            else -> 64.dp
        }
        val gestureHeight = (maxHeight - 240.dp).coerceIn(180.dp, 290.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = "App 安全锁",
                tint = Color.Unspecified,
                modifier = Modifier.size(58.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.app_name),
                color = BrandColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text("完成身份验证后进入", color = BrandColors.TextTertiary, fontSize = 12.sp)
            Spacer(Modifier.height(28.dp))

            if (mode == CredentialMethod.PASSWORD) {
                Text(
                    if (expectedPasswordLength == null) {
                        "首次升级：输完旧密码后点击键盘下方确认"
                    } else {
                        "输入正确后将自动进入"
                    },
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                NumericPasswordPad(
                    value = password,
                    onEdit = { edit ->
                        val result = passwordBuffer.apply(
                            edit = edit,
                            inputLimit = numericPasswordInputLimit(expectedPasswordLength),
                            expectedLength = expectedPasswordLength
                        )
                        password = result.value
                        result.completedSnapshot?.let(::verifyEnteredPassword)
                    },
                    expectedLength = expectedPasswordLength,
                    onConfirm = if (expectedPasswordLength == null) {
                        { verifyEnteredPassword() }
                    } else {
                        null
                    },
                    enabled = !isWorking,
                    keySize = keySize
                )
            } else {
                val patternNormalColor = BrandColors.TextTertiary.toArgb()
                val patternSelectedColor = BrandColors.Primary.toArgb()
                Text("绘制已设置的手势", color = BrandColors.TextSecondary)
                AndroidView(
                    factory = { PatternLockView(it) },
                    update = { view ->
                        view.setColors(patternNormalColor, patternSelectedColor)
                        view.isEnabled = !isWorking
                        view.onPatternComplete = patternComplete@ { pattern ->
                            if (isWorking) return@patternComplete
                            val patternSnapshot = pattern.toList()
                            val accepted = taskExecutor.submit(
                                task = { credentials.verifyGesture(patternSnapshot) },
                                onResult = { operation ->
                                    isWorking = false
                                    operation.fold(
                                        onSuccess = { result ->
                                            if (result.isSuccess) onUnlocked()
                                            showVerificationToast(context, result)
                                        },
                                        onFailure = { showCredentialOperationFailureToast(context) }
                                    )
                                }
                            )
                            if (accepted) {
                                isWorking = true
                                view.isEnabled = false
                            } else {
                                showCredentialOperationFailureToast(context)
                            }
                        }
                    },
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .height(gestureHeight)
                )
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = BrandColors.Primary,
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                if (gestureConfigured && passwordConfigured) {
                    TextButton(onClick = {
                        taskExecutor.invalidate()
                        isWorking = false
                        resetEnteredPassword()
                        mode = if (mode == CredentialMethod.PASSWORD) {
                            CredentialMethod.GESTURE
                        } else {
                            CredentialMethod.PASSWORD
                        }
                    }) {
                        Icon(Icons.Default.Pattern, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (mode == CredentialMethod.PASSWORD) "手势密码" else "数字密码")
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialPasswordSetup(
    isSaving: Boolean,
    onSave: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.OverlaySurface)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        val keySize = if (maxHeight < 620.dp) 54.dp else 64.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = "首次设置",
                tint = Color.Unspecified,
                modifier = Modifier.size(58.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "设置应急密码",
                color = BrandColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "首次使用必须先设置；监督开启后，重新进入 App 需要验证",
                color = BrandColors.TextTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(18.dp))
            PasswordSetupPad(onSave = onSave, enabled = !isSaving, keySize = keySize)
            if (isSaving) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = BrandColors.Primary,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun AuthenticationSettingsCard(
    credentials: CredentialStore
) {
    val context = LocalContext.current
    val taskExecutor = rememberCredentialTaskExecutor()
    val responsiveLayout = rememberResponsiveLayoutSpec()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showGestureDialog by remember { mutableStateOf(false) }
    var passwordConfigured by remember { mutableStateOf(credentials.hasPassword()) }
    var gestureConfigured by remember { mutableStateOf(credentials.hasGesture()) }
    var isWorking by remember { mutableStateOf(false) }
    var passwordFormGeneration by remember { mutableIntStateOf(0) }
    var gestureFormGeneration by remember { mutableIntStateOf(0) }

    fun refreshCredentialState() {
        passwordConfigured = credentials.hasPassword()
        gestureConfigured = credentials.hasGesture()
    }

    fun submitCredentialChange(
        task: () -> Unit,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        if (isWorking) return
        val accepted = taskExecutor.submit(
            task = task,
            onResult = { result ->
                isWorking = false
                result.fold(
                    onSuccess = { onSuccess() },
                    onFailure = {
                        onFailure()
                        showCredentialOperationFailureToast(context)
                    }
                )
            }
        )
        if (accepted) {
            isWorking = true
        } else {
            onFailure()
            showCredentialOperationFailureToast(context)
        }
    }

    fun submitCredentialRemoval(
        task: () -> CredentialRemovalResult,
        successMessage: String
    ) {
        if (isWorking) return
        val accepted = taskExecutor.submit(
            task = task,
            onResult = { result ->
                isWorking = false
                result.fold(
                    onSuccess = { removalResult ->
                        refreshCredentialState()
                        when (removalResult) {
                            CredentialRemovalResult.REMOVED ->
                                Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                            CredentialRemovalResult.LAST_CREDENTIAL ->
                                Toast.makeText(
                                    context,
                                    "请先设置另一种验证方式",
                                    Toast.LENGTH_SHORT
                                ).show()
                            CredentialRemovalResult.NOT_CONFIGURED -> Unit
                        }
                    },
                    onFailure = { showCredentialOperationFailureToast(context) }
                )
            }
        )
        if (accepted) {
            isWorking = true
        } else {
            showCredentialOperationFailureToast(context)
        }
    }

    fun dismissPasswordDialog() {
        if (isWorking) return
        taskExecutor.invalidate()
        passwordFormGeneration++
        showPasswordDialog = false
    }

    fun dismissGestureDialog() {
        if (isWorking) return
        taskExecutor.invalidate()
        gestureFormGeneration++
        showGestureDialog = false
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = com.example.controlfree.R.drawable.ic_password_setting),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFF1565C0),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "密码设置",
                    color = BrandColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showPasswordDialog = true },
                    enabled = !isWorking,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (passwordConfigured) "重设数字密码" else "设置数字密码", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { showGestureDialog = true },
                    enabled = !isWorking,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Pattern, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (gestureConfigured) "重设手势密码" else "设置手势密码", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
            }
            if (passwordConfigured || gestureConfigured) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (passwordConfigured) {
                        TextButton(
                            enabled = !isWorking,
                            onClick = {
                                submitCredentialRemoval(
                                    task = credentials::clearPassword,
                                    successMessage = "数字密码已移除"
                                )
                            }
                        ) {
                            Text("移除密码")
                        }
                    }
                    if (gestureConfigured) {
                        TextButton(
                            enabled = !isWorking,
                            onClick = {
                                submitCredentialRemoval(
                                    task = credentials::clearGesture,
                                    successMessage = "手势密码已移除"
                                )
                            }
                        ) {
                            Text("移除手势")
                        }
                    }
                }
            }
        }
    }

    if (showPasswordDialog) {
        val titleText = if (passwordConfigured) "重设数字密码" else "设置数字密码"
        AlertDialog(
            onDismissRequest = ::dismissPasswordDialog,
            title = { Text(titleText) },
            text = {
                key(passwordFormGeneration) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PasswordSetupPad(
                            keySize = if (
                                responsiveLayout.heightClass == WindowHeightClass.COMPACT
                            ) 48.dp else 56.dp,
                            enabled = !isWorking,
                            onSave = { newPassword ->
                                submitCredentialChange(
                                    task = { credentials.setPassword(newPassword) },
                                    onSuccess = {
                                        passwordConfigured = true
                                        showPasswordDialog = false
                                        Toast.makeText(context, "数字密码已设置", Toast.LENGTH_SHORT).show()
                                    },
                                    onFailure = { passwordFormGeneration++ }
                                )
                            }
                        )
                        if (isWorking) {
                            Spacer(Modifier.height(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = ::dismissPasswordDialog, enabled = !isWorking) { Text("取消") }
            }
        )
    }

    if (showGestureDialog) {
        key(gestureFormGeneration) {
            GestureSetupDialog(
                enabled = !isWorking,
                isReset = gestureConfigured,
                onDismiss = ::dismissGestureDialog,
                onSave = { pattern ->
                    val patternSnapshot = pattern.toList()
                    submitCredentialChange(
                        task = { credentials.setGesture(patternSnapshot) },
                        onSuccess = {
                            gestureConfigured = true
                            showGestureDialog = false
                            Toast.makeText(context, "手势密码已保存", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { gestureFormGeneration++ }
                    )
                }
            )
        }
    }
}

@Composable
private fun PasswordSetupPad(
    onSave: (String) -> Unit,
    enabled: Boolean = true,
    keySize: androidx.compose.ui.unit.Dp = 64.dp
) {
    val context = LocalContext.current
    val inputBuffer = remember { NumericPasswordInputBuffer() }
    var firstPassword by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("输入至少 4 位数字，然后点击键盘下方确认") }
    val expectedLength = firstPassword?.length

    fun acceptFirstEntry() {
        if (!enabled) return
        if (input.length < CredentialStore.MIN_PASSWORD_LENGTH) return
        firstPassword = input
        input = inputBuffer.reset()
        message = "请再次输入，新密码匹配后自动保存"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = BrandColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        NumericPasswordPad(
            value = input,
            onEdit = { edit ->
                val result = inputBuffer.apply(
                    edit = edit,
                    inputLimit = numericPasswordInputLimit(expectedLength),
                    expectedLength = expectedLength
                )
                input = result.value
                result.completedSnapshot?.let { completed ->
                    val first = firstPassword ?: return@let
                    if (completed == first) {
                        onSave(first)
                    } else {
                        firstPassword = null
                        input = inputBuffer.reset()
                        message = "两次输入不一致，请重新设置"
                        Toast.makeText(context, "两次输入不一致", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            expectedLength = expectedLength,
            onConfirm = if (firstPassword == null) ::acceptFirstEntry else null,
            enabled = enabled,
            keySize = keySize
        )
    }
}

@Composable
private fun GestureSetupDialog(
    enabled: Boolean,
    isReset: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<Int>) -> Unit
) {
    val responsiveLayout = rememberResponsiveLayoutSpec()
    val patternNormalColor = BrandColors.TextTertiary.toArgb()
    val patternSelectedColor = BrandColors.Primary.toArgb()
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var message by remember { mutableStateOf("请绘制新的手势密码") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isReset) "重设手势密码" else "设置手势密码") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message)
                AndroidView(
                    factory = { PatternLockView(it) },
                    update = { view ->
                        view.setColors(patternNormalColor, patternSelectedColor)
                        view.isEnabled = enabled
                        view.onPatternComplete = patternComplete@ { pattern ->
                            if (!enabled) return@patternComplete
                            when {
                                pattern.distinct().size < CredentialStore.MIN_GESTURE_POINTS -> {
                                    message = "至少连接 ${CredentialStore.MIN_GESTURE_POINTS} 个点"
                                }
                                firstPattern == null -> {
                                    firstPattern = pattern.toList()
                                    message = "请再次绘制进行确认"
                                }
                                firstPattern == pattern -> onSave(pattern)
                                else -> {
                                    firstPattern = null
                                    message = "两次手势不一致，请重新设置"
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .height(
                            if (responsiveLayout.heightClass == WindowHeightClass.COMPACT) {
                                200.dp
                            } else {
                                300.dp
                            }
                        )
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                if (!enabled) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("取消")
            }
        }
    )
}

@Composable
fun AllowlistDialog(
    repository: AllowlistRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val responsiveLayout = rememberResponsiveLayoutSpec()
    val repositoryState by repository.state.collectAsState()
    val snapshot = repositoryState.snapshot
    var selectedPackages by remember { mutableStateOf(emptySet<String>()) }
    var selectionInitialized by remember { mutableStateOf(false) }
    var selectionEdited by remember { mutableStateOf(false) }
    var editBaseCustomPackages by remember { mutableStateOf<Set<String>?>(null) }
    var search by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveErrorMessage by remember { mutableStateOf<String?>(null) }
    val hasAppData = snapshot?.hasAppPartitionData == true
    val hasSelectionConflict = selectionEdited &&
        editBaseCustomPackages != null &&
        snapshot?.customPackages != editBaseCustomPackages
    val filtered = remember(search, snapshot?.version) {
        snapshot?.selectableApps.orEmpty().filter { app ->
            search.isBlank() || app.label.contains(search, ignoreCase = true) ||
                app.packageName.contains(search, ignoreCase = true)
        }
    }

    LaunchedEffect(repository) {
        repository.refresh()
    }
    LaunchedEffect(snapshot?.version, hasAppData) {
        if (hasAppData && (!selectionInitialized || !selectionEdited)) {
            selectedPackages = snapshot.customPackages
            selectionInitialized = true
            editBaseCustomPackages = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("锁屏白名单") },
        text = {
            if (!hasAppData) {
                when (val state = repositoryState) {
                    AllowlistRepositoryState.Uninitialized,
                    is AllowlistRepositoryState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(14.dp))
                            Text("正在加载 App 列表…")
                        }
                    }
                    is AllowlistRepositoryState.Failed,
                    is AllowlistRepositoryState.Stale -> Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("App 列表加载失败")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when (state) {
                                    is AllowlistRepositoryState.Failed -> state.error.message
                                    is AllowlistRepositoryState.Stale -> state.error.message
                                },
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = repository::refresh) { Text("重试") }
                        }
                    }
                    is AllowlistRepositoryState.Ready -> Unit
                }
            } else {
                Column {
                    when (val state = repositoryState) {
                        is AllowlistRepositoryState.Loading -> Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在更新 App 列表…",
                                fontSize = 12.sp,
                                color = BrandColors.TextTertiary
                            )
                        }
                        is AllowlistRepositoryState.Stale -> Column {
                            Text(
                                state.error.message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                            OutlinedButton(onClick = repository::refresh) { Text("重新加载") }
                        }
                        else -> Unit
                    }
                    Text("电话和短信始终允许；设置、桌面和安装器不可添加。")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        enabled = !isSaving,
                        label = { Text("搜索 App") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        Modifier.height(
                            when {
                                responsiveLayout.heightClass == WindowHeightClass.COMPACT -> 160.dp
                                responsiveLayout.isLargeFont -> 300.dp
                                else -> 420.dp
                            }
                        )
                    ) {
                        items(filtered, key = AllowedApp::packageName) { app ->
                            val required = app.packageName in snapshot.requiredPackages
                            val checked = required || app.packageName in selectedPackages
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AllowlistAppIcon(repository, app, snapshot.version)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1)
                                    if (required) {
                                        Text(
                                            "系统白名单",
                                            fontSize = 11.sp,
                                            color = BrandColors.TextTertiary
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = checked,
                                    enabled = !required && !isSaving,
                                    onCheckedChange = { enabled ->
                                        if (!selectionEdited) {
                                            editBaseCustomPackages = snapshot.customPackages
                                        }
                                        selectionEdited = true
                                        selectedPackages = if (enabled) {
                                            selectedPackages + app.packageName
                                        } else {
                                            selectedPackages - app.packageName
                                        }
                                    }
                                )
                            }
                        }
                    }
                    saveErrorMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    if (hasSelectionConflict) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "白名单已在后台变化，请重新载入后再保存。",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = {
                                selectedPackages = snapshot.customPackages
                                selectionEdited = false
                                editBaseCustomPackages = null
                            }
                        ) {
                            Text("载入最新选择")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (hasAppData) {
                Button(
                    enabled = !isSaving && !hasSelectionConflict,
                    modifier = Modifier.width(96.dp),
                    onClick = {
                        val packagesToSave = selectedPackages.toSet()
                        val expectedPackages =
                            editBaseCustomPackages ?: snapshot.customPackages
                        isSaving = true
                        saveErrorMessage = null
                        repository.saveCustomPackages(
                            packages = packagesToSave,
                            expectedCustomPackages = expectedPackages
                        ) { result ->
                            isSaving = false
                            when (result) {
                                AllowlistSaveResult.SAVED -> onSaved()
                                AllowlistSaveResult.CONFLICT -> {
                                    saveErrorMessage = "白名单已在后台变化，请载入最新选择后重试"
                                }
                                AllowlistSaveResult.FAILED -> {
                                    saveErrorMessage = "保存失败，请重试"
                                }
                            }
                        }
                    }
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("保存中")
                    } else {
                        Text("保存")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
        }
    )
}

@Composable
private fun AllowlistAppIcon(
    repository: AllowlistRepository,
    app: AllowedApp,
    snapshotVersion: Long
) {
    val icon by produceState(initialValue = app.icon, app.packageName, snapshotVersion) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    repository.getApplicationIcon(app.packageName)
                } catch (_: RuntimeException) {
                    null
                }
            }
        }
    }
    AndroidView(
        factory = { ImageView(it) },
        update = { imageView -> imageView.setImageDrawable(icon) },
        modifier = Modifier.size(40.dp)
    )
}

internal suspend fun persistAllowlistSelection(
    selectedPackages: Set<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    persist: (Set<String>) -> Unit
) {
    val selectionSnapshot = selectedPackages.toSet()
    withContext(dispatcher) {
        persist(selectionSnapshot)
    }
}

@Composable
private fun rememberCredentialTaskExecutor(): CredentialTaskExecutor {
    val context = LocalContext.current
    val executor = remember(context) {
        CredentialTaskExecutor(ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(executor) {
        onDispose(executor::close)
    }
    return executor
}

private fun showVerificationToast(context: Context, result: VerificationResult) {
    val message = when (result.status) {
        VerificationStatus.SUCCESS -> "验证成功"
        VerificationStatus.FAILURE -> "验证失败，请重试"
        VerificationStatus.LOCKED -> "尝试次数过多，请 ${result.retryAfterSeconds} 秒后重试"
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun showCredentialOperationFailureToast(context: Context) {
    Toast.makeText(context, "身份验证操作失败，请重试", Toast.LENGTH_SHORT).show()
}
