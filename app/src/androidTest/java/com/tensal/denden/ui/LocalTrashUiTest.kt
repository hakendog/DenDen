package com.tensal.denden.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.TrashedChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class LocalTrashUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accessibilityTrashActionShowsUndoSnackbar() {
        var restored = false
        composeRule.setContent {
            LocalizedTestTheme {
                com.tensal.denden.MainScreen(
                    themeMode = com.tensal.denden.DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    events = listOf(event()),
                    onChannelTrashed = { _, result -> result(true) },
                    onChannelRestored = { _, result -> restored = true; result(true) }
                )
            }
        }

        val action = composeRule.onNodeWithText("Ops").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "移至垃圾桶" }
        composeRule.runOnIdle { action.action() }
        composeRule.onNodeWithText("Ops 已移至垃圾桶").assertIsDisplayed()
        composeRule.onNodeWithText("復原").performClick()
        composeRule.runOnIdle { assertTrue(restored) }
    }

    @Test
    fun accessibilityArchiveActionShowsNamedUndoSnackbar() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            LocalizedTestTheme {
                com.tensal.denden.MainScreen(
                    themeMode = com.tensal.denden.DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    events = listOf(event()),
                    onChannelArchivedChange = { channelId, archived -> changes += channelId to archived }
                )
            }
        }

        val action = composeRule.onNodeWithText("Ops").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "封存" }
        composeRule.runOnIdle { action.action() }
        composeRule.onNodeWithText("Ops 已封存").assertIsDisplayed()
        composeRule.onNodeWithText("復原").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("ops" to true, "ops" to false), changes)
        }
    }

    @Test
    fun archiveSnackbarTimeoutKeepsChannelArchived() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LocalizedTestTheme {
                com.tensal.denden.MainScreen(
                    themeMode = com.tensal.denden.DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    events = listOf(event()),
                    onChannelArchivedChange = { channelId, archived -> changes += channelId to archived }
                )
            }
        }

        val action = composeRule.onNodeWithText("Ops").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "封存" }
        composeRule.runOnIdle { action.action() }
        composeRule.mainClock.advanceTimeBy(30_000)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf("ops" to true), changes) }
    }

    @Test
    fun consecutiveArchiveUndoTargetsEachSnackbarChannel() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            LocalizedTestTheme {
                com.tensal.denden.MainScreen(
                    themeMode = com.tensal.denden.DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    events = listOf(event(), event(2, "dev", "Dev")),
                    onChannelArchivedChange = { channelId, archived -> changes += channelId to archived }
                )
            }
        }

        val opsAction = composeRule.onNodeWithText("Ops").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == "封存" }
        val devAction = composeRule.onNodeWithText("Dev").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == "封存" }
        composeRule.runOnIdle {
            opsAction.action()
            devAction.action()
        }
        composeRule.onNodeWithText("Ops 已封存").assertIsDisplayed()
        composeRule.onNodeWithText("復原").performClick()
        composeRule.onNodeWithText("Dev 已封存").assertIsDisplayed()
        composeRule.onNodeWithText("復原").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf("ops" to true, "dev" to true, "ops" to false, "dev" to false),
                changes
            )
        }
    }

    @Test
    fun trashScreenShowsEmptyState() {
        composeRule.setContent {
            LocalizedTestTheme {
                TrashScreen(
                    channels = emptyList(),
                    events = emptyList(),
                    onBack = {},
                    onChannelSelected = {},
                    onRestore = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("垃圾桶共有 0 個頻道").assertIsDisplayed()
        composeRule.onNodeWithText("已刪除的頻道暫存在這裡", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("垃圾桶是空的").assertIsDisplayed()
        composeRule.onNodeWithText("僅影響本機").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun trashListShowsDaysRestoresAndOpensTimeline() {
        val now = System.currentTimeMillis()
        var restoredChannelId: String? = null
        val events = listOf(
            event(),
            event().copy(id = 2, eventId = "event-2")
        )
        composeRule.setContent {
            LocalizedTestTheme {
                com.tensal.denden.MainScreen(
                    themeMode = com.tensal.denden.DenDenThemeMode.SYSTEM,
                    isNotificationPermissionGranted = true,
                    canUseFullScreenIntent = true,
                    isBatteryOptimizationIgnored = true,
                    isNotificationPolicyAccessGranted = true,
                    events = events,
                    trashedChannels = listOf(
                        TrashedChannel(
                            channelId = "ops",
                            trashedAtMillis = now,
                            purgeAtMillis = now + TimeUnit.DAYS.toMillis(2)
                        )
                    ),
                    onChannelRestored = { channelId, result ->
                        restoredChannelId = channelId
                        result(true)
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription("垃圾桶共有 1 個頻道").performClick()
        composeRule.onNodeWithContentDescription("垃圾桶共有 1 個頻道").assertIsDisplayed()
        composeRule.onNodeWithText("2 則事件 · 剩餘 2 天").assertIsDisplayed()
        composeRule.onNodeWithText("復原").performClick()
        composeRule.runOnIdle { assertEquals("ops", restoredChannelId) }
        composeRule.onNodeWithText("Ops").performClick()
        composeRule.onNodeWithText("從此裝置永久刪除").assertIsDisplayed()
    }

    @Test
    fun trashTimelineRequiresPermanentDeleteConfirmation() {
        var deletes = 0
        composeRule.setContent {
            LocalizedTestTheme {
                ChannelTimelineScreen(
                    channelId = "ops",
                    events = listOf(event()),
                    onBack = {},
                    isInTrash = true,
                    onPermanentDelete = { deletes++ }
                )
            }
        }

        composeRule.onNodeWithText("從此裝置永久刪除").performClick()
        composeRule.onNodeWithText("其他裝置的本機歷史不受影響。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("永久刪除").performClick()
        composeRule.runOnIdle { assertEquals(1, deletes) }
    }

    private fun event(id: Long = 1, channelId: String = "ops", channelName: String = "Ops") = DenDenEvent(
        id = id,
        eventId = "event-$id",
        action = "notify",
        channelId = channelId,
        channelName = channelName,
        title = "事件",
        receivedAt = 1
    )
}
