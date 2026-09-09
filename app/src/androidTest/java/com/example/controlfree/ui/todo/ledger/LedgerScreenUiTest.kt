package com.example.controlfree.ui.todo.ledger

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.controlfree.ai.ParsedLedgerEntry
import com.example.controlfree.ai.ParsedTripleRoutingResult
import com.example.controlfree.theme.ControlFreeTheme
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.ui.todo.viewmodel.LedgerComposerState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LedgerScreenUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun multipleAiEntries_shareOnePreviewCard_andUseShortConfirmLabel() {
        var confirmCount = 0
        composeTestRule.setContent {
            ControlFreeTheme {
                LedgerComposer(
                    state = LedgerComposerState(
                        content = "午餐 28 元，公交 2 元",
                        parsedResult = ParsedTripleRoutingResult(
                            ledgerEntries = listOf(
                                parsedEntry("午餐", 2_800L, LedgerCategory.FOOD),
                                parsedEntry("公交", 200L, LedgerCategory.TRANSPORT)
                            ),
                            todoItems = emptyList(),
                            tip = "量入为出",
                            warnings = emptyList()
                        ),
                        analysisBatchId = "batch-1"
                    ),
                    onContentChanged = {},
                    onAiClassify = {},
                    onConfirmSave = { confirmCount += 1 },
                    onEditEntry = {},
                    onDeleteEntry = {}
                )
            }
        }

        composeTestRule.onAllNodesWithTag("ledger-preview-card").assertCountEquals(1)
        composeTestRule.onNodeWithText("待确认账目 · 2 笔").assertExists()
        composeTestRule.onNodeWithText("午餐").assertExists()
        composeTestRule.onNodeWithText("公交").assertExists()
        composeTestRule.onNodeWithTag("ledger-confirm-button")
            .assertIsEnabled()
            .performClick()

        composeTestRule.runOnIdle { assertEquals(1, confirmCount) }
    }

    @Test
    fun savedEntryCard_clickOpensEditor() {
        var editCount = 0
        val entry = savedEntry()
        composeTestRule.setContent {
            ControlFreeTheme {
                LedgerTimelineItem(entry = entry, onClick = { editCount += 1 })
            }
        }

        composeTestRule.onNodeWithText("流水测试项").performClick()
        composeTestRule.runOnIdle { assertEquals(1, editCount) }
    }

    @Test
    fun savedEntryEditor_exposesDeleteAction() {
        var editCount = 0
        var deleteCount = 0
        val entry = savedEntry()
        composeTestRule.setContent {
            ControlFreeTheme {
                SavedLedgerAdjustmentDialog(
                    entry = entry,
                    isSaving = false,
                    onDismiss = {},
                    onDeleteRequest = { deleteCount += 1 },
                    onSave = { editCount += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("编辑账目").assertExists()
        composeTestRule.onNodeWithText("删除账目", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("删除").performClick()
        composeTestRule.runOnIdle { assertEquals(1, deleteCount) }
    }

    private fun parsedEntry(
        title: String,
        amountFen: Long,
        category: LedgerCategory
    ): ParsedLedgerEntry = ParsedLedgerEntry(
        title = title,
        amountFen = amountFen,
        isEstimated = false,
        direction = LedgerDirection.EXPENSE,
        category = category,
        emotion = "刚需",
        necessity = "need",
        occurredAtEpochMillis = Instant.parse("2026-07-23T04:00:00Z").toEpochMilli(),
        confidence = 0.9f
    )

    private fun savedEntry() = LedgerEntryEntity(
        id = "entry-1",
        title = "流水测试项",
        amount = 2_800L,
        direction = LedgerDirection.EXPENSE.storedValue,
        category = LedgerCategory.FOOD.storedValue,
        note = null,
        occurredAtEpochMillis = Instant.parse("2026-07-23T04:00:00Z").toEpochMilli(),
        sourceNoteId = null,
        emotion = null,
        necessity = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        sourceType = "manual"
    )
}
