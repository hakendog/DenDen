package com.tensal.denden.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.tensal.denden.AppLanguage
import com.tensal.denden.DenDenTheme
import com.tensal.denden.DenDenThemeMode
import com.tensal.denden.MainScreen
import com.tensal.denden.Tab
import com.tensal.denden.alarm.AlarmOutputMode
import com.tensal.denden.alarm.AlarmRuntimeSnapshot
import com.tensal.denden.alarm.AlarmVibrationPattern
import com.tensal.denden.alarm.writeAlarmOutputMode
import com.tensal.denden.alarm.writeAlarmRingtoneUri
import com.tensal.denden.alarm.writeAlarmVibrationPattern
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.readiness.buildDirectReadinessSnapshot
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class MarketingScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun captureTraditionalChineseProductGallery() {
        captureProductGallery("zh-TW", AppLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun captureEnglishProductGallery() {
        captureProductGallery("en", AppLanguage.ENGLISH)
    }

    private fun captureProductGallery(localeTag: String, appLanguage: AppLanguage) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        writeAlarmOutputMode(context, AlarmOutputMode.FOLLOW_SYSTEM)
        writeAlarmRingtoneUri(context, null)
        writeAlarmVibrationPattern(context, AlarmVibrationPattern.STANDARD)
        var shot by mutableStateOf(Shot.WELCOME)

        composeRule.setContent {
            LocalizedTestContent(localeTag) {
                DenDenTheme(DenDenThemeMode.LIGHT) {
                    androidx.compose.runtime.key(shot) { ShotContent(shot, appLanguage) }
                }
            }
        }

        Shot.entries.forEach { current ->
            shot = current
            composeRule.waitForIdle()
            val target = File(context.getExternalFilesDir(null), "marketing/$localeTag/${current.fileName}")
            target.parentFile?.mkdirs()
            FileOutputStream(target).use {
                composeRule.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ShotContent(shot: Shot, appLanguage: AppLanguage) {
        val isEnglish = appLanguage == AppLanguage.ENGLISH
        val now = System.currentTimeMillis()
        val events = sampleEvents(now, isEnglish)
        val readiness = buildDirectReadinessSnapshot(
            paired = true,
            notificationPermission = true,
            alarmChannelEnabled = true,
            lastFcmAtMillis = now - 90_000,
            lastTestAtMillis = now - 300_000,
            lastTestResult = "local_pass",
            lastDegradedReason = null
        )
        when (shot) {
            Shot.WELCOME -> SetupScreen(
                step = SetupStep.WELCOME,
                input = "",
                state = SetupDisplayState(),
                localTestInProgress = false,
                localTestMessage = null,
                isNotificationPermissionGranted = false,
                canUseFullScreenIntent = false,
                isBatteryOptimizationIgnored = false,
                isNotificationPolicyAccessGranted = false,
                onStepChange = {}, onInputChange = {}, onSubmit = {}, onScanQr = {},
                onConfirmPairing = {}, onCancelPairing = {}, onRunLocalTest = {},
                onRequestNotification = {}, onOpenFullScreenIntentSettings = {},
                onOpenBatteryOptimizationSettings = {}, onOpenDndSettings = {}
            )
            Shot.INBOX -> MainScreen(
                themeMode = DenDenThemeMode.LIGHT,
                appLanguage = appLanguage,
                isNotificationPermissionGranted = true,
                canUseFullScreenIntent = true,
                isBatteryOptimizationIgnored = true,
                isNotificationPolicyAccessGranted = true,
                readiness = readiness,
                firebaseProjectId = "denden-example",
                events = events
            )
            Shot.CHANNEL_TIMELINE -> ChannelTimelineScreen(
                channelId = "release",
                channelName = if (isEnglish) "Release work" else "發布工作",
                events = events,
                onBack = {}
            )
            Shot.ALARM -> AlarmScreen(
                alarmActivationCount = 1,
                payload = ActiveAlarmPayload(
                    channelId = "release",
                    channelName = if (isEnglish) "Release work" else "發布工作",
                    title = if (isEnglish) "Your approval is needed" else "需要你的確認",
                    message = if (isEnglish) "All checks passed. Confirm whether to continue." else "所有檢查已通過，請確認是否繼續。",
                    eventId = "alarm-demo",
                    durationSeconds = 45
                ),
                runtimeSnapshot = AlarmRuntimeSnapshot.Active("alarm-demo")
            )
        }
    }

    private fun sampleEvents(now: Long, isEnglish: Boolean): List<DenDenEvent> = if (isEnglish) {
        listOf(
            event(1, "release", "Release work", "Release candidate is ready", "All 148 checks passed.", "notify", "notification", "delivered", now - 120_000, "[\"release\",\"verified\"]"),
            event(2, "release", "Release work", "Security scan completed", "No high-risk issues found.", "quiet", "notification", "delivered", now - 900_000, "[\"security\"]"),
            event(3, "release", "Release work", "Waiting for approval", "Your approval is needed for the next step.", "ring", "alarm", "stopped", now - 3_600_000, "[\"approval\"]"),
            event(4, "research", "Research summary", "Comparison report completed", "The summary with source links is ready.", "notify", "notification", "delivered", now - 240_000, "[\"research\"]")
        )
    } else {
        listOf(
            event(1, "release", "發布工作", "候選版本已就緒", "全部 148 項檢查通過。", "notify", "notification", "delivered", now - 120_000, "[\"發布\",\"已驗證\"]"),
            event(2, "release", "發布工作", "安全掃描完成", "未發現高風險問題。", "quiet", "notification", "delivered", now - 900_000, "[\"安全\"]"),
            event(3, "release", "發布工作", "等待確認", "下一步需要你的同意。", "ring", "alarm", "stopped", now - 3_600_000, "[\"確認\"]"),
            event(4, "research", "研究摘要", "比較報告已完成", "附來源連結的摘要已準備好。", "notify", "notification", "delivered", now - 240_000, "[\"研究\"]")
        )
    }

    private fun event(
        id: Long,
        channelId: String,
        channelName: String,
        title: String,
        message: String,
        mode: String,
        kind: String,
        state: String,
        receivedAt: Long,
        tags: String
    ) = DenDenEvent(
        id = id,
        eventId = "marketing-$id",
        action = mode,
        title = title,
        message = message,
        tagsJson = tags,
        channelId = channelId,
        channelName = channelName,
        notificationMode = mode,
        kind = kind,
        state = state,
        receivedAt = receivedAt,
        issuedAtMillis = receivedAt
    )

    private enum class Shot(val fileName: String) {
        WELCOME("01-welcome.png"),
        INBOX("03-inbox.png"),
        CHANNEL_TIMELINE("04-channel-timeline.png"),
        ALARM("05-alarm.png")
    }
}
