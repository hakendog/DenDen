package com.tensal.denden.messaging

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tensal.denden.MainActivity
import com.tensal.denden.alarm.AlarmService
import com.tensal.denden.alarm.AlarmRuntime
import com.tensal.denden.alarm.isActiveFor
import com.tensal.denden.automation.isLocalAutomationEvent
import com.tensal.denden.branding.DirectBrandStore
import com.tensal.denden.branding.applyDenDenBranding
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.DirectBrandCommit
import com.tensal.denden.data.DirectEventCommit
import com.tensal.denden.data.DirectStopCommit
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.data.StopTombstone
import com.tensal.denden.notification.NotificationChannels
import com.tensal.denden.setup.DirectPairingStore
import com.tensal.denden.setup.PairingState
import com.tensal.denden.setup.directRuntimeMutex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

data class DirectReceiveResult(
    val accepted: Boolean,
    val action: String? = null,
    val duplicate: Boolean = false,
    val degradedReason: String? = null
)

class DirectMessageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = directRuntimeMutex.withLock { doLockedWork() }

    private suspend fun doLockedWork(): Result = runCatching {
        val pairingId = inputData.getString(PAIRING_ID_KEY) ?: return Result.failure()
        val revision = inputData.getLong(REVISION_KEY, -1)
        val snapshot = DirectPairingStore(applicationContext).snapshot()
        if (snapshot.state != PairingState.ACTIVE ||
            snapshot.localPairingRevision != revision ||
            snapshot.active?.pairingId != pairingId
        ) return Result.success()
        val dao = EventDatabase.getInstance(applicationContext).directMessageDao()
        inputData.getString(STOP_EVENT_ID_KEY)?.let { eventId ->
            if (dao.tombstone(eventId)?.dispatchPending == true) {
                stopDirectAlarm(applicationContext, eventId)
                dao.markStopDispatched(eventId)
            }
        }
        inputData.getString(EVENT_ID_KEY)?.let { eventId ->
            val pending = dao.pendingAlert(eventId)
            val event = dao.event(eventId)
            if (pending?.state == "pending" && event != null) {
                dispatchDirectEvent(
                    applicationContext,
                    event,
                    pending.expiresAtMillis,
                    System.currentTimeMillis(),
                    inputData.getBoolean(HIGH_PRIORITY_KEY, false),
                    dao
                )
            }
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    )

    companion object {
        const val EVENT_ID_KEY = "event_id"
        const val STOP_EVENT_ID_KEY = "stop_event_id"
        const val PAIRING_ID_KEY = "pairing_id"
        const val REVISION_KEY = "local_pairing_revision"
        const val HIGH_PRIORITY_KEY = "high_priority"
    }
}

class DirectMessageReconciliationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = directRuntimeMutex.withLock { doLockedWork() }

    private suspend fun doLockedWork(): Result = runCatching {
        val snapshot = DirectPairingStore(applicationContext).snapshot()
        val pairingId = snapshot.active?.pairingId ?: return Result.success()
        if (snapshot.state != PairingState.ACTIVE) return Result.success()
        val dao = EventDatabase.getInstance(applicationContext).directMessageDao()
        val now = System.currentTimeMillis()
        dao.pendingStops(now).forEach { tombstone ->
            runCatching {
                stopDirectAlarm(applicationContext, tombstone.eventId)
                dao.markStopDispatched(tombstone.eventId)
            }.onFailure {
                enqueueDirectSideEffect(applicationContext, pairingId, snapshot.localPairingRevision, stopEventId = tombstone.eventId)
            }
        }
        dao.pendingAlerts().forEach { pending ->
            dao.event(pending.eventId)?.let { event ->
                if (isLocalAutomationEvent(event)) return@let
                // 程序復原時沒有原始 FCM 優先級證據，不得猜成高優先級並提升為響鈴。
                dispatchDirectEvent(applicationContext, event, pending.expiresAtMillis, now, false, dao)
            }
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < MAX_RECONCILIATION_ATTEMPTS - 1) Result.retry() else Result.failure() }
    )

    private companion object {
        const val MAX_RECONCILIATION_ATTEMPTS = 5
    }
}

