package com.tensal.denden.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(
    tableName = "message_receipts",
    primaryKeys = ["pairingId", "messageId"],
    indices = [Index(value = ["eventId"]), Index(value = ["expiresAtMillis"])]
)
data class MessageReceipt(
    val pairingId: String,
    val messageId: String,
    val kind: String,
    val eventId: String?,
    val contentDigest: String,
    val receivedAtMillis: Long,
    val expiresAtMillis: Long
)

@Entity(tableName = "pending_alerts")
data class PendingAlert(
    @PrimaryKey val eventId: String,
    val action: String,
    val state: String = "pending",
    val expiresAtMillis: Long,
    val ringUntilMillis: Long?,
    val lastAttemptAtMillis: Long? = null,
    val degradedReason: String? = null
)

@Entity(tableName = "stop_tombstones", indices = [Index(value = ["expiresAtMillis"])])
data class StopTombstone(
    @PrimaryKey val eventId: String,
    val pairingId: String,
    val messageId: String,
    val receivedAtMillis: Long,
    val expiresAtMillis: Long,
    val dispatchPending: Boolean = true
)

enum class DirectEventCommit { INSERTED, DUPLICATE, CONFLICT, STOPPED }
enum class DirectStopCommit { INSERTED, DUPLICATE, CONFLICT }
enum class DirectBrandCommit { INSERTED, DUPLICATE, CONFLICT }

@Dao
interface DirectMessageDao {
    @Query("SELECT * FROM message_receipts WHERE pairingId = :pairingId AND messageId = :messageId LIMIT 1")
    suspend fun receipt(pairingId: String, messageId: String): MessageReceipt?

    @Query("SELECT * FROM events WHERE eventId = :eventId LIMIT 1")
    suspend fun event(eventId: String): DenDenEvent?

    @Query("SELECT * FROM stop_tombstones WHERE eventId = :eventId LIMIT 1")
    suspend fun tombstone(eventId: String): StopTombstone?

