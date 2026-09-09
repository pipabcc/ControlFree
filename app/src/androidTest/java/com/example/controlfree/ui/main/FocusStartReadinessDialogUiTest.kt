package com.example.controlfree.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.controlfree.theme.ControlFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FocusStartReadinessDialogUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingPreset_isShownAndCancelDoesNotStart() {
        var startCount = 0
        var dismissCount = 0

        composeTestRule.setContent {
            ControlFreeTheme {
                FocusStartReadinessDialog(
                    readiness = RuntimeReadiness(emptyList()),
                    lockMinutes = 15,
                    playMinutes = 3,
                    onFix = {},
                    onRefresh = {},
                    onDismiss = { dismissCount += 1 },
                    onStart = { startCount += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("立即锁定").assertExists()
        composeTestRule.onNodeWithText(
            "立即锁定 15 分钟 → 玩机 3 分钟 → 再次锁定并持续循环，直到手动终止。"
        ).assertExists()
        composeTestRule.onNodeWithText("取消").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(0, startCount)
        }
    }

    @Test
    fun readyDialog_confirmRequestsOneStart() {
        var startCount = 0

        composeTestRule.setContent {
            ControlFreeTheme {
                FocusStartReadinessDialog(
                    readiness = RuntimeReadiness(emptyList()),
                    lockMinutes = 30,
                    playMinutes = 5,
                    onFix = {},
                    onRefresh = {},
                    onDismiss = {},
                    onStart = { startCount += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("确认")
            .assertIsEnabled()
            .performClick()

        composeTestRule.runOnIdle { assertEquals(1, startCount) }
    }

    @Test
    fun blockedDialog_disablesConfirmAndKeepsFixAndRefreshAvailable() {
        var fixedRequirement: RuntimeRequirementKey? = null
        var refreshCount = 0
        val readiness = RuntimeReadiness(
            requirements = listOf(
                RuntimeRequirement(
                    key = RuntimeRequirementKey.USAGE_ACCESS,
                    title = "使用情况访问",
                    purpose = "识别前台应用",
                    state = RuntimeRequirementState.ACTION_REQUIRED,
                    blocksStart = true
                )
            )
        )

        composeTestRule.setContent {
            ControlFreeTheme {
                FocusStartReadinessDialog(
                    readiness = readiness,
                    lockMinutes = 5,
                    playMinutes = 1,
                    onFix = { fixedRequirement = it },
                    onRefresh = { refreshCount += 1 },
                    onDismiss = {},
                    onStart = {}
                )
            }
        }

        composeTestRule.onNodeWithText("确认").assertIsNotEnabled()
        composeTestRule.onNodeWithText("处理").performClick()
        composeTestRule.onNodeWithContentDescription("刷新权限状态").performClick()

        composeTestRule.runOnIdle {
            assertEquals(RuntimeRequirementKey.USAGE_ACCESS, fixedRequirement)
            assertEquals(1, refreshCount)
        }
    }
}