suspend fun receiveDirectMessage(
    context: Context,
    data: Map<String, String>,
    highPriority: Boolean,
    nowMillis: Long = System.currentTimeMillis()
): DirectReceiveResult = directRuntimeMutex.withLock {
    receiveDirectMessageLocked(context, data, highPriority, nowMillis)
}

private suspend fun receiveDirectMessageLocked(
    context: Context,
    data: Map<String, String>,
    highPriority: Boolean,
    nowMillis: Long
): DirectReceiveResult {
    val store = DirectPairingStore(context)
    val snapshot = store.snapshot()
    val active = snapshot.active ?: return DirectReceiveResult(false)
    val keys = snapshot.activeKeys() ?: return DirectReceiveResult(false)
    if (snapshot.state != PairingState.ACTIVE) return DirectReceiveResult(false)
    val decoded = runCatching { decodeDirectFcmMessage(data, keys, nowMillis) }
        .getOrElse { return DirectReceiveResult(false) }
    val dao = EventDatabase.getInstance(context).directMessageDao()
    val digest = canonicalDigest(data)
    var duplicate = false
    var visible = false
    var degradedReason: String? = null
    val action = when (decoded) {
        is DecodedDirectMessage.Event -> {
            val commit = dao.commitEvent(
                active.pairingId,
                decoded.messageId,
                digest,
                decoded.expiresAtMillis,
                decoded.event
            )
            duplicate = commit == DirectEventCommit.DUPLICATE
            if (commit == DirectEventCommit.CONFLICT || commit == DirectEventCommit.STOPPED) return DirectReceiveResult(false)
            if (!pairingStillCurrent(store, snapshot.localPairingRevision, active.pairingId)) return DirectReceiveResult(false)
            dao.pendingAlert(decoded.event.eventId)?.takeIf { it.state == "pending" }?.let { pending ->
                runCatching {
                    dispatchDirectEvent(context, decoded.event, pending.expiresAtMillis, nowMillis, highPriority, dao)
                }.onSuccess {
                    visible = it.notificationVisible
                    degradedReason = it.degradedReason
                }.onFailure {
                    enqueueDirectSideEffect(
                        context,
                        active.pairingId,
                        snapshot.localPairingRevision,
                        eventId = decoded.event.eventId,
                        highPriority = highPriority
                    )
                }
            }
            decoded.event.action
        }
        is DecodedDirectMessage.Stop -> {
            val commit = dao.commitStop(
                StopTombstone(
                    decoded.targetEventId,
                    active.pairingId,
                    decoded.messageId,
                    nowMillis,
                    maxOf(decoded.expiresAtMillis, nowMillis + STOP_TOMBSTONE_RETENTION_MILLIS)
                ),
                digest
            )
            if (commit == DirectStopCommit.CONFLICT) return DirectReceiveResult(false)
            duplicate = commit == DirectStopCommit.DUPLICATE
            if (!pairingStillCurrent(store, snapshot.localPairingRevision, active.pairingId)) return DirectReceiveResult(false)
            runCatching {
                stopDirectAlarm(context, decoded.targetEventId)
                dao.markStopDispatched(decoded.targetEventId)
            }.onFailure {
                enqueueDirectSideEffect(
                    context,
                    active.pairingId,
                    snapshot.localPairingRevision,
                    stopEventId = decoded.targetEventId
                )
            }
            "stop"
        }
        is DecodedDirectMessage.Brand -> {
            if (!pairingStillCurrent(store, snapshot.localPairingRevision, active.pairingId)) return DirectReceiveResult(false)
            BRAND_RECEIVE_MUTEX.withLock {
                val existing = dao.receipt(active.pairingId, decoded.messageId)
                if (existing != null) {
                    if (existing.kind != "brand" || existing.contentDigest != digest) return DirectReceiveResult(false)
                    duplicate = true
                } else {
                    val brandStore = DirectBrandStore(context)
                    val completed = brandStore.accept(active.pairingId, decoded.kind, decoded.payload, nowMillis)
                    when (dao.commitBrandReceipt(
                        active.pairingId,
                        decoded.messageId,
                        digest,
                        nowMillis,
                        decoded.expiresAtMillis
                    )) {
                        DirectBrandCommit.CONFLICT -> return DirectReceiveResult(false)
                        DirectBrandCommit.DUPLICATE -> duplicate = true
                        DirectBrandCommit.INSERTED -> Unit
                    }
                    if (completed) {
                        context.sendBroadcast(Intent(DirectBrandStore.ACTION_BRAND_CHANGED).setPackage(context.packageName))
                    }
                }
            }
            "brand"
        }
    }
    SharedPrefsMessageHealthStore(context).record(nowMillis, action, degradedReason, visible)
    dao.deleteExpiredReceipts(nowMillis)
    dao.deleteExpiredTombstones(nowMillis)
    dao.deleteExpiredAlerts(nowMillis)
    return DirectReceiveResult(true, action, duplicate, degradedReason)
}

