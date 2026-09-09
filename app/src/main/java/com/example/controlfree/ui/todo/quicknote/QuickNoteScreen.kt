package com.example.controlfree.ui.todo.quicknote

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import com.example.controlfree.ui.todo.components.CardActionHintStore
import com.example.controlfree.ui.todo.components.CardActionHintTab
import com.example.controlfree.ui.todo.components.TodoEmptyStatePanel
import com.example.controlfree.ui.todo.components.message
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.FolderOpen
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.controlfree.ExternalResultExpectation
import com.example.controlfree.productivity.quicknote.ParsedQuickNote
import com.example.controlfree.productivity.quicknote.QuickNoteIntent
import com.example.controlfree.productivity.quicknote.QuickNoteRecurrence
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.showWidgetPinResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.controlfree.ui.todo.viewmodel.QuickNoteAction
import com.example.controlfree.ui.todo.viewmodel.QuickNoteComposerState
import com.example.controlfree.ui.todo.viewmodel.QuickNoteViewModel
import com.example.controlfree.widget.ControlFreeWidgetPinning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QuickNoteScreen(
    viewModel: QuickNoteViewModel,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    createRequestRevision: Long? = null,
    onCreateRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val notes by viewModel.inboxNotes.collectAsStateWithLifecycle()
    val isInboxDataLoaded by viewModel.isInboxDataLoaded.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val busyActions by viewModel.busyActions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val cardHintStore = remember(context.applicationContext) {
        CardActionHintStore(context.applicationContext)
    }
    val pendingAssistantConfirm by viewModel.pendingAssistantConfirmation.collectAsStateWithLifecycle()
    var showAttachmentSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { wasCaptured ->
        val outputUri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        if (outputUri == null) return@rememberLauncherForActivityResult

        // 部分厂商相机点“对号”确认后仍回调 RESULT_CANCELED（wasCaptured=false），
        // 不能仅凭返回值丢弃照片；以照片数据是否实际落盘为准。
        if (!wasCaptured && !hasCapturedImageData(context, outputUri)) {
            discardPendingCameraImage(context, outputUri)
            return@rememberLauncherForActivityResult
        }

        runCatching {
            publishPendingCameraImage(context, outputUri)
            viewModel.attachImage(outputUri)
        }.onFailure { error ->
            discardPendingCameraImage(context, outputUri)
            viewModel.reportMessage("保存照片失败: ${error.localizedMessage ?: "未知错误"}")
        }
    }

    // 部分厂商系统在跳转相机期间干预宿主 Activity，TakePicture 的结果回调可能丢失。
    // 回到前台时若仍存在待处理的拍照记录（onActivityResult 先于 ON_RESUME，正常路径
    // 到这里已清空），按照片是否实际落盘来恢复或清理，保证拍完的照片不会凭空消失。
    // cameraRoundTripStarted 确保只在确实去过相机（应用暂停过）之后才触发兜底，
    // 避免权限弹窗关闭等瞬时 ON_RESUME 把刚创建的空照片记录误删。
    var cameraRoundTripStarted by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (pendingCameraUri != null) cameraRoundTripStarted = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!cameraRoundTripStarted) return@LifecycleEventObserver
                    cameraRoundTripStarted = false
                    val outputUri = pendingCameraUri?.let(Uri::parse)
                        ?: return@LifecycleEventObserver
                    pendingCameraUri = null
                    if (hasCapturedImageData(context, outputUri)) {
                        runCatching {
                            publishPendingCameraImage(context, outputUri)
                            viewModel.attachImage(outputUri)
                        }.onFailure { error ->
                            discardPendingCameraImage(context, outputUri)
                            viewModel.reportMessage(
                                "保存照片失败: ${error.localizedMessage ?: "未知错误"}"
                            )
                        }
                    } else {
                        discardPendingCameraImage(context, outputUri)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (cameraPermissions.all { permissions[it] == true }) {
            runCatching {
                createPendingCameraImage(context).also { outputUri ->
                    pendingCameraUri = outputUri.toString()
                    ExternalResultExpectation.expect()
                    cameraLauncher.launch(outputUri)
                }
            }.onFailure { error ->
                pendingCameraUri?.let(Uri::parse)?.let { discardPendingCameraImage(context, it) }
                pendingCameraUri = null
                viewModel.reportMessage("无法启动相机: ${error.localizedMessage ?: "未知错误"}")
            }
        } else {
            viewModel.reportMessage("需要相机和照片存储权限才能拍照，请在系统设置中开启")
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val hasPersistentAccess = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
            if (hasPersistentAccess) {
                viewModel.attachImage(uri)
            } else {
                viewModel.importPhotoPickerImage(uri)
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.attachImage(uri)
            } catch (_: Exception) {
                viewModel.reportMessage("文件提供方不支持长期读取，请换一个文件")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect(snackbarHostState::showSnackbar)
    }

    LaunchedEffect(notes.isNotEmpty(), cardHintStore) {
        if (cardHintStore.shouldShowWhenReady(CardActionHintTab.QUICK_NOTE, notes.isNotEmpty())) {
            snackbarHostState.showSnackbar(
                message = CardActionHintTab.QUICK_NOTE.message(),
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            cardHintStore.markShown(CardActionHintTab.QUICK_NOTE)
        }
    }

    val listState = rememberLazyListState()
    var highlightedNoteId by remember { mutableStateOf<String?>(null) }
    var lastNotesCount by remember { mutableStateOf(notes.size) }
    LaunchedEffect(notes.size) {
        if (notes.size > lastNotesCount && notes.isNotEmpty()) {
            listState.animateScrollToItem(notes.size - 1)
        }
        lastNotesCount = notes.size
    }
    LaunchedEffect(searchNavigationRequest?.revision, notes, isInboxDataLoaded) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        if (!isInboxDataLoaded) return@LaunchedEffect
        val index = notes.indexOfFirst { it.id == request.entityId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            highlightedNoteId = request.entityId
        }
        onSearchNavigationConsumed(request)
        if (index >= 0) {
            delay(1_800)
            if (highlightedNoteId == request.entityId) highlightedNoteId = null
        }
    }

    val deleteEditingWithUndo: () -> Unit = {
        coroutineScope.launch {
            val snapshot = viewModel.deleteEditingQuickNoteForUndo() ?: return@launch
            keyboardController?.hide()
            focusManager.clearFocus()
            var restored = false
            try {
                val result = snackbarHostState.showSnackbar(
                    message = "已删除「${snapshot.note.undoDisplayName()}」",
                    actionLabel = "撤销",
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    restored = withContext(NonCancellable) {
                        viewModel.restoreDeletedQuickNote(snapshot)
                    }
                    snackbarHostState.showSnackbar(
                        if (restored) "已撤销删除" else "撤销失败：同一闪记编号已存在"
                    )
                }
            } finally {
                if (!restored) viewModel.finalizeQuickNoteDeletion(snapshot)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BrandColors.PageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (notes.isEmpty()) {
                    item(key = "empty") { QuickNoteEmptyState() }
                } else {
                    items(notes, key = QuickNoteEntity::id) { note ->
                        val noteBusy = busyActions.any { it.startsWith("${note.id}:") }
                        val isEditingNote = composer.editingNoteId == note.id
                        QuickNoteCard(
                            note = note,
                            busyActions = busyActions,
                            enabled = !noteBusy && !isEditingNote,
                            highlighted = highlightedNoteId == note.id,
                            onCardClick = { viewModel.beginEditing(note) },
                            onAssistant = { viewModel.analyzeWithAssistant(note) }
                        )
                    }
                }
            }

            if (composer.attachment != null || composer.isResolvingAttachment) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    composer.attachment?.let { attachment ->
                        AttachmentPreview(
                            uri = attachment.uri,
                            displayName = attachment.displayName,
                            onRemove = if (composer.isSaving) null else viewModel::removeAttachment
                        )
                    }
                    if (composer.isResolvingAttachment) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "正在读取图片",
                                color = BrandColors.BackgroundTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            QuickNoteComposer(
                state = composer,
                createRequestRevision = createRequestRevision,
                onCreateRequestConsumed = onCreateRequestConsumed,
                onContentChanged = viewModel::updateContent,
                onPlusClick = { showAttachmentSourceDialog = true },
                onWidgetClick = {
                    context.showWidgetPinResult(
                        ControlFreeWidgetPinning.requestQuickNote(context)
                    )
                },
                onAiAssistantClick = {
                    cardHintStore.armForCreation(
                        tab = CardActionHintTab.QUICK_NOTE,
                        isDataLoaded = isInboxDataLoaded,
                        wasEmpty = notes.isEmpty(),
                        isNewItem = composer.editingNoteId == null
                    )
                    viewModel.saveAndAnalyzeWithAssistant()
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onSave = {
                    cardHintStore.armForCreation(
                        tab = CardActionHintTab.QUICK_NOTE,
                        isDataLoaded = isInboxDataLoaded,
                        wasEmpty = notes.isEmpty(),
                        isNewItem = composer.editingNoteId == null
                    )
                    viewModel.saveComposer()
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onDeleteEdit = deleteEditingWithUndo,
                onCancelEdit = viewModel::cancelEditing
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp)
        )
    }

    pendingAssistantConfirm?.let { confirmation ->
        val analysis = confirmation.analysis
        AlertDialog(
            onDismissRequest = { viewModel.cancelAssistantConversion() },
            title = { Text("确认 AI 助理分类及操作", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AssistantPreviewSection(
                        "账目",
                        analysis.ledgerEntries.map { entry ->
                            val sign = if (entry.direction == com.example.controlfree.todo.LedgerDirection.EXPENSE) "-" else "+"
                            "${entry.title} · $sign${String.format(Locale.CHINA, "%.2f", entry.amountFen / 100.0)} 元"
                        }
                    )
                    AssistantPreviewSection(
                        "待办",
                        analysis.todoItems.map { todo ->
                            val timing = buildList {
                                todo.scheduledStartEpochMillis?.let { startAt ->
                                    add("开始 ${formatTimestamp(startAt)}")
                                    todo.durationMinutes?.let { duration ->
                                        runCatching {
                                            Math.addExact(startAt, Math.multiplyExact(duration.toLong(), 60_000L))
                                        }.getOrNull()?.let { endAt ->
                                            add("预计结束 ${formatTimestamp(endAt)}")
                                        }
                                    }
                                }
                                if (todo.scheduledStartEpochMillis == null) {
                                    todo.durationMinutes?.let { add("预计用时 $it 分钟") }
                                }
                                todo.dueAtEpochMillis?.let { add("截止 ${formatTimestamp(it)}") }
                                if (todo.reminderMinutesBefore.isNotEmpty()) {
                                    add(todo.reminderMinutesBefore.joinToString(prefix = "提前 ", postfix = " 分钟提醒"))
                                }
                            }
                            listOf(todo.title, timing.joinToString(" · ")).filter(String::isNotBlank).joinToString("\n")
                        }
                    )
                    AssistantPreviewSection("习惯", analysis.habits.map { it.name })
                    AssistantPreviewSection("专注", analysis.focusSessions.map { "${it.title} · ${it.durationMinutes} 分钟" })
                    AssistantPreviewSection("时刻", analysis.anniversaries.map { "${it.title} · ${formatTimestamp(it.targetAtEpochMillis)}" })
                    AssistantPreviewSection("AI 建议", analysis.advice, BrandColors.Primary)
                    AssistantPreviewSection("注意", analysis.warnings, BrandColors.Warning)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmAssistantConversion(confirmation)
                    }
                ) {
                    Text("确认创建", fontWeight = FontWeight.Bold, color = BrandColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelAssistantConversion() }) {
                    Text("取消", color = BrandColors.TextSecondary)
                }
            }
        )
    }

    if (showAttachmentSourceDialog) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSourceDialog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = BrandColors.OverlaySurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 相册
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        showAttachmentSourceDialog = false
                        ExternalResultExpectation.expect()
                        imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(BrandColors.SurfaceRaised, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = "相册",
                            tint = BrandColors.TextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("相册", fontSize = 12.sp, color = BrandColors.TextSecondary)
                }
                // 本地文件
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        showAttachmentSourceDialog = false
                        ExternalResultExpectation.expect()
                        fileLauncher.launch(arrayOf("image/*"))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(BrandColors.SurfaceRaised, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "本地文件",
                            tint = BrandColors.TextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("本地文件", fontSize = 12.sp, color = BrandColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickNoteComposer(
    state: QuickNoteComposerState,
    createRequestRevision: Long?,
    onCreateRequestConsumed: () -> Unit,
    onContentChanged: (String) -> Unit,
    onPlusClick: () -> Unit,
    onWidgetClick: () -> Unit,
    onAiAssistantClick: () -> Unit,
    onSave: () -> Unit,
    onDeleteEdit: () -> Unit,
    onCancelEdit: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(createRequestRevision) {
        if (createRequestRevision == null) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboardController?.show()
        onCreateRequestConsumed()
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp, top = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = if (isFocused) 12.dp else 10.dp
            )
        ) {
            BasicTextField(
                value = state.content,
                onValueChange = onContentChanged,
                enabled = !state.isSaving && !state.isResolvingAttachment,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .then(
                        if (isFocused) Modifier.height(72.dp)
                        else Modifier
                    )
                    .onFocusChanged { isFocused = it.isFocused },
                singleLine = !isFocused,
                textStyle = TextStyle(fontSize = 14.sp, color = BrandColors.TextPrimary),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.content.isEmpty()) {
                            Text(
                                text = "随手记下此刻的想法...",
                                fontSize = 13.sp,
                                color = BrandColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(Modifier.height(if (isFocused) 8.dp else 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.editingNoteId != null) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(BrandColors.DangerContainer)
                                .clickable(
                                    enabled = !state.isSaving && !state.isResolvingAttachment,
                                    onClick = onDeleteEdit
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除闪记",
                                tint = BrandColors.Danger,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(BrandColors.SurfaceRaised)
                                .clickable(
                                    enabled = !state.isSaving && !state.isResolvingAttachment,
                                    onClick = onCancelEdit
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "取消编辑",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(BrandColors.SurfaceRaised)
                            .clickable(
                                enabled = !state.isSaving && !state.isResolvingAttachment,
                                onClick = onPlusClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "附件",
                            tint = BrandColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    val canUseAi = state.canSave && !state.isSaving
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (canUseAi) BrandColors.Primary.copy(alpha = 0.12f)
                                else BrandColors.SurfaceRaised.copy(alpha = 0.5f)
                            )
                            .clickable(
                                enabled = canUseAi,
                                onClick = onAiAssistantClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AI",
                            color = if (canUseAi) BrandColors.Primary else BrandColors.TextTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.canSave) BrandColors.Primary else BrandColors.OutlineSoft
                        )
                        .clickable(enabled = state.canSave && !state.isSaving, onClick = onSave),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            tint = if (state.canSave) Color.White else BrandColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(uri: String, displayName: String?, onRemove: (() -> Unit)?) {
    val bitmap by rememberQuickNoteThumbnail(uri)
    var showFullscreen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BrandColors.SurfaceRaised,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(148.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = checkNotNull(bitmap).asImageBitmap(),
                        contentDescription = displayName ?: "闪念图片",
                        modifier = Modifier.fillMaxSize().clickable { showFullscreen = true },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(BrandColors.SurfaceMuted),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = BrandColors.TextTertiary
                        )
                    }
                }
                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.align(Alignment.TopEnd).size(42.dp)
                    ) {
                        Surface(color = BrandColors.Surface.copy(alpha = 0.82f), shape = RoundedCornerShape(8.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除图片",
                                tint = BrandColors.TextPrimary,
                                modifier = Modifier.padding(7.dp)
                            )
                        }
                    }
                }
            }
            if (!displayName.isNullOrBlank()) {
                Text(
                    text = displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }

    if (showFullscreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showFullscreen = false },
                contentAlignment = Alignment.Center
            ) {
                val largeBitmap by rememberQuickNoteLargeImage(uri)
                if (largeBitmap != null) {
                    Image(
                        bitmap = checkNotNull(largeBitmap).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ParsePreview(parsed: ParsedQuickNote?, isParsing: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BrandColors.PrimaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (isParsing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = "AI",
                    color = BrandColors.Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isParsing) "正在识别" else "${parsed?.intent?.displayName.orEmpty()} · ${parsed?.title.orEmpty()}",
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                parsed?.takeUnless { isParsing }?.summaryText()?.takeIf(String::isNotBlank)?.let { summary ->
                    Text(summary, color = BrandColors.TextSecondary, fontSize = 12.sp)
                }
                parsed?.warnings?.firstOrNull()?.let { warning ->
                    Text(warning, color = BrandColors.Warning, fontSize = 11.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickNoteCard(
    note: QuickNoteEntity,
    busyActions: Set<String>,
    enabled: Boolean,
    highlighted: Boolean,
    onCardClick: () -> Unit,
    onAssistant: () -> Unit
) {
    val assistantBusy = QuickNoteViewModel.actionKey(
        note.id,
        QuickNoteAction.ASSISTANT
    ) in busyActions
    val deleteBusy = QuickNoteViewModel.actionKey(note.id, QuickNoteAction.DELETE) in busyActions
    val anyBusy = assistantBusy || deleteBusy

    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val cardShape = RoundedCornerShape(16.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .combinedClickable(
                enabled = enabled && !anyBusy,
                onClick = onCardClick,
                onLongClick = {
                    if (note.content.isNotBlank()) {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(note.content))
                        android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            ),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        border = if (highlighted) BorderStroke(2.dp, BrandColors.Primary) else null,
        shape = cardShape
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 6.dp)) {
            // 1. 内容板：文本/图片排版
            if (note.content.isNotBlank()) {
                Text(
                    note.content,
                    color = BrandColors.TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
            } else {
                Text(
                    "图片闪念",
                    color = BrandColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
            }

            note.mediaUri?.let { AttachmentPreview(it, note.mediaDisplayName, onRemove = null) }

            note.aiAdvice?.takeIf(String::isNotBlank)?.let { advice ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(BrandColors.PrimaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        "AI 建议",
                        color = BrandColors.Primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        advice,
                        color = BrandColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(note.createdAtEpochMillis),
                    color = BrandColors.TextTertiary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Start
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = onAssistant,
                    enabled = enabled && !anyBusy,
                    color = Color.Transparent,
                    contentColor = BrandColors.Primary,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (assistantBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                color = BrandColors.Primary,
                                strokeWidth = 1.5.dp
                            )
                            Text(
                                text = "处理中",
                                color = BrandColors.Primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "AI",
                                color = BrandColors.Primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun createPendingCameraImage(context: Context): Uri {
    val now = System.currentTimeMillis()
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "quick_note_$now.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, now)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/ControlFree"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    return checkNotNull(
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    ) {
        "无法创建照片存储位置"
    }
}

private fun publishPendingCameraImage(context: Context, uri: Uri) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.IS_PENDING, 0)
    }
    // 个别厂商的 MediaProvider 在值未变化等场景返回 0 行，不能据此判定发布失败；
    // 照片本体已写入，即便仍处于 pending 状态，本应用作为所有者也可正常读取。
    runCatching { context.contentResolver.update(uri, values, null, null) }
}

/** 判断相机是否已把照片数据写入该 MediaStore 记录（区别于取消拍照留下的空记录）。 */
private fun hasCapturedImageData(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0L } ?: false
    }.getOrDefault(false)

private fun discardPendingCameraImage(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
}

@Composable
private fun QuickNoteEmptyState() {
    TodoEmptyStatePanel(
        icon = Icons.AutoMirrored.Filled.StickyNote2,
        title = "记录第一条闪记",
        description = "写下脑海中的琐事、灵感或开销，稍后再从容整理",
        modifier = Modifier.padding(vertical = 32.dp)
    )
}

@Composable
private fun rememberQuickNoteThumbnail(uri: String): androidx.compose.runtime.State<android.graphics.Bitmap?> {
    val context = LocalContext.current
    return produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uri) {
        value = runCatching { Uri.parse(uri) }
            .getOrNull()
            ?.let { QuickNoteImageSampler.loadThumbnail(context, it) }
    }
}

@Composable
private fun rememberQuickNoteLargeImage(uri: String): androidx.compose.runtime.State<android.graphics.Bitmap?> {
    val context = LocalContext.current
    return produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uri) {
        value = runCatching { Uri.parse(uri) }
            .getOrNull()
            ?.let { QuickNoteImageSampler.loadThumbnail(context, it, maxDimension = 2048) }
    }
}

private val QuickNoteIntent.displayName: String
    get() = when (this) {
        QuickNoteIntent.NOTE -> "闪念"
        QuickNoteIntent.TODO -> "待办"
        QuickNoteIntent.HABIT -> "习惯"
        QuickNoteIntent.FOCUS -> "专注"
    }

private fun ParsedQuickNote.summaryText(): String {
    val parts = mutableListOf<String>()
    (startAtEpochMillis ?: dueAtEpochMillis)?.let { parts += formatTimestamp(it) }
    estimatedDurationMinutes?.let { parts += "$it 分钟" }
    if (recurrence != QuickNoteRecurrence.NONE) {
        parts += when (recurrence) {
            QuickNoteRecurrence.DAILY -> "每天"
            QuickNoteRecurrence.WEEKDAYS -> "工作日"
            QuickNoteRecurrence.WEEKLY -> "每周"
            QuickNoteRecurrence.NONE -> ""
        }
    }
    return parts.filter(String::isNotBlank).joinToString(" · ")
}

private fun formatTimestamp(epochMillis: Long): String = runCatching {
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}.getOrDefault("")

private fun QuickNoteEntity.undoDisplayName(): String = content
    .lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotEmpty)
    .let { it?.take(18) }
    ?: mediaDisplayName?.take(18)
    ?: "图片闪记"

@Composable
private fun AssistantPreviewSection(
    title: String,
    items: List<String>,
    accent: Color = BrandColors.TextPrimary
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "$title (${items.size})",
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        items.forEach { item ->
            Text(
                "• $item",
                color = BrandColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
