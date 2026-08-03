package com.tensal.denden.messaging

import android.app.Notification
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
import com.tensal.denden.notification.NotificationDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRoutingTest {
    @Test
    fun channelsAndGroupingStayLocal() {
        val event = DenDenEvent(eventId = "event-1", action = "notify", channelId = "agent-skills", channelName = "Agent Skills")
        assertEquals("Agent Skills", notificationGroupName(event))
        assertEquals(NotificationChannels.GENERAL_CHANNEL_ID, notificationChannelId(event))
        assertEquals(NotificationChannels.QUIET_CHANNEL_ID, notificationChannelId(event.copy(notificationMode = "quiet")))
        assertEquals(NotificationChannels.ALARM_CHANNEL_ID, notificationChannelId(event.copy(action = "ring")))
        assertTrue(notificationGroupKey("a") != notificationGroupKey("b"))
    }

    @Test
    fun readingChannelClearsOnlyItsDismissibleNotifications() {
        val opsGroup = notificationGroupKey("ops")

        assertTrue(shouldClearReadChannelNotification(opsGroup, 0, "ops"))
        assertTrue(shouldClearReadChannelNotification(opsGroup, Notification.FLAG_GROUP_SUMMARY, "ops"))
        assertTrue(shouldClearReadChannelNotification("denden.standard", 0, "ops", "ops"))
        assertFalse(shouldClearReadChannelNotification("denden.standard", Notification.FLAG_GROUP_SUMMARY, "ops"))
        assertTrue(shouldClearReadChannelNotification(null, 0, "ops", "ops"))
        assertFalse(shouldClearReadChannelNotification(notificationGroupKey("product"), 0, "ops"))
        assertFalse(shouldClearReadChannelNotification(null, 0, "ops", "product"))
        assertFalse(shouldClearReadChannelNotification(opsGroup, Notification.FLAG_ONGOING_EVENT, "ops"))
        assertFalse(shouldClearReadChannelNotification(opsGroup, Notification.FLAG_FOREGROUND_SERVICE, "ops"))
    }

    @Test
    fun notificationDisplayModesControlGroupingAndRetention() {
        val event = DenDenEvent(eventId = "event-1", action = "notify", channelId = "ops")
        val otherChannelEvent = event.copy(eventId = "event-2", channelId = "product")

        val full = notificationDisplayPlan(event, NotificationDisplayMode.FULL)
        assertEquals(event.eventId.hashCode(), full.notificationId)
        assertEquals(notificationGroupKey("ops"), full.groupKey)
        assertEquals(notificationGroupSummaryId("ops"), full.summaryId)
        assertEquals(NotificationReplacementScope.NONE, full.replacementScope)

        val standard = notificationDisplayPlan(event, NotificationDisplayMode.STANDARD)
        val otherChannelStandard = notificationDisplayPlan(otherChannelEvent, NotificationDisplayMode.STANDARD)
        assertEquals(notificationLatestPerChannelId("ops"), standard.notificationId)
        assertEquals("denden.standard", standard.groupKey)
        assertEquals(standard.groupKey, otherChannelStandard.groupKey)
        assertEquals(standard.summaryId, otherChannelStandard.summaryId)
        assertTrue(standard.notificationId != otherChannelStandard.notificationId)
        assertEquals(NotificationReplacementScope.CHANNEL, standard.replacementScope)

        val compact = notificationDisplayPlan(event, NotificationDisplayMode.COMPACT)
        assertNull(compact.groupKey)
        assertNull(compact.summaryId)
        assertEquals(NotificationReplacementScope.ALL, compact.replacementScope)
        assertEquals(
            compact.notificationId,
            notificationDisplayPlan(event.copy(channelId = "product"), NotificationDisplayMode.COMPACT).notificationId
        )
        assertEquals(NotificationDisplayMode.STANDARD, NotificationDisplayMode.fromStorage(null))
    }

    @Test
    fun highPriorityRingLetsAlarmServicePostTheOnlyVisibleNotification() {
        val ring = DenDenEvent(eventId = "alarm-1", action = "ring", kind = "alarm")
        assertFalse(shouldPostStandaloneNotification(ring, true))
        assertTrue(shouldPostStandaloneNotification(ring, false))
        assertTrue(shouldPostStandaloneNotification(ring.copy(action = "notify"), true))
    }

    @Test
    fun notificationRequiresGlobalAndChannelPermission() {
        assertTrue(notificationVisibilityAvailable(true, true))
        assertFalse(notificationVisibilityAvailable(false, true))
        assertFalse(notificationVisibilityAvailable(true, false))
    }
}
