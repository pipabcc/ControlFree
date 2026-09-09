package com.example.controlfree.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlanCardActionsUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val switchMatcher = SemanticsMatcher.expectValue(
        SemanticsProperties.Role,
        Role.Switch
    )

    @Test
    fun planSwitch_keepsFixedAccessibleTouchTarget_andHandlesOneClick() {
        var toggleCount = 0
        composeTestRule.setContent {
            StablePlanEnabledSwitch(
                checked = false,
                busy = false,
                onCheckedChange = { toggleCount += 1 }
            )
        }

        composeTestRule.onNode(switchMatcher)
            .assertIsEnabled()
            .assertWidthIsEqualTo(52.dp)
            .assertHeightIsEqualTo(48.dp)
            .performClick()

        composeTestRule.runOnIdle { assertEquals(1, toggleCount) }
    }

    @Test
    fun busyPlanSwitch_preservesBoundsAndDisablesRepeatedInput() {
        composeTestRule.setContent {
            StablePlanEnabledSwitch(
                checked = false,
                busy = true,
                onCheckedChange = {}
            )
        }

        composeTestRule.onNode(switchMatcher)
            .assertIsNotEnabled()
            .assertWidthIsEqualTo(52.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun clickingPlanSwitch_doesNotOpenParentCardMenu() {
        var toggleCount = 0
        composeTestRule.setContent {
            PlanActionCard(
                planId = "plan-1",
                busy = false,
                onEdit = {},
                onDelete = {}
            ) {
                StablePlanEnabledSwitch(
                    checked = false,
                    busy = false,
                    onCheckedChange = { toggleCount += 1 }
                )
            }
        }

        composeTestRule.onNode(switchMatcher).performClick()

        composeTestRule.runOnIdle { assertEquals(1, toggleCount) }
        composeTestRule.onNodeWithText("编辑").assertDoesNotExist()
    }
}
