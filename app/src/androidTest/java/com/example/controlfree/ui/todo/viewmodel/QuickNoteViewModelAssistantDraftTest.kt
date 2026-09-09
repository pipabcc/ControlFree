package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.controlfree.todo.TodoRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickNoteViewModelAssistantDraftTest {
    @Test
    fun 草稿分析和取消前不会写入闪记数据库() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = TodoRepository.getInstance(application)
        val before = repository.observeAllQuickNotes().first().map { it.id }.toSet()
        val viewModel = QuickNoteViewModel(application)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.updateContent("专注20分钟阅读论文")
            viewModel.saveAndAnalyzeWithAssistant()
        }
        val pending = withTimeout(15_000L) {
            viewModel.pendingAssistantConfirmation.filterNotNull().first()
        }

        assertNotNull(pending.composerSnapshot)
        assertEquals(before, repository.observeAllQuickNotes().first().map { it.id }.toSet())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.cancelAssistantConversion()
        }

        assertEquals("专注20分钟阅读论文", viewModel.composer.value.content)
        assertEquals(before, repository.observeAllQuickNotes().first().map { it.id }.toSet())
    }
}
