package com.tensal.denden.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tensal.denden.R
import com.tensal.denden.withSelectedAppLanguage

object NotificationChannels {
    const val ALARM_CHANNEL_ID = "denden_alarm_v2"
    const val GENERAL_CHANNEL_ID = "denden_general"
    const val QUIET_CHANNEL_ID = "denden_quiet"
    private const val LEGACY_ALARM_CHANNEL_ID = "denden_alarm"

    fun create(context: Context) {
        val strings = context.withSelectedAppLanguage()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmChannel = NotificationChannel(
            ALARM_CHANNEL_ID,
            strings.getString(R.string.notification_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = strings.getString(R.string.notification_channel_alarm_description)
            setBypassDnd(manager.isNotificationPolicyAccessGranted)
            setSound(null, null)
            enableVibration(false)
        }
        val generalChannel = NotificationChannel(
            GENERAL_CHANNEL_ID,
            strings.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = strings.getString(R.string.notification_channel_messages_description)
        }
        val quietChannel = NotificationChannel(
            QUIET_CHANNEL_ID,
            strings.getString(R.string.notification_channel_quiet),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = strings.getString(R.string.notification_channel_quiet_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(alarmChannel)
        manager.deleteNotificationChannel(LEGACY_ALARM_CHANNEL_ID)
        manager.createNotificationChannel(generalChannel)
        manager.createNotificationChannel(quietChannel)
    }
}
