package com.tensal.denden.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDaoObservationTest {
    @Test
    fun deletingProjectRemovesOnlyItsEventsFromObservation() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EventDatabase::class.java
        ).build()
        try {
            val dao = database.eventDao()
            database.commit(DenDenEvent(eventId = "a1", action = "notify", channelId = "a"))
            database.commit(DenDenEvent(eventId = "b1", action = "notify", channelId = "b"))
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                dao.observeAllEvents().first { events ->
                    events.none { it.channelId == "a" } && events.any { it.channelId == "b" }
                }
            }

            assertEquals(1, dao.deleteByChannelId("a"))
            assertEquals(listOf("b1"), withTimeout(5_000) { observed.await() }.map { it.eventId })
        } finally {
            database.close()
        }
    }

    @Test
    fun emitsWhenAnEventIsInserted() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EventDatabase::class.java
        ).build()
        try {
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                database.eventDao().observeAllEvents().first { it.isNotEmpty() }
            }

            database.commit(
                DenDenEvent(eventId = "event-1", action = "notify", receivedAt = 1)
            )

            assertEquals("event-1", withTimeout(5_000) { observed.await() }.single().eventId)
        } finally {
            database.close()
        }
    }
}

private suspend fun EventDatabase.commit(event: DenDenEvent) {
    check(directMessageDao().commitEvent("test", event.eventId, event.eventId, Long.MAX_VALUE, event) == DirectEventCommit.INSERTED)
}