private fun pairingStillCurrent(store: DirectPairingStore, revision: Long, pairingId: String): Boolean {
    val current = store.snapshot()
    return current.state == PairingState.ACTIVE &&
        current.localPairingRevision == revision &&
        current.active?.pairingId == pairingId
}

internal suspend fun dispatchDirectEvent(
    context: Context,
    event: DenDenEvent,
    expiresAtMillis: Long,
    now: Long,
    highPriority: Boolean,
    dao: com.tensal.denden.data.DirectMessageDao
): DispatchEvidence {
    val eventId = event.eventId
    val expired = expiresAtMillis < now || (event.action == "ring" && event.ringUntilMillis?.let { it < now } == true)
    if (expired) {
        dao.finishAlert(eventId, "expired", now, "expired")
        return DispatchEvidence(false, "expired")
    }
    val visible = if (shouldPostStandaloneNotification(event, highPriority)) {
        showDirectNotification(context, event)
    } else {
        directNotificationAvailable(context, event)
    }
    if (!visible) {
        dao.finishAlert(eventId, "degraded", now, "notification_unavailable")
        return DispatchEvidence(false, "notification_unavailable")
    }
    if (event.action == "ring" && !highPriority) {
        dao.finishAlert(eventId, "degraded", now, "degraded_priority")
        return DispatchEvidence(true, "degraded_priority")
    }
    if (event.action == "ring") {
        startDirectAlarm(context, event)
    } else {
        dao.finishAlert(eventId, "executed", now, null)
    }
    return DispatchEvidence(true, null)
}

private fun showDirectNotification(context: Context, event: DenDenEvent): Boolean {
    if (!directNotificationAvailable(context, event)) return false
    val contentIntent = directNotificationContentIntent(context, event)
    val notification = createDirectNotification(context, event, contentIntent)
    val summary = createDirectNotificationGroupSummary(context, event, contentIntent)
    return try {
        NotificationManagerCompat.from(context).apply {
            notify(event.eventId.hashCode(), notification)
            notify(notificationGroupSummaryId(event.channelId), summary)
        }
        true
    } catch (_: SecurityException) {
        false
    }
}

