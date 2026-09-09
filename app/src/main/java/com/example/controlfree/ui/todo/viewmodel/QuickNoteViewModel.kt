package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.ai.ProductivityAiCoordinator
import com.example.controlfree.ai.resolveLocalAssistantTodoTiming
import com.example.controlfree.productivity.quicknote.ParsedQuickNote
import com.example.controlfree.productivity.quicknote.QuickNoteIntent
import com.example.controlfree.productivity.quicknote.QuickNoteMediaMetadata
import com.example.controlfree.productivity.quicknote.QuickNoteMediaPolicy
import com.example.controlfree.productivity.quicknote.QuickNoteMediaValidation
import com.example.controlfree.productivity.quicknote.QuickNoteRecurrence
import com.example.controlfree.todo.QuickNoteCaptureSource
import com.example.controlfree.todo.QuickNoteAssistantAnalysis
import com.example.controlfree.todo.QuickNoteDeletionSnapshot
import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.todo.LedgerEntryDraft
import com.example.controlfree.todo.LedgerTodoDraft
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.supervision.persistence.OneTimeFocusScheduleResult
import com.example.controlfree.ui.todo.todo.DEFAULT_FOCUS_MINUTES
import com.example.controlfree.ui.todo.todo.MAX_FOCUS_MINUTES
import com.example.controlfree.ui.todo.todo.MIN_FOCUS_MINUTES
import com.example.controlfree.ui.todo.todo.TodoFocusRequest
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuickNoteComposerState(
    val content: String = "",
    val captureSource: QuickNoteCaptureSource = QuickNoteCaptureSource.TEXT,
    val attachment: QuickNoteMediaMetadata? = null,
    val parsed: ParsedQuickNote? = null,
    val isParsing: Boolean = false,
    val isResolvingAttachment: Boolean = false,
    val isSaving: Boolean = false,
    val editingNoteId: String? = null
) {
    val canSave: Boolean
        get() = !isSaving && !isResolvingAttachment &&
            (content.isNotBlank() || attachment != null)
}

internal fun mergeQuickNoteEdit(
    original: QuickNoteEntity,
    composer: QuickNoteComposerState,
    updatedAtEpochMillis: Long
): QuickNoteEntity {
    val attachment = composer.attachment
    val keepsOriginalAttachment = original.mediaUri != null &&
        attachment?.uri == original.mediaUri
    val contentChanged = original.content.trim() != composer.content.trim()
    return original.copy(
        content = composer.content,
        mediaUri = attachment?.uri,
        mediaMimeType = if (keepsOriginalAttachment) original.mediaMimeType else attachment?.mimeType,
        mediaDisplayName = if (keepsOriginalAttachment) {
            original.mediaDisplayName
        } else {
            attachment?.displayName
        },
        mediaSizeBytes = if (keepsOriginalAttachment) original.mediaSizeBytes else attachment?.sizeBytes,
        captureSource = original.captureSource,
        updatedAtEpochMillis = updatedAtEpochMillis,
        aiAdvice = original.aiAdvice.takeUnless { contentChanged },
        aiAssistantFingerprint = original.aiAssistantFingerprint.takeUnless { contentChanged }
    )
}

/** 构造只用于确认预览的内存实体；真正的闪记要到用户确认后才持久化。 */
internal fun quickNoteAssistantDraftPreview(
    original: QuickNoteEntity?,
    composer: QuickNoteComposerState,
    nowEpochMillis: Long,
    previewId: String = "assistant-preview:${UUID.randomUUID()}"
): QuickNoteEntity {
    require(composer.content.isNotBlank() || composer.attachment != null) { "闪记草稿不能为空" }
    if (original != null) {
        return mergeQuickNoteEdit(original, composer, nowEpochMillis)
    }
    val attachment = composer.attachment
    return QuickNoteEntity(
        id = previewId,
        content = composer.content,
        mediaUri = attachment?.uri,
        status = com.example.controlfree.todo.QuickNoteStatus.RAW.storedValue,
        createdAtEpochMillis = nowEpochMillis,
        mediaMimeType = attachment?.mimeType,
        mediaDisplayName = attachment?.displayName,
        mediaSizeBytes = attachment?.sizeBytes,
        captureSource = composer.captureSource.storedValue,
        updatedAtEpochMillis = nowEpochMillis
    )
}

enum class QuickNoteAction {
    ASSISTANT,
    TODO,
    HABIT,
    FOCUS,
    LEDGER,
    DELETE
}

data class PendingLedgerConfirmation(
    val note: QuickNoteEntity,
    val entries: List<LedgerEntryDraft>,
    val todos: List<LedgerTodoDraft>
)

data class PendingQuickNoteAssistantConfirmation(
    val note: QuickNoteEntity,
    val analysis: QuickNoteAssistantAnalysis,
    val composerSnapshot: QuickNoteComposerState? = null
)

internal fun shouldReleaseQuickNoteMediaGrant(
    savedReferenceCount: Int,
    draftOwnerCount: Int
): Boolean = savedReferenceCount == 0 && draftOwnerCount == 0