    @Query("SELECT * FROM stop_tombstones WHERE dispatchPending = 1 AND expiresAtMillis >= :nowMillis ORDER BY receivedAtMillis ASC LIMIT 50")
    suspend fun pendingStops(nowMillis: Long): List<StopTombstone>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: MessageReceipt): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: DenDenEvent): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<EventTag>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingAlert(alert: PendingAlert)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: StopTombstone)

    @Query("DELETE FROM trashed_channels WHERE channelId = :channelId AND cleanupState = 'active'")
    suspend fun restoreTrashedChannel(channelId: String): Int

    @Query("SELECT * FROM trashed_channels WHERE channelId = :channelId AND cleanupState = 'active' LIMIT 1")
    suspend fun activeTrash(channelId: String): TrashedChannel?

    @Query("UPDATE events SET state = 'stopped', stoppedAtMillis = :stoppedAtMillis, stopReason = 'remote' WHERE eventId = :eventId AND state NOT IN ('stopped', 'missed')")
    suspend fun markEventStopped(eventId: String, stoppedAtMillis: Long): Int

    @Query("SELECT * FROM pending_alerts WHERE state = 'pending' ORDER BY expiresAtMillis ASC LIMIT 50")
    suspend fun pendingAlerts(): List<PendingAlert>

    @Query("SELECT * FROM pending_alerts WHERE eventId = :eventId LIMIT 1")
    suspend fun pendingAlert(eventId: String): PendingAlert?

    @Query("UPDATE pending_alerts SET state = :state, lastAttemptAtMillis = :attemptedAtMillis, degradedReason = :reason WHERE eventId = :eventId AND state = 'pending'")
    suspend fun finishAlert(eventId: String, state: String, attemptedAtMillis: Long, reason: String?): Int

    @Query("UPDATE stop_tombstones SET dispatchPending = 0 WHERE eventId = :eventId")
    suspend fun markStopDispatched(eventId: String): Int

    @Query("DELETE FROM message_receipts WHERE expiresAtMillis < :beforeMillis")
    suspend fun deleteExpiredReceipts(beforeMillis: Long): Int

    @Query("DELETE FROM stop_tombstones WHERE expiresAtMillis < :beforeMillis")
    suspend fun deleteExpiredTombstones(beforeMillis: Long): Int

    @Query("DELETE FROM pending_alerts WHERE expiresAtMillis < :beforeMillis")
    suspend fun deleteExpiredAlerts(beforeMillis: Long): Int

    @Transaction
    suspend fun commitEvent(
        pairingId: String,
        messageId: String,
        contentDigest: String,
        expiresAtMillis: Long,
        event: DenDenEvent
    ): DirectEventCommit {
        val existingReceipt = receipt(pairingId, messageId)
        if (existingReceipt != null) {
            return if (existingReceipt.kind == "event" && existingReceipt.contentDigest == contentDigest) {
                DirectEventCommit.DUPLICATE
            } else DirectEventCommit.CONFLICT
        }
        val eventId = event.eventId
        val receipt = MessageReceipt(
            pairingId, messageId, "event", eventId, contentDigest, event.receivedAt, expiresAtMillis
        )
        val existing = event(eventId)
        if (existing != null) {
            if (!sameDirectEvent(existing, event)) return DirectEventCommit.CONFLICT
            check(insertReceipt(receipt) != -1L)
            return DirectEventCommit.DUPLICATE
        }
        if (tombstone(eventId) != null) {
            insertReceipt(receipt)
            return DirectEventCommit.STOPPED
        }
        val rowId = insertEvent(event)
        if (rowId == -1L) return DirectEventCommit.DUPLICATE
        val tags = event.tags()
        if (tags.isNotEmpty()) insertTags(tags.map { EventTag(rowId, it) })
        val trash = activeTrash(event.channelId)
        if (trash != null && shouldRestoreTrashedChannel(event.issuedAtMillis, trash.trashedAtMillis)) {
            restoreTrashedChannel(event.channelId)
        }
        upsertPendingAlert(PendingAlert(eventId, event.action, expiresAtMillis = expiresAtMillis, ringUntilMillis = event.ringUntilMillis))
        check(insertReceipt(receipt) != -1L)
        return DirectEventCommit.INSERTED
    }

    @Transaction
    suspend fun commitStop(tombstone: StopTombstone, contentDigest: String): DirectStopCommit {
        val existingReceipt = receipt(tombstone.pairingId, tombstone.messageId)
        if (existingReceipt != null && (existingReceipt.kind != "stop" || existingReceipt.contentDigest != contentDigest)) {
            return DirectStopCommit.CONFLICT
        }
        val existingTombstone = tombstone(tombstone.eventId)
        upsertTombstone(tombstone.copy(
            receivedAtMillis = maxOf(existingTombstone?.receivedAtMillis ?: 0, tombstone.receivedAtMillis),
            expiresAtMillis = maxOf(existingTombstone?.expiresAtMillis ?: 0, tombstone.expiresAtMillis),
            dispatchPending = true
        ))
        markEventStopped(tombstone.eventId, tombstone.receivedAtMillis)
        if (existingReceipt != null) return DirectStopCommit.DUPLICATE
        check(insertReceipt(MessageReceipt(
            tombstone.pairingId,
            tombstone.messageId,
            "stop",
            tombstone.eventId,
            contentDigest,
            tombstone.receivedAtMillis,
            tombstone.expiresAtMillis
        )) != -1L)
        return DirectStopCommit.INSERTED
    }

    @Transaction
    suspend fun commitBrandReceipt(
        pairingId: String,
        messageId: String,
        contentDigest: String,
        receivedAtMillis: Long,
        expiresAtMillis: Long
    ): DirectBrandCommit {
        val existing = receipt(pairingId, messageId)
        if (existing != null) {
            return if (existing.kind == "brand" && existing.contentDigest == contentDigest) {
                DirectBrandCommit.DUPLICATE
            } else DirectBrandCommit.CONFLICT
        }
        val inserted = insertReceipt(MessageReceipt(
            pairingId,
            messageId,
            "brand",
            null,
            contentDigest,
            receivedAtMillis,
            expiresAtMillis
        ))
        if (inserted != -1L) return DirectBrandCommit.INSERTED
        val raced = receipt(pairingId, messageId)
        return if (raced?.kind == "brand" && raced.contentDigest == contentDigest) {
            DirectBrandCommit.DUPLICATE
        } else DirectBrandCommit.CONFLICT
    }
}

private fun sameDirectEvent(first: DenDenEvent, second: DenDenEvent): Boolean =
    first.action == second.action &&
        first.channelId == second.channelId &&
        first.channelName == second.channelName &&
        first.title == second.title &&
        first.message == second.message &&
        first.durationSeconds == second.durationSeconds &&
        first.tags() == second.tags() &&
        first.notificationMode == second.notificationMode &&
        first.ringUntilMillis == second.ringUntilMillis &&
        first.issuedAtMillis == second.issuedAtMillis
