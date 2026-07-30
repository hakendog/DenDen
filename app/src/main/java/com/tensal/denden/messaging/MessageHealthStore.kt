package com.tensal.denden.messaging

import android.content.Context

data class MessageHealthSnapshot(
    val lastReceivedAtMillis: Long = 0,
    val lastAction: String? = null,
    val lastDegradedReason: String? = null,
    val lastNotificationVisible: Boolean = false
)

interface MessageHealthStore {
    fun record(
        receivedAtMillis: Long,
        action: String?,
        degradedReason: String?,
        notificationVisible: Boolean
    )

    fun snapshot(): MessageHealthSnapshot
}

class SharedPrefsMessageHealthStore(context: Context) : MessageHealthStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun record(
        receivedAtMillis: Long,
        action: String?,
        degradedReason: String?,
        notificationVisible: Boolean
    ) {
        prefs.edit()
            .putLong(KEY_RECEIVED_AT, receivedAtMillis)
            .putString(KEY_ACTION, action)
            .putString(KEY_DEGRADED_REASON, degradedReason)
            .putBoolean(KEY_NOTIFICATION_VISIBLE, notificationVisible)
            .apply()
    }

    override fun snapshot(): MessageHealthSnapshot = MessageHealthSnapshot(
        lastReceivedAtMillis = prefs.getLong(KEY_RECEIVED_AT, 0),
        lastAction = prefs.getString(KEY_ACTION, null),
        lastDegradedReason = prefs.getString(KEY_DEGRADED_REASON, null),
        lastNotificationVisible = prefs.getBoolean(KEY_NOTIFICATION_VISIBLE, false)
    )

    private companion object {
        const val PREFS_NAME = "message_health"
        const val KEY_RECEIVED_AT = "last_received_at"
        const val KEY_ACTION = "last_action"
        const val KEY_DEGRADED_REASON = "last_degraded_reason"
        const val KEY_NOTIFICATION_VISIBLE = "last_notification_visible"
    }
}
