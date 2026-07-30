package com.tensal.denden.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmNotificationSummaryTest {
    @Test
    fun `short alarm message is unchanged`() {
        assertEquals("short", alarmNotificationSummary("short"))
    }

    @Test
    fun `long alarm message is summarized`() {
        val message = "x".repeat(121)
        assertEquals("x".repeat(120) + "…", alarmNotificationSummary(message))
    }

    @Test
    fun `foreground notification id correlates with event id`() {
        assertEquals("event-1".hashCode(), alarmNotificationId("event-1"))
    }
}
