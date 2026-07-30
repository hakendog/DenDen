package com.tensal.denden.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmScreenStateTest {

    @Test
    fun `initial state is not alarming`() {
        val state = AlarmScreenState()
        assertFalse(state.isAlarming)
    }

    @Test
    fun `initial slide progress is zero`() {
        val state = AlarmScreenState()
        assertEquals(0f, state.slideProgress)
    }

    @Test
    fun `initial state is not stopped`() {
        val state = AlarmScreenState()
        assertFalse(state.isStopped)
    }

    @Test
    fun `onAlarmStarted sets isAlarming true`() {
        val state = AlarmScreenState()
        state.onAlarmStarted()
        assertTrue(state.isAlarming)
    }

    @Test
    fun `onAlarmStarted resets slide progress`() {
        val state = AlarmScreenState()
        state.onSlideProgress(0.5f)
        state.onAlarmStarted()
        assertEquals(0f, state.slideProgress)
    }

    @Test
    fun `onAlarmStarted resets isStopped`() {
        val state = AlarmScreenState()
        state.stop()
        state.onAlarmStarted()
        assertFalse(state.isStopped)
    }

    @Test
    fun `partial slide does not stop alarm`() {
        val state = AlarmScreenState()
        state.onAlarmStarted()
        state.onSlideProgress(0.5f)
        assertTrue(state.isAlarming)
        assertFalse(state.isStopped)
    }

    @Test
    fun `slide past threshold stops alarm`() {
        val state = AlarmScreenState()
        state.onAlarmStarted()
        state.onSlideProgress(0.8f)
        assertFalse(state.isAlarming)
        assertTrue(state.isStopped)
    }

    @Test
    fun `stop explicitly stops alarm`() {
        val state = AlarmScreenState()
        state.onAlarmStarted()
        state.stop()
        assertFalse(state.isAlarming)
        assertTrue(state.isStopped)
    }

    @Test
    fun `stop resets slide progress`() {
        val state = AlarmScreenState()
        state.onAlarmStarted()
        state.onSlideProgress(0.5f)
        state.stop()
        assertEquals(0f, state.slideProgress)
    }

    @Test
    fun `initial payload is null by default`() {
        val state = AlarmScreenState()
        assertEquals(null, state.payload)
    }

    @Test
    fun `initial payload is retained`() {
        val payload = ActiveAlarmPayload(channelId = "infra", title = "T", message = "M", eventId = "e1")
        val state = AlarmScreenState(initialPayload = payload)
        assertEquals(payload, state.payload)
    }

    @Test
    fun `onAlarmStarted sets payload`() {
        val state = AlarmScreenState()
        val payload = ActiveAlarmPayload(channelId = "infra", title = "T", message = "M", eventId = "e1")
        state.onAlarmStarted(payload)
        assertEquals(payload, state.payload)
    }

    @Test
    fun `onAlarmStarted with null payload clears previous payload`() {
        val state = AlarmScreenState(initialPayload = ActiveAlarmPayload("a", "b", "c", "d"))
        state.onAlarmStarted()
        assertEquals(null, state.payload)
    }

    @Test
    fun `initially alarming state starts alarming`() {
        val state = AlarmScreenState(initiallyAlarming = true)
        assertTrue(state.isAlarming)
        assertFalse(state.isStopped)
        assertEquals(0f, state.slideProgress)
    }

    @Test
    fun `initially alarming state can be stopped by slide`() {
        val state = AlarmScreenState(initiallyAlarming = true)
        state.onSlideProgress(0.8f)
        assertFalse(state.isAlarming)
        assertTrue(state.isStopped)
        assertEquals(0f, state.slideProgress)
    }

    @Test
    fun `initially alarming state can be stopped explicitly`() {
        val state = AlarmScreenState(initiallyAlarming = true)
        state.stop()
        assertFalse(state.isAlarming)
        assertTrue(state.isStopped)
    }

    @Test
    fun `onStopAlarm fires when slide stops initially alarming state`() {
        var invoked = false
        val state = AlarmScreenState(
            initiallyAlarming = true,
            initialPayload = ActiveAlarmPayload("infra", "T", "M", "e1")
        )
        state.onStopAlarm = { invoked = true }
        state.onSlideProgress(0.8f)
        assertTrue(invoked)
    }

    @Test
    fun `onStopAlarm fires when stop called on initially alarming state`() {
        var invoked = false
        val state = AlarmScreenState(
            initiallyAlarming = true,
            initialPayload = ActiveAlarmPayload("infra", "T", "M", "e1")
        )
        state.onStopAlarm = { invoked = true }
        state.stop()
        assertTrue(invoked)
    }

    @Test
    fun `onStopAlarm does not fire when already stopped`() {
        var count = 0
        val state = AlarmScreenState(
            initiallyAlarming = true,
            initialPayload = ActiveAlarmPayload("infra", "T", "M", "e1")
        )
        state.onStopAlarm = { count++ }
        state.stop()
        state.stop()
        assertEquals(1, count)
    }

    @Test
    fun `onStopAlarm does not fire on non alarming state`() {
        var invoked = false
        val state = AlarmScreenState()
        state.onStopAlarm = { invoked = true }
        state.stop()
        assertFalse(invoked)
    }

    @Test
    fun `matching service terminal stops without requesting stop again`() {
        var count = 0
        val state = AlarmScreenState(
            initiallyAlarming = true,
            initialPayload = ActiveAlarmPayload("infra", "T", "M", "e1")
        )
        state.onStopAlarm = { count++ }

        state.onAlarmTerminated("e1")

        assertFalse(state.isAlarming)
        assertTrue(state.isStopped)
        assertEquals(0, count)
    }

    @Test
    fun `terminal for another event does not stop current alarm`() {
        val state = AlarmScreenState(
            initiallyAlarming = true,
            initialPayload = ActiveAlarmPayload("infra", "T", "M", "e1")
        )

        state.onAlarmTerminated("e2")

        assertTrue(state.isAlarming)
        assertFalse(state.isStopped)
    }

    @Test
    fun `snail velocity follows drag speed and direction`() {
        val slowTracker = SnailMotionTracker()
        slowTracker.update(0f, 0L)
        val slowVelocity = slowTracker.update(0.1f, 100L)
        val fastTracker = SnailMotionTracker()
        fastTracker.update(0f, 0L)
        val fastVelocity = fastTracker.update(0.4f, 100L)
        val reverseVelocity = fastTracker.update(0.2f, 150L)

        assertTrue(fastVelocity > slowVelocity)
        assertTrue(reverseVelocity < 0f)
    }

    @Test
    fun `snail motion lags opposite travel direction and stays bounded`() {
        val movingRight = snailMotionTransform(100f)
        val movingLeft = snailMotionTransform(-100f)

        assertEquals(-14f, movingRight.lagDp)
        assertEquals(14f, movingLeft.lagDp)
        assertEquals(1.12f, movingRight.scaleX, 0.0001f)
        assertEquals(0.92f, movingRight.scaleY, 0.0001f)
    }

    @Test
    fun `snail velocity resets after an idle interval`() {
        val tracker = SnailMotionTracker()
        tracker.update(0f, 0L)
        assertTrue(tracker.update(0.2f, 100L) > 0f)

        assertEquals(0f, tracker.update(0.3f, 300L))
    }

}
