package com.tensal.denden.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY receivedAt DESC, id DESC")
    suspend fun getAllEvents(): List<DenDenEvent>

    @Query("SELECT * FROM events ORDER BY receivedAt DESC, id DESC")
    fun observeAllEvents(): Flow<List<DenDenEvent>>

    @Query("SELECT COUNT(*) FROM events WHERE eventId = :eventId")
    suspend fun countByEventId(eventId: String): Int

    @Query("SELECT * FROM events WHERE channelId = :channelId ORDER BY receivedAt DESC, id DESC")
    suspend fun getEventsByChannelId(channelId: String): List<DenDenEvent>

    @Query("SELECT channelId, COUNT(*) AS eventCount, MAX(receivedAt) AS lastEventTime FROM events GROUP BY channelId ORDER BY lastEventTime DESC")
    suspend fun getChannelSummaries(): List<ChannelSummary>

    @Query("SELECT * FROM events WHERE eventId = :eventId LIMIT 1")
    suspend fun getEventByEventId(eventId: String): DenDenEvent?

    @Query("DELETE FROM events WHERE channelId = :channelId")
    suspend fun deleteByChannelId(channelId: String): Int

    @Query("DELETE FROM channel_state WHERE channelId = :channelId")
    suspend fun deleteChannelState(channelId: String)

    @Query("DELETE FROM pending_alerts WHERE eventId IN (SELECT eventId FROM events WHERE channelId = :channelId)")
    suspend fun deletePendingAlertsByChannelId(channelId: String)

    @Query("DELETE FROM message_receipts WHERE eventId IN (SELECT eventId FROM events WHERE channelId = :channelId)")
    suspend fun deleteMessageReceiptsByChannelId(channelId: String)

    @Query("DELETE FROM stop_tombstones WHERE eventId IN (SELECT eventId FROM events WHERE channelId = :channelId)")
    suspend fun deleteStopTombstonesByChannelId(channelId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrashedChannel(channel: TrashedChannel)

    @Query("SELECT * FROM trashed_channels WHERE cleanupState = 'active' ORDER BY trashedAtMillis DESC")
    fun observeTrashedChannels(): Flow<List<TrashedChannel>>

    @Query("SELECT * FROM trashed_channels WHERE channelId = :channelId AND cleanupState = 'active' LIMIT 1")
    suspend fun getActiveTrashedChannel(channelId: String): TrashedChannel?

    @Query("SELECT * FROM trashed_channels WHERE cleanupState = 'active' AND purgeAtMillis <= :nowMillis")
    suspend fun getExpiredTrashedChannels(nowMillis: Long): List<TrashedChannel>

    @Query("SELECT * FROM trashed_channels WHERE cleanupState = 'pending_preferences'")
    suspend fun getPendingTrashCleanup(): List<TrashedChannel>

    @Query("DELETE FROM trashed_channels WHERE channelId = :channelId AND cleanupState = 'active'")
    suspend fun restoreTrashedChannel(channelId: String): Int

    @Query("UPDATE trashed_channels SET cleanupState = 'pending_preferences' WHERE channelId = :channelId AND cleanupState = 'active'")
    suspend fun markTrashPendingPreferences(channelId: String): Int

    @Query("DELETE FROM trashed_channels WHERE channelId = :channelId AND cleanupState = 'pending_preferences'")
    suspend fun completeTrashCleanup(channelId: String): Int

    @Query("UPDATE events SET state = 'ringing', startedAtMillis = :startedAtMillis WHERE eventId = :eventId AND kind = 'alarm' AND state = 'pending'")
    suspend fun markRinging(eventId: String, startedAtMillis: Long): Int

    @Query("UPDATE events SET state = 'delivered' WHERE eventId = :eventId AND kind = 'notification' AND state = 'pending'")
    suspend fun markDelivered(eventId: String): Int

    @Query("UPDATE events SET state = 'missed' WHERE eventId = :eventId AND kind = 'alarm' AND state = 'pending'")
    suspend fun markMissed(eventId: String): Int

    @Query("UPDATE events SET state = 'stopped', stoppedAtMillis = :stoppedAtMillis, stopReason = :reason WHERE eventId = :eventId AND kind = 'alarm' AND state NOT IN ('stopped', 'missed')")
    suspend fun markStopped(eventId: String, stoppedAtMillis: Long, reason: String): Int

    @Transaction
    suspend fun moveChannelToTrash(channelId: String, trashedAtMillis: Long): TrashedChannel =
        TrashedChannel(channelId, trashedAtMillis, trashPurgeAt(trashedAtMillis)).also {
            upsertTrashedChannel(it)
        }

    @Transaction
    suspend fun preparePermanentTrashDeletion(channelId: String): Boolean {
        if (getActiveTrashedChannel(channelId) == null) return false
        deleteDirectMetadataByChannelId(channelId)
        deleteByChannelId(channelId)
        deleteChannelState(channelId)
        return markTrashPendingPreferences(channelId) == 1
    }

    @Transaction
    suspend fun prepareExpiredTrashCleanup(nowMillis: Long): Int {
        val expired = getExpiredTrashedChannels(nowMillis)
        expired.forEach { channel ->
            deleteDirectMetadataByChannelId(channel.channelId)
            deleteByChannelId(channel.channelId)
            deleteChannelState(channel.channelId)
            markTrashPendingPreferences(channel.channelId)
        }
        return expired.size
    }

    private suspend fun deleteDirectMetadataByChannelId(channelId: String) {
        deletePendingAlertsByChannelId(channelId)
        deleteMessageReceiptsByChannelId(channelId)
        deleteStopTombstonesByChannelId(channelId)
    }
}