internal data class QuickNoteMediaGrantCleanupTicket(
    val uri: String,
    val generation: Long
)

internal class QuickNoteDraftMediaGrantRegistry {
    private data class UriState(
        var draftOwnerCount: Int,
        var generation: Long
    )

    private val uriStates = mutableMapOf<String, UriState>()
    private var nextGeneration = 0L

    @Synchronized
    fun acquire(uri: String) {
        val state = uriStates[uri]
        if (state == null) {
            uriStates[uri] = UriState(
                draftOwnerCount = 1,
                generation = advanceGeneration()
            )
        } else {
            state.draftOwnerCount++
            state.generation = advanceGeneration()
        }
    }

    @Synchronized
    fun relinquish(uri: String) {
        val state = uriStates[uri] ?: return
        state.draftOwnerCount--
        state.generation = advanceGeneration()
        if (state.draftOwnerCount <= 0) uriStates.remove(uri)
    }

    @Synchronized
    fun beginCleanup(uri: String): QuickNoteMediaGrantCleanupTicket {
        val generation = advanceGeneration()
        val state = uriStates[uri]
        if (state == null) {
            uriStates[uri] = UriState(draftOwnerCount = 0, generation = generation)
        } else {
            state.generation = generation
        }
        return QuickNoteMediaGrantCleanupTicket(uri = uri, generation = generation)
    }

    @Synchronized
    fun releaseIfUnused(
        ticket: QuickNoteMediaGrantCleanupTicket,
        savedReferenceCount: Int,
        release: () -> Unit
    ): Boolean {
        val state = uriStates[ticket.uri] ?: return false
        if (state.generation != ticket.generation) return false
        if (!shouldReleaseQuickNoteMediaGrant(savedReferenceCount, state.draftOwnerCount)) {
            if (state.draftOwnerCount == 0) uriStates.remove(ticket.uri)
            return false
        }
        release()
        uriStates.remove(ticket.uri)
        return true
    }

    private fun advanceGeneration(): Long = ++nextGeneration
}

private val quickNoteDraftMediaGrantRegistry = QuickNoteDraftMediaGrantRegistry()

class QuickNoteViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TodoRepository.getInstance(application)
    private val supervisionPlanRepository = com.example.controlfree.supervision.persistence.SupervisionPlanRepository.getInstance(application)
    private val aiCoordinator = ProductivityAiCoordinator.getInstance(application)
    private val clock: Clock = Clock.systemDefaultZone()
    private var parseJob: Job? = null
    private var attachmentResolutionJob: Job? = null
    private var mediaImportJob: Job? = null
    private var mediaImportVersion: Long = 0
    private var resolvingAttachmentUri: String? = null
    private var attachmentResolutionVersion: Long = 0
    private val ownedDraftMediaUris = mutableSetOf<String>()
    private var editingOriginalNote: QuickNoteEntity? = null

    private val _isInboxDataLoaded = MutableStateFlow(false)
    val isInboxDataLoaded: StateFlow<Boolean> = _isInboxDataLoaded.asStateFlow()

    val inboxNotes: StateFlow<List<QuickNoteEntity>> = repository.observeQuickNoteInbox()
        .map { list -> list.sortedBy { it.createdAtEpochMillis } }
        .onStart { _isInboxDataLoaded.value = false }
        .onEach { _isInboxDataLoaded.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _composer = MutableStateFlow(QuickNoteComposerState())
    val composer: StateFlow<QuickNoteComposerState> = _composer.asStateFlow()

    private val _pendingLedgerConfirmation = MutableStateFlow<PendingLedgerConfirmation?>(null)
    val pendingLedgerConfirmation: StateFlow<PendingLedgerConfirmation?> = _pendingLedgerConfirmation.asStateFlow()

    private val _pendingAssistantConfirmation =
        MutableStateFlow<PendingQuickNoteAssistantConfirmation?>(null)
    val pendingAssistantConfirmation: StateFlow<PendingQuickNoteAssistantConfirmation?> =
        _pendingAssistantConfirmation.asStateFlow()

    private val _isAnalyzingText = MutableStateFlow(false)
    val isAnalyzingText: StateFlow<Boolean> = _isAnalyzingText.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _busyActions = MutableStateFlow<Set<String>>(emptySet())
    val busyActions: StateFlow<Set<String>> = _busyActions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun updateContent(content: String) {
        if (_composer.value.isSaving) return
        _composer.value = _composer.value.copy(
            content = content.take(MAX_COMPOSER_CHARS),
            captureSource = QuickNoteCaptureSource.TEXT,
            parsed = null,
            isParsing = false
        )
    }

    fun attachImage(uri: Uri) {
        cancelPendingMediaImport()
        startAttachmentResolution(uri)
    }

    /** Photo Picker 的临时授权不可持久化时，将图片复制到应用私有目录。 */
    fun importPhotoPickerImage(uri: Uri): Job {
        cancelPendingMediaImport()
        cancelPendingAttachmentResolution()
        val version = mediaImportVersion
        _composer.value = _composer.value.copy(isResolvingAttachment = true)
        val job = viewModelScope.launch {
            val copiedUri = AtomicReference<Uri?>()
            try {
                val importedUri = withContext(Dispatchers.IO) {
                    copyToPrivateMedia(uri, copiedUri::set)
                }
                if (!isActive || version != mediaImportVersion) return@launch
                mediaImportJob = null
                startAttachmentResolution(importedUri)
                copiedUri.set(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == mediaImportVersion) {
                    _composer.value = _composer.value.copy(isResolvingAttachment = false)
                    reportFailure(error, "无法导入所选图片")
                }
            } finally {
                copiedUri.getAndSet(null)?.let(::deletePrivateMedia)
                if (version == mediaImportVersion) mediaImportJob = null
            }
        }
        mediaImportJob = job
        return job
    }

    private fun startAttachmentResolution(uri: Uri) {
        val uriValue = uri.toString()
        cancelPendingAttachmentResolution(uriValue)
        val resolutionVersion = attachmentResolutionVersion
        acquireDraftMediaUri(uriValue)
        resolvingAttachmentUri = uriValue
        _composer.value = _composer.value.copy(isResolvingAttachment = true)
        attachmentResolutionJob = viewModelScope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) { resolveMediaMetadata(uri) }
                if (resolutionVersion != attachmentResolutionVersion) return@launch
                when (val validation = QuickNoteMediaPolicy.validate(metadata)) {
                    is QuickNoteMediaValidation.Accepted -> {
                        val previousUri = _composer.value.attachment?.uri
                        _composer.value = _composer.value.copy(
                            attachment = validation.metadata,
                            isResolvingAttachment = false
                        )
                        if (previousUri != null && previousUri != validation.metadata.uri) {
                            relinquishDraftMediaUri(previousUri)
                        }
                    }
                    QuickNoteMediaValidation.EmptyFile -> rejectAttachment(uri, "图片内容为空")
                    QuickNoteMediaValidation.FileTooLarge -> rejectAttachment(uri, "图片不能超过 12 MB")
                    QuickNoteMediaValidation.MissingUri -> rejectAttachment(uri, "图片地址无效")
                    QuickNoteMediaValidation.UnsupportedType -> rejectAttachment(uri, "仅支持图片附件")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rejectAttachment(uri, error.message?.takeIf(String::isNotBlank) ?: "无法读取所选图片")
            } finally {
                if (resolutionVersion == attachmentResolutionVersion && resolvingAttachmentUri == uriValue) {
                    attachmentResolutionJob = null
                    resolvingAttachmentUri = null
                    _composer.value = _composer.value.copy(isResolvingAttachment = false)
                }
            }
        }
    }

    fun removeAttachment() {
        cancelPendingMediaImport()
        cancelPendingAttachmentResolution()
        val attachmentUri = _composer.value.attachment?.uri
        _composer.value = _composer.value.copy(attachment = null, isResolvingAttachment = false)
        attachmentUri?.let(::relinquishDraftMediaUri)
    }

    fun beginEditing(note: QuickNoteEntity) {
        if (_composer.value.isSaving) return
        cancelPendingMediaImport()
        cancelPendingAttachmentResolution()
        val previousDraftUri = _composer.value.attachment?.uri
        _composer.value = QuickNoteComposerState()
        previousDraftUri?.let(::relinquishDraftMediaUri)

        editingOriginalNote = note
        val attachment = note.mediaUri?.let { uri ->
            acquireDraftMediaUri(uri)
            QuickNoteMediaMetadata(
                uri = uri,
                mimeType = note.mediaMimeType.orEmpty(),
                displayName = note.mediaDisplayName,
                sizeBytes = note.mediaSizeBytes ?: 0L
            )
        }
        _composer.value = QuickNoteComposerState(
            content = note.content,
            captureSource = QuickNoteCaptureSource.TEXT,
            attachment = attachment,
            editingNoteId = note.id
        )
    }

    fun cancelEditing() {
        if (_composer.value.editingNoteId == null || _composer.value.isSaving) return
        cancelPendingMediaImport()
        cancelPendingAttachmentResolution()
        val attachmentUri = _composer.value.attachment?.uri
        _composer.value = QuickNoteComposerState()
        editingOriginalNote = null
        attachmentUri?.let(::relinquishDraftMediaUri)
    }

    fun saveComposer() {
        val snapshot = _composer.value
        if (!snapshot.canSave) {
            reportMessage("请输入闪念或添加图片")
            return
        }
        _composer.value = snapshot.copy(isSaving = true)
        viewModelScope.launch {
            try {
                val saved = persistComposer(snapshot)
                completeComposerSave(snapshot, saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _composer.value = _composer.value.copy(isSaving = false)
                reportFailure(error, "记录闪念失败")
            }
        }
    }

    fun saveAndConvertToLedger() {
        val snapshot = _composer.value
        if (!snapshot.canSave) {
            reportMessage("请输入记账文本")
            return
        }
        _composer.value = snapshot.copy(isSaving = true)
        viewModelScope.launch {
            try {
                val saved = persistComposer(snapshot)
                completeComposerSave(snapshot, saved)
                convertToLedger(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _composer.value = _composer.value.copy(isSaving = false)
                reportFailure(error, "记录闪记失败")
            }
        }
    }

    fun saveAndAnalyzeWithAssistant() {
        val snapshot = _composer.value
        if (!snapshot.canSave) {
            reportMessage("请输入需要 AI 助理分析的内容")
            return
        }
        _composer.value = snapshot.copy(isSaving = true)
        viewModelScope.launch {
            try {
                val preview = quickNoteAssistantDraftPreview(
                    original = editingOriginalNote?.takeIf { it.id == snapshot.editingNoteId },
                    composer = snapshot,
                    nowEpochMillis = clock.millis()
                )
                prepareAssistantConfirmation(preview, composerSnapshot = snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error, "AI 助理分析失败")
            } finally {
                if (_composer.value.editingNoteId == snapshot.editingNoteId) {
                    _composer.value = _composer.value.copy(isSaving = false)
                }
            }
        }
    }

    fun analyzeDirectText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            reportMessage("请输入需要 AI 助理分析的内容")
            return
        }
        viewModelScope.launch {
            _analysisError.value = null
            _pendingAssistantConfirmation.value = null
            _isAnalyzingText.value = true
            try {
                val newNote = repository.addQuickNote(trimmed)
                prepareAssistantConfirmation(newNote, composerSnapshot = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _analysisError.value = "AI 助理分析失败: ${error.localizedMessage ?: error.message ?: "未知错误"}"
                reportFailure(error, "AI 助理分析失败")
            } finally {
                _isAnalyzingText.value = false
            }
        }
    }

    private suspend fun persistComposer(snapshot: QuickNoteComposerState): QuickNoteEntity {
        val original = editingOriginalNote?.takeIf { it.id == snapshot.editingNoteId }
        val attachment = snapshot.attachment
        return if (original == null) {
            repository.addQuickNote(
                content = snapshot.content,
                mediaUri = attachment?.uri,
                mediaMimeType = attachment?.mimeType,
                mediaDisplayName = attachment?.displayName,
                mediaSizeBytes = attachment?.sizeBytes,
                captureSource = snapshot.captureSource,
                nowEpochMillis = clock.millis()
            )
        } else {
            repository.updateQuickNote(
                mergeQuickNoteEdit(
                    original = original,
                    composer = snapshot,
                    updatedAtEpochMillis = clock.millis()
                )
            ) ?: error("闪记已不存在，请重新创建")
        }
    }

    private fun completeComposerSave(
        snapshot: QuickNoteComposerState,
        saved: QuickNoteEntity
    ) {
        val previousMediaUri = editingOriginalNote
            ?.takeIf { it.id == snapshot.editingNoteId }
            ?.mediaUri
        snapshot.attachment?.uri?.let(::transferDraftMediaUriToSavedNote)
        if (previousMediaUri != null && previousMediaUri != saved.mediaUri) {
            schedulePersistedPermissionReleaseIfUnused(previousMediaUri)
        }
        editingOriginalNote = null
        parseJob?.cancel()
        _composer.value = QuickNoteComposerState()
    }

    fun deleteQuickNote(id: String) = runAction(id, QuickNoteAction.DELETE) {
        repository.deleteQuickNote(id)?.let(::schedulePersistedPermissionReleaseIfUnused)
    }

    suspend fun deleteQuickNoteForUndo(id: String): QuickNoteDeletionSnapshot? {
        val key = actionKey(id, QuickNoteAction.DELETE)
        val noteActionPrefix = "$id:"
        if (_busyActions.value.any { it.startsWith(noteActionPrefix) }) return null
        _busyActions.value = _busyActions.value + key
        return try {
            repository.deleteQuickNoteForUndo(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportFailure(error, "删除闪记失败")
            null
        } finally {
            _busyActions.value = _busyActions.value - key
        }
    }

    suspend fun deleteEditingQuickNoteForUndo(): QuickNoteDeletionSnapshot? {
        val composerSnapshot = _composer.value
        val noteId = composerSnapshot.editingNoteId ?: return null
        if (composerSnapshot.isSaving) return null
        val original = editingOriginalNote?.takeIf { it.id == noteId } ?: return null
        val key = actionKey(noteId, QuickNoteAction.DELETE)
        val noteActionPrefix = "$noteId:"
        if (_busyActions.value.any { it.startsWith(noteActionPrefix) }) return null

        cancelPendingMediaImport()
        cancelPendingAttachmentResolution()
        val originalMediaUri = original.mediaUri
        val currentDraftMediaUri = composerSnapshot.attachment?.uri
        val acquiredOriginalForDeletion = originalMediaUri != null &&
            originalMediaUri !in ownedDraftMediaUris
        if (acquiredOriginalForDeletion) {
            acquireDraftMediaUri(originalMediaUri)
        }

        _busyActions.value = _busyActions.value + key
        return try {
            val deletionSnapshot = repository.deleteQuickNoteForUndo(noteId) ?: run {
                if (acquiredOriginalForDeletion) {
                    relinquishDraftMediaUri(originalMediaUri)
                }
                return null
            }

            parseJob?.cancel()
            _composer.value = QuickNoteComposerState()
            editingOriginalNote = null

            if (currentDraftMediaUri != null && currentDraftMediaUri != originalMediaUri) {
                relinquishDraftMediaUri(currentDraftMediaUri)
            }
            originalMediaUri?.let(::relinquishDraftMediaUriWithoutCleanup)
            deletionSnapshot
        } catch (cancelled: CancellationException) {
            if (acquiredOriginalForDeletion) {
                relinquishDraftMediaUri(originalMediaUri)
            }
            throw cancelled
        } catch (error: Exception) {
            if (acquiredOriginalForDeletion) {
                relinquishDraftMediaUri(originalMediaUri)
            }
            reportFailure(error, "删除闪记失败")
            null
        } finally {
            _busyActions.value = _busyActions.value - key
        }
    }

    suspend fun restoreDeletedQuickNote(snapshot: QuickNoteDeletionSnapshot): Boolean = try {
        repository.restoreDeletedQuickNote(snapshot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        reportFailure(error, "撤销删除失败")
        false
    }

    fun finalizeQuickNoteDeletion(snapshot: QuickNoteDeletionSnapshot) {
        snapshot.mediaUriToRelease?.let(::schedulePersistedPermissionReleaseIfUnused)
    }

    /** 只分析并生成确认预览，不写入待办、习惯、账本、专注或时刻。 */
    fun analyzeWithAssistant(note: QuickNoteEntity) =
        runAction(note.id, QuickNoteAction.ASSISTANT) {
            prepareAssistantConfirmation(note)
        }

    fun confirmAssistantConversion(confirmation: PendingQuickNoteAssistantConfirmation) =
        runAction(confirmation.note.id, QuickNoteAction.ASSISTANT) {
            val sourceNote = confirmation.composerSnapshot?.let { snapshot ->
                _composer.value = snapshot.copy(isSaving = true)
                try {
                    persistComposer(snapshot).also { saved ->
                        completeComposerSave(snapshot, saved)
                        _pendingAssistantConfirmation.value = confirmation.copy(
                            note = saved,
                            composerSnapshot = null
                        )
                    }
                } catch (error: Exception) {
                    _composer.value = snapshot.copy(isSaving = false)
                    throw error
                }
            } ?: confirmation.note
            val result = repository.confirmQuickNoteAssistant(
                noteId = sourceNote.id,
                analysis = confirmation.analysis
            )
            if (result.focusSchedules.isNotEmpty()) {
                runCatching {
                    com.example.controlfree.supervision.runtime.SupervisionScheduleCoordinator(
                        context = getApplication(),
                        clock = clock
                    ).reconcile()
                }
            }
            _pendingAssistantConfirmation.value = null
            val baseMessage = when {
                !result.wasCreated -> "该闪记已经确认过，未重复创建"
                result.targetCount > 0 -> "AI 助理已创建 ${result.targetCount} 项内容"
                else -> "AI 建议已保存到闪记"
            }
            reportMessage(baseMessage)
        }

    fun cancelAssistantConversion() {
        _pendingAssistantConfirmation.value = null
        _analysisError.value = null
    }

    private suspend fun prepareAssistantConfirmation(
        note: QuickNoteEntity,
        composerSnapshot: QuickNoteComposerState? = null
    ) {
        val content = note.content.trim()
        if (content.isEmpty()) {
            val errMsg = "图片闪记暂不支持自动识别，请补充文字"
            _analysisError.value = errMsg
            reportMessage(errMsg)
            return
        }
        val analysis = aiCoordinator.analyzeQuickNote(content, ZonedDateTime.now(clock))
        if (analysis.actionableCount == 0 && analysis.advice.isEmpty()) {
            val errMsg = analysis.warnings.firstOrNull() ?: "AI 助理未能从输入中识别出可创建的账目、待办、习惯、专注或时刻内容。"
            _analysisError.value = errMsg
            reportMessage(errMsg)
            return
        }
        _pendingAssistantConfirmation.value = PendingQuickNoteAssistantConfirmation(
            note = note,
            analysis = analysis,
            composerSnapshot = composerSnapshot
        )
    }

    fun convertToTodo(note: QuickNoteEntity) = runAction(note.id, QuickNoteAction.TODO) {
        val parsed = parseForConversion(note)
        val timing = resolveLocalAssistantTodoTiming(note.content, parsed)
        val duration = timing.durationMinutes
        val scheduledStart = timing.scheduledStartEpochMillis
        repository.convertNoteToTodo(
            noteId = note.id,
            todoTitle = parsed.title,
            priority = 0,
            dueDate = timing.dueAtEpochMillis,
            scheduledStartEpochMillis = scheduledStart,
            scheduledEndEpochMillis = scheduledStart?.let { start ->
                duration?.let { start + it * 60_000L }
            },
            estimatedFocusMinutes = duration,
            recurrenceType = parsed.todoRecurrenceType(),
            recurrenceDaysMask = parsed.weekdayMask(clock)
        )
        reportMessage("已转为待办")
    }

    fun convertToHabit(note: QuickNoteEntity) = runAction(note.id, QuickNoteAction.HABIT) {
        val parsed = parseForConversion(note)
        repository.convertNoteToHabit(
            noteId = note.id,
            habitName = parsed.title,
            colorHex = DEFAULT_HABIT_COLOR,
            iconRes = DEFAULT_HABIT_ICON,
            frequencyType = parsed.habitFrequencyType(),
            weekdaysMask = parsed.weekdayMask(clock)
        )
        reportMessage("已转为习惯")
    }

    fun convertToFocus(
        note: QuickNoteEntity,
        onFocusReady: (TodoFocusRequest) -> Unit
    ) = runAction(note.id, QuickNoteAction.FOCUS) {
        val parsed = parseForConversion(note)
        val requestId = repository.convertNoteToFocus(note.id).targetId
        val duration = (parsed.estimatedDurationMinutes ?: DEFAULT_FOCUS_MINUTES)
            .coerceIn(MIN_FOCUS_MINUTES, MAX_FOCUS_MINUTES)
        
        val startEpochMillis = parsed.startAtEpochMillis ?: clock.millis()
        val endEpochMillis = startEpochMillis + duration * 60_000L
        
        supervisionPlanRepository.scheduleOneTimeFocusForTodo(
            todoId = requestId,
            expectedTodoUpdatedAtEpochMillis = null,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            nowEpochMillis = clock.millis()
        )
        
        runCatching {
            com.example.controlfree.supervision.runtime.SupervisionScheduleCoordinator(
                context = getApplication(),
                clock = clock
            ).reconcile()
        }
        
        onFocusReady(TodoFocusRequest(requestId, parsed.title, duration))
        reportMessage("已安排一次性专注锁")
    }

    fun convertToLedger(note: QuickNoteEntity) = runAction(note.id, QuickNoteAction.LEDGER) {
        val result = aiCoordinator.classifyLedger(note.content, ZonedDateTime.now(clock))
        if (result.ledgerEntries.isEmpty() && result.todoItems.isEmpty()) {
            reportMessage(result.warnings.firstOrNull() ?: "未检测到有效信息")
            return@runAction
        }
        val entries = result.ledgerEntries.map { entry ->
            LedgerEntryDraft(
                title = entry.title,
                amountFen = entry.amountFen,
                direction = entry.direction,
                category = entry.category,
                occurredAtEpochMillis = entry.occurredAtEpochMillis,
                isEstimated = entry.isEstimated,
                aiConfidence = entry.confidence,
                emotion = entry.emotion,
                necessity = entry.necessity,
                note = entry.note,
                warnings = result.warnings
            )
        }
        val todos = result.todoItems.map { todo ->
            LedgerTodoDraft(
                content = todo.content,
                dueDateEpochMillis = todo.dueDateEpochMillis
            )
        }
        _pendingLedgerConfirmation.value = PendingLedgerConfirmation(note, entries, todos)
    }

    fun confirmLedgerConversion(confirmation: PendingLedgerConfirmation) {
        viewModelScope.launch {
            runAction(confirmation.note.id, QuickNoteAction.LEDGER) {
                runCatching {
                    val conversion = repository.convertNoteToLedger(
                        confirmation.note.id,
                        confirmation.entries,
                        confirmation.todos
                    )
                    val createdText = if (conversion.wasCreated) "已转为" else "已经转为"
                    val parts = buildList {
                        if (confirmation.entries.isNotEmpty()) add("${confirmation.entries.size} 笔账目")
                        if (confirmation.todos.isNotEmpty()) add("${confirmation.todos.size} 条待办")
                    }
                    reportMessage("$createdText ${parts.joinToString("和")}")
                    _pendingLedgerConfirmation.value = null
                }.onFailure { error ->
                    reportMessage("记账转化失败: ${error.localizedMessage ?: "未知错误"}")
                    _pendingLedgerConfirmation.value = null
                }
            }
        }
    }

    fun cancelLedgerConversion() {
        _pendingLedgerConfirmation.value = null
    }

    /** 兼容旧页面，完整页面接入后由 [saveComposer] 取代。 */
    fun addQuickNote(content: String, mediaUri: String? = null) {
        viewModelScope.launch {
            runCatching {
                repository.addQuickNote(content = content, mediaUri = mediaUri)
            }.onFailure { reportFailure(it, "记录闪念失败") }
        }
    }

    /** 兼容旧页面的显式转化参数。 */
    fun convertToTodo(noteId: String, todoTitle: String, priority: Int, dueDate: Long?) {
        runAction(noteId, QuickNoteAction.TODO) {
            repository.convertNoteToTodo(noteId, todoTitle, priority, dueDate)
            reportMessage("已转为待办")
        }
    }

    /** 兼容旧页面的显式转化参数。 */
    fun convertToHabit(noteId: String, habitName: String, colorHex: String, iconRes: String) {
        runAction(noteId, QuickNoteAction.HABIT) {
            repository.convertNoteToHabit(noteId, habitName, colorHex, iconRes)
            reportMessage("已转为习惯")
        }
    }

    fun reportMessage(message: String) {
        _messages.tryEmit(message)
    }

    private fun scheduleParse() {
        parseJob?.cancel()
        val content = _composer.value.content.trim()
        if (content.isEmpty()) {
            _composer.value = _composer.value.copy(parsed = null, isParsing = false)
            return
        }
        parseJob = viewModelScope.launch {
            delay(PARSE_DEBOUNCE_MILLIS)
            _composer.value = _composer.value.copy(isParsing = true)
            try {
                val parsed = aiCoordinator.parseQuickNote(content, ZonedDateTime.now(clock))
                if (_composer.value.content.trim() == content) {
                    _composer.value = _composer.value.copy(parsed = parsed, isParsing = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_composer.value.content.trim() == content) {
                    _composer.value = _composer.value.copy(isParsing = false)
                }
            }
        }
    }

    private suspend fun parseForConversion(note: QuickNoteEntity): ParsedQuickNote {
        val content = note.content.trim()
        if (content.isNotEmpty()) {
            return aiCoordinator.parseQuickNote(content, ZonedDateTime.now(clock))
        }
        val title = note.mediaDisplayName?.substringBeforeLast('.')?.trim().orEmpty()
            .ifEmpty { "图片闪念" }
            .take(MAX_TITLE_CHARS)
        return ParsedQuickNote(
            originalText = "",
            title = title,
            intent = QuickNoteIntent.NOTE,
            startAtEpochMillis = null,
            dueAtEpochMillis = null,
            estimatedDurationMinutes = null,
            recurrence = QuickNoteRecurrence.NONE,
            confidence = 0f
        )
    }

    private fun runAction(
        noteId: String,
        action: QuickNoteAction,
        block: suspend () -> Unit
    ): Job {
        val key = actionKey(noteId, action)
        val noteActionPrefix = "$noteId:"
        if (_busyActions.value.any { it.startsWith(noteActionPrefix) }) {
            return Job().apply { cancel() }
        }
        _busyActions.value = _busyActions.value + key
        return viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error, "操作失败")
            } finally {
                _busyActions.value = _busyActions.value - key
            }
        }
    }

    private fun rejectAttachment(uri: Uri, message: String) {
        val uriValue = uri.toString()
        if (_composer.value.attachment?.uri != uriValue) relinquishDraftMediaUri(uriValue)
        _composer.value = _composer.value.copy(isResolvingAttachment = false)
        reportMessage(message)
    }

    private fun acquireDraftMediaUri(uri: String) {
        if (ownedDraftMediaUris.add(uri)) quickNoteDraftMediaGrantRegistry.acquire(uri)
    }

    private fun relinquishDraftMediaUri(uri: String) {
        if (!ownedDraftMediaUris.remove(uri)) return
        quickNoteDraftMediaGrantRegistry.relinquish(uri)
        schedulePersistedPermissionReleaseIfUnused(uri)
    }

    private fun relinquishDraftMediaUriWithoutCleanup(uri: String) {
        if (!ownedDraftMediaUris.remove(uri)) return
        quickNoteDraftMediaGrantRegistry.relinquish(uri)
    }

    private fun transferDraftMediaUriToSavedNote(uri: String) {
        relinquishDraftMediaUriWithoutCleanup(uri)
    }

    private fun cancelPendingMediaImport() {
        mediaImportVersion++
        mediaImportJob?.cancel()
        mediaImportJob = null
    }

    private fun cancelPendingAttachmentResolution(replacementUri: String? = null) {
        attachmentResolutionVersion++
        val pendingUri = resolvingAttachmentUri
        attachmentResolutionJob?.cancel()
        attachmentResolutionJob = null
        resolvingAttachmentUri = null
        if (pendingUri != null && pendingUri != replacementUri && pendingUri != _composer.value.attachment?.uri) {
            relinquishDraftMediaUri(pendingUri)
        }
    }

    private fun schedulePersistedPermissionReleaseIfUnused(uri: String) {
        val application = getApplication<Application>()
        val todoRepository = repository
        val cleanupTicket = quickNoteDraftMediaGrantRegistry.beginCleanup(uri)
        mediaGrantCleanupScope.launch {
            val savedReferenceCount = runCatching {
                todoRepository.countQuickNotesUsingMediaUri(uri)
            }.getOrNull() ?: return@launch
            quickNoteDraftMediaGrantRegistry.releaseIfUnused(cleanupTicket, savedReferenceCount) {
                if (isPrivateMediaUri(uri)) {
                    deletePrivateMedia(Uri.parse(uri))
                } else {
                    runCatching {
                        application.contentResolver.releasePersistableUriPermission(
                            Uri.parse(uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
            }
        }
    }

    private fun copyToPrivateMedia(
        sourceUri: Uri,
        onTargetCreated: (Uri) -> Unit
    ): Uri {
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        val directory = File(application.filesDir, PRIVATE_MEDIA_DIRECTORY).apply { mkdirs() }
        val displayName = runCatching {
            resolver.query(
                sourceUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
            }
        }.getOrNull()
        val mimeType = resolver.getType(sourceUri)
        val extension = sequenceOf(
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.orEmpty()),
            displayName?.substringAfterLast('.', "")
        ).filterNotNull()
            .map { it.lowercase() }
            .firstOrNull { candidate ->
                candidate.length in 1..8 && candidate.all(Char::isLetterOrDigit)
            }
            ?: "img"
        val target = File.createTempFile("note_${UUID.randomUUID()}_", ".$extension", directory)
        val targetUri = Uri.fromFile(target)
        onTargetCreated(targetUri)
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1_024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= QuickNoteMediaPolicy.MAX_IMAGE_BYTES) { "图片不能超过 12 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取所选图片")
            require(target.length() > 0L) { "图片内容为空" }
            return targetUri
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun isPrivateMediaUri(uri: String): Boolean =
        runCatching { Uri.parse(uri) }.getOrNull()?.let(::isPrivateMediaUri) == true

    private fun isPrivateMediaUri(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val file = uri.path?.let(::File) ?: return false
        val directory = File(getApplication<Application>().filesDir, PRIVATE_MEDIA_DIRECTORY)
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        return canonicalFile.path.startsWith(canonicalDirectory.path + File.separator)
    }

    private fun deletePrivateMedia(uri: Uri) {
        if (!isPrivateMediaUri(uri)) return
        runCatching { File(requireNotNull(uri.path)).delete() }
    }

    private fun resolveMediaMetadata(uri: Uri): QuickNoteMediaMetadata {
        val resolver = getApplication<Application>().contentResolver
        var displayName: String? = null
        var sizeBytes = -1L
        if (uri.scheme != "file") {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { displayName = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { sizeBytes = cursor.getLong(it) }
                }
            }
        }
        if (displayName == null && uri.scheme == "file") {
            displayName = uri.path?.let(::File)?.name
        }
        if (sizeBytes <= 0L && uri.scheme == "file") {
            sizeBytes = uri.path?.let(::File)?.length() ?: -1L
        }
        if (sizeBytes <= 0L) {
            sizeBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        if (sizeBytes <= 0L) {
            sizeBytes = resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1_024)
                var total = 0L
                while (total <= QuickNoteMediaPolicy.MAX_IMAGE_BYTES) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            } ?: -1L
        }
        val mimeType = resolver.getType(uri)
            ?: displayName
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()
                ?.takeIf(String::isNotBlank)
                ?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
            ?: ""
        return QuickNoteMediaMetadata(
            uri = uri.toString(),
            mimeType = mimeType,
            displayName = displayName,
            sizeBytes = sizeBytes
        )
    }

    private fun reportFailure(error: Throwable, fallback: String) {
        reportMessage(error.message?.takeIf(String::isNotBlank) ?: fallback)
    }

    override fun onCleared() {
        cancelPendingMediaImport()
        cancelPendingAttachmentResolution()
        ownedDraftMediaUris.toList().forEach(::relinquishDraftMediaUri)
        super.onCleared()
    }

    companion object {
        private const val MAX_COMPOSER_CHARS = 4_000
        private const val MAX_TITLE_CHARS = 120
        private const val PARSE_DEBOUNCE_MILLIS = 550L
        private const val DEFAULT_HABIT_COLOR = "#21C76A"
        private const val DEFAULT_HABIT_ICON = "check"
        private const val PRIVATE_MEDIA_DIRECTORY = "quick_note_media"
        private val mediaGrantCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun actionKey(noteId: String, action: QuickNoteAction): String = "$noteId:${action.name}"
    }
}

private fun ParsedQuickNote.todoRecurrenceType(): String = when (recurrence) {
    QuickNoteRecurrence.NONE -> com.example.controlfree.todo.TodoRecurrenceType.NONE.storedValue
    QuickNoteRecurrence.DAILY -> com.example.controlfree.todo.TodoRecurrenceType.DAILY.storedValue
    QuickNoteRecurrence.WEEKDAYS -> com.example.controlfree.todo.TodoRecurrenceType.WEEKDAYS.storedValue
    QuickNoteRecurrence.WEEKLY -> com.example.controlfree.todo.TodoRecurrenceType.WEEKLY_DAYS.storedValue
}

private fun ParsedQuickNote.habitFrequencyType(): String = when (recurrence) {
    QuickNoteRecurrence.NONE,
    QuickNoteRecurrence.DAILY -> com.example.controlfree.todo.HabitFrequencyType.DAILY.storedValue
    QuickNoteRecurrence.WEEKDAYS,
    QuickNoteRecurrence.WEEKLY -> com.example.controlfree.todo.HabitFrequencyType.SPECIFIC_WEEKDAYS.storedValue
}

private fun ParsedQuickNote.weekdayMask(clock: Clock): Int = when (recurrence) {
    QuickNoteRecurrence.WEEKDAYS -> MONDAY_TO_FRIDAY_MASK
    QuickNoteRecurrence.WEEKLY -> {
        val reference = startAtEpochMillis ?: dueAtEpochMillis ?: clock.millis()
        val dayOfWeek = Instant.ofEpochMilli(reference).atZone(clock.zone).dayOfWeek.value
        1 shl (dayOfWeek - 1)
    }
    QuickNoteRecurrence.NONE,
    QuickNoteRecurrence.DAILY -> 0
}

private const val MONDAY_TO_FRIDAY_MASK = 0b0011111
