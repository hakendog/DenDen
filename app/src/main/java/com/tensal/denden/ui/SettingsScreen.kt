package com.tensal.denden.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tensal.denden.AppLanguage
import com.tensal.denden.DenDenColors
import com.tensal.denden.DenDenThemeMode
import com.tensal.denden.R
import com.tensal.denden.alarm.AlarmOutputMode
import com.tensal.denden.alarm.AlarmVibrationPattern
import com.tensal.denden.alarm.previewAlarmVibration
import com.tensal.denden.alarm.readAlarmOutputMode
import com.tensal.denden.alarm.readAlarmRingtoneUri
import com.tensal.denden.alarm.readAlarmVibrationPattern
import com.tensal.denden.alarm.writeAlarmOutputMode
import com.tensal.denden.alarm.writeAlarmRingtoneUri
import com.tensal.denden.alarm.writeAlarmVibrationPattern
import com.tensal.denden.branding.DirectBrandStatus
import com.tensal.denden.notification.NotificationDisplayMode
import com.tensal.denden.notification.readNotificationDisplayMode
import com.tensal.denden.notification.writeNotificationDisplayMode
import com.tensal.denden.readiness.ReadinessSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    themeMode: DenDenThemeMode,
    readiness: ReadinessSnapshot,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    testInProgress: Boolean = false,
    testMessage: String? = null,
    onThemeModeChange: (DenDenThemeMode) -> Unit = {},
    onAppLanguageChange: (AppLanguage) -> Unit = {},
    onOpenNotificationDisplaySettings: () -> Unit = {},
    onOpenAlarmOutputSettings: () -> Unit = {},
    onOpenDeviceManagement: () -> Unit = {},
    onOpenSystemPermissions: () -> Unit = {},
    onOpenSystemSettings: () -> Unit = {},
    onRunLocalTest: () -> Unit = {}
) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(DenDenColors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionLabel(stringResource(R.string.appearance))
        SurfaceCard {
            Text(stringResource(R.string.theme_mode), fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DenDenThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(themeModeLabel(mode)) }
                    )
                }
            }
        }
        SurfaceCard(contentPadding = PaddingValues(0.dp)) {
            NavigationRow(
                stringResource(R.string.language),
                stringResource(appLanguage.labelRes),
                onClick = { showLanguageDialog = true }
            )
        }
        SectionLabel(stringResource(R.string.notification_permission))
        SurfaceCard(contentPadding = PaddingValues(0.dp)) {
            NavigationRow(
                stringResource(R.string.notification_display),
                notificationDisplayModeLabel(readNotificationDisplayMode(context)),
                onOpenNotificationDisplaySettings
            )
        }
        SectionLabel(stringResource(R.string.alarm))
        SurfaceCard(contentPadding = PaddingValues(0.dp)) {
            NavigationRow(
                stringResource(R.string.alarm_output),
                alarmOutputModeLabel(readAlarmOutputMode(context)),
                onOpenAlarmOutputSettings
            )
        }
        SectionLabel(stringResource(R.string.management))
        SurfaceCard(contentPadding = PaddingValues(0.dp)) {
            NavigationRow(
                stringResource(R.string.system_permissions),
                stringResource(R.string.system_permissions_summary),
                onOpenSystemPermissions
            )
            HorizontalDivider(color = DenDenColors.outlineVariant)
            NavigationRow(
                stringResource(R.string.system_settings),
                stringResource(R.string.system_settings_summary),
                onOpenSystemSettings
            )
        }
    }
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.language_description),
                        color = DenDenColors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    AppLanguage.entries.forEach { language ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                showLanguageDialog = false
                                onAppLanguageChange(language)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = appLanguage == language,
                                onClick = {
                                    showLanguageDialog = false
                                    onAppLanguageChange(language)
                                }
                            )
                            Text(stringResource(language.labelRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }
}

@Composable
fun NotificationDisplaySettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(readNotificationDisplayMode(context)) }
    SettingsSubpage(
        stringResource(R.string.notification_display),
        stringResource(R.string.notification_display_description),
        onBack
    ) {
        SurfaceCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            NotificationDisplayMode.entries.forEach { option ->
                NotificationDisplayModeRow(
                    mode = option,
                    selected = mode == option,
                    onClick = {
                        mode = option
                        writeNotificationDisplayMode(context, option)
                    }
                )
            }
        }
    }
}

