package com.tensal.denden.messaging

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
import com.tensal.denden.notification.NotificationDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertNull(createDirectNotification(context, ops, groupKey = null).group)
    }

    @Test
    fun standardModeCombinesAllLogicalChannelsIntoOneGroup() {
        NotificationChannels.create(context)
        val ops = event("ops", "Operations")
        val product = event("product", "Product")
        val opsPlan = notificationDisplayPlan(ops, NotificationDisplayMode.STANDARD)
        val productPlan = notificationDisplayPlan(product, NotificationDisplayMode.STANDARD)

        assertEquals(opsPlan.groupKey, productPlan.groupKey)
        assertEquals(opsPlan.summaryId, productPlan.summaryId)
        assertNotEquals(opsPlan.notificationId, productPlan.notificationId)
        assertEquals(
            opsPlan.groupKey,
            createDirectNotification(context, ops, groupKey = opsPlan.groupKey).group
        )
        assertEquals(
            opsPlan.groupKey,
            createDirectNotificationGroupSummary(
                context,
                ops,
                groupKey = requireNotNull(opsPlan.groupKey)
            ).group
        )
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
