package com.tensal.denden

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tensal.denden.alarm.AlarmController
import com.tensal.denden.alarm.AlarmRuntime
import com.tensal.denden.alarm.AlarmService
import com.tensal.denden.alarm.isTerminalFor
import com.tensal.denden.branding.CachedBranding
import com.tensal.denden.branding.DirectBrandCandidate
import com.tensal.denden.branding.DirectBrandStore
import com.tensal.denden.branding.DirectBrandStatus
import com.tensal.denden.branding.retryPendingShortcutUpdate
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.DirectEventCommit
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.data.EventRepository
import com.tensal.denden.data.ChannelReadStateStore
import com.tensal.denden.data.ChannelInboxRecord
import com.tensal.denden.data.TrashedChannel
import com.tensal.denden.data.cleanupExpiredLocalTrash
import com.tensal.denden.data.finishPendingTrashCleanup
import com.tensal.denden.messaging.SharedPrefsMessageHealthStore
import com.tensal.denden.messaging.reconcileDirectMessages
import com.tensal.denden.notification.NotificationChannels
import com.tensal.denden.readiness.ReadinessSnapshot
import com.tensal.denden.readiness.TestExecutionStore
import com.tensal.denden.readiness.buildDirectReadinessSnapshot
import com.tensal.denden.readiness.awaitLocalTestConfirmation
import com.tensal.denden.setup.DirectPairingStore
import com.tensal.denden.setup.PairingState
import com.tensal.denden.setup.directRuntimeMutex
import com.tensal.denden.setup.initializeDirectFirebaseRuntime
import com.tensal.denden.setup.resumeDirectPairing
import com.tensal.denden.setup.scheduleDirectPairing
import com.tensal.denden.protocol.DirectFcmInvite
import com.tensal.denden.protocol.parseDirectFcmInvite
import com.tensal.denden.ui.ActiveAlarmPayload
import com.tensal.denden.ui.AlarmScreen
import com.tensal.denden.ui.AlarmOutputSettingsScreen
import com.tensal.denden.ui.ArchivedChannelsScreen
import com.tensal.denden.ui.ChannelListScreen
import com.tensal.denden.ui.ChannelInboxItem
import com.tensal.denden.ui.ChannelTimelineScreen
import com.tensal.denden.ui.TIMELINE_PAGE_SIZE
import com.tensal.denden.ui.BrandCandidateDialog
import com.tensal.denden.ui.SettingsScreen
import com.tensal.denden.ui.SystemSettingsScreen
import com.tensal.denden.ui.SystemPermissionsScreen
import com.tensal.denden.ui.TrashScreen
import com.tensal.denden.ui.channelDisplayName
import com.tensal.denden.ui.toChannelInboxItems
import com.tensal.denden.ui.SetupDisplayState
import com.tensal.denden.ui.SetupScreen
import com.tensal.denden.ui.SetupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class SettingsPage { HOME, ALARM_OUTPUT, PERMISSIONS, SYSTEM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    themeMode: DenDenThemeMode,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    isNotificationPermissionGranted: Boolean,
    canUseFullScreenIntent: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isNotificationPolicyAccessGranted: Boolean,
    readiness: ReadinessSnapshot = ReadinessSnapshot.Loading,
    firebaseProjectId: String? = null,
    brandingStatus: DirectBrandStatus? = null,
    mascot: Bitmap? = null,
    mascotBackgroundColor: Color? = null,
    alarmMascot: Bitmap? = mascot,
    alarmMascotBackgroundColor: Color? = mascotBackgroundColor,
    testInProgress: Boolean = false,
    testMessage: String? = null,
    events: List<DenDenEvent> = emptyList(),
    channelItems: List<ChannelInboxItem>? = null,
    trashChannelItems: List<ChannelInboxItem>? = null,
    lastReadAtByChannel: Map<String, Long> = emptyMap(),
    archivedChannelIds: Set<String> = emptySet(),
    trashedChannels: List<TrashedChannel> = emptyList(),
    initialTab: Tab = Tab.CHANNELS,
    initialChannelId: String? = null,
    routeChangeCount: Int = 0,
    alarmActivationCount: Int = 0,
    activeAlarmPayload: ActiveAlarmPayload? = null,
    onChannelOpened: (String) -> Unit = {},
    onChannelArchivedChange: (String, Boolean) -> Unit = { _, _ -> },
    onChannelTrashed: (String, (Boolean) -> Unit) -> Unit = { _, result -> result(false) },
    onChannelRestored: (String, (Boolean) -> Unit) -> Unit = { _, result -> result(false) },
    onChannelPermanentlyDeleted: (String, (Boolean) -> Unit) -> Unit = { _, result -> result(false) },
    onAlarmRouteEnded: () -> Unit = {},
    onThemeModeChange: (DenDenThemeMode) -> Unit = {},
    onAppLanguageChange: (AppLanguage) -> Unit = {},
    onRequestNotification: () -> Unit = {},
    onOpenFullScreenIntentSettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onOpenDndSettings: () -> Unit = {},
    onRunLocalTest: () -> Unit = {},
    onRefreshReadiness: () -> Unit = {},
    onRestoreBuiltInAppearance: () -> Unit = {},
    onClearPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val alarmRuntimeSnapshot by AlarmRuntime.snapshot.collectAsState()
    val isCurrentAlarmTerminal = alarmRuntimeSnapshot.isTerminalFor(activeAlarmPayload?.eventId)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedChannelId by remember(initialChannelId) { mutableStateOf(initialChannelId) }
    var showArchive by remember { mutableStateOf(false) }
    var selectedArchivedChannelId by remember { mutableStateOf<String?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var selectedTrashChannelId by remember { mutableStateOf<String?>(null) }
    var settingsPage by remember { mutableStateOf(SettingsPage.HOME) }
    var initialTimelinePage by remember { mutableStateOf<Pair<String, List<DenDenEvent>>?>(null) }
    var timelineOpenJob by remember { mutableStateOf<Job?>(null) }
    val trashedChannelIds = trashedChannels.mapTo(mutableSetOf()) { it.channelId }
    val fallbackChannelItems = remember(events, lastReadAtByChannel) {
        events.toChannelInboxItems(lastReadAtByChannel)
    }
    val visibleChannelItems = channelItems ?: fallbackChannelItems.filterNot { it.channelId in trashedChannelIds }
    val visibleTrashItems = trashChannelItems ?: fallbackChannelItems.filter { it.channelId in trashedChannelIds }
    val selectedChannelLatestReceivedAt = selectedChannelId?.let { channelId ->
        visibleChannelItems.firstOrNull { it.channelId == channelId }?.latestEvent?.receivedAt
    }
    val openTimeline: (String, (String) -> Unit) -> Unit = { channelId, onReady ->
        if (channelItems == null) {
            onReady(channelId)
        } else {
            timelineOpenJob?.cancel()
            initialTimelinePage = null
            timelineOpenJob = scope.launch {
                val rows = EventDatabase.getInstance(context).messageQueryDao().getTimelinePage(
                    channelId = channelId,
                    query = "",
                    filter = "all",
                    tag = null,
                    beforeReceivedAt = null,
                    beforeId = null,
                    limit = TIMELINE_PAGE_SIZE + 1
                )
                initialTimelinePage = channelId to rows
                onReady(channelId)
            }
        }
    }

    LaunchedEffect(initialTab, initialChannelId, routeChangeCount, alarmActivationCount) {
        selectedTab = initialTab
        if (initialTab == Tab.CHANNELS) {
            selectedChannelId = initialChannelId
            showArchive = false
            selectedArchivedChannelId = null
            showTrash = false
            selectedTrashChannelId = null
            settingsPage = SettingsPage.HOME
        }
    }

    LaunchedEffect(selectedTrashChannelId, trashedChannelIds) {
        if (selectedTrashChannelId != null && selectedTrashChannelId !in trashedChannelIds) {
            selectedTrashChannelId = null
        }
    }

    LaunchedEffect(selectedChannelId, selectedChannelLatestReceivedAt) {
        val channelId = selectedChannelId ?: return@LaunchedEffect
        if (selectedChannelLatestReceivedAt != null) onChannelOpened(channelId)
    }

    BackHandler(
        enabled = selectedTab == Tab.CHANNELS &&
            (selectedChannelId != null || selectedArchivedChannelId != null || showArchive || showTrash)
    ) {
        when {
            selectedChannelId != null -> selectedChannelId = null
            selectedArchivedChannelId != null -> selectedArchivedChannelId = null
            selectedTrashChannelId != null -> selectedTrashChannelId = null
            showArchive -> showArchive = false
            else -> showTrash = false
        }
    }

    BackHandler(enabled = selectedTab == Tab.SETTINGS && settingsPage != SettingsPage.HOME) {
        settingsPage = SettingsPage.HOME
    }

    BackHandler(enabled = selectedTab == Tab.SETTINGS && settingsPage == SettingsPage.HOME) {
        selectedTab = Tab.CHANNELS
        selectedChannelId = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val showCommonTopBar = when (selectedTab) {
                Tab.CHANNELS -> selectedChannelId == null && !showArchive && !showTrash
                Tab.SETTINGS -> settingsPage == SettingsPage.HOME
                Tab.ALARM -> true
            }
            if (showCommonTopBar) {
                DenDenTopAppBar(
                    selectedTab = selectedTab,
                    mascot = mascot,
                    mascotBackgroundColor = mascotBackgroundColor,
                    trashCount = trashedChannels.size,
                    isAlarmTerminal = isCurrentAlarmTerminal,
                    onBackClick = {
                        if (settingsPage == SettingsPage.HOME) selectedTab = Tab.CHANNELS
                        else settingsPage = SettingsPage.HOME
                    },
                    onTrashClick = {
                        showArchive = false
                        selectedArchivedChannelId = null
                        showTrash = true
                        selectedChannelId = null
                    },
                    onArchiveClick = {
                        showArchive = true
                        selectedArchivedChannelId = null
                        showTrash = false
                        selectedTrashChannelId = null
                        selectedChannelId = null
                    },
                    onSettingsClick = {
                        selectedTab = Tab.SETTINGS
                        selectedChannelId = null
                        showArchive = false
                        selectedArchivedChannelId = null
                        showTrash = false
                        selectedTrashChannelId = null
                        settingsPage = SettingsPage.HOME
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DenDenColors.background)
                .padding(padding)
        ) {
            when (selectedTab) {
                Tab.SETTINGS -> when (settingsPage) {
                    SettingsPage.ALARM_OUTPUT -> AlarmOutputSettingsScreen(
                        onBack = { settingsPage = SettingsPage.HOME }
                    )
                    SettingsPage.PERMISSIONS -> SystemPermissionsScreen(
                        isNotificationPermissionGranted = isNotificationPermissionGranted,
                        canUseFullScreenIntent = canUseFullScreenIntent,
                        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                        isNotificationPolicyAccessGranted = isNotificationPolicyAccessGranted,
                        onRequestNotification = onRequestNotification,
                        onOpenFullScreenIntentSettings = onOpenFullScreenIntentSettings,
                        onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                        onOpenDndSettings = onOpenDndSettings,
                        onBack = { settingsPage = SettingsPage.HOME }
                    )
                    SettingsPage.SYSTEM -> SystemSettingsScreen(
                        readiness = readiness,
                        firebaseProjectId = firebaseProjectId,
                        brandingStatus = brandingStatus,
                        testInProgress = testInProgress,
                        testMessage = testMessage,
                        onRunLocalTest = onRunLocalTest,
                        onRestoreBuiltInAppearance = onRestoreBuiltInAppearance,
                        onClearPairing = onClearPairing,
                        onBack = { settingsPage = SettingsPage.HOME }
                    )
                    SettingsPage.HOME -> SettingsScreen(
                        themeMode = themeMode,
                        appLanguage = appLanguage,
                        readiness = readiness,
                        testInProgress = testInProgress,
                        testMessage = testMessage,
                        onThemeModeChange = onThemeModeChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onOpenAlarmOutputSettings = { settingsPage = SettingsPage.ALARM_OUTPUT },
                        onOpenDeviceManagement = {},
                        onOpenSystemPermissions = { settingsPage = SettingsPage.PERMISSIONS },
                        onOpenSystemSettings = {
                            onRefreshReadiness()
                            settingsPage = SettingsPage.SYSTEM
                        },
                        onRunLocalTest = onRunLocalTest
                    )
                }
                Tab.CHANNELS -> {
                    if (showArchive && selectedArchivedChannelId == null) {
                        ArchivedChannelsScreen(
                            events = events,
                            channelItems = channelItems,
                            lastReadAtByChannel = lastReadAtByChannel,
                            archivedChannelIds = archivedChannelIds,
                            trashedChannelIds = trashedChannelIds,
                            onBack = { showArchive = false },
                            onChannelSelected = {
                                openTimeline(it) { channelId ->
                                    selectedArchivedChannelId = channelId
                                    onChannelOpened(channelId)
                                }
                            },
                            onChannelUnarchived = { onChannelArchivedChange(it, false) }
                        )
                    } else if (showArchive) {
                        val channelId = selectedArchivedChannelId!!
                        ChannelTimelineScreen(
                            channelId = channelId,
                            events = events.takeIf { channelItems == null },
                            initialNewestEvents = initialTimelinePage
                                ?.takeIf { it.first == channelId }?.second,
                            channelName = visibleChannelItems.firstOrNull { it.channelId == channelId }?.displayName,
                            onBack = { selectedArchivedChannelId = null }
                        )
                    } else if (showTrash && selectedTrashChannelId == null) {
                        TrashScreen(
                            channels = trashedChannels,
                            events = events,
                            channelItems = visibleTrashItems,
                            onBack = { showTrash = false },
                            onChannelSelected = {
                                openTimeline(it) { channelId -> selectedTrashChannelId = channelId }
                            },
                            onRestore = { channelId ->
                                onChannelRestored(channelId) { restored ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(
                                                if (restored) R.string.channel_restored else R.string.restore_failed
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    } else if (showTrash) {
                        val channelId = selectedTrashChannelId!!
                        ChannelTimelineScreen(
                            channelId = channelId,
                            events = events.takeIf { channelItems == null },
                            initialNewestEvents = initialTimelinePage
                                ?.takeIf { it.first == channelId }?.second,
                            channelName = visibleTrashItems.firstOrNull { it.channelId == channelId }?.displayName,
                            onBack = { selectedTrashChannelId = null },
                            isInTrash = true,
                            onRestore = {
                                onChannelRestored(channelId) { restored ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(
                                                if (restored) R.string.channel_restored else R.string.restore_failed
                                            )
                                        )
                                    }
                                }
                            },
                            onPermanentDelete = {
                                onChannelPermanentlyDeleted(channelId) { deleted ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(
                                                if (deleted) R.string.permanent_delete_success
                                                else R.string.permanent_delete_failed
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    } else if (selectedChannelId == null) {
                        ChannelListScreen(
                            events = events,
                            channelItems = channelItems,
                            lastReadAtByChannel = lastReadAtByChannel,
                            archivedChannelIds = archivedChannelIds,
                            trashedChannelIds = trashedChannelIds,
                            onChannelArchivedChange = { channelId, archived ->
                                val name = visibleChannelItems.firstOrNull { it.channelId == channelId }?.displayName
                                    ?: events.channelDisplayName(channelId)
                                onChannelArchivedChange(channelId, archived)
                                if (archived) scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.channel_archived, name),
                                        actionLabel = context.getString(R.string.undo),
                                        withDismissAction = true
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onChannelArchivedChange(channelId, false)
                                    }
                                }
                            },
                            onChannelDeleted = { channelId ->
                                val name = events.channelDisplayName(channelId)
                                onChannelTrashed(channelId) { moved ->
                                    scope.launch {
                                        if (!moved) {
                                            snackbarHostState.showSnackbar(context.getString(R.string.trash_failed))
                                            return@launch
                                        }
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.channel_moved_to_trash, name),
                                            actionLabel = context.getString(R.string.undo),
                                            withDismissAction = true
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            onChannelRestored(channelId) { restored ->
                                                if (!restored) scope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.restore_failed))
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onChannelSelected = {
                                openTimeline(it) { channelId -> selectedChannelId = channelId }
                            }
                        )
                    } else {
                        ChannelTimelineScreen(
                            channelId = selectedChannelId!!,
                            events = events.takeIf { channelItems == null },
                            initialNewestEvents = initialTimelinePage
                                ?.takeIf { it.first == selectedChannelId }?.second,
                            channelName = visibleChannelItems.firstOrNull { it.channelId == selectedChannelId }?.displayName,
                            onBack = { selectedChannelId = null }
                        )
                    }
                }
                Tab.ALARM -> AlarmScreen(
                    alarmActivationCount = alarmActivationCount,
                    payload = activeAlarmPayload,
                    mascot = alarmMascot,
                    mascotBackgroundColor = alarmMascotBackgroundColor,
                    runtimeSnapshot = alarmRuntimeSnapshot,
                    onStopAlarm = { eventId ->
                        context.startService(
                            Intent(context, AlarmService::class.java).apply {
                                action = AlarmService.STOP_ALARM_ACTION
                                putExtra(AlarmService.EXTRA_EVENT_ID, eventId)
                            }
                        )
                    },
                    onTerminalShown = {
                        onAlarmRouteEnded()
                        selectedTab = Tab.CHANNELS
                        selectedChannelId = null
                    }
                )
            }
        }
    }
}

@Composable
private fun DenDenTopAppBar(
    selectedTab: Tab,
    mascot: Bitmap?,
    mascotBackgroundColor: Color?,
    trashCount: Int,
    isAlarmTerminal: Boolean,
    onBackClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onTrashClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val usesTwoRows = selectedTab == Tab.CHANNELS && LocalDensity.current.fontScale >= 1.5f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DenDenColors.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (selectedTab == Tab.ALARM) 52.dp else 64.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DenDenTopBarIdentity(
                selectedTab = selectedTab,
                mascot = mascot,
                mascotBackgroundColor = mascotBackgroundColor,
                onBackClick = onBackClick,
                modifier = if (selectedTab == Tab.CHANNELS) Modifier.weight(1f) else Modifier
            )

            when (selectedTab) {
                Tab.CHANNELS -> if (!usesTwoRows) {
                    ChannelHeaderActions(
                        trashCount = trashCount,
                        onArchiveClick = onArchiveClick,
                        onTrashClick = onTrashClick,
                        onSettingsClick = onSettingsClick
                    )
                }
                Tab.ALARM -> {
                    Icon(
                        imageVector = if (isAlarmTerminal) Icons.Default.Check else Icons.Default.NotificationsActive,
                        contentDescription = stringResource(
                            if (isAlarmTerminal) R.string.alarm_resolved else R.string.alarm_ringing
                        ),
                        tint = if (isAlarmTerminal) DenDenColors.success else DenDenColors.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Tab.SETTINGS -> Spacer(Modifier.size(40.dp))
            }
        }

        if (usesTwoRows) {
            ChannelHeaderActions(
                trashCount = trashCount,
                onArchiveClick = onArchiveClick,
                onTrashClick = onTrashClick,
                onSettingsClick = onSettingsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun DenDenTopBarIdentity(
    selectedTab: Tab,
    mascot: Bitmap?,
    mascotBackgroundColor: Color?,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mascotImage = remember(mascot) { mascot?.asImageBitmap() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedTab == Tab.SETTINGS) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_to_channels),
                    tint = DenDenColors.primary
                )
            }
        } else if (selectedTab != Tab.ALARM) {
            val background = mascotBackgroundColor ?: DenDenColors.mascotBackground
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = background,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    DenDenColors.mascotBorder(background)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (mascotImage != null) Image(
                        bitmap = mascotImage,
                        contentDescription = stringResource(R.string.custom_denden_logo),
                        modifier = Modifier.size(30.dp)
                    ) else Image(
                        painter = painterResource(R.drawable.denden_builtin_logo_transparent),
                        contentDescription = stringResource(R.string.builtin_denden_logo),
                        modifier = Modifier.size(28.dp),
                        colorFilter = ColorFilter.tint(DenDenColors.mascotForeground)
                    )
                }
            }
        }
        Text(
            text = when (selectedTab) {
                Tab.CHANNELS -> stringResource(R.string.message_channel)
                Tab.SETTINGS -> stringResource(R.string.settings)
                Tab.ALARM -> stringResource(R.string.app_name)
            },
            modifier = if (selectedTab == Tab.CHANNELS) Modifier.weight(1f) else Modifier,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color = DenDenColors.onSurface
            )
        )
    }
}

@Composable
private fun ChannelHeaderActions(
    trashCount: Int,
    onArchiveClick: () -> Unit,
    onTrashClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trashDescription = if (trashCount > 0) {
        pluralStringResource(R.plurals.trash_count, trashCount, trashCount)
    } else {
        stringResource(R.string.trash)
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onArchiveClick) {
            Icon(
                imageVector = Icons.Default.Archive,
                contentDescription = stringResource(R.string.archive),
                tint = DenDenColors.onSurface
            )
        }
        BadgedBox(
            badge = {
                if (trashCount > 0) Badge { Text(if (trashCount > 99) "99+" else "$trashCount") }
            }
        ) {
            IconButton(onClick = onTrashClick) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = trashDescription,
                    tint = DenDenColors.onSurface
                )
            }
        }
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = DenDenColors.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
internal fun DenDenTheme(themeMode: DenDenThemeMode, brandColor: Color? = null, content: @Composable () -> Unit) {
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    DenDenColors.darkMode = darkTheme
    DenDenColors.brandColor = brandColor
    val colorScheme = remember(darkTheme, brandColor) {
        if (darkTheme) {
            darkColorScheme(
                primary = DenDenColors.primary,
                onPrimary = DenDenColors.onPrimary,
                primaryContainer = DenDenColors.primaryContainer,
                onPrimaryContainer = DenDenColors.onPrimaryContainer,
                background = DenDenColors.background,
                onBackground = DenDenColors.onSurface,
                surface = DenDenColors.surface,
                onSurface = DenDenColors.onSurface,
                surfaceVariant = DenDenColors.surfaceContainerHigh,
                onSurfaceVariant = DenDenColors.onSurfaceVariant,
                error = DenDenColors.error,
                errorContainer = DenDenColors.errorContainer,
                onErrorContainer = DenDenColors.onErrorContainer,
                outline = DenDenColors.outline,
                outlineVariant = DenDenColors.outlineVariant
            )
        } else {
            lightColorScheme(
                primary = DenDenColors.primary,
                onPrimary = DenDenColors.onPrimary,
                primaryContainer = DenDenColors.primaryContainer,
                onPrimaryContainer = DenDenColors.onPrimaryContainer,
                background = DenDenColors.background,
                onBackground = DenDenColors.onSurface,
                surface = DenDenColors.surface,
                onSurface = DenDenColors.onSurface,
                surfaceVariant = DenDenColors.surfaceContainerHigh,
                onSurfaceVariant = DenDenColors.onSurfaceVariant,
                error = DenDenColors.error,
                errorContainer = DenDenColors.errorContainer,
                onErrorContainer = DenDenColors.onErrorContainer,
                outline = DenDenColors.outline,
                outlineVariant = DenDenColors.outlineVariant
            )
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = {
            CompositionLocalProvider(LocalContentColor provides DenDenColors.onSurface) {
                content()
            }
        }
    )
}

object DenDenColors {
    var darkMode: Boolean = false
    var brandColor: Color? = null
    private var cachedAccentSeed: Color? = null
    private var cachedAccentBackground: Color? = null
    private var cachedAccent: Color? = null
    val background: Color get() = if (darkMode) Color(0xFF12151A) else Color(0xFFF5F2EB)
    val surface: Color get() = if (darkMode) Color(0xFF1A1E24) else Color(0xFFFAF8F3)
    val surfaceDim: Color get() = if (darkMode) Color(0xFF12151A) else Color(0xFFE8E3D9)
    val surfaceContainerLowest: Color get() = if (darkMode) Color(0xFF222830) else Color(0xFFFFFFFF)
    val surfaceContainerLow: Color get() = if (darkMode) Color(0xFF1A1E24) else Color(0xFFFAF8F3)
    val surfaceContainer: Color get() = if (darkMode) Color(0xFF20252C) else Color(0xFFF2EEE6)
    val surfaceContainerHigh: Color get() = if (darkMode) Color(0xFF282E36) else Color(0xFFECE7DE)
    val surfaceContainerHighest: Color get() = if (darkMode) Color(0xFF303741) else Color(0xFFE4DED4)
    val primary: Color get() = brandAccent()
        ?: if (darkMode) Color(0xFF3391FF) else Color(0xFF005FCC)
    val primaryContainer: Color get() = brandColor?.let { lerp(surface, primary, if (darkMode) 0.28f else 0.16f) }
        ?: if (darkMode) Color(0xFF183A60) else Color(0xFFDCEBFF)
    val onPrimary: Color get() = brandColor?.let { contrastingContent(primary) } ?: Color.White
    val onPrimaryContainer: Color get() = brandColor?.let { contrastingContent(primaryContainer) }
        ?: if (darkMode) Color(0xFFD7E8FF) else Color(0xFF003F88)
    val primaryFixed: Color get() = brandColor?.let { lerp(surface, primary, if (darkMode) 0.22f else 0.12f) }
        ?: if (darkMode) Color(0xFF203A57) else Color(0xFFE5F0FF)
    val onPrimaryFixed: Color get() = brandColor?.let { contrastingContent(primaryFixed) }
        ?: if (darkMode) Color(0xFFD7E8FF) else Color(0xFF003F88)
    val onSurface: Color get() = if (darkMode) Color(0xFFEEF1F5) else Color(0xFF0F1C2E)
    val onSurfaceVariant: Color get() = if (darkMode) Color(0xFF9AA3AD) else Color(0xFF5C6570)
    val error: Color get() = if (darkMode) Color(0xFFE07060) else Color(0xFFB34234)
    val errorContainer: Color get() = if (darkMode) Color(0xFF2C1816) else Color(0xFFFDF1EF)
    val onErrorContainer: Color get() = if (darkMode) Color(0xFFFFD2CC) else Color(0xFF842D24)
    val outline: Color get() = if (darkMode) Color(0xFF7F8994) else Color(0xFF8C8B86)
    val outlineVariant: Color get() = if (darkMode) Color(0x1AFFFFFF) else Color(0xFFE0DDD6)
    val success: Color get() = if (darkMode) Color(0xFF5DBF8B) else Color(0xFF2D6A4F)
    val warning: Color get() = if (darkMode) Color(0xFFE3B044) else Color(0xFF9A6700)
    val mascotBackground: Color get() = surfaceContainerLowest
    val mascotForeground: Color get() = if (darkMode) Color.White else Color.Black
    fun mascotBorder(background: Color): Color =
        brandColor?.let { primary.copy(alpha = 0.72f) }
            ?: if (background.luminance() > 0.5f) Color.Black.copy(alpha = 0.18f)
            else Color.White.copy(alpha = 0.24f)

    private fun brandAccent(): Color? {
        val seed = brandColor ?: return null
        val against = background
        if (cachedAccentSeed != seed || cachedAccentBackground != against) {
            cachedAccentSeed = seed
            cachedAccentBackground = against
            cachedAccent = readableAccent(seed, against)
        }
        return cachedAccent
    }

    private fun readableAccent(seed: Color, against: Color): Color {
        var color = seed.copy(alpha = 1f)
        val target = if (against.luminance() < 0.5f) Color.White else Color.Black
        repeat(8) {
            if (contrastRatio(color, against) >= 3f) return color
            color = lerp(color, target, 0.2f)
        }
        return color
    }

    private fun contrastingContent(background: Color): Color =
        if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
