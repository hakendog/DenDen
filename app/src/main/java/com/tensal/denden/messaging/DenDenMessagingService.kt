package com.tensal.denden.messaging

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
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
fun notificationGroupName(event: DenDenEvent): String = event.channelDisplayName

internal fun clearReadChannelNotifications(context: Context, channelId: String) {
    runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { shouldClearReadChannelNotification(it.notification.group, it.notification.flags, channelId) }
            .forEach { notification ->
                if (notification.tag == null) manager.cancel(notification.id)
                else manager.cancel(notification.tag, notification.id)
            }
    }
}

internal fun shouldClearReadChannelNotification(group: String?, flags: Int, channelId: String): Boolean {
    val persistentFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
    return group == notificationGroupKey(channelId) && flags and persistentFlags == 0
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
