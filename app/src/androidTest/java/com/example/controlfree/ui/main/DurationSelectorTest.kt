package com.example.controlfree.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DurationSelectorTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun incrementButton_updatesDisplayedValue() {
        composeTestRule.setContent {
            var value = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(30) }
            DurationSelector(
                title = "可用时长",
                value = value.intValue,
                minValue = 5,
                maxValue = 180,
                step = 1,
                presets = listOf(5, 15, 30, 45),
                accentColor = Color.Cyan,
                onValueChange = { value.intValue = it }
            )
        }

        composeTestRule.onNodeWithContentDescription("增加可用时长").performClick()
        composeTestRule.onNodeWithText("31").assertExists()
    }
}