private fun directNotificationContentIntent(context: Context, event: DenDenEvent): PendingIntent =
    PendingIntent.getActivity(
        context,
        event.eventId.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_CHANNEL_ID, event.channelId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

internal fun createDirectNotification(
    context: Context,
    event: DenDenEvent,
    contentIntent: PendingIntent = directNotificationContentIntent(context, event)
): Notification = NotificationCompat.Builder(context, notificationChannelId(event))
    .applyDenDenBranding(context)
    .setContentTitle(event.title ?: "DenDen")
    .setContentText(event.computedSummary)
    .setSubText(event.channelDisplayName)
    .setContentIntent(contentIntent)
    .setAutoCancel(true)
    .setGroup(notificationGroupKey(event.channelId))
    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
    .setSilent(event.notificationMode == "quiet")
    .setPriority(if (event.action == "ring") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
    .build()

internal fun createDirectNotificationGroupSummary(
    context: Context,
    event: DenDenEvent,
    contentIntent: PendingIntent = directNotificationContentIntent(context, event)
): Notification = NotificationCompat.Builder(context, notificationChannelId(event))
    .applyDenDenBranding(context)
    .setContentTitle(notificationGroupName(event))
    .setContentText(event.computedSummary)
    .setContentIntent(contentIntent)
    .setAutoCancel(true)
    .setGroup(notificationGroupKey(event.channelId))
    .setGroupSummary(true)
    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
    .setSilent(true)
    .setPriority(NotificationCompat.PRIORITY_LOW)
    .build()

private fun directNotificationAvailable(context: Context, event: DenDenEvent): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    val globallyEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelId = notificationChannelId(event)
    val manager = context.getSystemService(NotificationManager::class.java)
    val channelEnabled = manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    return notificationVisibilityAvailable(globallyEnabled, channelEnabled)
}

private fun startDirectAlarm(context: Context, event: DenDenEvent) {
    val intent = Intent(context, AlarmService::class.java).apply {
        putExtra("action", event.action)
        putExtra("eventId", event.eventId)
        putExtra("title", event.title)
        putExtra("message", event.message)
        putExtra("duration", event.durationSeconds)
        putExtra("channelId", event.channelId)
        putExtra("channelName", event.channelName)
        putExtra("tags", event.tagsJson)
        putExtra("receivedAt", event.receivedAt)
        putExtra("issuedAtMillis", event.issuedAtMillis)
        event.ringUntilMillis?.let { putExtra("ringUntilMillis", it) }
    }
    context.startForegroundService(intent)
}

private fun stopDirectAlarm(context: Context, eventId: String) {
    if (!AlarmRuntime.snapshot.value.isActiveFor(eventId)) return
    context.startService(Intent(context, AlarmService::class.java).apply {
        action = DenDenMessagingService.STOP_ALARM_ACTION
        putExtra("eventId", eventId)
        putExtra("remote_stop", true)
    })
}

internal data class DispatchEvidence(val notificationVisible: Boolean, val degradedReason: String?)

private val BRAND_RECEIVE_MUTEX = Mutex()

private fun enqueueDirectSideEffect(
    context: Context,
    pairingId: String,
    revision: Long,
    eventId: String? = null,
    stopEventId: String? = null,
    highPriority: Boolean = false
) {
    val data = Data.Builder()
        .putString(DirectMessageWorker.PAIRING_ID_KEY, pairingId)
        .putLong(DirectMessageWorker.REVISION_KEY, revision)
        .putBoolean(DirectMessageWorker.HIGH_PRIORITY_KEY, highPriority)
        .apply {
            eventId?.let { putString(DirectMessageWorker.EVENT_ID_KEY, it) }
            stopEventId?.let { putString(DirectMessageWorker.STOP_EVENT_ID_KEY, it) }
        }
        .build()
    val identity = eventId?.let { "alert:$it" } ?: "stop:$stopEventId"
    WorkManager.getInstance(context).enqueueUniqueWork(
        "denden-direct-side-effect:$identity",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<DirectMessageWorker>().setInputData(data).build()
    )
}

fun reconcileDirectMessages(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "denden-direct-reconciliation",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<DirectMessageReconciliationWorker>().build()
    )
}

private fun canonicalDigest(data: Map<String, String>): String {
    val canonical = data.entries.sortedBy { it.key }.joinToString("\n") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private const val STOP_TOMBSTONE_RETENTION_MILLIS = 7 * 60 * 1000L
