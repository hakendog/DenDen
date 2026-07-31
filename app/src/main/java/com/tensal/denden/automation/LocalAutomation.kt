package com.tensal.denden.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tensal.denden.R
import com.tensal.denden.codePointLength
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.DirectEventCommit
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.messaging.dispatchDirectEvent
import com.tensal.denden.notification.NotificationChannels
import com.tensal.denden.withSelectedAppLanguage
import java.text.DateFormat
import java.util.Date
import java.util.UUID

enum class LocalAutomationMode(val value: String) {
    QUIET("quiet"),
    NOTIFY("notify"),
    RING("ring");

    companion object {
        fun fromValue(value: String?): LocalAutomationMode = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unsupported automation mode")
    }
}

enum class LocalAutomationSource(val value: String, val channelId: String) {
    BIXBY("bixby", BIXBY_CHANNEL_ID),
    TASKER("tasker", TASKER_CHANNEL_ID)
}

data class LocalAutomationRequest(
    val source: LocalAutomationSource,
    val mode: LocalAutomationMode,
    val title: String?,
    val message: String?,
    val durationSeconds: Int = DEFAULT_RING_DURATION_SECONDS,
    val channelName: String? = null
) {
    init {
        require(title == null || title.codePointLength() <= 200) { "Title is too long" }
        require(message == null || message.codePointLength() <= 1000) { "Message is too long" }
        require(durationSeconds in 0..DenDenEvent.MAX_DURATION) { "Duration must be between 0 and 300 seconds" }
    }
}

data class LocalAutomationResult(val eventId: String, val degradedReason: String?)

fun taskerAutomationRequest(
    mode: String?,
    title: String?,
    message: String?,
    durationSeconds: String?,
    channelName: String? = null
): LocalAutomationRequest {
    val normalizedTitle = title?.takeIf(String::isNotBlank)
    val normalizedMessage = message?.takeIf(String::isNotBlank)
    val duration = durationSeconds?.takeIf(String::isNotBlank)?.toIntOrNull()
        ?: if (durationSeconds.isNullOrBlank()) DEFAULT_RING_DURATION_SECONDS
        else throw IllegalArgumentException("Duration must be a number")
    return LocalAutomationRequest(
        source = LocalAutomationSource.TASKER,
        mode = LocalAutomationMode.fromValue(mode),
        title = normalizedTitle,
        message = normalizedMessage,
        durationSeconds = duration,
        channelName = channelName
    )
}

fun bixbyAutomationRequest(
    context: Context,
    mode: LocalAutomationMode,
    nowMillis: Long = System.currentTimeMillis()
): LocalAutomationRequest {
    val strings = context.withSelectedAppLanguage()
    val locale = strings.resources.configuration.locales[0]
    val triggeredAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
        .format(Date(nowMillis))
    val messageRes = when (mode) {
        LocalAutomationMode.QUIET -> R.string.bixby_quiet_message
        LocalAutomationMode.NOTIFY -> R.string.bixby_notify_message
        LocalAutomationMode.RING -> R.string.bixby_ring_message
    }
    return LocalAutomationRequest(
        source = LocalAutomationSource.BIXBY,
        mode = mode,
        title = strings.getString(R.string.bixby_event_title),
        message = strings.getString(messageRes, triggeredAt),
        channelName = strings.getString(R.string.bixby_channel_name)
    )
}

suspend fun triggerLocalAutomation(
    context: Context,
    request: LocalAutomationRequest,
    nowMillis: Long = System.currentTimeMillis(),
    eventId: String = "local-${request.source.value}-${UUID.randomUUID()}"
): LocalAutomationResult {
    NotificationChannels.create(context)
    val dao = EventDatabase.getInstance(context).directMessageDao()
    val expiresAt = nowMillis + LOCAL_EVENT_LIFETIME_MILLIS
    val event = DenDenEvent(
        eventId = eventId,
        action = if (request.mode == LocalAutomationMode.RING) "ring" else "notify",
        title = request.title,
        message = request.message,
        durationSeconds = request.durationSeconds.takeIf { request.mode == LocalAutomationMode.RING },
        channelId = request.source.channelId,
        channelName = request.channelName,
        notificationMode = if (request.mode == LocalAutomationMode.QUIET) "quiet" else "normal",
        ringUntilMillis = (nowMillis + RING_START_WINDOW_MILLIS).takeIf { request.mode == LocalAutomationMode.RING },
        kind = if (request.mode == LocalAutomationMode.RING) "alarm" else "notification",
        state = "pending",
        receivedAt = nowMillis,
        issuedAtMillis = nowMillis
    )
    when (dao.commitEvent("local:${request.source.value}", eventId, eventId, expiresAt, event)) {
        DirectEventCommit.INSERTED, DirectEventCommit.DUPLICATE -> Unit
        DirectEventCommit.CONFLICT, DirectEventCommit.STOPPED -> error("Local event was rejected")
    }
    val pending = dao.pendingAlert(eventId)
        ?: return LocalAutomationResult(eventId, null)
    val evidence = runCatching {
        dispatchDirectEvent(context, event, pending.expiresAtMillis, nowMillis, request.mode == LocalAutomationMode.RING, dao)
    }.getOrElse {
        enqueueLocalAutomationSideEffect(context, eventId)
        return LocalAutomationResult(eventId, "dispatch_queued")
    }
    dao.deleteExpiredReceipts(nowMillis)
    return LocalAutomationResult(eventId, evidence.degradedReason)
}

fun isLocalAutomationEvent(event: DenDenEvent): Boolean =
    event.channelId == BIXBY_CHANNEL_ID || event.channelId == TASKER_CHANNEL_ID

class LocalAutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val eventId = inputData.getString(EVENT_ID_KEY) ?: return Result.failure()
        val dao = EventDatabase.getInstance(applicationContext).directMessageDao()
        val pending = dao.pendingAlert(eventId) ?: return Result.success()
        val event = dao.event(eventId)?.takeIf(::isLocalAutomationEvent) ?: return Result.success()
        dispatchDirectEvent(
            applicationContext,
            event,
            pending.expiresAtMillis,
            System.currentTimeMillis(),
            event.action == "ring",
            dao
        )
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    )

    companion object {
        const val EVENT_ID_KEY = "event_id"
    }
}

private fun enqueueLocalAutomationSideEffect(context: Context, eventId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "denden-local-automation:$eventId",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<LocalAutomationWorker>()
            .setInputData(Data.Builder().putString(LocalAutomationWorker.EVENT_ID_KEY, eventId).build())
            .build()
    )
}

fun reconcileLocalAutomation(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        LOCAL_RECONCILIATION_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<LocalAutomationReconciliationWorker>().build()
    )
}

class LocalAutomationReconciliationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val dao = EventDatabase.getInstance(applicationContext).directMessageDao()
        val now = System.currentTimeMillis()
        dao.pendingAlerts().forEach { pending ->
            dao.event(pending.eventId)?.takeIf(::isLocalAutomationEvent)?.let { event ->
                dispatchDirectEvent(applicationContext, event, pending.expiresAtMillis, now, event.action == "ring", dao)
            }
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    )
}

const val DEFAULT_RING_DURATION_SECONDS = 30
private const val BIXBY_CHANNEL_ID = "local-bixby"
private const val TASKER_CHANNEL_ID = "local-tasker"
private const val LOCAL_EVENT_LIFETIME_MILLIS = 5 * 60 * 1000L
private const val RING_START_WINDOW_MILLIS = 60 * 1000L
private const val LOCAL_RECONCILIATION_WORK = "denden-local-automation-reconciliation"
