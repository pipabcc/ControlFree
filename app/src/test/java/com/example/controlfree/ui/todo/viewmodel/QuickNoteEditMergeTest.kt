package com.example.controlfree.ui.todo.viewmodel

import com.example.controlfree.productivity.quicknote.QuickNoteMediaMetadata
import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.todo.QuickNoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickNoteEditMergeTest {
    @Test
    fun unchangedAttachmentPreservesNullableMetadataAndOriginalCaptureSource() {
        val original = note(
            mediaUri = "content://quick-note/original",
            mediaMimeType = null,
            mediaSizeBytes = null,
            captureSource = "IMAGE_IMPORT"
        )
        val composer = QuickNoteComposerState(
            content = "修改后的正文",
            attachment = QuickNoteMediaMetadata(
                uri = original.mediaUri!!,
                mimeType = "",
                displayName = null,
                sizeBytes = 0L
            ),
            editingNoteId = original.id
        )

        val merged = mergeQuickNoteEdit(original, composer, updatedAtEpochMillis = 2_000L)

        assertEquals("修改后的正文", merged.content)
        assertNull(merged.mediaMimeType)
        assertNull(merged.mediaSizeBytes)
        assertEquals("IMAGE_IMPORT", merged.captureSource)
    }

    @Test
    fun replacingAttachmentUsesNewMetadata() {
        val original = note(
            mediaUri = "content://quick-note/original",
            aiAdvice = "保留建议",
            aiAssistantFingerprint = "a".repeat(64)
        )
        val replacement = QuickNoteMediaMetadata(
            uri = "content://quick-note/replacement",
            mimeType = "image/png",
            displayName = "replacement.png",
            sizeBytes = 42L
        )

        val merged = mergeQuickNoteEdit(
            original = original,
            composer = QuickNoteComposerState(
                content = original.content,
                attachment = replacement,
                editingNoteId = original.id
            ),
            updatedAtEpochMillis = 2_000L
        )

        assertEquals(replacement.uri, merged.mediaUri)
        assertEquals(replacement.mimeType, merged.mediaMimeType)
        assertEquals(replacement.displayName, merged.mediaDisplayName)
        assertEquals(replacement.sizeBytes, merged.mediaSizeBytes)
        assertEquals(original.aiAdvice, merged.aiAdvice)
        assertEquals(original.aiAssistantFingerprint, merged.aiAssistantFingerprint)
    }

    @Test
    fun changingContentClearsStaleAssistantAdviceAndFingerprint() {
        val original = note(
            aiAdvice = "旧建议",
            aiAssistantFingerprint = "b".repeat(64)
        )

        val merged = mergeQuickNoteEdit(
            original = original,
            composer = QuickNoteComposerState(
                content = "已经修改的正文",
                editingNoteId = original.id
            ),
            updatedAtEpochMillis = 2_000L
        )

        assertNull(merged.aiAdvice)
        assertNull(merged.aiAssistantFingerprint)
    }

    @Test
    fun assistantDraftPreviewKeepsTheUnsavedComposerSnapshotUntouched() {
        val composer = QuickNoteComposerState(content = "专注20分钟")
        val before = composer.copy()

        val preview = quickNoteAssistantDraftPreview(
            original = null,
            composer = composer,
            nowEpochMillis = 2_000L,
            previewId = "assistant-preview:test"
        )

        assertEquals("assistant-preview:test", preview.id)
        assertEquals(composer.content, preview.content)
        assertEquals(before, composer)
    }

    private fun note(
        mediaUri: String? = null,
        mediaMimeType: String? = "image/jpeg",
        mediaSizeBytes: Long? = 100L,
        captureSource: String = "TEXT",
        aiAdvice: String? = null,
        aiAssistantFingerprint: String? = null
    ) = QuickNoteEntity(
        id = "note-id",
        content = "原正文",
        mediaUri = mediaUri,
        status = QuickNoteStatus.RAW.storedValue,
        createdAtEpochMillis = 1_000L,
        mediaMimeType = mediaMimeType,
        mediaDisplayName = "original.jpg",
        mediaSizeBytes = mediaSizeBytes,
        captureSource = captureSource,
        updatedAtEpochMillis = 1_000L,
        aiAdvice = aiAdvice,
        aiAssistantFingerprint = aiAssistantFingerprint
    )
}
