package com.example.controlfree.productivity.quicknote

import org.junit.Assert.assertTrue
import org.junit.Test

class QuickNoteMediaPolicyTest {
    @Test
    fun `只接受大小受限的图片`() {
        val accepted = QuickNoteMediaPolicy.validate(
            QuickNoteMediaMetadata("content://image/1", "image/png", "灵感.png", 1024L)
        )
        val rejectedType = QuickNoteMediaPolicy.validate(
            QuickNoteMediaMetadata("content://file/1", "application/pdf", "文档.pdf", 1024L)
        )
        val rejectedSize = QuickNoteMediaPolicy.validate(
            QuickNoteMediaMetadata(
                "content://image/2",
                "image/jpeg",
                "过大.jpg",
                QuickNoteMediaPolicy.MAX_IMAGE_BYTES + 1L
            )
        )

        assertTrue(accepted is QuickNoteMediaValidation.Accepted)
        assertTrue(rejectedType is QuickNoteMediaValidation.UnsupportedType)
        assertTrue(rejectedSize is QuickNoteMediaValidation.FileTooLarge)
    }
}
