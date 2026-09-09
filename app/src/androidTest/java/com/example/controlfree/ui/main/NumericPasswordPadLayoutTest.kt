package com.example.controlfree.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class NumericPasswordPadLayoutTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearZeroAndBackspace_shareBottomRow_andConfirmationIsBelow() {
        var currentValue = "1234"
        composeTestRule.setContent {
            var value by remember { mutableStateOf(currentValue) }
            NumericPasswordPad(
                value = value,
                onEdit = { edit ->
                    val updated = reduceNumericPassword(
                        current = value,
                        edit = edit,
                        inputLimit = numericPasswordInputLimit(expectedLength = null)
                    )
                    value = updated
                    currentValue = updated
                },
                expectedLength = null,
                onConfirm = {}
            )
        }

        val clearBounds = composeTestRule
            .onNodeWithContentDescription("清空输入")
            .fetchSemanticsNode()
            .boundsInRoot
        val zeroBounds = composeTestRule
            .onNodeWithContentDescription("数字 0")
            .fetchSemanticsNode()
            .boundsInRoot
        val backspaceBounds = composeTestRule
            .onNodeWithContentDescription("删除一位")
            .fetchSemanticsNode()
            .boundsInRoot
        val confirmationBounds = composeTestRule
            .onNodeWithText("确认输入")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(abs(clearBounds.center.y - zeroBounds.center.y) < 1f)
        assertTrue(abs(zeroBounds.center.y - backspaceBounds.center.y) < 1f)
        assertTrue(confirmationBounds.top >= zeroBounds.bottom)

        composeTestRule.onNodeWithContentDescription("清空输入").performClick()
        composeTestRule.runOnIdle { assertEquals("", currentValue) }
    }

    @Test
    fun clearAndBackspace_remainEnabledWhileValueSnapshotIsEmpty() {
        composeTestRule.setContent {
            NumericPasswordPad(
                value = "",
                onEdit = {},
                expectedLength = 6,
                onConfirm = null
            )
        }

        composeTestRule.onNodeWithContentDescription("清空输入").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("删除一位").assertIsEnabled()
    }
}
