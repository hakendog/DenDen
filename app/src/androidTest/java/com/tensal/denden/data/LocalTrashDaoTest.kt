package com.tensal.denden.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalTrashDaoTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: EventDatabase
    private lateinit var dao: EventDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        dao = database.eventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun moveAndRestoreFollowAuthenticatedIssueTime() = runBlocking {
        database.commit(DenDenEvent(eventId = "old", action = "notify", channelId = "ops", issuedAtMillis = 900, receivedAt = 900))
        dao.moveChannelToTrash("ops", 1_000L)

        database.commit(DenDenEvent(eventId = "delayed", action = "notify", channelId = "ops", issuedAtMillis = 999, receivedAt = 1_100))
        assertTrue(dao.getActiveTrashedChannel("ops") != null)

        database.commit(DenDenEvent(eventId = "new", action = "notify", channelId = "ops", issuedAtMillis = 1_001, receivedAt = 1_101))
        assertNull(dao.getActiveTrashedChannel("ops"))
        assertEquals(3, dao.getEventsByChannelId("ops").size)
    }

    @Test
    fun permanentDeleteResumesPreferenceCleanupWithoutTouchingOtherChannel() = runBlocking {
        val repository = EventRepository(dao)
        val readState = ChannelReadStateStore(context)
        readState.markRead("trash-target", 123L)
        readState.setArchived("trash-target", true)
        database.commit(DenDenEvent(eventId = "target", action = "notify", channelId = "trash-target"))
        database.commit(DenDenEvent(eventId = "other", action = "notify", channelId = "other"))
        database.directMessageDao().commitStop(
            StopTombstone("target", "test", "stop-target", 200L, 2_000L),
            "stop-target"
        )
        repository.moveChannelToTrash("trash-target", 1_000L)

        assertTrue(repository.preparePermanentDelete("trash-target"))
        assertEquals(1, repository.getPendingTrashCleanup().size)
        assertEquals(1, finishPendingTrashCleanup(context, repository))

        assertFalse(readState.isArchived("trash-target"))
        assertFalse(readState.getLastReadAtByChannel().containsKey("trash-target"))
        assertEquals(listOf("other"), repository.getAllEvents().map { it.channelId })
        assertNull(database.directMessageDao().pendingAlert("target"))
        assertNull(database.directMessageDao().receipt("test", "target"))
        assertNull(database.directMessageDao().tombstone("target"))
        assertTrue(repository.getPendingTrashCleanup().isEmpty())
    }

    @Test
    fun expiryPreparationIsBoundaryCorrectAndIdempotent() = runBlocking {
        val repository = EventRepository(dao)
        database.commit(DenDenEvent(eventId = "expiring", action = "notify", channelId = "expiry"))
        val trashed = repository.moveChannelToTrash("expiry", 1_000L)

        assertEquals(0, repository.prepareExpiredTrashCleanup(trashed.purgeAtMillis - 1))
        assertEquals(1, repository.prepareExpiredTrashCleanup(trashed.purgeAtMillis))
        assertEquals(0, repository.prepareExpiredTrashCleanup(trashed.purgeAtMillis + 1))
        assertEquals(1, repository.getPendingTrashCleanup().size)
    }

    @Test
    fun pendingPreferenceCleanupConvergesAfterInterruptionAndWorkerRetry() = runBlocking {
        val repository = EventRepository(dao)
        val readState = ChannelReadStateStore(context)
        val channelId = "trash-retry-${UUID.randomUUID()}"
        readState.markRead(channelId, 123L)
        readState.setArchived(channelId, true)
        database.commit(DenDenEvent(eventId = "retry", action = "notify", channelId = channelId))
        val trashed = repository.moveChannelToTrash(channelId, 1_000L)

        assertEquals(1, repository.prepareExpiredTrashCleanup(trashed.purgeAtMillis))
        assertTrue(readState.removeChannel(channelId))
        assertEquals(listOf(channelId), repository.getPendingTrashCleanup().map { it.channelId })

        assertEquals(0, cleanupExpiredLocalTrash(context, repository, trashed.purgeAtMillis))
        assertTrue(repository.getPendingTrashCleanup().isEmpty())
        assertFalse(readState.isArchived(channelId))
        assertFalse(readState.getLastReadAtByChannel().containsKey(channelId))
        assertTrue(repository.getEventsByChannelId(channelId).isEmpty())
        assertEquals(0, cleanupExpiredLocalTrash(context, repository, trashed.purgeAtMillis))
    }

    @Test
    fun sharedPreferenceReadAndArchiveStateImportsIdempotentlyIntoRoom() = runBlocking {
        context.getSharedPreferences("denden_channel_read_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = ChannelReadStateStore(context)
        store.markRead("ops", 123L)
        store.setArchived("ops", true)

        store.importInto(database.messageQueryDao())
        store.importInto(database.messageQueryDao())

        assertEquals(ChannelState("ops", 123L, true), database.messageQueryDao().getChannelState("ops"))
    }
}

private suspend fun EventDatabase.commit(event: DenDenEvent) {
    check(directMessageDao().commitEvent("test", event.eventId, event.eventId, Long.MAX_VALUE, event) == DirectEventCommit.INSERTED)
}
