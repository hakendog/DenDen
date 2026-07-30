package com.tensal.denden.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class EventRepository(private val dao: EventDao) {
    suspend fun getAllEvents(): List<DenDenEvent> = dao.getAllEvents()
    suspend fun getEventsByChannelId(channelId: String): List<DenDenEvent> = dao.getEventsByChannelId(channelId)
    suspend fun getChannelSummaries(): List<ChannelSummary> = dao.getChannelSummaries()
    suspend fun getEventByEventId(eventId: String): DenDenEvent? = dao.getEventByEventId(eventId)

    fun observeTrashedChannels(): Flow<List<TrashedChannel>> = dao.observeTrashedChannels()
    suspend fun moveChannelToTrash(channelId: String, nowMillis: Long = System.currentTimeMillis()): TrashedChannel =
        dao.moveChannelToTrash(channelId, nowMillis)
    suspend fun restoreChannel(channelId: String): Boolean = dao.restoreTrashedChannel(channelId) == 1
    suspend fun preparePermanentDelete(channelId: String): Boolean = dao.preparePermanentTrashDeletion(channelId)
    suspend fun prepareExpiredTrashCleanup(nowMillis: Long = System.currentTimeMillis()): Int =
        dao.prepareExpiredTrashCleanup(nowMillis)
    suspend fun getPendingTrashCleanup(): List<TrashedChannel> = dao.getPendingTrashCleanup()
    suspend fun completeTrashCleanup(channelId: String): Boolean = dao.completeTrashCleanup(channelId) == 1

    suspend fun markRinging(eventId: String, startedAtMillis: Long): Boolean =
        dao.markRinging(eventId, startedAtMillis) == 1
    suspend fun markDelivered(eventId: String): Boolean = dao.markDelivered(eventId) == 1
    suspend fun markMissed(eventId: String): Boolean = dao.markMissed(eventId) == 1
    suspend fun markStopped(eventId: String, stoppedAtMillis: Long, reason: String): Boolean =
        dao.markStopped(eventId, stoppedAtMillis, reason) == 1
}

suspend fun finishPendingTrashCleanup(context: Context, repository: EventRepository): Int {
    val readStateStore = ChannelReadStateStore(context)
    var completed = 0
    repository.getPendingTrashCleanup().forEach { channel ->
        if (readStateStore.removeChannel(channel.channelId) && repository.completeTrashCleanup(channel.channelId)) completed++
    }
    return completed
}

suspend fun cleanupExpiredLocalTrash(
    context: Context,
    repository: EventRepository,
    nowMillis: Long = System.currentTimeMillis()
): Int {
    val prepared = repository.prepareExpiredTrashCleanup(nowMillis)
    finishPendingTrashCleanup(context, repository)
    return prepared
}
