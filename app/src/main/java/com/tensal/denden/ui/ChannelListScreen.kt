package com.tensal.denden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tensal.denden.DenDenColors
import com.tensal.denden.R
import com.tensal.denden.data.DenDenEvent
import java.util.concurrent.TimeUnit

internal enum class ChannelSwipeAction { ARCHIVE, DELETE, NONE }

internal fun channelSwipeAction(value: SwipeToDismissBoxValue): ChannelSwipeAction = when (value) {
    SwipeToDismissBoxValue.EndToStart -> ChannelSwipeAction.ARCHIVE
    SwipeToDismissBoxValue.StartToEnd -> ChannelSwipeAction.DELETE
    SwipeToDismissBoxValue.Settled -> ChannelSwipeAction.NONE
}

@Composable
fun ChannelListScreen(
    events: List<DenDenEvent> = emptyList(),
    channelItems: List<ChannelInboxItem>? = null,
    lastReadAtByChannel: Map<String, Long> = emptyMap(),
    archivedChannelIds: Set<String> = emptySet(),
    trashedChannelIds: Set<String> = emptySet(),
    onChannelArchivedChange: (String, Boolean) -> Unit = { _, _ -> },
    onChannelDeleted: (String) -> Unit = {},
    onChannelSelected: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val allActiveChannels = remember(channelItems, events, lastReadAtByChannel, archivedChannelIds, trashedChannelIds) {
        (channelItems ?: events.filterChannelInboxItems("", lastReadAtByChannel, trashedChannelIds))
            .filterNot { it.channelId in trashedChannelIds }
            .filterNot {
                if (channelItems != null) it.archived else it.channelId in archivedChannelIds
            }
    }
    val channels = remember(allActiveChannels, normalized) {
        allActiveChannels.filter {
            normalized.isBlank() || it.channelId.lowercase().contains(normalized) ||
                it.displayName.lowercase().contains(normalized)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DenDenColors.background)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            placeholder = { Text(stringResource(R.string.search_channels)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = DenDenColors.outline
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DenDenColors.surfaceContainerLowest,
                unfocusedContainerColor = DenDenColors.surfaceContainerLowest,
                focusedBorderColor = DenDenColors.outline,
                unfocusedBorderColor = DenDenColors.outlineVariant
            )
        )

        Box(modifier = Modifier.weight(1f)) {
            if (channels.isEmpty()) {
                EmptyChannelList(isSearchResult = query.isNotBlank() && allActiveChannels.isNotEmpty())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { "active:${it.channelId}" }) { channel ->
                        SwipeableChannelInboxCard(
                            channel = channel,
                            archived = false,
                            onArchivedChange = onChannelArchivedChange,
                            onDeleted = onChannelDeleted,
                            onClick = { onChannelSelected(channel.channelId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArchivedChannelsScreen(
    events: List<DenDenEvent> = emptyList(),
    channelItems: List<ChannelInboxItem>? = null,
    lastReadAtByChannel: Map<String, Long> = emptyMap(),
    archivedChannelIds: Set<String> = emptySet(),
    trashedChannelIds: Set<String> = emptySet(),
    onBack: () -> Unit,
    onChannelSelected: (String) -> Unit,
    onChannelUnarchived: (String) -> Unit
) {
    val archivedChannels = remember(channelItems, events, lastReadAtByChannel, archivedChannelIds, trashedChannelIds) {
        (channelItems ?: events.filterChannelInboxItems("", lastReadAtByChannel, trashedChannelIds))
            .filterNot { it.channelId in trashedChannelIds }
            .filter { if (channelItems != null) it.archived else it.channelId in archivedChannelIds }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DenDenColors.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_to_channels),
                    tint = DenDenColors.primary
                )
            }
            Text(
                text = stringResource(R.string.archive),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = DenDenColors.onSurface
                )
            )
        }

        Column(
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.archived_channels_count,
                    archivedChannels.size,
                    archivedChannels.size
                ),
                style = MaterialTheme.typography.titleMedium,
                color = DenDenColors.onSurface
            )
            Text(
                text = stringResource(R.string.archived_channels_description),
                style = MaterialTheme.typography.bodyMedium,
                color = DenDenColors.onSurfaceVariant
            )
        }

        if (archivedChannels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_archived_channels),
                    style = MaterialTheme.typography.bodyLarge,
                    color = DenDenColors.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(archivedChannels, key = { "archived:${it.channelId}" }) { channel ->
                    ArchivedChannelCard(
                        channel = channel,
                        onClick = { onChannelSelected(channel.channelId) },
                        onUnarchive = { onChannelUnarchived(channel.channelId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeableChannelInboxCard(
    channel: ChannelInboxItem,
    archived: Boolean,
    onArchivedChange: (String, Boolean) -> Unit,
    onDeleted: (String) -> Unit,
    onClick: () -> Unit
) {
    val actionLabel = stringResource(if (archived) R.string.unarchive else R.string.archive)
    val moveToTrashLabel = stringResource(R.string.move_to_trash)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (channelSwipeAction(it)) {
                ChannelSwipeAction.ARCHIVE -> {
                    onArchivedChange(channel.channelId, !archived)
                    true
                }
                ChannelSwipeAction.DELETE -> {
                    onDeleted(channel.channelId)
                    false
                }
                ChannelSwipeAction.NONE -> false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DenDenColors.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    text = moveToTrashLabel,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Text(
                    text = actionLabel,
                    color = DenDenColors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        },
        content = {
            ChannelInboxCard(
                channel = channel,
                onClick = onClick,
                modifier = Modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(actionLabel) {
                            onArchivedChange(channel.channelId, !archived)
                            true
                        },
                        CustomAccessibilityAction(moveToTrashLabel) {
                            onDeleted(channel.channelId)
                            true
                        }
                    )
                }
            )
        }
    )
}

@Composable
private fun EmptyChannelList(isSearchResult: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(DenDenColors.surfaceContainerLow, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = DenDenColors.outline,
                    modifier = Modifier.size(48.dp)
                )
            }
            Text(
                text = stringResource(
                    if (isSearchResult) R.string.no_matching_channels else R.string.no_channels
                ),
                style = MaterialTheme.typography.titleMedium,
                color = DenDenColors.onSurface
            )
            Text(
                text = stringResource(
                    if (isSearchResult) R.string.try_another_channel_search else R.string.first_message_hint
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = DenDenColors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun ChannelInboxCard(
    channel: ChannelInboxItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = DenDenColors.surfaceContainerLowest,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (channel.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                            color = DenDenColors.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = localizedRelativeTime(channel.latestEvent.receivedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = DenDenColors.onSurfaceVariant
                    )
                }

                Text(
                    text = channel.latestEvent.title ?: channel.latestEvent.computedSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenDenColors.onSurfaceVariant
                )
            }

            unreadBadgeText(channel.unreadCount)?.let { unread ->
                val alarm = channel.hasUnreadAlarm()
                val unreadDescription = pluralStringResource(
                    if (alarm) R.plurals.unread_alarms else R.plurals.unread_messages,
                    channel.unreadCount,
                    channel.unreadCount
                )
                Surface(
                    shape = CircleShape,
                    color = if (alarm) DenDenColors.error else DenDenColors.primary,
                    contentColor = if (alarm) MaterialTheme.colorScheme.onError else DenDenColors.onPrimary
                ) {
                    Text(
                        text = unread,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .wrapContentSize(Alignment.Center)
                            .padding(horizontal = 5.dp)
                            .semantics {
                                contentDescription = unreadDescription
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedChannelCard(
    channel: ChannelInboxItem,
    onClick: () -> Unit,
    onUnarchive: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DenDenColors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, DenDenColors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onClick),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = DenDenColors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = localizedRelativeTime(channel.latestEvent.receivedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = DenDenColors.onSurfaceVariant
                    )
                }
                Text(
                    text = channel.latestEvent.title ?: channel.latestEvent.computedSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenDenColors.onSurfaceVariant
                )
            }
            TextButton(onClick = onUnarchive) { Text(stringResource(R.string.unarchive)) }
        }
    }
}

data class ChannelInboxItem(
    val channelId: String,
    val displayName: String,
    val latestEvent: DenDenEvent,
    val eventCount: Int,
    val unreadCount: Int = 0,
    val archived: Boolean = false
)

fun List<DenDenEvent>.toChannelInboxItems(
    lastReadAtByChannel: Map<String, Long> = emptyMap()
): List<ChannelInboxItem> {
    return groupBy { it.channelId }
        .map { (channelId, events) ->
            // ponytail: maxByOrNull is safe because groupBy values are non-empty
            val latest = events.maxByOrNull { it.receivedAt }!!
            val lastReadAt = lastReadAtByChannel[channelId] ?: 0L
            val displayName = events.asSequence()
                .sortedByDescending { it.receivedAt }
                .mapNotNull { it.channelName?.takeIf { name -> name.isNotBlank() } }
                .firstOrNull() ?: channelId
            ChannelInboxItem(
                channelId = channelId,
                displayName = displayName,
                latestEvent = latest,
                eventCount = events.size,
                unreadCount = events.count { it.receivedAt > lastReadAt }
            )
        }
        .sortedByDescending { it.latestEvent.receivedAt }
}

fun List<DenDenEvent>.filterChannelInboxItems(
    query: String,
    lastReadAtByChannel: Map<String, Long> = emptyMap(),
    excludedChannelIds: Set<String> = emptySet()
): List<ChannelInboxItem> {
    val normalized = query.trim().lowercase()
    return filterNot { it.channelId in excludedChannelIds }.toChannelInboxItems(lastReadAtByChannel).filter {
        normalized.isBlank() ||
            it.channelId.lowercase().contains(normalized) ||
            it.displayName.lowercase().contains(normalized)
    }
}

internal fun unreadBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

internal fun ChannelInboxItem.hasUnreadAlarm(): Boolean = unreadCount > 0 && when (
    latestEvent.toTimelineDisplayType()
) {
    TimelineDisplayType.PENDING_ALARM,
    TimelineDisplayType.RING_ALARM,
    TimelineDisplayType.MISSED_ALARM -> true
    else -> false
}

fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - timestamp).coerceAtLeast(0)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "剛剛"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            "${mins} 分鐘前"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "${hours} 小時前"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "${days} 天前"
        }
        else -> "很久以前"
    }
}

@Composable
internal fun localizedRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - timestamp).coerceAtLeast(0)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> stringResource(R.string.just_now)
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff).toInt()
            pluralStringResource(R.plurals.minutes_ago, minutes, minutes)
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff).toInt()
            pluralStringResource(R.plurals.hours_ago, hours, hours)
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()
            pluralStringResource(R.plurals.days_ago, days, days)
        }
        else -> stringResource(R.string.long_ago)
    }
}
