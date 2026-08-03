package com.tensal.denden.messaging

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
import com.tensal.denden.notification.NotificationDisplayMode
import com.tensal.denden.setup.DirectPairingStore
import com.tensal.denden.setup.PairingState
import com.tensal.denden.setup.scheduleDirectPairing
import com.tensal.denden.setup.defaultFirebaseMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class DenDenMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                receiveDirectMessage(this@DenDenMessagingService, message.data, message.priority == RemoteMessage.PRIORITY_HIGH)
            }
        }.onFailure {
            SharedPrefsMessageHealthStore(this).record(
                System.currentTimeMillis(),
                null,
                "message_rejected",
                false
            )
        }
    }

    override fun onNewToken(token: String) {
        val snapshot = DirectPairingStore(this).snapshot()
        if ((snapshot.state == PairingState.ACTIVE || snapshot.state == PairingState.PENDING) && defaultFirebaseMatches(snapshot)) {
            scheduleDirectPairing(this, snapshot.localPairingRevision)
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        SharedPrefsMessageHealthStore(this).record(System.currentTimeMillis(), null, "messages_deleted", false)
    }

    companion object {
        const val STOP_ALARM_ACTION = "com.tensal.denden.action.STOP_ALARM"
    }
}

fun notificationGroupKey(channelId: String): String = "denden.channel.$channelId"
fun notificationGroupSummaryId(channelId: String): Int = "summary:${notificationGroupKey(channelId)}".hashCode()
fun notificationLatestPerChannelId(channelId: String): Int = "latest:${notificationGroupKey(channelId)}".hashCode()
fun notificationGroupName(event: DenDenEvent): String = event.channelDisplayName

private val latestNotificationId = "denden.latest".hashCode()
internal const val STANDARD_NOTIFICATION_GROUP_KEY = "denden.standard"
internal val STANDARD_NOTIFICATION_GROUP_SUMMARY_ID = "summary:$STANDARD_NOTIFICATION_GROUP_KEY".hashCode()
internal const val NOTIFICATION_CHANNEL_ID_EXTRA = "denden.notification.channel_id"

internal enum class NotificationReplacementScope { NONE, CHANNEL, ALL }

internal data class NotificationDisplayPlan(
    val notificationId: Int,
    val groupKey: String?,
    val summaryId: Int?,
    val replacementScope: NotificationReplacementScope
)

internal fun notificationDisplayPlan(
    event: DenDenEvent,
    mode: NotificationDisplayMode
): NotificationDisplayPlan = when (mode) {
    NotificationDisplayMode.FULL -> NotificationDisplayPlan(
        event.eventId.hashCode(),
        notificationGroupKey(event.channelId),
        notificationGroupSummaryId(event.channelId),
        NotificationReplacementScope.NONE
    )
    NotificationDisplayMode.STANDARD -> NotificationDisplayPlan(
        notificationLatestPerChannelId(event.channelId),
        STANDARD_NOTIFICATION_GROUP_KEY,
        STANDARD_NOTIFICATION_GROUP_SUMMARY_ID,
        NotificationReplacementScope.CHANNEL
    )
    NotificationDisplayMode.COMPACT -> NotificationDisplayPlan(
        latestNotificationId,
        null,
        null,
        NotificationReplacementScope.ALL
    )
}

internal fun clearReadChannelNotifications(context: Context, channelId: String) {
    clearReplaceableNotifications(context, channelId)
}

internal fun clearReplaceableNotifications(context: Context, channelId: String? = null) {
    runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        val activeNotifications = manager.activeNotifications.toList()
        val notificationsToClear = activeNotifications
            .filter {
                shouldClearReplaceableNotification(
                    it.notification.group,
                    it.notification.flags,
                    channelId,
                    it.notification.extras?.getString(NOTIFICATION_CHANNEL_ID_EXTRA)
                )
            }
        val clearedKeys = notificationsToClear.mapTo(mutableSetOf()) { it.tag to it.id }
        notificationsToClear.forEach { notification ->
            if (notification.tag == null) manager.cancel(notification.id)
            else manager.cancel(notification.tag, notification.id)
        }
        if (channelId != null) {
            val standardChildRemains = activeNotifications.any {
                it.notification.group == STANDARD_NOTIFICATION_GROUP_KEY &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                    (it.tag to it.id) !in clearedKeys
            }
            if (!standardChildRemains) manager.cancel(STANDARD_NOTIFICATION_GROUP_SUMMARY_ID)
        }
    }
}

internal fun shouldClearReadChannelNotification(
    group: String?,
    flags: Int,
    channelId: String,
    notificationChannelId: String? = null
): Boolean = shouldClearReplaceableNotification(group, flags, channelId, notificationChannelId)

private fun shouldClearReplaceableNotification(
    group: String?,
    flags: Int,
    channelId: String?,
    notificationChannelId: String?
): Boolean {
    val persistentFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
    if (flags and persistentFlags != 0) return false
    return channelId == null || group == notificationGroupKey(channelId) || notificationChannelId == channelId
}

fun notificationChannelId(event: DenDenEvent): String = when {
    event.action == "ring" || event.action == "stop" -> NotificationChannels.ALARM_CHANNEL_ID
    event.notificationMode == "quiet" -> NotificationChannels.QUIET_CHANNEL_ID
    else -> NotificationChannels.GENERAL_CHANNEL_ID
}

fun notificationVisibilityAvailable(globalAvailable: Boolean, channelEnabled: Boolean): Boolean =
    globalAvailable && channelEnabled

fun shouldPostStandaloneNotification(event: DenDenEvent, isHighPriority: Boolean): Boolean =
    event.action != "ring" || !isHighPriority
