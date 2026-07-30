package com.tensal.denden.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveAlarmRegistryTest {
    @Test
    fun runtimeSnapshotIgnoresUnknownAndMismatchedTerminalEvents() {
        val runtime = AlarmRuntimeStore()
        assertEquals(AlarmRuntimeSnapshot.Unknown, runtime.snapshot.value)

        runtime.markActive("alarm-a")
        runtime.markTerminal("alarm-b", "remote")
        assertEquals(AlarmRuntimeSnapshot.Active("alarm-a"), runtime.snapshot.value)
        assertTrue(runtime.snapshot.value.isActiveFor("alarm-a"))

        runtime.markTerminal("alarm-a", "timeout")
        assertEquals(AlarmRuntimeSnapshot.Terminal("alarm-a", "timeout"), runtime.snapshot.value)
        assertTrue(runtime.snapshot.value.isTerminalFor("alarm-a"))
        assertFalse(runtime.snapshot.value.isTerminalFor("alarm-b"))
    }

    @Test
    fun stopOnlyMatchesTheCurrentEventId() {
        val registry = ActiveAlarmRegistry()
        assertTrue(registry.start("alarm-a"))
        assertFalse(registry.stop("alarm-b"))
        assertEquals("alarm-a", registry.eventId)
        assertTrue(registry.stop("alarm-a"))
        assertEquals(null, registry.eventId)
    }

    @Test
    fun duplicateStartDoesNotRestartTheSameAlarm() {
        val registry = ActiveAlarmRegistry()
        assertTrue(registry.start("alarm-a"))
        assertFalse(registry.start("alarm-a"))
        assertFalse(registry.start("alarm-b"))
        assertEquals("alarm-a", registry.eventId)
    }

    @Test
    fun lateStopShutsDownAnIdleService() {
        assertEquals(
            StopIntentDisposition.STOP_IDLE_SERVICE,
            stopIntentDisposition("finished-event", null)
        )
    }

    @Test
    fun wrongStopDoesNotInterruptAnotherActiveAlarm() {
        assertEquals(
            StopIntentDisposition.IGNORE_ACTIVE_MISMATCH,
            stopIntentDisposition("old-event", "current-event")
        )
    }

    @Test
    fun matchingStopStopsTheActiveAlarm() {
        assertEquals(
            StopIntentDisposition.STOP_ACTIVE,
            stopIntentDisposition("current-event", "current-event")
        )
    }
}
