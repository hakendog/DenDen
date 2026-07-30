package com.tensal.denden.ui

import androidx.compose.material3.SwipeToDismissBoxValue
import com.tensal.denden.data.DenDenEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ChannelUiStateTest {

    @Test
    fun `right swipe deletes and left swipe archives`() {
        assertEquals(ChannelSwipeAction.DELETE, channelSwipeAction(SwipeToDismissBoxValue.StartToEnd))
        assertEquals(ChannelSwipeAction.ARCHIVE, channelSwipeAction(SwipeToDismissBoxValue.EndToStart))
        assertEquals(ChannelSwipeAction.NONE, channelSwipeAction(SwipeToDismissBoxValue.Settled))
    }

    @Test
    fun `groups events by channelId`() {
        val events = listOf(
            event(channelId = "alpha", action = "notify", receivedAt = 1000),
            event(channelId = "beta", action = "notify", receivedAt = 2000),
            event(channelId = "alpha", action = "ring", receivedAt = 3000)
        )
        val items = events.toChannelInboxItems()
        assertEquals(2, items.size)
        assertEquals(setOf("alpha", "beta"), items.map { it.channelId }.toSet())
    }

    @Test
    fun `orders channels by latest event descending`() {
        val events = listOf(
            event(channelId = "older", action = "notify", receivedAt = 1000),
            event(channelId = "newer", action = "notify", receivedAt = 5000),
            event(channelId = "older", action = "notify", receivedAt = 2000)
        )
        val items = events.toChannelInboxItems()
        assertEquals(listOf("newer", "older"), items.map { it.channelId })
    }

    @Test
    fun `latest event is most recent per channel`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", title = "old", receivedAt = 1000),
            event(channelId = "p1", action = "notify", title = "latest", receivedAt = 3000),
            event(channelId = "p1", action = "notify", title = "middle", receivedAt = 2000)
        )
        val items = events.toChannelInboxItems()
        assertEquals("latest", items.first().latestEvent.title)
    }

    @Test
    fun `event count per channel is correct`() {
        val events = listOf(
            event(channelId = "p1", receivedAt = 1000),
            event(channelId = "p1", receivedAt = 2000),
            event(channelId = "p2", receivedAt = 3000)
        )
        val items = events.toChannelInboxItems()
        assertEquals(2, items.first { it.channelId == "p1" }.eventCount)
        assertEquals(1, items.first { it.channelId == "p2" }.eventCount)
    }

    @Test
    fun `unread count uses last read time per channel`() {
        val events = listOf(
            event(channelId = "p1", receivedAt = 1000),
            event(channelId = "p1", receivedAt = 2000),
            event(channelId = "p2", receivedAt = 3000)
        )
        val items = events.toChannelInboxItems(mapOf("p1" to 1000L, "p2" to 3000L))
        assertEquals(1, items.first { it.channelId == "p1" }.unreadCount)
        assertEquals(0, items.first { it.channelId == "p2" }.unreadCount)
    }

    @Test
    fun `unread count treats never opened channel as all unread`() {
        val events = listOf(
            event(channelId = "p1", receivedAt = 1000),
            event(channelId = "p1", receivedAt = 2000)
        )
        val item = events.toChannelInboxItems().first()
        assertEquals(2, item.unreadCount)
    }

    @Test
    fun `unread badge hides zero and marks unread alarms`() {
        val alarm = ChannelInboxItem(
            channelId = "alarm",
            displayName = "警報",
            latestEvent = event(action = "ring", kind = "alarm", state = "pending"),
            eventCount = 1,
            unreadCount = 1
        )
        val normal = alarm.copy(latestEvent = event(action = "notify"))

        assertEquals(null, unreadBadgeText(0))
        assertEquals("99+", unreadBadgeText(100))
        assertEquals(true, alarm.hasUnreadAlarm())
        assertEquals(false, normal.hasUnreadAlarm())
        assertEquals(false, alarm.copy(unreadCount = 0).hasUnreadAlarm())
    }

    @Test
    fun `timeline timestamp is exact and includes date outside today`() {
        val zone = ZoneId.of("Asia/Taipei")
        val now = Instant.parse("2026-07-19T07:00:00Z").toEpochMilli()
        val today = Instant.parse("2026-07-19T06:03:04Z").toEpochMilli()
        val yesterday = Instant.parse("2026-07-18T06:03:04Z").toEpochMilli()

        assertEquals("14:03:04", formatTimelineTimestamp(today, now, zone))
        assertEquals("7/18 14:03:04", formatTimelineTimestamp(yesterday, now, zone))
        assertEquals("2026年7月18日 14:03:04", timelineTimestampContentDescription(yesterday, zone))
    }

    @Test
    fun `relative timestamp is deterministic`() {
        val now = 10 * 60_000L
        assertEquals("剛剛", relativeTime(now - 30_000, now))
        assertEquals("2 分鐘前", relativeTime(now - 2 * 60_000, now))
        assertEquals("剛剛", relativeTime(now + 30_000, now))
    }

    @Test
    fun `channel search preserves unread count`() {
        val events = listOf(
            event(channelId = "alpha", receivedAt = 1000),
            event(channelId = "alpha", receivedAt = 2000),
            event(channelId = "beta", receivedAt = 3000)
        )
        val items = events.filterChannelInboxItems("alp", mapOf("alpha" to 1000L))
        assertEquals(1, items.size)
        assertEquals(1, items.first().unreadCount)
    }

    @Test
    fun `trashed channels are excluded from inbox and search`() {
        val events = listOf(
            event(channelId = "active", receivedAt = 1000),
            event(channelId = "trashed", receivedAt = 2000)
        )

        assertEquals(
            listOf("active"),
            events.filterChannelInboxItems("", excludedChannelIds = setOf("trashed")).map { it.channelId }
        )
        assertEquals(
            emptyList<String>(),
            events.filterChannelInboxItems("trashed", excludedChannelIds = setOf("trashed")).map { it.channelId }
        )
    }

    @Test
    fun `channel inbox displays channelName when present`() {
        val events = listOf(
            event(channelId = "p1", channelName = "頻道一", receivedAt = 1000)
        )
        val items = events.toChannelInboxItems()
        assertEquals("頻道一", items.first().displayName)
    }

    @Test
    fun `channel inbox display name survives latest event without name`() {
        val events = listOf(
            event(channelId = "p1", channelName = "頻道一", receivedAt = 1000),
            event(channelId = "p1", action = "stop", receivedAt = 2000)
        )
        val items = events.toChannelInboxItems()
        assertEquals("頻道一", items.first().displayName)
    }

    @Test
    fun `filters timeline events by channelId`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", receivedAt = 1000),
            event(channelId = "p2", action = "notify", receivedAt = 2000),
            event(channelId = "p1", action = "ring", receivedAt = 3000)
        )
        val timeline = events.channelTimeline("p1")
        assertEquals(2, timeline.size)
        assertEquals("p1", timeline.first().channelId)
    }

    @Test
    fun `timeline orders events by receivedAt ascending`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", receivedAt = 1000),
            event(channelId = "p1", action = "ring", receivedAt = 3000),
            event(channelId = "p1", action = "notify", receivedAt = 2000)
        )
        val timeline = events.channelTimeline("p1")
        assertEquals(listOf(1000L, 2000L, 3000L), timeline.map { it.receivedAt })
    }

    @Test
    fun `timeline display name falls back to channelId`() {
        val events = listOf(event(channelId = "p1", receivedAt = 1000))
        assertEquals("p1", events.channelDisplayName("p1"))
    }

    @Test
    fun `timeline display name uses latest received channelName`() {
        val events = listOf(
            event(channelId = "p1", channelName = "依接收時間", receivedAt = 3000),
            event(channelId = "p1", channelName = "舊名稱", receivedAt = 2000),
            event(channelId = "p1", receivedAt = 1000)
        )
        assertEquals("依接收時間", events.channelDisplayName("p1"))
        assertEquals("依接收時間", events.toChannelInboxItems().single().displayName)
    }

    @Test
    fun `classifies notify with normal mode as normal notification`() {
        assertEquals(
            TimelineDisplayType.NORMAL_NOTIFICATION,
            event(action = "notify", notificationMode = "normal").toTimelineDisplayType()
        )
    }

    @Test
    fun `classifies notify with quiet mode as quiet notification`() {
        assertEquals(
            TimelineDisplayType.QUIET_NOTIFICATION,
            event(action = "notify", notificationMode = "quiet").toTimelineDisplayType()
        )
    }

    @Test
    fun `classifies lifecycle stopped alarm as stop record`() {
        assertEquals(
            TimelineDisplayType.STOP_RECORD,
            event(action = "ring", kind = "alarm", state = "stopped").toTimelineDisplayType()
        )
    }

    @Test
    fun `classifies lifecycle missed alarm as missed`() {
        assertEquals(
            TimelineDisplayType.MISSED_ALARM,
            event(action = "ring", kind = "alarm", state = "missed").toTimelineDisplayType()
        )
    }

    @Test
    fun `classifies pending lifecycle alarm as waiting not ringing`() {
        assertEquals(
            TimelineDisplayType.PENDING_ALARM,
            event(action = "ring", kind = "alarm", state = "pending").toTimelineDisplayType()
        )
    }

    @Test
    fun `classifies ringing lifecycle alarm as ringing`() {
        assertEquals(
            TimelineDisplayType.RING_ALARM,
            event(action = "ring", kind = "alarm", state = "ringing").toTimelineDisplayType()
        )
    }

    @Test
    fun `channel list search matches channelId`() {
        val events = listOf(
            event(channelId = "alpha", receivedAt = 1000),
            event(channelId = "beta", receivedAt = 2000)
        )
        val items = events.filterChannelInboxItems("bet")
        assertEquals(listOf("beta"), items.map { it.channelId })
    }

    @Test
    fun `channel list search matches channelName`() {
        val events = listOf(
            event(channelId = "p1", channelName = "會議室 A", receivedAt = 1000),
            event(channelId = "p2", channelName = "倉庫 B", receivedAt = 2000)
        )
        val items = events.filterChannelInboxItems("會議室")
        assertEquals(1, items.size)
        assertEquals("會議室 A", items.first().displayName)
    }

    @Test
    fun `channel list search is case insensitive`() {
        val events = listOf(
            event(channelId = "Alpha", channelName = "頻道", receivedAt = 1000)
        )
        val items = events.filterChannelInboxItems("alpha")
        assertEquals(1, items.size)
    }

    @Test
    fun `timeline search matches title`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", title = "緊急會議", receivedAt = 1000),
            event(channelId = "p1", action = "notify", title = "日常更新", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "緊急", TimelineFilter.ALL)
        assertEquals(1, filtered.size)
        assertEquals("緊急會議", filtered.first().title)
    }

    @Test
    fun `timeline search matches message`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", message = "設備離線", receivedAt = 1000),
            event(channelId = "p1", action = "notify", message = "運作正常", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "離線", TimelineFilter.ALL)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `timeline search matches tags`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", tagsJson = """["urgent"]""", receivedAt = 1000),
            event(channelId = "p1", action = "notify", tagsJson = """["normal"]""", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "urgent", TimelineFilter.ALL)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `timeline filter normal`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", notificationMode = "normal", receivedAt = 1000),
            event(channelId = "p1", action = "notify", notificationMode = "quiet", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "", TimelineFilter.NORMAL)
        assertEquals(1, filtered.size)
        assertEquals("normal", filtered.first().notificationMode)
    }

    @Test
    fun `timeline filter quiet`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", notificationMode = "normal", receivedAt = 1000),
            event(channelId = "p1", action = "notify", notificationMode = "quiet", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "", TimelineFilter.QUIET)
        assertEquals(1, filtered.size)
        assertEquals("quiet", filtered.first().notificationMode)
    }

    @Test
    fun `timeline filter pending alarm as undelivered`() {
        val events = listOf(
            event(channelId = "p1", action = "ring", kind = "alarm", state = "pending", receivedAt = 1000),
            event(channelId = "p1", action = "ring", kind = "alarm", state = "ringing", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "", TimelineFilter.PENDING)
        assertEquals(1, filtered.size)
        assertEquals("pending", filtered.first().state)
    }

    @Test
    fun `timeline filter ring`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", receivedAt = 1000),
            event(channelId = "p1", action = "ring", kind = "alarm", state = "ringing", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "", TimelineFilter.RING, now = 1000)
        assertEquals(1, filtered.size)
        assertEquals("ring", filtered.first().action)
    }

    @Test
    fun `timeline filter stop`() {
        val events = listOf(
            event(channelId = "p1", action = "notify", receivedAt = 1000),
            event(channelId = "p1", action = "ring", kind = "alarm", state = "stopped", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "", TimelineFilter.STOP)
        assertEquals(1, filtered.size)
        assertEquals("stopped", filtered.first().state)
    }

    @Test
    fun `timeline filter missed`() {
        val events = listOf(
            event(channelId = "p1", action = "ring", kind = "alarm", state = "missed", receivedAt = 1000),
            event(channelId = "p1", action = "ring", kind = "alarm", state = "ringing", receivedAt = 2000)
        )
        val filtered = events.filterTimeline("p1", "", TimelineFilter.MISSED, now = 2000)
        assertEquals(1, filtered.size)
        assertEquals("missed", filtered.first().state)
    }

    private fun event(
        channelId: String = "default",
        channelName: String? = null,
        action: String = "notify",
        title: String? = null,
        message: String? = null,
        tagsJson: String? = null,
        notificationMode: String = "normal",
        ringUntilMillis: Long? = null,
        kind: String = "notification",
        state: String = "pending",
        receivedAt: Long = 0
    ) = DenDenEvent(
        eventId = "$channelId-$receivedAt-$action",
        channelId = channelId,
        channelName = channelName,
        action = action,
        title = title,
        message = message,
        tagsJson = tagsJson,
        notificationMode = notificationMode,
        ringUntilMillis = ringUntilMillis,
        kind = kind,
        state = state,
        receivedAt = receivedAt
    )
}
