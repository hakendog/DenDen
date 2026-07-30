package com.tensal.denden.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import com.tensal.denden.DenDenThemeMode
import com.tensal.denden.MainScreen
import com.tensal.denden.alarm.AlarmRuntimeSnapshot
import com.tensal.denden.branding.DirectBrandCandidate
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class BrandingAlarmUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun customMascotHasStableTopBarLogoSemantics() {
        val mascot = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            LocalizedTestTheme {
                MainScreen(
                    themeMode = DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    mascot = mascot,
                    events = emptyList()
                )
            }
        }

        composeRule.onNodeWithContentDescription("自訂 DenDen Logo").assertIsDisplayed()
    }

    @Test
    fun alarmUsesDraggableDendenThumbWithBuiltInFallback() {
        var stops = 0
        val payload = ActiveAlarmPayload("infra", "T", "M", "e1")
        val state = AlarmScreenState(initiallyAlarming = true, initialPayload = payload)
        composeRule.setContent {
            LocalizedTestTheme {
                AlarmScreen(payload = payload, state = state, onStopAlarm = { stops++ })
            }
        }

        composeRule.onNodeWithContentDescription("滑動以停止警報").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("可拖曳 DenDen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("滑動以停止警報")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun matchingTerminalSnapshotShowsResolvedThenEndsRouteOnce() {
        val payload = ActiveAlarmPayload("infra", "T", "M", "e1")
        var stopRequests = 0
        var routeEnds = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LocalizedTestTheme {
                AlarmScreen(
                    alarmActivationCount = 1,
                    payload = payload,
                    runtimeSnapshot = AlarmRuntimeSnapshot.Terminal("e1", "timeout"),
                    onStopAlarm = { stopRequests++ },
                    onTerminalShown = { routeEnds++ }
                )
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("已解除警報").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, stopRequests)
            assertEquals(0, routeEnds)
        }
        composeRule.mainClock.advanceTimeBy(1_201)
        composeRule.runOnIdle { assertEquals(1, routeEnds) }
    }

    @Test
    fun completedBrandCandidateRequiresExplicitAcceptance() {
        val mascot = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        var accepted = 0
        var rejected = 0
        composeRule.setContent {
            LocalizedTestTheme {
                BrandCandidateDialog(
                    candidate = DirectBrandCandidate(2, "revision", false, null, null, mascot),
                    onAccept = { accepted++ },
                    onReject = { rejected++ }
                )
            }
        }

        composeRule.onNodeWithText("套用新的 DenDen？").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("新 DenDen 外觀預覽").assertIsDisplayed()
        composeRule.onNodeWithText("套用").performClick()
        composeRule.runOnIdle {
            assertEquals(1, accepted)
            assertEquals(0, rejected)
        }
    }
}
