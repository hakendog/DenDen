package com.tensal.denden.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tensal.denden.MainActivity
import com.tensal.denden.R
import com.tensal.denden.withSelectedAppLanguage
import com.tensal.denden.branding.applyDenDenBranding
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.data.EventRepository
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

class AlarmService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val registry = ActiveAlarmRegistry()
    private val lifecycleMutex = Mutex()
    private lateinit var controller: AlarmController
    private var activeEvent: DenDenEvent? = null
    private var activeStartId: Int? = null
    private var latestStartId: Int = 0
    private var pendingStartJob: Job? = null
    private val eventRepo: EventRepository by lazy {
        EventRepository(EventDatabase.getInstance(applicationContext).eventDao())
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSelectedAppLanguage())
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        controller = AlarmController(
            audioPlayer = DefaultAudioPlayer(this),
            vibratorController = DefaultVibratorController(this),
            clock = SystemClock(),
            scope = serviceScope,
            onStopped = { reason ->
                val eventId = registry.eventId
                if (eventId != null) {
                    val startId = activeStartId
                    registry.stop(eventId)
                    activeEvent = null
                    activeStartId = null
                    finishStoppedEvent(eventId, reason, startId)
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = maxOf(latestStartId, startId)
        if (intent?.action == STOP_ALARM_ACTION) {
            val targetEventId = intent.getStringExtra(EXTRA_EVENT_ID)
            val remote = intent.getBooleanExtra(EXTRA_REMOTE_STOP, false)
            val openAppAfterStop = intent.getBooleanExtra(EXTRA_OPEN_APP_AFTER_STOP, false)
            val reason = if (remote) "remote" else "manual"
            when (stopIntentDisposition(targetEventId, registry.eventId)) {
                StopIntentDisposition.STOP_IDLE_SERVICE -> {
                    if (targetEventId.isNullOrBlank()) {
                        stopSelf(latestStartId)
                    } else {
                        finishStoppedEvent(
                            eventId = targetEventId,
                            reason = reason,
                            startId = startId,
                            openChannelsAfter = openAppAfterStop
                        )
                    }
                    return START_NOT_STICKY
                }
                StopIntentDisposition.IGNORE_ACTIVE_MISMATCH -> return START_NOT_STICKY
                StopIntentDisposition.STOP_ACTIVE -> Unit
            }
            val stoppedEvent = activeEvent
            if (controller.isPlaying) {
                activeStartId = startId
                controller.stop(reason)
            } else {
                pendingStartJob?.cancel()
                pendingStartJob = null
                val event = activeEvent
                registry.stop(targetEventId)
                activeEvent = null
                activeStartId = null
                if (event != null) {
                    finishStoppedEvent(event.eventId, reason, startId)
                } else {
                    stopSelf(latestStartId)
                }
            }
            if (openAppAfterStop) {
                startActivity(stoppedEvent?.let(::alarmActivityIntent) ?: channelsActivityIntent())
            }
            return START_NOT_STICKY
        }

        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID)
        if (eventId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ringUntilMillis = intent.getLongExtra("ringUntilMillis", -1L).takeIf { it > 0 }
        if (isRingExpired(ringUntilMillis, System.currentTimeMillis())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registry.start(eventId)) return START_NOT_STICKY

        val title = intent.getStringExtra("title") ?: "DenDen"
        val message = intent.getStringExtra("message") ?: ""
        val channelId = intent.getStringExtra("channelId") ?: "default"
        val channelName = intent.getStringExtra("channelName")
        val duration = intent.getIntExtra("duration", AlarmController.DEFAULT_DURATION)
        val event = alarmEventFromIntent(intent, eventId, System.currentTimeMillis())
        activeEvent = event
        activeStartId = startId
        val openIntent = alarmActivityIntent(title, message, eventId, channelId, channelName, duration)
        val preClaimNotificationId = preClaimAlarmNotificationId(eventId)
        startForeground(
            preClaimNotificationId,
            createPreClaimAlarmNotification(this, eventId, title)
        )
        pendingStartJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val activated = try {
                claimAndActivateAlarm(
                    claimRinging = {
                        withLifecycleLock {
                            withContext(Dispatchers.IO) {
                                val directDao = EventDatabase.getInstance(applicationContext).directMessageDao()
                                if (directDao.tombstone(eventId) != null) false
                                else eventRepo.markRinging(eventId, System.currentTimeMillis())
                            }
                        }
                    },
                    requestStillActive = {
                        registry.eventId == eventId && activeEvent?.eventId == eventId
                    },
                    activate = {
                        startForeground(
                            alarmNotificationId(eventId),
                            createNotification(title, message, eventId, openIntent)
                        )
                        NotificationManagerCompat.from(this@AlarmService).cancel(preClaimNotificationId)
                        AlarmRuntime.markActive(eventId)
                        controller.start(duration)
                    }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            pendingStartJob = null
            if (!activated && registry.eventId == eventId) {
                AlarmRuntime.markTerminal(eventId, "activation_failed")
                registry.stop(eventId)
                activeEvent = null
                activeStartId = null
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf(latestStartId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun <T> withLifecycleLock(block: suspend () -> T): T {
        lifecycleMutex.lock()
        return try {
            block()
        } finally {
            lifecycleMutex.unlock()
        }
    }

    private fun finishStoppedEvent(
        eventId: String,
        reason: String,
        startId: Int?,
        openChannelsAfter: Boolean = false
    ) {
        AlarmRuntime.markTerminal(eventId, reason)
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withLifecycleLock {
                    withContext(Dispatchers.IO) {
                        eventRepo.markStopped(eventId, System.currentTimeMillis(), reason)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Audio is already stopped; local readiness evidence exposes persistence failure.
            }
            if (openChannelsAfter) startActivity(channelsActivityIntent())
            if (registry.eventId == null) {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val cleanupStartId = if (registry.eventId == null) {
                maxOf(startId ?: 0, latestStartId)
            } else {
                startId ?: 0
            }
            if (cleanupStartId > 0) stopSelf(cleanupStartId) else stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::controller.isInitialized) controller.dispose()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotification(
        title: String,
        message: String,
        eventId: String,
        openIntent: Intent
    ): Notification {
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = STOP_ALARM_ACTION
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OPEN_APP_AFTER_STOP, true)
        }
        val requestCode = eventId.hashCode()
        val stopPendingIntent = PendingIntent.getService(
            this, requestCode, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPendingIntent = PendingIntent.getActivity(
            this, requestCode, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NotificationChannels.ALARM_CHANNEL_ID)
            .applyDenDenBranding(this)
            .setContentTitle(title)
            .setContentText(alarmNotificationSummary(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(openPendingIntent, true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_alarm), stopPendingIntent)
            .build()
    }

    private fun alarmActivityIntent(
        title: String,
        message: String,
        eventId: String,
        channelId: String,
        channelName: String?,
        duration: Int
    ): Intent = Intent(this, MainActivity::class.java).apply {
        putExtra("open_tab", "ALARM")
        putExtra("alarm_active", true)
        putExtra("title", title)
        putExtra("message", message)
        putExtra(EXTRA_EVENT_ID, eventId)
        putExtra("channelId", channelId)
        putExtra("channelName", channelName)
        putExtra("duration", duration)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    private fun alarmActivityIntent(event: DenDenEvent): Intent = alarmActivityIntent(
        title = event.title ?: "DenDen",
        message = event.message ?: "",
        eventId = requireNotNull(event.eventId),
        channelId = event.channelId,
        channelName = event.channelName,
        duration = event.durationSeconds ?: AlarmController.DEFAULT_DURATION
    )

    private fun channelsActivityIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    companion object {
        const val STOP_ALARM_ACTION = "com.tensal.denden.action.STOP_ALARM"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_REMOTE_STOP = "remote_stop"
        const val EXTRA_OPEN_APP_AFTER_STOP = "open_app_after_stop"
    }
}

internal fun alarmNotificationId(eventId: String): Int = eventId.hashCode().let { if (it == 0) 1001 else it }

internal fun preClaimAlarmNotificationId(eventId: String): Int =
    alarmNotificationId(eventId).let { if (it == Int.MIN_VALUE) Int.MAX_VALUE else -it }

internal fun alarmNotificationSummary(message: String): String =
    if (message.length <= 120) message else message.take(120) + "…"

internal fun isRingExpired(ringUntilMillis: Long?, now: Long): Boolean =
    ringUntilMillis != null && ringUntilMillis < now

internal fun createPreClaimAlarmNotification(
    context: Context,
    eventId: String,
    title: String
): Notification {
    val projectsIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        "pre-claim:$eventId".hashCode(),
        projectsIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(context, NotificationChannels.QUIET_CHANNEL_ID)
        .applyDenDenBranding(context)
        .setContentTitle(title)
        .setContentText(context.getString(R.string.alarm_checking_status))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setContentIntent(contentIntent)
        .build()
}

internal suspend fun claimAndActivateAlarm(
    claimRinging: suspend () -> Boolean,
    requestStillActive: () -> Boolean,
    activate: () -> Unit
): Boolean {
    if (!claimRinging()) return false
    if (!requestStillActive()) return false
    activate()
    return true
}

internal fun alarmEventFromIntent(intent: Intent, eventId: String, receivedAt: Long): DenDenEvent =
    DenDenEvent(
        eventId = eventId,
        action = "ring",
        kind = "alarm",
        state = "pending",
        title = intent.getStringExtra("title"),
        message = intent.getStringExtra("message"),
        durationSeconds = intent.getIntExtra("duration", AlarmController.DEFAULT_DURATION),
        tagsJson = intent.getStringExtra("tags"),
        channelId = intent.getStringExtra("channelId") ?: "default",
        channelName = intent.getStringExtra("channelName"),
        ringUntilMillis = intent.getLongExtra("ringUntilMillis", -1L).takeIf { it > 0 },
        receivedAt = intent.getLongExtra("receivedAt", receivedAt),
        issuedAtMillis = intent.getLongExtra("issuedAtMillis", receivedAt)
    )

class DefaultAudioPlayer(private val context: Context) : AudioPlayer {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var mediaPlayer: MediaPlayer? = null
    override var isPlaying = false

    override fun play() {
        stop()
        val mode = readAlarmOutputMode(context)
        if (!alarmOutputPolicy(mode, audioManager.ringerMode, false).playSound) return
        val selectedUri = readAlarmRingtoneUri(context)
        val defaultUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        for (uri in listOfNotNull(selectedUri, defaultUri).distinct()) {
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(alarmAudioUsage(mode))
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(context, uri)
                player.isLooping = true
                player.prepare()
                player.start()
                mediaPlayer = player
                isPlaying = true
                return
            } catch (_: Exception) {
                player.release()
            }
        }
        isPlaying = false
    }

    override fun stop() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
    }
}

class DefaultVibratorController(private val context: Context) : VibratorController {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)!!
    override var isVibrating = false

    @Suppress("DEPRECATION")
    override fun vibrate() {
        cancel()
        val mode = readAlarmOutputMode(context)
        val policy = alarmOutputPolicy(
            mode,
            audioManager.ringerMode,
            audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)
        )
        if (!policy.vibrate) return
        try {
            val pattern = readAlarmVibrationPattern(context)
            val attributes = AudioAttributes.Builder()
                .setUsage(alarmAudioUsage(mode))
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(VibrationEffect.createWaveform(pattern.timings, 0), attributes)
            isVibrating = true
        } catch (_: Exception) {
            isVibrating = false
        }
    }

    override fun cancel() {
        isVibrating = false
        vibrator.cancel()
    }
}

internal data class AlarmOutputPolicy(val playSound: Boolean, val vibrate: Boolean)

enum class AlarmOutputMode {
    FOLLOW_SYSTEM,
    RING_AND_VIBRATE,
    RING_ONLY,
    VIBRATE_ONLY,
    SILENT;

    companion object {
        fun fromStorage(value: String?): AlarmOutputMode =
            entries.firstOrNull { it.name == value } ?: FOLLOW_SYSTEM
    }
}

enum class AlarmVibrationPattern(
    internal val timings: LongArray
) {
    GENTLE(longArrayOf(0, 250, 750)),
    STANDARD(longArrayOf(0, 1_000, 500)),
    URGENT(longArrayOf(0, 300, 150, 300, 600)),
    LONG(longArrayOf(0, 2_000, 800));

    companion object {
        fun fromStorage(value: String?): AlarmVibrationPattern =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

private const val ALARM_OUTPUT_PREFS = "app_settings"
private const val ALARM_OUTPUT_MODE_KEY = "alarm_output_mode"
private const val ALARM_RINGTONE_URI_KEY = "alarm_ringtone_uri"
private const val ALARM_VIBRATION_PATTERN_KEY = "alarm_vibration_pattern"

fun readAlarmOutputMode(context: Context): AlarmOutputMode = AlarmOutputMode.fromStorage(
    context.getSharedPreferences(ALARM_OUTPUT_PREFS, Context.MODE_PRIVATE)
        .getString(ALARM_OUTPUT_MODE_KEY, null)
)

fun writeAlarmOutputMode(context: Context, mode: AlarmOutputMode) {
    context.getSharedPreferences(ALARM_OUTPUT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(ALARM_OUTPUT_MODE_KEY, mode.name)
        .apply()
}

fun readAlarmRingtoneUri(context: Context): android.net.Uri? =
    context.getSharedPreferences(ALARM_OUTPUT_PREFS, Context.MODE_PRIVATE)
        .getString(ALARM_RINGTONE_URI_KEY, null)
        ?.let(android.net.Uri::parse)

fun writeAlarmRingtoneUri(context: Context, uri: android.net.Uri?) {
    val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    context.getSharedPreferences(ALARM_OUTPUT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .apply {
            if (uri == null || uri == defaultUri) remove(ALARM_RINGTONE_URI_KEY)
            else putString(ALARM_RINGTONE_URI_KEY, uri.toString())
        }
        .apply()
}

fun readAlarmVibrationPattern(context: Context): AlarmVibrationPattern = AlarmVibrationPattern.fromStorage(
    context.getSharedPreferences(ALARM_OUTPUT_PREFS, Context.MODE_PRIVATE)
        .getString(ALARM_VIBRATION_PATTERN_KEY, null)
)

fun writeAlarmVibrationPattern(context: Context, pattern: AlarmVibrationPattern) {
    context.getSharedPreferences(ALARM_OUTPUT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(ALARM_VIBRATION_PATTERN_KEY, pattern.name)
        .apply()
}

fun previewAlarmVibration(context: Context, pattern: AlarmVibrationPattern) {
    try {
        context.getSystemService(Vibrator::class.java)?.apply {
            cancel()
            vibrate(VibrationEffect.createWaveform(pattern.timings, -1))
        }
    } catch (_: Exception) {
    }
}

internal fun alarmAudioUsage(mode: AlarmOutputMode): Int =
    if (mode == AlarmOutputMode.FOLLOW_SYSTEM) {
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    } else {
        AudioAttributes.USAGE_ALARM
    }

internal fun alarmOutputPolicy(
    mode: AlarmOutputMode,
    ringerMode: Int,
    vibrateWhenRinging: Boolean
): AlarmOutputPolicy = when (mode) {
    AlarmOutputMode.FOLLOW_SYSTEM -> when (ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> AlarmOutputPolicy(playSound = true, vibrate = vibrateWhenRinging)
        AudioManager.RINGER_MODE_VIBRATE -> AlarmOutputPolicy(playSound = false, vibrate = true)
        else -> AlarmOutputPolicy(playSound = false, vibrate = false)
    }
    AlarmOutputMode.RING_AND_VIBRATE -> AlarmOutputPolicy(playSound = true, vibrate = true)
    AlarmOutputMode.RING_ONLY -> AlarmOutputPolicy(playSound = true, vibrate = false)
    AlarmOutputMode.VIBRATE_ONLY -> AlarmOutputPolicy(playSound = false, vibrate = true)
    AlarmOutputMode.SILENT -> AlarmOutputPolicy(playSound = false, vibrate = false)
}

class SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