@Composable
fun AlarmOutputSettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var alarmOutputMode by remember { mutableStateOf(readAlarmOutputMode(context)) }
    var alarmRingtoneUri by remember { mutableStateOf(readAlarmRingtoneUri(context)) }
    var alarmVibrationPattern by remember { mutableStateOf(readAlarmVibrationPattern(context)) }
    var showVibrationDialog by remember { mutableStateOf(false) }
    val defaultRingtoneTitle = stringResource(R.string.phone_default_ringtone)
    val ringtoneTitle = remember(alarmRingtoneUri, defaultRingtoneTitle) {
        val uri = alarmRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: defaultRingtoneTitle
    }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            writeAlarmRingtoneUri(context, picked)
            alarmRingtoneUri = readAlarmRingtoneUri(context)
        }
    }

    SettingsSubpage(
        stringResource(R.string.alarm_output),
        stringResource(R.string.alarm_output_description),
        onBack
    ) {
        SurfaceCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            AlarmOutputMode.entries.forEach { mode ->
                AlarmOutputModeRow(
                    mode = mode,
                    selected = alarmOutputMode == mode,
                    onClick = {
                        alarmOutputMode = mode
                        writeAlarmOutputMode(context, mode)
                    }
                )
            }
            HorizontalDivider(color = DenDenColors.outlineVariant)
            AlarmSettingRow(
                title = stringResource(R.string.ringtone),
                value = ringtoneTitle,
                description = stringResource(R.string.ringtone_description),
                onClick = {
                    ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            alarmRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        )
                    })
                }
            )
            HorizontalDivider(color = DenDenColors.outlineVariant)
            AlarmSettingRow(
                title = stringResource(R.string.vibration_pattern),
                value = vibrationPatternLabel(alarmVibrationPattern),
                description = vibrationPatternDescription(alarmVibrationPattern),
                onClick = { showVibrationDialog = true }
            )
            Text(
                stringResource(R.string.alarm_volume_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = DenDenColors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (showVibrationDialog) {
        AlertDialog(
            onDismissRequest = { showVibrationDialog = false },
            title = { Text(stringResource(R.string.vibration_pattern)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AlarmVibrationPattern.entries.forEach { pattern ->
                        AlarmVibrationPatternRow(
                            pattern = pattern,
                            selected = alarmVibrationPattern == pattern,
                            onClick = {
                                alarmVibrationPattern = pattern
                                writeAlarmVibrationPattern(context, pattern)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVibrationDialog = false }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = { previewAlarmVibration(context, alarmVibrationPattern) }) {
                    Text(stringResource(R.string.preview))
                }
            }
        )
    }
}

@Composable
private fun NotificationDisplayModeRow(
    mode: NotificationDisplayMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(notificationDisplayModeLabel(mode), fontWeight = FontWeight.Medium)
            Text(
                notificationDisplayModeDescription(mode),
                color = DenDenColors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AlarmOutputModeRow(mode: AlarmOutputMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(alarmOutputModeLabel(mode), fontWeight = FontWeight.Medium)
            Text(
                alarmOutputModeDescription(mode),
                color = DenDenColors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AlarmSettingRow(title: String, value: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(value, color = DenDenColors.primary, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = DenDenColors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.open_setting_for, title))
    }
}

@Composable
private fun AlarmVibrationPatternRow(
    pattern: AlarmVibrationPattern,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(vibrationPatternLabel(pattern), fontWeight = FontWeight.Medium)
            Text(
                vibrationPatternDescription(pattern),
                color = DenDenColors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun SystemPermissionsScreen(
    isNotificationPermissionGranted: Boolean,
    canUseFullScreenIntent: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isNotificationPolicyAccessGranted: Boolean,
    onRequestNotification: () -> Unit = {},
    onOpenFullScreenIntentSettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onOpenDndSettings: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    SettingsSubpage(
        stringResource(R.string.system_permissions),
        stringResource(R.string.lock_screen_explanation),
        onBack
    ) {
        SectionLabel(stringResource(R.string.required))
        SurfaceCard(contentPadding = PaddingValues(0.dp)) {
            PermissionRow(
                stringResource(R.string.notification_permission),
                stringResource(R.string.required),
                isNotificationPermissionGranted,
                onRequestNotification
            )
            HorizontalDivider(color = DenDenColors.outlineVariant)
            PermissionRow(
                stringResource(R.string.lock_screen_alarm),
                stringResource(R.string.lock_screen_alarm_requirement),
                canUseFullScreenIntent,
                onOpenFullScreenIntentSettings
            )
        }
        SectionLabel(stringResource(R.string.recommended))
        SurfaceCard(contentPadding = PaddingValues(0.dp)) {
            PermissionRow(
                stringResource(R.string.ignore_battery_optimization),
                stringResource(R.string.recommended),
                isBatteryOptimizationIgnored,
                onOpenBatteryOptimizationSettings
            )
            HorizontalDivider(color = DenDenColors.outlineVariant)
            PermissionRow(
                stringResource(R.string.do_not_disturb),
                stringResource(R.string.recommended),
                isNotificationPolicyAccessGranted,
                onOpenDndSettings
            )
        }
    }
}

@Composable
fun SystemSettingsScreen(
    readiness: ReadinessSnapshot,
    firebaseProjectId: String? = null,
    brandingStatus: DirectBrandStatus? = null,
    testInProgress: Boolean = false,
    testMessage: String? = null,
    onRunLocalTest: () -> Unit = {},
    onRestoreBuiltInAppearance: () -> Unit = {},
    onClearPairing: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsSubpage(
        stringResource(R.string.direct_fcm_status_title),
        stringResource(R.string.direct_fcm_status_subtitle),
        onBack
    ) {
        SectionLabel(stringResource(R.string.fcm_pairing))
        SurfaceCard {
            Text(stringResource(R.string.firebase_project), fontWeight = FontWeight.Medium)
            Text(firebaseProjectId ?: stringResource(R.string.not_paired), color = DenDenColors.onSurfaceVariant)
            Text(stringResource(R.string.fcm_token_privacy), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (firebaseProjectId == null) onClearPairing() else confirmClear = true
            }) {
                Text(stringResource(if (firebaseProjectId == null) R.string.pair_now else R.string.pair_again))
            }
        }
        SectionLabel(stringResource(R.string.denden_appearance))
        SurfaceCard {
            Text(
                stringResource(
                    if (brandingStatus?.isCustom == true) R.string.custom_denden_active
                    else R.string.builtin_denden_active
                ),
                fontWeight = FontWeight.Medium
            )
            brandingStatus?.let { status ->
                status.receivingTransferFingerprint?.let { transfer ->
                    Text(
                        pluralStringResource(
                            R.plurals.brand_receiving,
                            status.receivedChunks,
                            status.receivedChunks,
                            status.totalChunks,
                            transfer
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (status.shortcutUpdatePending) Text(
                    stringResource(R.string.shortcut_retry_pending),
                    color = DenDenColors.onSurfaceVariant
                )
                status.lastError?.let {
                    Text(stringResource(R.string.brand_error_generic), color = MaterialTheme.colorScheme.error)
                }
            }
            if (brandingStatus?.isCustom == true) OutlinedButton(onClick = onRestoreBuiltInAppearance) {
                Text(stringResource(R.string.use_builtin_denden))
            }
            Text(stringResource(R.string.brand_pairing_note), style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel(stringResource(R.string.health_status))
        ReadinessCard(readiness)
        SectionLabel(stringResource(R.string.alarm_test))
        SurfaceCard {
            Button(
                onClick = onRunLocalTest,
                enabled = !testInProgress && readiness.requiredReady("notification_permission", "alarm_channel")
            ) { Text(stringResource(R.string.local_alarm_test)) }
            if (testInProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
            testMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.pair_again_title)) },
        text = { Text(stringResource(R.string.pair_again_message)) },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; onClearPairing() }) {
                Text(stringResource(R.string.continue_action))
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SettingsSubpage(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(DenDenColors.background).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_settings))
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(subtitle, color = DenDenColors.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun SurfaceCard(contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DenDenColors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, DenDenColors.outlineVariant)
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = DenDenColors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.enter_section, title))
    }
}

@Composable
private fun PermissionRow(title: String, requirement: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                "$requirement · ${stringResource(if (granted) R.string.completed else R.string.not_completed)}",
                color = if (granted) DenDenColors.success else DenDenColors.onSurfaceVariant
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.open_setting_for, title))
    }
}

@Composable
private fun ReadinessCard(readiness: ReadinessSnapshot) = SurfaceCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.overall_status), fontWeight = FontWeight.Medium)
        Text(
            stringResource(
                when {
                    readiness.isLoading -> R.string.checking
                    readiness.isReady -> R.string.ready
                    else -> R.string.needs_attention
                }
            ),
            color = if (readiness.isReady) DenDenColors.success else DenDenColors.onSurfaceVariant
        )
    }
    readiness.evidence.forEach { evidence ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(readinessEvidenceLabel(evidence.key, evidence.label))
            Text(readinessEvidenceDetail(evidence), color = when (evidence.satisfied) {
                true -> DenDenColors.success
                false -> DenDenColors.error
                null -> DenDenColors.onSurfaceVariant
            })
        }
    }
    readiness.lastDegradedReason?.let {
        Text(stringResource(R.string.recent_degradation, it), color = DenDenColors.error)
    }
}

