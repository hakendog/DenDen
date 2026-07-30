package com.tensal.denden.alarm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tensal.denden.MainActivity
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.DirectEventCommit
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.data.EventRepository
import com.tensal.denden.notification.NotificationChannels
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlarmServiceReplayTest {
    @Test
    fun preClaimForegroundNotificationHasNoFullScreenRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification = createPreClaimAlarmNotification(context, "pre-claim", "Alarm")

        assertNull(notification.fullScreenIntent)
        assertEquals(NotificationChannels.QUIET_CHANNEL_ID, notification.channelId)
        assertNotEquals(alarmNotificationId("pre-claim"), preClaimAlarmNotificationId("pre-claim"))
    }

    @Test
    fun stoppedEventReplayStopsServiceWithoutReactivatingLifecycle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val eventId = "terminal-replay-${UUID.randomUUID()}"
        val event = DenDenEvent(eventId = eventId, action = "ring", kind = "alarm", state = "pending")
        val database = EventDatabase.getInstance(context)
        val repo = EventRepository(database.eventDao())
        check(database.directMessageDao().commitEvent("test", eventId, eventId, Long.MAX_VALUE, event) == DirectEventCommit.INSERTED)
        repo.markStopped(eventId, System.currentTimeMillis(), "manual")
        NotificationChannels.create(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            val intent = Intent(context, AlarmService::class.java).apply {
                putExtra(AlarmService.EXTRA_EVENT_ID, eventId)
                putExtra("duration", 30)
                putExtra("ringUntilMillis", System.currentTimeMillis() + 30_000)
            }
            ContextCompat.startForegroundService(context, intent)

            repeat(20) {
                if (!isAlarmServiceRunning(context)) return@repeat
                delay(100L)
            }
        }

        assertEquals("stopped", repo.getEventByEventId(eventId)?.state)
        assertFalse(isAlarmServiceRunning(context))
    }

    @Test
    fun newerDuplicateRingDoesNotPreventTimeoutCleanup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val eventId = "duplicate-cleanup-${UUID.randomUUID()}"
        val repo = EventRepository(EventDatabase.getInstance(context).eventDao())
        seedPendingAlarm(context, eventId)
        NotificationChannels.create(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context, ringIntent(context, eventId, duration = 1))
            awaitState(repo, eventId, "ringing")
            ContextCompat.startForegroundService(context, ringIntent(context, eventId, duration = 1))
            awaitServiceStopped(context)
        }

        assertEquals("stopped", repo.getEventByEventId(eventId)?.state)
    }

    @Test
    fun newerMismatchedStopDoesNotPreventTimeoutCleanup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val eventId = "mismatch-cleanup-${UUID.randomUUID()}"
        val repo = EventRepository(EventDatabase.getInstance(context).eventDao())
        seedPendingAlarm(context, eventId)
        NotificationChannels.create(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context, ringIntent(context, eventId, duration = 1))
            awaitState(repo, eventId, "ringing")
            val mismatch = Intent(context, AlarmService::class.java).apply {
                action = AlarmService.STOP_ALARM_ACTION
                putExtra(AlarmService.EXTRA_EVENT_ID, "different-$eventId")
                putExtra(AlarmService.EXTRA_REMOTE_STOP, true)
            }
            ContextCompat.startForegroundService(context, mismatch)
            awaitServiceStopped(context)
        }

        assertEquals("stopped", repo.getEventByEventId(eventId)?.state)
    }

    @Test
    fun oldStopCompletionDoesNotCancelNewDifferentAlarm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstEventId = "handoff-first-${UUID.randomUUID()}"
        val secondEventId = "handoff-second-${UUID.randomUUID()}"
        val repo = EventRepository(EventDatabase.getInstance(context).eventDao())
        seedPendingAlarm(context, firstEventId)
        seedPendingAlarm(context, secondEventId)
        NotificationChannels.create(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context, ringIntent(context, firstEventId, duration = 30))
            awaitState(repo, firstEventId, "ringing")

            val stopFirst = Intent(context, AlarmService::class.java).apply {
                action = AlarmService.STOP_ALARM_ACTION
                putExtra(AlarmService.EXTRA_EVENT_ID, firstEventId)
                putExtra(AlarmService.EXTRA_REMOTE_STOP, true)
            }
            ContextCompat.startForegroundService(context, stopFirst)
            ContextCompat.startForegroundService(context, ringIntent(context, secondEventId, duration = 30))

            awaitState(repo, secondEventId, "ringing")

            val stopSecond = Intent(context, AlarmService::class.java).apply {
                action = AlarmService.STOP_ALARM_ACTION
                putExtra(AlarmService.EXTRA_EVENT_ID, secondEventId)
                putExtra(AlarmService.EXTRA_REMOTE_STOP, true)
            }
            ContextCompat.startForegroundService(context, stopSecond)
            awaitServiceStopped(context)
        }

        assertEquals("stopped", repo.getEventByEventId(firstEventId)?.state)
        assertEquals("stopped", repo.getEventByEventId(secondEventId)?.state)
    }
}

private suspend fun seedPendingAlarm(context: Context, eventId: String) {
    val result = EventDatabase.getInstance(context).directMessageDao().commitEvent(
        "test",
        eventId,
        eventId,
        Long.MAX_VALUE,
        DenDenEvent(eventId = eventId, action = "ring", kind = "alarm", state = "pending")
    )
    check(result == DirectEventCommit.INSERTED)
}

private fun ringIntent(context: Context, eventId: String, duration: Int): Intent =
    Intent(context, AlarmService::class.java).apply {
        putExtra(AlarmService.EXTRA_EVENT_ID, eventId)
        putExtra("duration", duration)
        putExtra("ringUntilMillis", System.currentTimeMillis() + 30_000)
    }

private suspend fun awaitState(repo: EventRepository, eventId: String, expected: String) {
    repeat(30) {
        if (repo.getEventByEventId(eventId)?.state == expected) return
        delay(100L)
    }
    fail("Event $eventId never reached $expected")
}

private suspend fun awaitServiceStopped(context: Context) {
    repeat(40) {
        if (!isAlarmServiceRunning(context)) return
        delay(100L)
    }
    fail("AlarmService did not stop")
}

@Suppress("DEPRECATION")
private fun isAlarmServiceRunning(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java)
    return manager.getRunningServices(Int.MAX_VALUE).any {
        it.service.className == AlarmService::class.java.name
    }
}
