package com.tensal.denden.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageQueryDaoTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: EventDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exactTagFilterAndSameTimestampCursorDoNotLeakOrDuplicate() = runBlocking {
        val events = listOf(
            event("one", 100, "[\"urgent\"]"),
            event("two", 100, "[\"urgent-ish\"]"),
            event("three", 100, "[\"urgent\"]")
        )
        events.forEach { database.commit(it) }

        val first = database.messageQueryDao().getTimelinePage(
            "ops", "", "all", "urgent", null, null, 1
        )
        val second = database.messageQueryDao().getTimelinePage(
            "ops", "", "all", "urgent", first.single().receivedAt, first.single().id, 2
        )

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(setOf("one", "three"), (first + second).mapNotNull { it.eventId }.toSet())
        assertFalse((first + second).any { it.eventId == "two" })
    }

    @Test
    fun channelFiltersContainOnlyStatusesThatActuallyAppear() = runBlocking {
        val normal = event("normal", 100, "[]")
        val pending = event("pending", 200, "[]").copy(
            action = "ring",
            kind = "alarm",
            state = "pending"
        )
        val stopped = event("stopped", 300, "[]").copy(
            action = "stop",
            kind = "alarm",
            state = "stopped"
        )
        listOf(normal, pending, stopped).forEach { database.commit(it) }

        val filters = database.messageQueryDao().observeChannelFilters("ops").first()

        assertEquals(setOf("normal", "pending", "stop"), filters.toSet())
        assertFalse("quiet" in filters)
        assertFalse("missed" in filters)
    }

    @Test
    fun capacityFixtureKeepsInboxAndInitialTimelineBounded() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            val insert = sql.compileStatement(
                "INSERT INTO events(eventId, action, channelId, notificationMode, kind, state, receivedAt, issuedAtMillis) " +
                    "VALUES(?, 'notify', ?, 'normal', 'notification', 'delivered', ?, ?)"
            )
            repeat(100_000) { index ->
                insert.clearBindings()
                insert.bindString(1, "event-$index")
                insert.bindString(2, "channel-${index % 200}")
                insert.bindLong(3, index.toLong())
                insert.bindLong(4, index.toLong())
                insert.executeInsert()
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }

        val inbox = database.messageQueryDao().observeChannelInbox().first()
        val firstPage = database.messageQueryDao().getTimelinePage(
            "channel-0", "", "all", null, null, null, 100
        )
        val indices = buildSet {
            sql.query("PRAGMA index_list('events')").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }

        assertEquals(200, inbox.size)
        assertTrue(inbox.all { it.eventCount == 500 })
        assertEquals(100, firstPage.size)
        assertTrue("index_events_channelId_receivedAt_id" in indices)
    }

    private fun event(eventId: String, receivedAt: Long, tagsJson: String) = DenDenEvent(
        eventId = eventId,
        action = "notify",
        channelId = "ops",
        tagsJson = tagsJson,
        kind = "notification",
        state = "delivered",
        receivedAt = receivedAt
    )
}

private suspend fun EventDatabase.commit(event: DenDenEvent) {
    check(directMessageDao().commitEvent("test", event.eventId, event.eventId, Long.MAX_VALUE, event) == DirectEventCommit.INSERTED)
}
