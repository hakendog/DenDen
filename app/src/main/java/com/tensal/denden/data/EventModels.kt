package com.tensal.denden.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.concurrent.TimeUnit

data class ChannelSummary(
    val channelId: String,
    val eventCount: Int,
    val lastEventTime: Long
)

@Entity(
    tableName = "event_tags",
    primaryKeys = ["eventRowId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = DenDenEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventRowId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tag", "eventRowId"]), Index(value = ["eventRowId"])]
)
data class EventTag(val eventRowId: Long, val tag: String)

@Entity(tableName = "channel_state")
data class ChannelState(
    @PrimaryKey val channelId: String,
    val lastReadAt: Long = 0,
    val archived: Boolean = false
)

data class ChannelInboxRecord(
    @Embedded val latestEvent: DenDenEvent,
    val displayName: String,
    val eventCount: Int,
    val unreadCount: Int,
    val archived: Boolean
)

data class TimelineVersion(val latestId: Long?, val eventCount: Int)

@Entity(tableName = "trashed_channels")
data class TrashedChannel(
    @PrimaryKey val channelId: String,
    val trashedAtMillis: Long,
    val purgeAtMillis: Long,
    val cleanupState: String = TRASH_STATE_ACTIVE
)

const val TRASH_STATE_ACTIVE = "active"
const val TRASH_STATE_PENDING_PREFERENCES = "pending_preferences"
val TRASH_RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)

fun trashPurgeAt(trashedAtMillis: Long): Long = trashedAtMillis + TRASH_RETENTION_MILLIS
fun isTrashExpired(purgeAtMillis: Long, nowMillis: Long): Boolean = purgeAtMillis <= nowMillis

fun shouldRestoreTrashedChannel(eventIssuedAtMillis: Long, trashedAtMillis: Long): Boolean =
    eventIssuedAtMillis > trashedAtMillis
