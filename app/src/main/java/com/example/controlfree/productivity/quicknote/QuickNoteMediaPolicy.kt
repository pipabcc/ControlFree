package com.example.controlfree.productivity.quicknote

data class QuickNoteMediaMetadata(
    val uri: String,
    val mimeType: String,
    val displayName: String?,
    val sizeBytes: Long
)

sealed interface QuickNoteMediaValidation {
    data class Accepted(val metadata: QuickNoteMediaMetadata) : QuickNoteMediaValidation
    data object MissingUri : QuickNoteMediaValidation
    data object UnsupportedType : QuickNoteMediaValidation
    data object EmptyFile : QuickNoteMediaValidation
    data object FileTooLarge : QuickNoteMediaValidation
}

object QuickNoteMediaPolicy {
    const val MAX_IMAGE_BYTES = 12L * 1_024L * 1_024L

    fun validate(metadata: QuickNoteMediaMetadata): QuickNoteMediaValidation = when {
        metadata.uri.isBlank() -> QuickNoteMediaValidation.MissingUri
        !metadata.mimeType.lowercase().startsWith("image/") -> QuickNoteMediaValidation.UnsupportedType
        metadata.sizeBytes <= 0L -> QuickNoteMediaValidation.EmptyFile
        metadata.sizeBytes > MAX_IMAGE_BYTES -> QuickNoteMediaValidation.FileTooLarge
        else -> QuickNoteMediaValidation.Accepted(
            metadata.copy(
                uri = metadata.uri.trim(),
                mimeType = metadata.mimeType.lowercase(),
                displayName = metadata.displayName?.trim()?.take(120)
            )
        )
    }
}
