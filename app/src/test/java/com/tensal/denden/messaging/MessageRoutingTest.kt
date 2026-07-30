package com.tensal.denden.messaging

import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.notification.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun highPriorityRingLetsAlarmServicePostTheOnlyVisibleNotification() {
        val ring = DenDenEvent(eventId = "alarm-1", action = "ring", kind = "alarm")
        assertFalse(shouldPostStandaloneNotification(ring, true))
        assertTrue(shouldPostStandaloneNotification(ring, false))
        assertTrue(shouldPostStandaloneNotification(ring.copy(action = "notify"), true))
    }
}
