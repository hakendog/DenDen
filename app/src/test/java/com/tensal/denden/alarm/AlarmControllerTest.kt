@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tensal.denden.alarm

import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlarmControllerTest {
    @Test
    fun `output mode controls sound and vibration`() {
        assertEquals(
            AlarmOutputPolicy(false, false),
            alarmOutputPolicy(AlarmOutputMode.FOLLOW_SYSTEM, AudioManager.RINGER_MODE_SILENT, true)
        )
        assertEquals(
            AlarmOutputPolicy(false, true),
            alarmOutputPolicy(AlarmOutputMode.FOLLOW_SYSTEM, AudioManager.RINGER_MODE_VIBRATE, false)
        )
        assertEquals(
            AlarmOutputPolicy(true, false),
            alarmOutputPolicy(AlarmOutputMode.FOLLOW_SYSTEM, AudioManager.RINGER_MODE_NORMAL, false)
        )
        assertEquals(
            AlarmOutputPolicy(true, true),
            alarmOutputPolicy(AlarmOutputMode.FOLLOW_SYSTEM, AudioManager.RINGER_MODE_NORMAL, true)
        )
        assertEquals(
            AlarmOutputPolicy(true, true),
            alarmOutputPolicy(AlarmOutputMode.RING_AND_VIBRATE, AudioManager.RINGER_MODE_SILENT, false)
        )
        assertEquals(
            AlarmOutputPolicy(true, false),
            alarmOutputPolicy(AlarmOutputMode.RING_ONLY, AudioManager.RINGER_MODE_VIBRATE, true)
        )
        assertEquals(
            AlarmOutputPolicy(false, true),
            alarmOutputPolicy(AlarmOutputMode.VIBRATE_ONLY, AudioManager.RINGER_MODE_NORMAL, false)
        )
        assertEquals(
            AlarmOutputPolicy(false, false),
            alarmOutputPolicy(AlarmOutputMode.SILENT, AudioManager.RINGER_MODE_NORMAL, true)
        )
    }

    @Test
    fun `unknown stored output mode falls back to system`() {
        assertEquals(AlarmOutputMode.FOLLOW_SYSTEM, AlarmOutputMode.fromStorage(null))
        assertEquals(AlarmOutputMode.FOLLOW_SYSTEM, AlarmOutputMode.fromStorage("future_mode"))
        assertEquals(AlarmOutputMode.RING_ONLY, AlarmOutputMode.fromStorage("RING_ONLY"))
    }

    @Test
    fun `unknown stored vibration pattern falls back to standard`() {
        assertEquals(AlarmVibrationPattern.STANDARD, AlarmVibrationPattern.fromStorage(null))
        assertEquals(AlarmVibrationPattern.STANDARD, AlarmVibrationPattern.fromStorage("future_pattern"))
        assertEquals(AlarmVibrationPattern.URGENT, AlarmVibrationPattern.fromStorage("URGENT"))
    }

    private lateinit var audio: FakeAudioPlayer
    private lateinit var vibrator: FakeVibratorController
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        audio = FakeAudioPlayer()
        vibrator = FakeVibratorController()
        clock = FakeClock()
    }

    @Test
    fun `start plays audio`() = runTest {
        val controller = controller(this)
        controller.start(30)
        assertTrue(audio.isPlaying)
    }

    @Test
    fun `start vibrates`() = runTest {
        val controller = controller(this)
        controller.start(30)
        assertTrue(vibrator.isVibrating)
    }

    @Test
    fun `vibration starts before sound and stops before sound`() = runTest {
        val calls = mutableListOf<String>()
        val controller = AlarmController(FakeAudioPlayer(calls), FakeVibratorController(calls), clock, this)

        controller.start(30)
        controller.stop()

        assertEquals(listOf("vibrate", "play", "cancel", "stop"), calls)
    }

    @Test
    fun `isPlaying false initially`() = runTest {
        val controller = controller(this)
        assertFalse(controller.isPlaying)
    }

    @Test
    fun `isPlaying true after start`() = runTest {
        val controller = controller(this)
        controller.start(30)
        assertTrue(controller.isPlaying)
    }

    @Test
    fun `isPlaying false after stop`() = runTest {
        val controller = controller(this)
        controller.start(30)
        controller.stop()
        assertFalse(controller.isPlaying)
    }

    @Test
    fun `stop halts audio`() = runTest {
        val controller = controller(this)
        controller.start(30)
        controller.stop()
        assertFalse(audio.isPlaying)
    }

    @Test
    fun `stop halts vibration`() = runTest {
        val controller = controller(this)
        controller.start(30)
        controller.stop()
        assertFalse(vibrator.isVibrating)
    }

    @Test
    fun `stop records stoppedAt`() = runTest {
        val controller = controller(this)
        clock.now = 1000L
        controller.start(30)
        clock.now = 5000L
        controller.stop()
        assertNotNull(controller.stoppedAt)
    }

    @Test
    fun `startedAt recorded on start`() = runTest {
        val controller = controller(this)
        clock.now = 1000L
        controller.start(30)
        assertEquals(1000L, controller.startedAt)
    }

    @Test
    fun `null duration uses default 30 seconds`() = runTest(StandardTestDispatcher()) {
        val controller = controller(this)
        controller.start(null)
        assertTrue(audio.isPlaying)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(audio.isPlaying)
    }

    @Test
    fun `zero duration uses finite default`() = runTest(StandardTestDispatcher()) {
        val controller = controller(this)
        controller.start(0)
        assertTrue(audio.isPlaying)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(audio.isPlaying)
    }

    @Test
    fun `negative duration uses default 30 seconds`() = runTest(StandardTestDispatcher()) {
        val controller = controller(this)
        controller.start(-5)
        assertTrue(audio.isPlaying)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(audio.isPlaying)
    }

    @Test
    fun `duration capped at 300`() = runTest(StandardTestDispatcher()) {
        val controller = controller(this)
        controller.start(500)
        assertTrue(audio.isPlaying)
        advanceTimeBy(300_000)
        runCurrent()
        assertFalse(audio.isPlaying)
    }

    @Test
    fun `auto-stop fires after specified duration`() = runTest(StandardTestDispatcher()) {
        val controller = controller(this)
        controller.start(5)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(audio.isPlaying)
        assertFalse(controller.isPlaying)
    }

    @Test
    fun `restart resets auto-stop timer`() = runTest(StandardTestDispatcher()) {
        val controller = controller(this)
        controller.start(300)
        advanceTimeBy(200_000)
        runCurrent()
        assertTrue(audio.isPlaying)
        controller.start(10)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(audio.isPlaying)
    }

    @Test
    fun `onStopped callback fires on auto-stop`() = runTest(StandardTestDispatcher()) {
        var fired = false
        val controller = AlarmController(audio, vibrator, clock, this, onStopped = { fired = true })
        controller.start(1)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fired)
    }

    @Test
    fun `onStopped callback fires on manual stop`() = runTest {
        var fired = false
        val controller = AlarmController(audio, vibrator, clock, this, onStopped = { fired = true })
        controller.start(30)
        controller.stop()
        assertTrue(fired)
    }

    @Test
    fun `multiple stop calls only fires callback once`() = runTest {
        var count = 0
        val controller = AlarmController(audio, vibrator, clock, this, onStopped = { count++ })
        controller.start(30)
        controller.stop()
        controller.stop()
        assertEquals(1, count)
    }

    @Test
    fun `dispose releases alarm resources without firing lifecycle callback`() = runTest {
        var callbacks = 0
        val controller = AlarmController(audio, vibrator, clock, this, onStopped = { callbacks++ })
        controller.start(30)
        controller.dispose()
        assertFalse(audio.isPlaying)
        assertFalse(vibrator.isVibrating)
        assertFalse(controller.isPlaying)
        assertEquals(0, callbacks)
    }

    @Test
    fun `startedAt null before start`() = runTest {
        val controller = controller(this)
        assertNull(controller.startedAt)
    }

    @Test
    fun `stoppedAt null before stop`() = runTest {
        val controller = controller(this)
        assertNull(controller.stoppedAt)
    }

    private fun controller(scope: CoroutineScope) = AlarmController(audio, vibrator, clock, scope)

    @Test
    fun `isRingExpired returns true when ringUntilMillis is in the past`() {
        assertTrue(isRingExpired(1000L, 2000L))
    }

    @Test
    fun `isRingExpired returns false when ringUntilMillis equals now`() {
        assertFalse(isRingExpired(1000L, 1000L))
    }

    @Test
    fun `isRingExpired returns false when ringUntilMillis is in the future`() {
        assertFalse(isRingExpired(2000L, 1000L))
    }

    @Test
    fun `isRingExpired returns false when ringUntilMillis is null`() {
        assertFalse(isRingExpired(null, 1000L))
    }

}

class FakeAudioPlayer(private val calls: MutableList<String>? = null) : AudioPlayer {
    override var isPlaying = false

    override fun play() {
        calls?.add("play")
        isPlaying = true
    }

    override fun stop() {
        calls?.add("stop")
        isPlaying = false
    }
}

class FakeVibratorController(private val calls: MutableList<String>? = null) : VibratorController {
    override var isVibrating = false

    override fun vibrate() {
        calls?.add("vibrate")
        isVibrating = true
    }

    override fun cancel() {
        calls?.add("cancel")
        isVibrating = false
    }
}

class FakeClock : Clock {
    var now = 0L

    override fun currentTimeMillis(): Long = now
}