@Composable
private fun themeModeLabel(mode: DenDenThemeMode): String = stringResource(
    when (mode) {
        DenDenThemeMode.SYSTEM -> R.string.theme_system
        DenDenThemeMode.LIGHT -> R.string.theme_light
        DenDenThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
private fun notificationDisplayModeLabel(mode: NotificationDisplayMode): String = stringResource(
    when (mode) {
        NotificationDisplayMode.FULL -> R.string.notification_display_full
        NotificationDisplayMode.STANDARD -> R.string.notification_display_standard
        NotificationDisplayMode.COMPACT -> R.string.notification_display_compact
    }
)

@Composable
private fun notificationDisplayModeDescription(mode: NotificationDisplayMode): String = stringResource(
    when (mode) {
        NotificationDisplayMode.FULL -> R.string.notification_display_full_description
        NotificationDisplayMode.STANDARD -> R.string.notification_display_standard_description
        NotificationDisplayMode.COMPACT -> R.string.notification_display_compact_description
    }
)

@Composable
private fun alarmOutputModeLabel(mode: AlarmOutputMode): String = stringResource(
    when (mode) {
        AlarmOutputMode.FOLLOW_SYSTEM -> R.string.alarm_mode_follow_system
        AlarmOutputMode.RING_AND_VIBRATE -> R.string.alarm_mode_ring_and_vibrate
        AlarmOutputMode.RING_ONLY -> R.string.alarm_mode_ring_only
        AlarmOutputMode.VIBRATE_ONLY -> R.string.alarm_mode_vibrate_only
        AlarmOutputMode.SILENT -> R.string.alarm_mode_silent
    }
)

@Composable
private fun alarmOutputModeDescription(mode: AlarmOutputMode): String = stringResource(
    when (mode) {
        AlarmOutputMode.FOLLOW_SYSTEM -> R.string.alarm_mode_follow_system_description
        AlarmOutputMode.RING_AND_VIBRATE -> R.string.alarm_mode_ring_and_vibrate_description
        AlarmOutputMode.RING_ONLY -> R.string.alarm_mode_ring_only_description
        AlarmOutputMode.VIBRATE_ONLY -> R.string.alarm_mode_vibrate_only_description
        AlarmOutputMode.SILENT -> R.string.alarm_mode_silent_description
    }
)

@Composable
private fun vibrationPatternLabel(pattern: AlarmVibrationPattern): String = stringResource(
    when (pattern) {
        AlarmVibrationPattern.GENTLE -> R.string.vibration_gentle
        AlarmVibrationPattern.STANDARD -> R.string.vibration_standard
        AlarmVibrationPattern.URGENT -> R.string.vibration_urgent
        AlarmVibrationPattern.LONG -> R.string.vibration_long
    }
)

@Composable
private fun vibrationPatternDescription(pattern: AlarmVibrationPattern): String = stringResource(
    when (pattern) {
        AlarmVibrationPattern.GENTLE -> R.string.vibration_gentle_description
        AlarmVibrationPattern.STANDARD -> R.string.vibration_standard_description
        AlarmVibrationPattern.URGENT -> R.string.vibration_urgent_description
        AlarmVibrationPattern.LONG -> R.string.vibration_long_description
    }
)

@Composable
private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = DenDenColors.primary)

private fun ReadinessSnapshot.requiredReady(vararg keys: String): Boolean =
    !isLoading && keys.all { key -> evidence.firstOrNull { it.key == key }?.satisfied == true }

@Composable
private fun readinessEvidenceLabel(key: String, fallback: String): String = when (key) {
    "fcm_pairing" -> stringResource(R.string.readiness_fcm_pairing)
    "notification_permission" -> stringResource(R.string.readiness_notification_permission)
    "alarm_channel" -> stringResource(R.string.readiness_alarm_channel)
    "last_fcm" -> stringResource(R.string.readiness_last_fcm)
    "last_test" -> stringResource(R.string.readiness_last_test)
    else -> fallback
}

@Composable
private fun readinessEvidenceDetail(evidence: com.tensal.denden.readiness.ReadinessEvidence): String {
    if (evidence.key !in setOf("last_fcm", "last_test")) return when {
        evidence.satisfied == null -> stringResource(R.string.checking)
        evidence.key == "fcm_pairing" && evidence.satisfied -> stringResource(R.string.subscribed)
        evidence.key == "fcm_pairing" -> stringResource(R.string.not_paired)
        evidence.key == "notification_permission" && evidence.satisfied -> stringResource(R.string.authorized)
        evidence.key == "notification_permission" -> stringResource(R.string.not_authorized)
        evidence.key == "alarm_channel" && evidence.satisfied -> stringResource(R.string.available)
        evidence.key == "alarm_channel" -> stringResource(R.string.disabled_or_missing)
        else -> evidence.detail
    }
    val millis = evidence.detail.substringAfterLast(" · ", evidence.detail).toLongOrNull()
        ?: return stringResource(R.string.no_records)
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}
