package com.tensal.denden.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageQueryDao {
    @Query(
        """WITH summaries AS (
            SELECT counted.channelId,
                COUNT(*) AS eventCount,
                SUM(CASE WHEN counted.receivedAt > COALESCE(state.lastReadAt, 0) THEN 1 ELSE 0 END) AS unreadCount,
                COALESCE(state.archived, 0) AS archived,
                (SELECT newest.id FROM events newest
                    WHERE newest.channelId = counted.channelId
                    ORDER BY newest.receivedAt DESC, newest.id DESC LIMIT 1) AS latestId
            FROM events counted
            LEFT JOIN channel_state state ON state.channelId = counted.channelId
            GROUP BY counted.channelId
        )
        SELECT e.*,
            COALESCE((SELECT named.channelName FROM events named
                WHERE named.channelId = e.channelId AND named.channelName IS NOT NULL AND named.channelName != ''
                ORDER BY named.receivedAt DESC, named.id DESC LIMIT 1), e.channelId) AS displayName,
            summaries.eventCount AS eventCount,
            summaries.unreadCount AS unreadCount,
            summaries.archived AS archived
        FROM summaries
        JOIN events e ON e.id = summaries.latestId
        WHERE NOT EXISTS (SELECT 1 FROM trashed_channels trash
              WHERE trash.channelId = e.channelId AND trash.cleanupState = 'active')
        ORDER BY e.receivedAt DESC, e.id DESC"""
    )
    fun observeChannelInbox(): Flow<List<ChannelInboxRecord>>

    @Query(
        """WITH summaries AS (
            SELECT trash.channelId, trash.trashedAtMillis,
                (SELECT COUNT(*) FROM events counted WHERE counted.channelId = trash.channelId) AS eventCount,
                (SELECT newest.id FROM events newest
                    WHERE newest.channelId = trash.channelId
                    ORDER BY newest.receivedAt DESC, newest.id DESC LIMIT 1) AS latestId,
                COALESCE(state.archived, 0) AS archived
            FROM trashed_channels trash
            LEFT JOIN channel_state state ON state.channelId = trash.channelId
            WHERE trash.cleanupState = 'active'
        )
        SELECT e.*,
            COALESCE((SELECT named.channelName FROM events named
                WHERE named.channelId = e.channelId AND named.channelName IS NOT NULL AND named.channelName != ''
                ORDER BY named.receivedAt DESC, named.id DESC LIMIT 1), e.channelId) AS displayName,
            summaries.eventCount AS eventCount,
            0 AS unreadCount,
            summaries.archived AS archived
        FROM summaries
        JOIN events e ON e.id = summaries.latestId
        ORDER BY summaries.trashedAtMillis DESC"""
    )
    fun observeTrashInbox(): Flow<List<ChannelInboxRecord>>

    @Query(
        """SELECT * FROM events e
        WHERE e.channelId = :channelId
          AND (:filter = 'all'
               OR (:filter = 'normal' AND e.notificationMode != 'quiet' AND e.kind = 'notification')
               OR (:filter = 'quiet' AND e.notificationMode = 'quiet' AND e.kind = 'notification')
               OR (:filter = 'pending' AND e.state = 'pending' AND e.kind = 'alarm')
               OR (:filter = 'ring' AND e.state = 'ringing')
               OR (:filter = 'stop' AND e.state = 'stopped')
               OR (:filter = 'missed' AND e.state = 'missed'))
          AND (:query = '' OR e.title LIKE '%' || :query || '%' COLLATE NOCASE
               OR e.message LIKE '%' || :query || '%' COLLATE NOCASE
               OR EXISTS (SELECT 1 FROM event_tags searched
                    WHERE searched.eventRowId = e.id AND searched.tag LIKE '%' || :query || '%' COLLATE NOCASE))
          AND (:tag IS NULL OR EXISTS (SELECT 1 FROM event_tags selected
               WHERE selected.eventRowId = e.id AND selected.tag = :tag))
          AND (:beforeReceivedAt IS NULL OR e.receivedAt < :beforeReceivedAt
               OR (e.receivedAt = :beforeReceivedAt AND e.id < :beforeId))
        ORDER BY e.receivedAt DESC, e.id DESC LIMIT :limit"""
    )
    suspend fun getTimelinePage(
        channelId: String,
        query: String,
        filter: String,
        tag: String?,
        beforeReceivedAt: Long?,
        beforeId: Long?,
        limit: Int
    ): List<DenDenEvent>

    @Query(
        """SELECT DISTINCT tags.tag FROM event_tags tags
        JOIN events e ON e.id = tags.eventRowId
        WHERE e.channelId = :channelId
        ORDER BY tags.tag LIMIT :limit"""
    )
    fun observeChannelTags(channelId: String, limit: Int): Flow<List<String>>

    @Query(
        """SELECT DISTINCT CASE
            WHEN e.state = 'stopped' THEN 'stop'
            WHEN e.state = 'missed' THEN 'missed'
            WHEN e.state = 'ringing' THEN 'ring'
            WHEN e.state = 'pending' AND e.kind = 'alarm' THEN 'pending'
            WHEN e.notificationMode = 'quiet' THEN 'quiet'
            ELSE 'normal'
        END AS filterValue
        FROM events e
        WHERE e.channelId = :channelId
        ORDER BY filterValue"""
    )
    fun observeChannelFilters(channelId: String): Flow<List<String>>

    @Query("SELECT MAX(id) AS latestId, COUNT(*) AS eventCount FROM events WHERE channelId = :channelId")
    fun observeTimelineVersion(channelId: String): Flow<TimelineVersion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannelState(state: ChannelState)

    @Query("SELECT * FROM channel_state WHERE channelId = :channelId LIMIT 1")
    suspend fun getChannelState(channelId: String): ChannelState?

    @Query("SELECT MAX(receivedAt) FROM events WHERE channelId = :channelId")
    suspend fun getLatestReceivedAt(channelId: String): Long?

    @Transaction
    suspend fun markReadToLatest(channelId: String) {
        val latest = getLatestReceivedAt(channelId) ?: return
        val current = getChannelState(channelId)
        if (latest > (current?.lastReadAt ?: 0)) {
            upsertChannelState(ChannelState(channelId, latest, current?.archived ?: false))
        }
    }

    @Transaction
    suspend fun setArchived(channelId: String, archived: Boolean) {
        val current = getChannelState(channelId)
        upsertChannelState(ChannelState(channelId, current?.lastReadAt ?: 0, archived))
    }

    @Transaction
    suspend fun importChannelStates(states: List<ChannelState>) {
        states.forEach { incoming ->
            val current = getChannelState(incoming.channelId)
            upsertChannelState(
                ChannelState(
                    channelId = incoming.channelId,
                    lastReadAt = maxOf(incoming.lastReadAt, current?.lastReadAt ?: 0),
                    archived = incoming.archived || current?.archived == true
                )
            )
        }
    }
}
