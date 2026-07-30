package com.tensal.denden.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectMessageDaoTest {
    private lateinit var database: EventDatabase
    private lateinit var dao: DirectMessageDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EventDatabase::class.java
        ).build()
        dao = database.directMessageDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun eventReceiptHistoryAndPendingAlertCommitAtomicallyAndDeduplicate() = runTest {
        val event = DenDenEvent(
            eventId = "event-1",
            action = "ring",
            channelId = "main",
            kind = "alarm",
            state = "pending",
            receivedAt = 100
        )
        assertEquals(
            DirectEventCommit.INSERTED,
            dao.commitEvent("pairing", "message-1", "digest", 1000, event)
        )
        assertEquals(
            DirectEventCommit.DUPLICATE,
            dao.commitEvent("pairing", "message-1", "digest", 1000, event)
        )
        assertEquals(1, dao.pendingAlerts().size)
        assertEquals("event-1", dao.event("event-1")?.eventId)

        val stop = StopTombstone("future-event", "pairing", "message-stop", 200, 2000)
        assertEquals(DirectStopCommit.INSERTED, dao.commitStop(stop, "stop-digest"))
        assertEquals(
            DirectEventCommit.STOPPED,
            dao.commitEvent("pairing", "message-2", "future-digest", 2000, event.copy(eventId = "future-event"))
        )
        assertEquals(null, dao.event("future-event"))
    }

    @Test
    fun conflictingReceiptsAndEventsFailClosed() = runTest {
        val event = DenDenEvent(
            eventId = "event-conflict",
            action = "notify",
            channelId = "main",
            kind = "message",
            state = "received",
            message = "first",
            receivedAt = 100
        )
        assertEquals(DirectEventCommit.INSERTED, dao.commitEvent("pairing", "message-a", "digest-a", 1000, event))
        assertEquals(DirectEventCommit.CONFLICT, dao.commitEvent("pairing", "message-a", "digest-b", 1000, event))
        assertEquals(
            DirectEventCommit.CONFLICT,
            dao.commitEvent("pairing", "message-b", "digest-c", 1000, event.copy(message = "different"))
        )
        assertEquals(null, dao.receipt("pairing", "message-b"))
        assertEquals(
            DirectEventCommit.CONFLICT,
            dao.commitEvent("pairing", "message-b", "digest-c", 1000, event.copy(message = "different"))
        )
        assertEquals("first", dao.event("event-conflict")?.message)
        assertEquals(1, dao.pendingAlerts().size)
    }

    @Test
    fun duplicateStopExtendsTombstoneAndReassertsDispatch() = runTest {
        val first = StopTombstone("event-stop", "pairing", "stop-a", 100, 1000)
        assertEquals(DirectStopCommit.INSERTED, dao.commitStop(first, "digest-a"))
        dao.markStopDispatched("event-stop")
        assertEquals(false, dao.tombstone("event-stop")?.dispatchPending)

        val repeated = first.copy(receivedAtMillis = 200, expiresAtMillis = 2000)
        assertEquals(DirectStopCommit.DUPLICATE, dao.commitStop(repeated, "digest-a"))
        assertEquals(true, dao.tombstone("event-stop")?.dispatchPending)
        assertEquals(2000L, dao.tombstone("event-stop")?.expiresAtMillis)
        assertEquals(DirectStopCommit.CONFLICT, dao.commitStop(repeated.copy(expiresAtMillis = 3000), "different"))
        assertEquals(2000L, dao.tombstone("event-stop")?.expiresAtMillis)
    }

    @Test
    fun brandReceiptDeduplicatesAndRejectsMessageIdConflicts() = runTest {
        assertEquals(
            DirectBrandCommit.INSERTED,
            dao.commitBrandReceipt("pairing", "brand-message", "brand-digest", 100, 1000)
        )
        assertEquals(
            DirectBrandCommit.DUPLICATE,
            dao.commitBrandReceipt("pairing", "brand-message", "brand-digest", 200, 2000)
        )
        assertEquals(
            DirectBrandCommit.CONFLICT,
            dao.commitBrandReceipt("pairing", "brand-message", "different-digest", 300, 3000)
        )
        val receipt = dao.receipt("pairing", "brand-message")
        assertEquals("brand", receipt?.kind)
        assertEquals(1000L, receipt?.expiresAtMillis)
    }

    @Test
    fun concurrentCopiesOfTheSameEventCommitOnlyOnce() = runTest {
        val event = DenDenEvent(
            eventId = "event-concurrent",
            action = "ring",
            channelId = "main",
            kind = "alarm",
            state = "pending",
            receivedAt = 100
        )
        val results = coroutineScope {
            (1..20).map {
                async(Dispatchers.Default) {
                    dao.commitEvent("pairing", "message-concurrent", "digest", 1000, event)
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it == DirectEventCommit.INSERTED })
        assertEquals(19, results.count { it == DirectEventCommit.DUPLICATE })
        assertEquals(1, dao.pendingAlerts().size)
        assertEquals("event-concurrent", dao.event("event-concurrent")?.eventId)
    }

    @Test
    fun onlyAUniqueEventIssuedAfterTrashRestoresTheChannel() = runTest {
        val events = database.eventDao()
        val original = DenDenEvent(
            eventId = "original",
            action = "notify",
            channelId = "ops",
            issuedAtMillis = 100,
            receivedAt = 100
        )
        assertEquals(DirectEventCommit.INSERTED, dao.commitEvent("pairing", "message-original", "original", 1_000, original))
        events.moveChannelToTrash("ops", 200)

        assertEquals(DirectEventCommit.DUPLICATE, dao.commitEvent("pairing", "message-replay", "original", 1_000, original))
        assertEquals(DirectEventCommit.DUPLICATE, dao.commitEvent("pairing", "message-original", "original", 1_000, original))
        assertEquals(
            DirectEventCommit.INSERTED,
            dao.commitEvent(
                "pairing",
                "message-delayed",
                "delayed",
                1_000,
                original.copy(eventId = "delayed", issuedAtMillis = 150, receivedAt = 300)
            )
        )
        assertEquals("ops", events.getActiveTrashedChannel("ops")?.channelId)

        assertEquals(
            DirectEventCommit.INSERTED,
            dao.commitEvent(
                "pairing",
                "message-new",
                "new",
                1_000,
                original.copy(eventId = "new", issuedAtMillis = 201, receivedAt = 301)
            )
        )
        assertEquals(null, events.getActiveTrashedChannel("ops"))
    }

    @Test
    fun localAutomationEventAppearsInInbox() = runTest {
        val event = DenDenEvent(
            eventId = "local-tasker-1",
            action = "notify",
            title = "Tasker title",
            message = "Tasker message",
            channelId = "local-tasker",
            channelName = "Tasker",
            receivedAt = 100,
            issuedAtMillis = 100
        )
        assertEquals(
            DirectEventCommit.INSERTED,
            dao.commitEvent("local:tasker", event.eventId, event.eventId, 1_000, event)
        )
        val inbox = database.messageQueryDao().observeChannelInbox().first()
        assertEquals(1, inbox.size)
        assertEquals("local-tasker-1", inbox.single().latestEvent.eventId)
        assertEquals("Tasker", inbox.single().displayName)
    }

    @Test
    fun expiredPendingAlertsAreRemoved() = runTest {
        val event = DenDenEvent(eventId = "expired-alert", action = "notify", receivedAt = 100)
        assertEquals(DirectEventCommit.INSERTED, dao.commitEvent("pairing", "expired-message", "digest", 200, event))

        assertEquals(0, dao.deleteExpiredAlerts(200))
        assertEquals(1, dao.deleteExpiredAlerts(201))
        assertEquals(null, dao.pendingAlert("expired-alert"))
    }
}
