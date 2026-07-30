package com.tensal.denden.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tensal.denden.AppLanguage
import com.tensal.denden.DenDenThemeMode
import com.tensal.denden.branding.DirectBrandStatus
import com.tensal.denden.readiness.buildDirectReadinessSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemSettingsShowsOnlyDirectFcmAndLocalTest() {
        composeRule.setContent {
            LocalizedTestTheme {
                SystemSettingsScreen(
                    readiness = buildDirectReadinessSnapshot(false, false, false, 0, 0, null, null),
                    firebaseProjectId = "denden-demo-123"
                )
            }
        }
        composeRule.onNodeWithText("denden-demo-123").assertIsDisplayed()
        composeRule.onNodeWithText("本機警報測試").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("遠端測試").assertDoesNotExist()
        composeRule.onNodeWithText("成本警示").assertDoesNotExist()
        composeRule.onNodeWithText("後端網域").assertDoesNotExist()
    }

    @Test
    fun unpairedLocalModeCanStartPairingFromSettings() {
        var pairingOpened = false
        composeRule.setContent {
            LocalizedTestTheme {
                SystemSettingsScreen(
                    readiness = buildDirectReadinessSnapshot(false, true, true, 0, 0, null, null),
                    onClearPairing = { pairingOpened = true }
                )
            }
        }

        composeRule.onNodeWithText("立即配對").performClick()
        composeRule.runOnIdle { assertTrue(pairingOpened) }
    }

    @Test
    fun readyStateEnablesLocalTest() {
        composeRule.setContent {
            LocalizedTestTheme {
                SystemSettingsScreen(
                    readiness = buildDirectReadinessSnapshot(true, true, true, 10, 20, "local_pass", null)
                )
            }
        }
        composeRule.onNodeWithText("本機警報測試").performScrollTo().assertIsEnabled()
    }

    @Test
    fun systemSettingsShowsBoundedBrandProgressAndShortcutRetry() {
        composeRule.setContent {
            LocalizedTestTheme {
                SystemSettingsScreen(
                    readiness = buildDirectReadinessSnapshot(true, true, true, 10, 20, "local_pass", null),
                    brandingStatus = DirectBrandStatus(
                        isCustom = true,
                        generation = 7,
                        activeRevisionFingerprint = "abcdef123456",
                        receivingTransferFingerprint = "transfer1234",
                        receivedChunks = 3,
                        totalChunks = 8,
                        shortcutUpdatePending = true
                    )
                )
            }
        }
        composeRule.onNodeWithText("自訂 DenDen 已啟用").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("DenDen 世代：7").assertIsDisplayed()
        composeRule.onNodeWithText("接收中：3/8 片 · transfer1234").assertIsDisplayed()
        composeRule.onNodeWithText("捷徑圖示等待前景重試").assertIsDisplayed()
        composeRule.onNodeWithText("abcdef123456").assertDoesNotExist()
    }

    @Test
    fun homeContainsNoServerDeviceManagement() {
        var alarmOutputOpened = false
        composeRule.setContent {
            LocalizedTestTheme {
                SettingsScreen(
                    DenDenThemeMode.SYSTEM,
                    buildDirectReadinessSnapshot(true, true, true, 0, 0, null, null),
                    onOpenAlarmOutputSettings = { alarmOutputOpened = true }
                )
            }
        }
        composeRule.onNodeWithText("警報輸出").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(alarmOutputOpened) }
        composeRule.onNodeWithText("系統權限").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("通知、鎖定畫面最上層、電池與勿擾設定").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("系統設定").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("裝置管理").assertDoesNotExist()
    }

    @Test
    fun languageChoicesOpenInDialog() {
        composeRule.setContent {
            LocalizedTestTheme {
                SettingsScreen(
                    DenDenThemeMode.SYSTEM,
                    buildDirectReadinessSnapshot(true, true, true, 0, 0, null, null),
                    appLanguage = AppLanguage.TRADITIONAL_CHINESE
                )
            }
        }
        composeRule.onNodeWithText("跟隨系統").assertDoesNotExist()
        composeRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText("語言")),
            useUnmergedTree = true
        ).performClick()
        composeRule.onNode(hasText("跟隨系統") or hasText("System default")).assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
    }

    @Test
    fun alarmOutputSettingsHaveTheirOwnScreen() {
        composeRule.setContent {
            LocalizedTestTheme { AlarmOutputSettingsScreen() }
        }
        composeRule.onNodeWithText("響鈴並震動").assertIsDisplayed()
        composeRule.onNodeWithText("響鈴聲音").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("震動方式").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun permissionsExplainLockScreenWithoutOverlayAccess() {
        composeRule.setContent {
            LocalizedTestTheme {
                SystemPermissionsScreen(false, false, false, false)
            }
        }
        composeRule.onNodeWithText("鎖定畫面最上層").assertIsDisplayed()
        composeRule.onNodeWithText(
            "DenDen 以全螢幕通知顯示在鎖定畫面最上層，不要求「顯示在其他應用程式上層」。"
        ).assertIsDisplayed()
    }
}
