package com.tensal.denden.messaging

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectNotificationGroupingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun childAndSummaryUseTheLogicalChannelGroup() {
        NotificationChannels.create(context)
        val ops = event("ops", "Operations")
        val product = event("product", "Product")
        val child = createDirectNotification(context, ops)
        val summary = createDirectNotificationGroupSummary(context, ops)

        assertEquals(notificationGroupKey("ops"), child.group)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, child.groupAlertBehavior)
        assertEquals(notificationGroupKey("ops"), summary.group)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertNotEquals(child.group, createDirectNotification(context, product).group)
    }

    private fun event(channelId: String, channelName: String) = DenDenEvent(
        eventId = "event-$channelId",
        action = "notify",
        kind = "notification",
        state = "delivered",
        title = "Build complete",
        message = "Ready",
        channelId = channelId,
        channelName = channelName
    )
}
