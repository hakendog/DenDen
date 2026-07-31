package com.tensal.denden.automation

import com.tensal.denden.automation.tasker.isTrustedTaskerCaller
import com.tensal.denden.automation.tasker.isExplicitTaskerFireIntent
import com.tensal.denden.automation.tasker.validateTaskerConfig
import com.tensal.denden.data.DenDenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAutomationTest {
    @Test
    fun taskerInputAcceptsSupportedModesAndBounds() {
        val request = taskerAutomationRequest("ring", "Title", "Message", "300", "Tasker")
        assertEquals(LocalAutomationMode.RING, request.mode)
        assertEquals(300, request.durationSeconds)
        assertNull(validateTaskerConfig("notify", "%title", "%message", "%duration"))
        assertEquals("duration", validateTaskerConfig("ring", "", "", "301"))
        assertThrows(IllegalArgumentException::class.java) {
            taskerAutomationRequest("notify", "", "", "not-a-number")
        }
    }

    @Test
    fun bixbyActionsMapOnlyToThreeFixedModes() {
        assertEquals(LocalAutomationMode.QUIET, bixbyModeForAction(ACTION_BIXBY_QUIET))
        assertEquals(LocalAutomationMode.NOTIFY, bixbyModeForAction(ACTION_BIXBY_NOTIFY))
        assertEquals(LocalAutomationMode.RING, bixbyModeForAction(ACTION_BIXBY_RING))
        assertNull(bixbyModeForAction("com.example.UNTRUSTED"))
    }

    @Test
    fun localSourcesAndTaskerCallerStayBounded() {
        assertTrue(isLocalAutomationEvent(DenDenEvent(eventId = "1", action = "notify", channelId = "local-bixby")))
        assertTrue(isLocalAutomationEvent(DenDenEvent(eventId = "2", action = "notify", channelId = "local-tasker")))
        assertTrue(isTrustedTaskerCaller("net.dinglisch.android.taskerm"))
        assertEquals(false, isTrustedTaskerCaller("com.example.other"))
        assertTrue(
            isExplicitTaskerFireIntent(
                "com.tensal.denden",
                "com.twofortyfouram.locale.intent.action.FIRE_SETTING",
                null,
                "com.tensal.denden"
            )
        )
        assertEquals(
            false,
            isExplicitTaskerFireIntent(
                "com.tensal.denden",
                "com.twofortyfouram.locale.intent.action.FIRE_SETTING",
                "com.example.other",
                null
            )
        )
    }
}
