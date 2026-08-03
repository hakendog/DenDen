package com.tensal.denden.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasStateDescription
import com.tensal.denden.DenDenThemeMode
import com.tensal.denden.MainScreen
import com.tensal.denden.data.DenDenEvent
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun channelListUsesTextFirstRowsAndAlarmColoredUnreadSemantics() {
        val alarm = event(
            id = 1,
            action = "ring",
            kind = "alarm",
            state = "ringing",
            title = "付款失敗率升高"
        )
        composeRule.setContent {
            LocalizedTestTheme {
                ChannelListScreen(
                    channelItems = listOf(
                        ChannelInboxItem("ops", "支付監控", alarm, eventCount = 1, unreadCount = 1)
                    ),
                    onChannelSelected = {}
                )
            }
        }

        composeRule.onNodeWithText("支付監控").assertIsDisplayed()
        composeRule.onNodeWithText("#支付監控").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("1 則未讀警報").assertIsDisplayed()
    }

    @Test
    fun expandedTimelineShowsOnlyStatusesAndTagsPresentInChannel() {
        val normal = event(
            id = 1,
            action = "notify",
            kind = "notification",
            state = "delivered",
            title = "部署完成",
            tagsJson = "[\"production\"]"
        )
        val pending = event(
            id = 2,
            action = "ring",
            kind = "alarm",
            state = "pending",
            title = "等待投遞",
            tagsJson = "[\"urgent\"]"
        )
        composeRule.setContent {
            LocalizedTestTheme {
                ChannelTimelineScreen(
                    channelId = "ops",
                    channelName = "支付監控",
                    events = listOf(normal, pending),
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("搜尋事件").performClick()
        composeRule.onNodeWithText("全部").assertIsDisplayed()
        composeRule.onNodeWithText("一般").assertIsDisplayed()
        composeRule.onNodeWithText("未送達").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("production").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("urgent").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("靜音").assertDoesNotExist()
        composeRule.onNodeWithText("響鈴").assertDoesNotExist()
        composeRule.onNodeWithText("錯過").assertDoesNotExist()
    }

    @Test
    fun timelineTimestampToggleAppliesToEveryMessage() {
        val first = event(
            id = 1,
            action = "notify",
            kind = "notification",
            state = "delivered",
            title = "部署完成",
            receivedAt = System.currentTimeMillis() - 2 * 60_000
        )
        val second = event(
            id = 2,
            action = "notify",
            kind = "notification",
            state = "delivered",
            title = "測試完成",
            receivedAt = System.currentTimeMillis() - 3 * 60_000
        )
        composeRule.setContent {
            LocalizedTestTheme {
                ChannelTimelineScreen(
                    channelId = "ops",
                    events = listOf(first, second),
                    onBack = {}
                )
            }
        }

        composeRule.onAllNodes(hasStateDescription("相對時間")).assertCountEquals(2)[0].performClick()
        composeRule.onAllNodes(hasStateDescription("確切時間")).assertCountEquals(2)
    }

    @Test
    fun navigationIsImmediateAndContentReady() {
        val message = event(
            id = 1,
            action = "notify",
            kind = "notification",
            state = "delivered",
            title = "部署完成"
        )
        composeRule.setContent {
            LocalizedTestTheme {
                MainScreen(
                    themeMode = DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    events = listOf(message)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("支付監控").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回訊息頻道").assertIsDisplayed()
        composeRule.onNodeWithText("部署完成").assertIsDisplayed()
        composeRule.onNodeWithText("訊息頻道").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("返回訊息頻道").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("訊息頻道").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回訊息頻道").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("設定").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("外觀").assertIsDisplayed()
        composeRule.onNodeWithText("訊息頻道").assertDoesNotExist()

        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText("系統設定").performScrollTo()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("系統設定").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回設定").assertIsDisplayed()
        composeRule.onNodeWithText("外觀").assertDoesNotExist()
    }

    private fun event(
        id: Long,
        action: String,
        kind: String,
        state: String,
        title: String,
        tagsJson: String? = null,
        receivedAt: Long = System.currentTimeMillis() + id
    ) = DenDenEvent(
        id = id,
        eventId = "event-$id",
        action = action,
        kind = kind,
        state = state,
        title = title,
        message = "訊息內容",
        tagsJson = tagsJson,
        channelId = "ops",
        channelName = "支付監控",
        receivedAt = receivedAt
    )
}
