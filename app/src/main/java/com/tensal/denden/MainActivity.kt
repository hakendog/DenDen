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
import com.tensal.denden.automation.reconcileLocalAutomation
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
import com.tensal.denden.ui.ArchivedChannelsScreen
import com.tensal.denden.ui.ChannelListScreen
import com.tensal.denden.ui.ChannelInboxItem
import com.tensal.denden.ui.ChannelTimelineScreen
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

private const val THEME_MODE_KEY = "theme_mode"
private const val ONBOARDING_SHOWN_KEY = "onboarding_shown"
private const val LOCAL_MODE_ENABLED_KEY = "local_mode_enabled"

private fun ChannelInboxRecord.toUiItem() = ChannelInboxItem(
    channelId = latestEvent.channelId,
    displayName = displayName,
    latestEvent = latestEvent,
    eventCount = eventCount,
    unreadCount = unreadCount,
    archived = archived
)

class MainActivity : ComponentActivity() {
    private var currentTab by mutableStateOf(Tab.CHANNELS)
    private var requestedChannelId by mutableStateOf<String?>(null)
    private var routeChangeCount by mutableStateOf(0)
    private var channelItems by mutableStateOf<List<ChannelInboxItem>>(emptyList())
    private var trashChannelItems by mutableStateOf<List<ChannelInboxItem>>(emptyList())
    private var trashedChannels by mutableStateOf<List<TrashedChannel>>(emptyList())
    private var alarmActivationCount by mutableStateOf(0)
    private var activeAlarmPayload by mutableStateOf<ActiveAlarmPayload?>(null)
    private var alarmBrandingSnapshot by mutableStateOf<AlarmBrandingSnapshot?>(null)
    private var themeMode by mutableStateOf(DenDenThemeMode.SYSTEM)
    private var appLanguage by mutableStateOf(AppLanguage.SYSTEM)
    private lateinit var readStateStore: ChannelReadStateStore
    private lateinit var testExecutionStore: TestExecutionStore
    private lateinit var directPairingStore: DirectPairingStore
    private lateinit var directBrandingStore: DirectBrandStore
    private var readiness by mutableStateOf(ReadinessSnapshot.Loading)
    private var testInProgress by mutableStateOf(false)
    private var testMessage by mutableStateOf<String?>(null)
    private var isPaired by mutableStateOf(false)
    private var localModeEnabled by mutableStateOf(false)
    private var forceSetup by mutableStateOf(false)
    private var directProjectId by mutableStateOf<String?>(null)
    private var inviteInput by mutableStateOf("")
    private var setupStep by mutableStateOf(SetupStep.WELCOME)
    private var setupDisplayState by mutableStateOf(SetupDisplayState())
    private var pendingDirectInvite: DirectFcmInvite? = null
    private var cachedBranding by mutableStateOf<CachedBranding?>(null)
    private var directBrandStatus by mutableStateOf<DirectBrandStatus?>(null)
    private var pendingBrandCandidate by mutableStateOf<DirectBrandCandidate?>(null)
    private val pairingAttemptGuard = PairingAttemptGuard()
    private val pairingAttemptMutex = Mutex()
    private var pairingJob: Job? = null
    private var stopObservingPairingState: (() -> Unit)? = null
    private var brandReceiverRegistered = false
    private val brandChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingBrandCandidate = directBrandingStore.candidate()
            directBrandStatus = directBrandingStore.status()
        }
    }

    private var isNotificationPermissionGranted by mutableStateOf(true)
    private var canUseFullScreenIntent by mutableStateOf(true)
    private var isBatteryOptimizationIgnored by mutableStateOf(false)
    private var isNotificationPolicyAccessGranted by mutableStateOf(false)
    private var runLocalTestAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        isNotificationPermissionGranted = it
        refreshReadiness()
        if (runLocalTestAfterPermission) {
            runLocalTestAfterPermission = false
            if (it) runLocalTest() else testMessage = getString(R.string.notification_permission_required)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSelectedAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        readStateStore = ChannelReadStateStore(this)
        testExecutionStore = TestExecutionStore(this)
        directPairingStore = DirectPairingStore(this)
        directBrandingStore = DirectBrandStore(this)
        cachedBranding = directBrandingStore.load()
        directBrandStatus = directBrandingStore.status()
        NotificationChannels.create(this)
        val appPrefs = getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
        themeMode = DenDenThemeMode.fromStorage(
            appPrefs.getString(THEME_MODE_KEY, null)
        )
        localModeEnabled = appPrefs.getBoolean(LOCAL_MODE_ENABLED_KEY, false)
        appLanguage = selectedAppLanguage()
        refreshStoredPairingState()
        lifecycleScope.launch(Dispatchers.IO) {
            readStateStore.importInto(EventDatabase.getInstance(this@MainActivity).messageQueryDao())
        }
        observeMessageSummaries()
        observeTrashedChannels()

        refreshPermissionStates()

        val alarmEventId = intent.getStringExtra(AlarmService.EXTRA_EVENT_ID)?.takeIf(String::isNotBlank)
        val isAlarmRoute = alarmEventId != null &&
            intent.getStringExtra("open_tab") == "ALARM" &&
            intent.getBooleanExtra("alarm_active", false)
        applyAlarmWindowFlags(isAlarmRoute)
        activeAlarmPayload = if (isAlarmRoute) {
            intent.extractActiveAlarmPayload(requireNotNull(alarmEventId))
        } else null
        alarmBrandingSnapshot = if (isAlarmRoute) cachedBranding.toAlarmSnapshot() else null
        val firstLaunch = !appPrefs.getBoolean(ONBOARDING_SHOWN_KEY, false)
        if (firstLaunch) appPrefs.edit().putBoolean(ONBOARDING_SHOWN_KEY, true).apply()
        currentTab = when {
            isAlarmRoute -> Tab.ALARM
            firstLaunch && isPaired -> Tab.SETTINGS
            else -> Tab.CHANNELS
        }
        requestedChannelId = if (isAlarmRoute) null else intent.getStringExtra(EXTRA_OPEN_CHANNEL_ID)
        alarmActivationCount = if (isAlarmRoute) 1 else 0
        setContent {
            val visibleBranding = cachedBranding.takeIf { isPaired }
            DenDenTheme(
                themeMode = themeMode,
                brandColor = visibleBranding?.brandColor?.let(::Color)
            ) {
                if (!isPaired && (!localModeEnabled || forceSetup) && currentTab != Tab.ALARM) {
                    SetupScreen(
                        step = setupStep,
                        input = inviteInput,
                        state = setupDisplayState,
                        localTestInProgress = testInProgress,
                        localTestMessage = testMessage,
                        isNotificationPermissionGranted = isNotificationPermissionGranted,
                        canUseFullScreenIntent = canUseFullScreenIntent,
                        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                        isNotificationPolicyAccessGranted = isNotificationPolicyAccessGranted,
                        onStepChange = ::changeSetupStep,
                        onInputChange = ::updateInviteInput,
                        onSubmit = { inspectInvite(inviteInput) },
                        onScanQr = ::scanPairingQr,
                        onConfirmPairing = ::confirmPairing,
                        onCancelPairing = ::cancelPairing,
                        onRunLocalTest = ::runLocalTest,
                        onRequestNotification = ::requestNotification,
                        onOpenFullScreenIntentSettings = ::openFullScreenIntentSettings,
                        onOpenBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                        onOpenDndSettings = ::openDndSettings,
                        onUseLocalMode = ::useLocalMode
                    )
                } else {
                    val alarmBranding = alarmBrandingSnapshot
                    MainScreen(
                    themeMode = themeMode,
                    isNotificationPermissionGranted = isNotificationPermissionGranted,
                    canUseFullScreenIntent = canUseFullScreenIntent,
                    isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    isNotificationPolicyAccessGranted = isNotificationPolicyAccessGranted,
                    readiness = readiness,
                    firebaseProjectId = directProjectId,
                    brandingStatus = directBrandStatus,
                    mascot = visibleBranding?.mascot,
                    mascotBackgroundColor = visibleBranding?.backgroundColor?.let(::Color),
                    alarmMascot = if (alarmBranding != null) alarmBranding.mascot else visibleBranding?.mascot,
                    alarmMascotBackgroundColor = if (alarmBranding != null) {
                        alarmBranding.backgroundColor?.let(::Color)
                    } else visibleBranding?.backgroundColor?.let(::Color),
                    testInProgress = testInProgress,
                    testMessage = testMessage,
                    channelItems = channelItems,
                    trashChannelItems = trashChannelItems,
                    trashedChannels = trashedChannels,
                    initialTab = currentTab,
                    initialChannelId = requestedChannelId,
                    routeChangeCount = routeChangeCount,
                    alarmActivationCount = alarmActivationCount,
                    activeAlarmPayload = activeAlarmPayload,
                    onChannelOpened = ::markChannelRead,
                    onChannelArchivedChange = ::setChannelArchived,
                    onChannelTrashed = ::moveChannelToTrash,
                    onChannelRestored = ::restoreChannel,
                    onChannelPermanentlyDeleted = ::permanentlyDeleteChannel,
                    onAlarmRouteEnded = {
                        applyAlarmWindowFlags(false)
                        currentTab = Tab.CHANNELS
                        activeAlarmPayload = null
                        alarmBrandingSnapshot = null
                    },
                    onThemeModeChange = ::updateThemeMode,
                    appLanguage = appLanguage,
                    onAppLanguageChange = ::updateAppLanguage,
                    onRequestNotification = ::requestNotification,
                    onOpenFullScreenIntentSettings = ::openFullScreenIntentSettings,
                    onOpenBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                    onOpenDndSettings = ::openDndSettings,
                    onRunLocalTest = ::runLocalTest,
                    onRefreshReadiness = ::refreshReadiness,
                    onClearPairing = ::managePairing,
                )
                    pendingBrandCandidate?.let { candidate ->
                        BrandCandidateDialog(
                            candidate = candidate,
                            onAccept = ::acceptBrandCandidate,
                            onReject = ::rejectBrandCandidate
                        )
                    }
                }
            }
        }
        if (!isPaired) recoverExistingPairing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val alarmEventId = intent.getStringExtra(AlarmService.EXTRA_EVENT_ID)?.takeIf(String::isNotBlank)
        val isAlarmRoute = alarmEventId != null &&
            intent.getStringExtra("open_tab") == "ALARM" &&
            intent.getBooleanExtra("alarm_active", false)
        if (isAlarmRoute) {
            applyAlarmWindowFlags(true)
            currentTab = Tab.ALARM
            requestedChannelId = null
            routeChangeCount = routeChangeCount + 1
            alarmActivationCount = alarmActivationCount + 1
            activeAlarmPayload = intent.extractActiveAlarmPayload(requireNotNull(alarmEventId))
            alarmBrandingSnapshot = cachedBranding.toAlarmSnapshot()
        } else {
            applyAlarmWindowFlags(false)
            currentTab = Tab.CHANNELS
            requestedChannelId = intent.getStringExtra(EXTRA_OPEN_CHANNEL_ID)
            routeChangeCount = routeChangeCount + 1
            alarmBrandingSnapshot = null
        }
    }

    private fun updateThemeMode(mode: DenDenThemeMode) {
        themeMode = mode
        getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(THEME_MODE_KEY, mode.storageValue)
            .apply()
    }

    private fun updateAppLanguage(language: AppLanguage) {
        getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(APP_LANGUAGE_KEY, language.storageValue)
            .apply()
        recreate()
    }

    private fun inspectInvite(value: String) {
        pendingDirectInvite = null
        setupDisplayState = SetupDisplayState(loading = true)
        launchPairingAttempt { attempt ->
            val result = runCatching {
                parseDirectFcmInvite(value).also {
                    require(it.androidPackageName == packageName) { getString(R.string.qr_package_mismatch) }
                }
            }
            if (!isActive || !pairingAttemptGuard.isCurrent(attempt)) return@launchPairingAttempt
            result.onSuccess { invite ->
                pendingDirectInvite = invite
                setupDisplayState = SetupDisplayState(
                    confirmFirebaseProjectId = invite.projectId,
                    confirmDomain = "Firebase Cloud Messaging"
                )
            }.onFailure {
                setupDisplayState = SetupDisplayState(status = getString(R.string.invite_check_failed))
            }
        }
    }

    private fun updateInviteInput(value: String) {
        invalidatePairingAttempt()
        inviteInput = value
        pendingDirectInvite = null
        setupDisplayState = SetupDisplayState()
    }

    private fun scanPairingQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let(::openPairingInvite)
            }
            .addOnFailureListener {
                setupDisplayState = SetupDisplayState(
                    status = getString(R.string.qr_scan_failed, it.javaClass.simpleName)
                )
            }
    }

    private fun cancelPairing() {
        invalidatePairingAttempt()
        pendingDirectInvite = null
        setupDisplayState = SetupDisplayState()
    }

    private fun changeSetupStep(step: SetupStep) {
        if (localModeEnabled && forceSetup && setupStep == SetupStep.PAIRING && step == SetupStep.DEMO) {
            useLocalMode()
            return
        }
        if (setupStep == SetupStep.PAIRING && step != SetupStep.PAIRING) cancelPairing()
        setupStep = step
    }

    private fun openPairingInvite(rawValue: String) {
        val invite = rawValue.trim().takeIf { it.startsWith("DDC.") } ?: run {
            setupDisplayState = SetupDisplayState(status = getString(R.string.invalid_pairing_qr))
            return
        }
        setupStep = SetupStep.PAIRING
        inviteInput = invite
        inspectInvite(invite)
    }

    private fun confirmPairing() {
        val invite = pendingDirectInvite ?: return
        setupDisplayState = SetupDisplayState(loading = true, status = getString(R.string.pairing_in_progress))
        launchPairingAttempt { attempt ->
            val result = runCatching {
                directRuntimeMutex.withLock {
                    directPairingStore.stage(invite)
                    runCatching { directBrandingStore.clearPairing() }
                }
                resumeDirectPairing(this@MainActivity, directPairingStore)
                check(directPairingStore.snapshot().state == PairingState.ACTIVE) {
                    getString(R.string.topic_subscription_incomplete)
                }
            }
            val afterAttempt = directPairingStore.snapshot()
            if (afterAttempt.state == PairingState.PENDING || afterAttempt.state == PairingState.ERROR) {
                scheduleDirectPairing(this@MainActivity)
            }
            if (!isActive || !pairingAttemptGuard.isCurrent(attempt)) return@launchPairingAttempt
            result.onSuccess {
                showActivePairing()
            }.onFailure {
                setupDisplayState = SetupDisplayState(
                    status = getString(R.string.pairing_failed_retry)
                )
            }
        }
    }

    private fun recoverExistingPairing() {
        val direct = directPairingStore.snapshot()
        if (direct.state == PairingState.PENDING || direct.state == PairingState.ERROR) {
            setupDisplayState = SetupDisplayState(
                loading = true,
                status = getString(R.string.pairing_recovering)
            )
            launchPairingAttempt { attempt ->
                val result = runCatching { resumeDirectPairing(this@MainActivity, directPairingStore) }
                if (!isActive || !pairingAttemptGuard.isCurrent(attempt)) return@launchPairingAttempt
                result.onSuccess {
                    refreshStoredPairingState()
                    setupDisplayState = if (isPaired) SetupDisplayState() else SetupDisplayState(
                        status = getString(R.string.pairing_incomplete)
                    )
                }.onFailure {
                    setupDisplayState = SetupDisplayState(
                        status = getString(R.string.pairing_recovery_failed)
                    )
                }
            }
        }
    }

    private fun launchPairingAttempt(block: suspend CoroutineScope.(Long) -> Unit) {
        pairingJob?.cancel()
        val attempt = pairingAttemptGuard.begin()
        pairingJob = lifecycleScope.launch {
            pairingAttemptMutex.withLock {
                if (!isActive || !pairingAttemptGuard.isCurrent(attempt)) return@withLock
                block(attempt)
            }
        }
    }

    private fun invalidatePairingAttempt() {
        pairingAttemptGuard.invalidate()
        pairingJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        reconcileDirectMessages(this)
        reconcileLocalAutomation(this)
        refreshStoredPairingState()
        refreshPermissionStates()
        cleanupLocalTrash()
        refreshReadiness()
    }

    override fun onStart() {
        super.onStart()
        if (stopObservingPairingState == null) {
            stopObservingPairingState = directPairingStore.observeState {
                runOnUiThread {
                    if (!isPaired && directPairingStore.snapshot().state == PairingState.ACTIVE) showActivePairing()
                }
            }
        }
        if (!brandReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                brandChangedReceiver,
                IntentFilter(DirectBrandStore.ACTION_BRAND_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            brandReceiverRegistered = true
        }
        if (directPairingStore.snapshot().state == PairingState.ACTIVE) {
            cachedBranding = directBrandingStore.load()
            pendingBrandCandidate = directBrandingStore.candidate()
            directBrandStatus = directBrandingStore.status()
        }
    }

    override fun onStop() {
        stopObservingPairingState?.invoke()
        stopObservingPairingState = null
        if (brandReceiverRegistered) {
            unregisterReceiver(brandChangedReceiver)
            brandReceiverRegistered = false
        }
        super.onStop()
    }

    private fun observeMessageSummaries() {
        val dao = EventDatabase.getInstance(this).messageQueryDao()
        lifecycleScope.launch {
            dao.observeChannelInbox().collect { rows ->
                channelItems = rows.map(ChannelInboxRecord::toUiItem)
            }
        }
        lifecycleScope.launch {
            dao.observeTrashInbox().collect { rows ->
                trashChannelItems = rows.map(ChannelInboxRecord::toUiItem)
            }
        }
    }

    private fun observeTrashedChannels() {
        val repository = EventRepository(EventDatabase.getInstance(this).eventDao())
        lifecycleScope.launch {
            repository.observeTrashedChannels().collect { trashedChannels = it }
        }
    }

    private fun cleanupLocalTrash() {
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = EventRepository(EventDatabase.getInstance(this@MainActivity).eventDao())
            cleanupExpiredLocalTrash(this@MainActivity, repository)
        }
    }

    private fun markChannelRead(channelId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            EventDatabase.getInstance(this@MainActivity).messageQueryDao().markReadToLatest(channelId)
        }
    }

    private fun setChannelArchived(channelId: String, archived: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            EventDatabase.getInstance(this@MainActivity).messageQueryDao().setArchived(channelId, archived)
        }
    }

    private fun moveChannelToTrash(channelId: String, onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) {
                EventRepository(EventDatabase.getInstance(this@MainActivity).eventDao())
                        .moveChannelToTrash(channelId)
                }
            }.isSuccess
            onResult(success)
        }
    }

    private fun restoreChannel(channelId: String, onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) {
                EventRepository(EventDatabase.getInstance(this@MainActivity).eventDao())
                    .restoreChannel(channelId)
            }
            onResult(restored)
        }
    }

    private fun permanentlyDeleteChannel(channelId: String, onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val repository = EventRepository(EventDatabase.getInstance(this@MainActivity).eventDao())
                if (!repository.preparePermanentDelete(channelId)) return@withContext false
                finishPendingTrashCleanup(this@MainActivity, repository)
                true
            }
            onResult(deleted)
        }
    }

    private fun refreshPermissionStates() {
        isNotificationPermissionGranted = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
        isBatteryOptimizationIgnored = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        isNotificationPolicyAccessGranted = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
    }

    private fun refreshReadiness() {
        if (!isPaired || !::testExecutionStore.isInitialized) return
        val messageHealth = SharedPrefsMessageHealthStore(this).snapshot()
        val test = testExecutionStore.snapshot()
        val channel = getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.ALARM_CHANNEL_ID)
        readiness = buildDirectReadinessSnapshot(
            paired = directPairingStore.snapshot().state == PairingState.ACTIVE,
            notificationPermission = isNotificationPermissionGranted &&
                NotificationManagerCompat.from(this).areNotificationsEnabled(),
            alarmChannelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE,
            lastFcmAtMillis = messageHealth.lastReceivedAtMillis,
            lastTestAtMillis = test.atMillis,
            lastTestResult = test.result,
            lastDegradedReason = messageHealth.lastDegradedReason
        )
    }

    private fun refreshStoredPairingState() {
        val direct = directPairingStore.snapshot()
        isPaired = direct.state == PairingState.ACTIVE
        directProjectId = direct.active?.projectId
        cachedBranding = if (isPaired) directBrandingStore.load() else null
        pendingBrandCandidate = if (isPaired) directBrandingStore.candidate() else null
        directBrandStatus = if (isPaired) directBrandingStore.status() else null
        if (isPaired || (localModeEnabled && !forceSetup)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun showActivePairing() {
        pendingDirectInvite = null
        inviteInput = ""
        setupStep = SetupStep.WELCOME
        setupDisplayState = SetupDisplayState()
        forceSetup = false
        refreshStoredPairingState()
        refreshReadiness()
    }

    private fun useLocalMode() {
        invalidatePairingAttempt()
        localModeEnabled = true
        forceSetup = false
        getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(LOCAL_MODE_ENABLED_KEY, true)
            .apply()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        currentTab = Tab.CHANNELS
        refreshReadiness()
    }

    private fun managePairing() {
        if (isPaired) {
            clearPairing()
            return
        }
        invalidatePairingAttempt()
        setupStep = SetupStep.PAIRING
        setupDisplayState = SetupDisplayState()
        forceSetup = true
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun clearPairing() {
        setupDisplayState = SetupDisplayState(loading = true, status = getString(R.string.pairing_clearing))
        launchPairingAttempt { attempt ->
            val result = runCatching {
                directRuntimeMutex.withLock {
                    directPairingStore.beginClear()
                    val brandCleared = runCatching { directBrandingStore.clearPairing() }.isSuccess
                    val clearing = directPairingStore.snapshot()
                    val autoInitDisabled = clearing.state == PairingState.UNPAIRED || runCatching {
                        initializeDirectFirebaseRuntime(this@MainActivity, directPairingStore)
                    }.getOrDefault(false)
                    brandCleared && autoInitDisabled
                }
            }
            if (result.isSuccess) scheduleDirectPairing(this@MainActivity)
            if (!isActive || !pairingAttemptGuard.isCurrent(attempt)) return@launchPairingAttempt
            result.onSuccess { cleanupImmediate ->
                isPaired = false
                forceSetup = true
                directProjectId = null
                cachedBranding = null
                pendingBrandCandidate = null
                directBrandStatus = null
                readiness = ReadinessSnapshot.Loading
                setupStep = SetupStep.WELCOME
                setupDisplayState = SetupDisplayState(status = if (cleanupImmediate) {
                    getString(R.string.pairing_cleanup_pending)
                } else {
                    getString(R.string.pairing_cleanup_retry)
                })
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }.onFailure {
                setupDisplayState = SetupDisplayState(
                    status = getString(R.string.pairing_clear_failed)
                )
            }
        }
    }

    private fun acceptBrandCandidate() {
        lifecycleScope.launch(Dispatchers.IO) {
            val applied = runCatching { directBrandingStore.applyCandidate() }
            withContext(Dispatchers.Main) {
                applied.onSuccess {
                    if (it) {
                        cachedBranding = directBrandingStore.load()
                        pendingBrandCandidate = null
                        directBrandStatus = directBrandingStore.status()
                        retryPendingShortcutUpdate(this@MainActivity)
                    }
                }.onFailure {
                    directBrandStatus = directBrandingStore.status()
                }
            }
        }
    }

    private fun rejectBrandCandidate() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rejected = runCatching { directBrandingStore.rejectCandidate() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (rejected) pendingBrandCandidate = null
                directBrandStatus = directBrandingStore.status()
            }
        }
    }

    private fun runLocalTest() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runLocalTestAfterPermission = true
            testMessage = getString(R.string.local_test_permission_required)
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        testInProgress = true
        testMessage = null
        val localTestTitle = getString(R.string.local_test_title)
        val localTestMessage = getString(R.string.local_test_message)
        val localTestChannel = getString(R.string.local_test_channel_name)
        lifecycleScope.launch {
            val eventId = "local-test-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val dao = EventDatabase.getInstance(this@MainActivity).directMessageDao()
                    check(dao.commitEvent(
                        pairingId = "local-test",
                        messageId = eventId,
                        contentDigest = eventId,
                        expiresAtMillis = now + 60_000,
                        event = DenDenEvent(
                            eventId = eventId,
                            action = "ring",
                            kind = "alarm",
                            state = "pending",
                            title = localTestTitle,
                            message = localTestMessage,
                            durationSeconds = 10,
                            channelId = "denden-test",
                            channelName = localTestChannel,
                            ringUntilMillis = now + 30_000,
                            receivedAt = now,
                            issuedAtMillis = now
                        )
                    ) == DirectEventCommit.INSERTED)
                    dao.finishAlert(eventId, "dispatched", now, null)
                }
                testExecutionStore.record("local_pending", eventId)
                val payload = ActiveAlarmPayload(
                    channelId = "denden-test",
                    title = localTestTitle,
                    message = localTestMessage,
                    eventId = eventId,
                    channelName = localTestChannel,
                    durationSeconds = 10
                )
                startForegroundService(Intent(this@MainActivity, AlarmService::class.java).apply {
                    putExtra(AlarmService.EXTRA_EVENT_ID, eventId)
                    putExtra("title", localTestTitle)
                    putExtra("message", localTestMessage)
                    putExtra("duration", 10)
                    putExtra("channelId", "denden-test")
                    putExtra("channelName", localTestChannel)
                    putExtra("ringUntilMillis", now + 30_000)
                    putExtra("receivedAt", now)
                })
                applyAlarmWindowFlags(true)
                activeAlarmPayload = payload
                currentTab = Tab.ALARM
                alarmActivationCount += 1
                val confirmed = awaitLocalTestConfirmation(loadState = {
                    withContext(Dispatchers.IO) {
                        EventRepository(EventDatabase.getInstance(this@MainActivity).eventDao())
                            .getEventByEventId(eventId)?.state
                    }
                })
                check(confirmed) { "AlarmService acknowledgement timeout" }
                testExecutionStore.record("local_pass", eventId)
                testMessage = getString(R.string.local_test_confirmed)
            } catch (error: Exception) {
                val detail = error.javaClass.simpleName
                testExecutionStore.record("local_fail", detail)
                testMessage = getString(R.string.local_test_failed, detail)
            } finally {
                testInProgress = false
                refreshReadiness()
            }
        }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= 33 && !isNotificationPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppDetails()
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.fromParts("package", packageName, null)
                startActivity(this)
            }
        } else {
            openAppDetails()
        }
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }.getOrElse {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openDndSettings() {
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            startActivity(this)
        }
    }

    private fun openAppDetails() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyAlarmWindowFlags(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            return
        }

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    private fun Intent.extractActiveAlarmPayload(eventId: String): ActiveAlarmPayload = ActiveAlarmPayload(
        channelId = getStringExtra("channelId") ?: "default",
        channelName = getStringExtra("channelName"),
        title = getStringExtra("title") ?: "DenDen",
        message = getStringExtra("message") ?: "",
        eventId = eventId,
        durationSeconds = getIntExtra("duration", AlarmController.DEFAULT_DURATION)
    )

    companion object {
        const val EXTRA_OPEN_CHANNEL_ID = "open_channel_id"
    }
}

private data class AlarmBrandingSnapshot(val mascot: Bitmap?, val backgroundColor: Int?)

private fun CachedBranding?.toAlarmSnapshot() = AlarmBrandingSnapshot(this?.mascot, this?.backgroundColor)

internal class PairingAttemptGuard {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun isCurrent(attempt: Long): Boolean = attempt == generation
}

enum class Tab { ALARM, CHANNELS, SETTINGS }
enum class DenDenThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStorage(value: String?): DenDenThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}
