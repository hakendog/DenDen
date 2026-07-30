package com.tensal.denden.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun walksThroughFourStepsAndAllowsSkippingDemo() {
        var step by mutableStateOf(SetupStep.WELCOME)
        composeRule.setContent {
            LocalizedTestTheme {
                TestSetupScreen(step = step, onStepChange = { step = it })
            }
        }

        composeRule.onNodeWithText("歡迎使用 DenDen").assertIsDisplayed()
        composeRule.onNodeWithText("開始設定").performClick()
        composeRule.onNodeWithText("必要權限").assertIsDisplayed()
        composeRule.onNodeWithText("必要 · 未授權；警報可能無法顯示").assertIsDisplayed()
        composeRule.onNodeWithText("鎖定畫面最上層").assertIsDisplayed()
        composeRule.onNodeWithText("DenDen 不需要「顯示在其他應用程式上層」權限。暫不授權仍可繼續，之後可在設定中調整。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("下一步").performScrollTo().performClick()
        composeRule.onNodeWithText("10 秒本機示範").assertIsDisplayed()
        composeRule.onNodeWithText("跳過示範").performClick()
        composeRule.onNodeWithText("掃描 DenDen 配對碼").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("檢查配對").assertIsNotEnabled()
        composeRule.onNodeWithText("後端 URL").assertDoesNotExist()
        composeRule.onNodeWithText("DDC 或邀請網址").assertDoesNotExist()
    }

    @Test
    fun pairingShowsBackendScopeBeforeConsumingInvite() {
        var confirmed = false
        composeRule.setContent {
            LocalizedTestTheme {
                TestSetupScreen(
                    step = SetupStep.PAIRING,
                    input = "DDC.example",
                    state = SetupDisplayState(
                        confirmFirebaseProjectId = "my-denden",
                        confirmDomain = "denden.example"
                    ),
                    onConfirmPairing = { confirmed = true }
                )
            }
        }

        composeRule.onNodeWithText("檢查配對").assertIsEnabled()
        composeRule.onNodeWithText("確認配對").assertIsDisplayed()
        composeRule.onNodeWithText("my-denden").assertIsDisplayed()
        composeRule.onNodeWithText("denden.example").assertIsDisplayed()
        composeRule.onNodeWithText("確認後，配對資料會安全保存在這台手機，並開始接收這組 DenDen 訊息。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("確認後才會建立本機匿名身分並消耗一次性邀請。").assertDoesNotExist()
        composeRule.onNodeWithText("確認並配對").assertIsEnabled().assertIsFocused().performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun pairingConfirmationCanReturnToInputAndErrorStopsLoading() {
        var cancelled = false
        composeRule.setContent {
            LocalizedTestTheme {
                TestSetupScreen(
                    step = SetupStep.PAIRING,
                    input = "bad",
                    state = SetupDisplayState(
                        status = "邀請已過期",
                        confirmFirebaseProjectId = "my-denden",
                        confirmDomain = "Firebase Cloud Messaging"
                    ),
                    onCancelPairing = { cancelled = true }
                )
            }
        }

        composeRule.onNodeWithText("邀請已過期").assertIsDisplayed()
        composeRule.onNodeWithText("檢查中…").assertDoesNotExist()
        composeRule.onNodeWithText("重新輸入").performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }

    @Test
    fun pairingCanContinueWithLocalAutomationOnly() {
        var localMode = false
        composeRule.setContent {
            LocalizedTestTheme {
                TestSetupScreen(step = SetupStep.PAIRING, onUseLocalMode = { localMode = true })
            }
        }

        composeRule.onNodeWithText("只使用本機自動化").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(localMode) }
    }
}

@androidx.compose.runtime.Composable
private fun TestSetupScreen(
    step: SetupStep,
    input: String = "",
    state: SetupDisplayState = SetupDisplayState(),
    onStepChange: (SetupStep) -> Unit = {},
    onConfirmPairing: () -> Unit = {},
    onCancelPairing: () -> Unit = {},
    onUseLocalMode: () -> Unit = {}
) {
    SetupScreen(
        step = step,
        input = input,
        state = state,
        localTestInProgress = false,
        localTestMessage = null,
        isNotificationPermissionGranted = false,
        canUseFullScreenIntent = false,
        isBatteryOptimizationIgnored = false,
        isNotificationPolicyAccessGranted = false,
        onStepChange = onStepChange,
        onInputChange = {},
        onSubmit = {},
        onScanQr = {},
        onConfirmPairing = onConfirmPairing,
        onCancelPairing = onCancelPairing,
        onRunLocalTest = {},
        onRequestNotification = {},
        onOpenFullScreenIntentSettings = {},
        onOpenBatteryOptimizationSettings = {},
        onOpenDndSettings = {},
        onUseLocalMode = onUseLocalMode
    )
}
