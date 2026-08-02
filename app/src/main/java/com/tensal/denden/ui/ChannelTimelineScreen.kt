package com.tensal.denden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.tensal.denden.DenDenColors
import com.tensal.denden.R
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.data.TimelineVersion
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val TIMELINE_PAGE_SIZE = 100
private const val MAX_VISIBLE_TAGS = 50
private val TODAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.TAIWAN)
private val DATED_TIME_FORMATTER = DateTimeFormatter.ofPattern("M/d HH:mm:ss", Locale.TAIWAN)

private data class TimelineQueryKey(
    val channelId: String,
    val query: String,
    val filter: String,
    val tag: String?
)

internal fun prependOlderTimelineEvents(
    current: List<DenDenEvent>,
    newestFirst: List<DenDenEvent>,
    pageSize: Int
): List<DenDenEvent> {
    val currentIds = current.asSequence().map(DenDenEvent::id).toHashSet()
    return newestFirst.take(pageSize).asReversed().filterNot { it.id in currentIds } + current
}

internal fun mergeNewestTimelineEvents(
    current: List<DenDenEvent>,
    newestFirst: List<DenDenEvent>,
    pageSize: Int
): List<DenDenEvent> {
    val newestPage = newestFirst.take(pageSize)
    if (newestPage.isEmpty() || newestFirst.size <= pageSize) return newestPage.asReversed()
    val boundary = newestPage.last()
    val retainedOlder = current.filter {
        it.receivedAt < boundary.receivedAt || it.receivedAt == boundary.receivedAt && it.id < boundary.id
    }
    return (retainedOlder + newestPage.asReversed()).distinctBy(DenDenEvent::id)
}

@Composable
fun ChannelTimelineScreen(
    channelId: String,
    events: List<DenDenEvent>? = null,
    initialNewestEvents: List<DenDenEvent>? = null,
    channelName: String? = null,
    onBack: () -> Unit,
    isInTrash: Boolean = false,
    onRestore: () -> Unit = {},
    onPermanentDelete: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(TimelineFilter.ALL) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var databaseEvents by remember(channelId, initialNewestEvents) {
        mutableStateOf(initialNewestEvents?.take(TIMELINE_PAGE_SIZE)?.asReversed())
    }
    var hasMore by remember(channelId, initialNewestEvents) {
        mutableStateOf((initialNewestEvents?.size ?: 0) > TIMELINE_PAGE_SIZE)
    }
    var loadOlderRequest by remember(channelId) { mutableIntStateOf(0) }
    var handledLoadOlderRequest by remember(channelId) { mutableIntStateOf(0) }
    var loadedQueryKey by remember(channelId) { mutableStateOf<TimelineQueryKey?>(null) }
    var loadedTimelineVersion by remember(channelId) { mutableStateOf<TimelineVersion?>(null) }
    var confirmPermanentDelete by remember { mutableStateOf(false) }
    var showExactTimestamps by remember { mutableStateOf(false) }
    var nowMillis by remember(channelId) { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val dao = remember(context) { EventDatabase.getInstance(context).messageQueryDao() }
    val timelineVersion by dao.observeTimelineVersion(channelId)
        .collectAsState(initial = TimelineVersion(null, 0))
    val databaseTags by dao.observeChannelTags(channelId, MAX_VISIBLE_TAGS)
        .collectAsState(initial = emptyList())
    val databaseFilterValues by dao.observeChannelFilters(channelId)
        .collectAsState(initial = emptyList())
    val inMemoryTags = remember(events, channelId) {
        events.orEmpty().asSequence()
            .filter { it.channelId == channelId }
            .flatMap { it.tags().asSequence() }
            .distinct().take(MAX_VISIBLE_TAGS).toList()
    }
    val inMemoryFilterValues = remember(events, channelId) {
        events.orEmpty().asSequence()
            .filter { it.channelId == channelId }
            .map { it.timelineFilter().queryValue }
            .toSet()
    }
    val availableTags = if (events == null) databaseTags else inMemoryTags
    val availableFilterValues = if (events == null) {
        databaseFilterValues.toSet()
    } else {
        inMemoryFilterValues
    }
    val availableFilters = remember(availableFilterValues) {
        listOf(TimelineFilter.ALL) + TimelineFilter.entries
            .drop(1)
            .filter { it.queryValue in availableFilterValues }
    }
    val timelineLoading = events == null && databaseEvents == null
    val timelineEvents = if (events == null) {
        databaseEvents.orEmpty()
    } else {
        remember(events, channelId, query, filter, selectedTag) {
            events.filterTimeline(channelId, query, filter)
                .filter { selectedTag == null || selectedTag in it.tags() }
        }
    }
    val displayName = channelName ?: events.orEmpty().channelDisplayName(channelId)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = timelineEvents.lastIndex.coerceAtLeast(0)
    )
    val closeSearch = {
        query = ""
        filter = TimelineFilter.ALL
        selectedTag = null
        searchExpanded = false
    }
    val timelineQueryKey = remember(channelId, query, filter, selectedTag) {
        TimelineQueryKey(channelId, query.trim(), filter.queryValue, selectedTag)
    }

    LaunchedEffect(channelId) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(availableFilters) {
        if (filter !in availableFilters) filter = TimelineFilter.ALL
    }

    LaunchedEffect(timelineQueryKey, timelineVersion, loadOlderRequest) {
        if (events != null) return@LaunchedEffect
        suspend fun page(before: DenDenEvent? = null) = dao.getTimelinePage(
            channelId = timelineQueryKey.channelId,
            query = timelineQueryKey.query,
            filter = timelineQueryKey.filter,
            tag = timelineQueryKey.tag,
            beforeReceivedAt = before?.receivedAt,
            beforeId = before?.id,
            limit = TIMELINE_PAGE_SIZE + 1
        )

        val previousVersion = loadedTimelineVersion
        val queryChanged = loadedQueryKey != timelineQueryKey
        val latestId = timelineVersion.latestId
        val previousLatestId = previousVersion?.latestId
        val dataShrank = previousVersion != null && (
            timelineVersion.eventCount < previousVersion.eventCount ||
                (previousLatestId != null && (latestId == null || latestId < previousLatestId))
            )
        if (queryChanged || previousVersion == null || dataShrank) {
            val rows = page()
            databaseEvents = rows.take(TIMELINE_PAGE_SIZE).asReversed()
            hasMore = rows.size > TIMELINE_PAGE_SIZE
            handledLoadOlderRequest = loadOlderRequest
        } else {
            var nextEvents = databaseEvents.orEmpty()
            var nextHasMore = hasMore
            if (timelineVersion != previousVersion) {
                val rows = page()
                val hadLoadedEvents = nextEvents.isNotEmpty()
                nextEvents = mergeNewestTimelineEvents(nextEvents, rows, TIMELINE_PAGE_SIZE)
                if (!hadLoadedEvents) nextHasMore = rows.size > TIMELINE_PAGE_SIZE
            }
            if (loadOlderRequest != handledLoadOlderRequest && nextHasMore) {
                val rows = page(nextEvents.firstOrNull())
                nextEvents = prependOlderTimelineEvents(nextEvents, rows, TIMELINE_PAGE_SIZE)
                nextHasMore = rows.size > TIMELINE_PAGE_SIZE
            }
            databaseEvents = nextEvents
            hasMore = nextHasMore
            handledLoadOlderRequest = loadOlderRequest
        }
        loadedQueryKey = timelineQueryKey
        loadedTimelineVersion = timelineVersion
    }

    LaunchedEffect(channelId, timelineEvents.isNotEmpty()) {
        if (timelineEvents.isNotEmpty()) listState.scrollToItem(timelineEvents.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DenDenColors.background)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_to_channels),
                    tint = DenDenColors.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = DenDenColors.onSurface
                    )
                )
                Text(
                    text = if (displayName == channelId) stringResource(R.string.message_channel) else channelId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = DenDenColors.onSurfaceVariant
                    )
                )
            }
            IconButton(
                onClick = {
                    if (searchExpanded) closeSearch() else searchExpanded = true
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = stringResource(
                        if (searchExpanded) R.string.collapse_search else R.string.search_events
                    ),
                    tint = DenDenColors.primary
                )
            }
        }

        if (searchExpanded) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.search_events_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = DenDenColors.outline
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_search),
                                tint = DenDenColors.outline
                            )
                        }
                    }
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
            TimelineFilterChips(
                filters = availableFilters,
                selected = filter,
                onSelected = { filter = it },
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (availableTags.isNotEmpty()) {
                TimelineTagFilterChips(
                    tags = availableTags,
                    selected = selectedTag,
                    onSelected = { selectedTag = it },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        if (isInTrash) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.restore_channel))
                }
                Button(
                    onClick = { confirmPermanentDelete = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DenDenColors.error)
                ) { Text(stringResource(R.string.delete_permanently_from_device)) }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                timelineLoading -> LoadingTimeline()
                timelineEvents.isEmpty() -> EmptyTimeline()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (events == null && hasMore) {
                        item(key = "load-older") {
                            TextButton(
                                onClick = { loadOlderRequest++ },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.load_older_messages)) }
                        }
                    }
                    items(timelineEvents, key = { it.id }) { event ->
                        TimelineEventCard(
                            event = event,
                            nowMillis = nowMillis,
                            showExactTime = showExactTimestamps,
                            onToggleTime = { showExactTimestamps = !showExactTimestamps }
                        )
                    }
                }
            }
        }
    }

    if (confirmPermanentDelete) {
        val deleteTitle = stringResource(R.string.delete_permanently_title)
        val deleteMessage = stringResource(R.string.delete_permanently_message)
        val deleteLabel = stringResource(R.string.delete_permanently)
        val cancelLabel = stringResource(R.string.cancel)
        AlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanentDelete = false
                    onPermanentDelete()
                }) { Text(deleteLabel) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) { Text(cancelLabel) }
            }
        )
    }
}

@Composable
private fun TimelineTagFilterChips(
    tags: List<String>,
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.all_tags)) }
        )
        tags.forEach { tag ->
            FilterChip(
                selected = selected == tag,
                onClick = { onSelected(if (selected == tag) null else tag) },
                label = { Text(tag, maxLines = 1) }
            )
        }
    }
}

@Composable
private fun TimelineFilterChips(
    filters: List<TimelineFilter>,
    selected: TimelineFilter,
    onSelected: (TimelineFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { entry ->
            FilterChip(
                selected = selected == entry,
                onClick = { onSelected(entry) },
                label = { Text(timelineFilterLabel(entry)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = DenDenColors.primaryContainer,
                    selectedLabelColor = DenDenColors.onPrimaryContainer,
                    containerColor = DenDenColors.surfaceContainerLowest,
                    labelColor = DenDenColors.onSurface
                )
            )
        }
    }
}

@Composable
private fun LoadingTimeline() {
    val label = stringResource(R.string.loading_events)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = label },
            color = DenDenColors.primary
        )
    }
}

@Composable
private fun EmptyTimeline() {
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
                text = stringResource(R.string.no_events),
                style = MaterialTheme.typography.titleMedium,
                color = DenDenColors.onSurface
            )
            Text(
                text = stringResource(R.string.no_events_description),
                style = MaterialTheme.typography.bodyMedium,
                color = DenDenColors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun TimelineEventCard(
    event: DenDenEvent,
    nowMillis: Long,
    showExactTime: Boolean,
    onToggleTime: () -> Unit
) {
    val style = timelineEventStyle(event)
    val styleLabel = stringResource(style.labelRes)
    val relativeTimeLabel = stringResource(R.string.show_relative_time)
    val exactTimeLabel = stringResource(R.string.show_exact_time)
    val exactState = stringResource(R.string.exact_time)
    val relativeState = stringResource(R.string.relative_time)
    val locale = Locale.getDefault()
    val timestampDescription = remember(event.receivedAt, locale) {
        timelineTimestampContentDescription(event.receivedAt, locale = locale)
    }
    val tags = remember(event.tagsJson) { event.tags() }
    val title = event.title?.takeIf { it.isNotBlank() }
    val message = event.message?.takeIf { it.isNotBlank() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DenDenColors.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, DenDenColors.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = DenDenColors.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (showExactTime) relativeTimeLabel else exactTimeLabel,
                            onClick = onToggleTime
                        )
                        .semantics {
                            contentDescription = "$styleLabel, $timestampDescription"
                            stateDescription = if (showExactTime) exactState else relativeState
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.iconColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (showExactTime) {
                            formatTimelineTimestamp(event.receivedAt, nowMillis)
                        } else {
                            localizedRelativeTime(event.receivedAt, nowMillis)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = DenDenColors.onSurfaceVariant
                    )
                }
            }

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenDenColors.onSurfaceVariant
                )
            } else if (title == null) {
                Text(
                    text = styleLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenDenColors.onSurfaceVariant
                )
            }

            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = DenDenColors.primaryContainer.copy(alpha = 0.16f),
                            contentColor = DenDenColors.primary
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatTimelineTimestamp(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val time = Instant.ofEpochMilli(timestamp).atZone(zoneId)
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    return time.format(if (time.toLocalDate() == today) TODAY_TIME_FORMATTER else DATED_TIME_FORMATTER)
}

internal fun timelineTimestampContentDescription(
    timestamp: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.TAIWAN
): String = Instant.ofEpochMilli(timestamp).atZone(zoneId)
    .format(
        DateTimeFormatter.ofPattern(
            if (locale.language == "zh") "yyyy年M月d日 HH:mm:ss" else "MMM d, yyyy HH:mm:ss",
            locale
        )
    )

enum class TimelineDisplayType {
    NORMAL_NOTIFICATION,
    QUIET_NOTIFICATION,
    PENDING_ALARM,
    RING_ALARM,
    STOP_RECORD,
    MISSED_ALARM
}

fun DenDenEvent.toTimelineDisplayType(
    @Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis()
): TimelineDisplayType = when (state) {
    "stopped" -> TimelineDisplayType.STOP_RECORD
    "missed" -> TimelineDisplayType.MISSED_ALARM
    "ringing" -> TimelineDisplayType.RING_ALARM
    "delivered" -> notificationDisplayType()
    "pending" -> if (kind == "alarm") {
        TimelineDisplayType.PENDING_ALARM
    } else {
        notificationDisplayType()
    }
    else -> notificationDisplayType()
}

private fun DenDenEvent.notificationDisplayType(): TimelineDisplayType =
    if (notificationMode == "quiet") TimelineDisplayType.QUIET_NOTIFICATION
    else TimelineDisplayType.NORMAL_NOTIFICATION

enum class TimelineFilter { ALL, NORMAL, QUIET, PENDING, RING, STOP, MISSED }

private val TimelineFilter.queryValue: String
    get() = when (this) {
        TimelineFilter.ALL -> "all"
        TimelineFilter.NORMAL -> "normal"
        TimelineFilter.QUIET -> "quiet"
        TimelineFilter.PENDING -> "pending"
        TimelineFilter.RING -> "ring"
        TimelineFilter.STOP -> "stop"
        TimelineFilter.MISSED -> "missed"
    }

private fun DenDenEvent.timelineFilter(): TimelineFilter {
    return when (toTimelineDisplayType()) {
        TimelineDisplayType.NORMAL_NOTIFICATION -> TimelineFilter.NORMAL
        TimelineDisplayType.QUIET_NOTIFICATION -> TimelineFilter.QUIET
        TimelineDisplayType.PENDING_ALARM -> TimelineFilter.PENDING
        TimelineDisplayType.RING_ALARM -> TimelineFilter.RING
        TimelineDisplayType.STOP_RECORD -> TimelineFilter.STOP
        TimelineDisplayType.MISSED_ALARM -> TimelineFilter.MISSED
    }
}

fun DenDenEvent.matchesTimelineQuery(query: String): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isBlank()) return true
    return title?.lowercase()?.contains(normalized) == true ||
        message?.lowercase()?.contains(normalized) == true ||
        tagsJson?.lowercase()?.contains(normalized) == true
}

fun DenDenEvent.matchesTimelineFilter(filter: TimelineFilter, now: Long = System.currentTimeMillis()): Boolean =
    when (filter) {
        TimelineFilter.ALL -> true
        TimelineFilter.NORMAL -> toTimelineDisplayType(now) == TimelineDisplayType.NORMAL_NOTIFICATION
        TimelineFilter.QUIET -> toTimelineDisplayType(now) == TimelineDisplayType.QUIET_NOTIFICATION
        TimelineFilter.PENDING -> toTimelineDisplayType(now) == TimelineDisplayType.PENDING_ALARM
        TimelineFilter.RING -> toTimelineDisplayType(now) == TimelineDisplayType.RING_ALARM
        TimelineFilter.STOP -> toTimelineDisplayType(now) == TimelineDisplayType.STOP_RECORD
        TimelineFilter.MISSED -> toTimelineDisplayType(now) == TimelineDisplayType.MISSED_ALARM
    }

fun List<DenDenEvent>.filterTimeline(
    channelId: String,
    query: String,
    filter: TimelineFilter,
    now: Long = System.currentTimeMillis()
): List<DenDenEvent> {
    return filter { it.channelId == channelId }
        .filter { it.matchesTimelineQuery(query) && it.matchesTimelineFilter(filter, now) }
        .sortedBy { it.receivedAt }
}

fun List<DenDenEvent>.channelTimeline(channelId: String): List<DenDenEvent> {
    return filter { it.channelId == channelId }
        .sortedBy { it.receivedAt }
}

fun List<DenDenEvent>.channelDisplayName(channelId: String): String {
    return filter { it.channelId == channelId }
        .sortedByDescending { it.receivedAt }
        .firstNotNullOfOrNull { it.channelName?.takeIf { name -> name.isNotBlank() } }
        ?: channelId
}

private data class TimelineEventStyle(
    val icon: ImageVector,
    val iconColor: Color,
    @param:StringRes val labelRes: Int
)

private fun timelineEventStyle(event: DenDenEvent): TimelineEventStyle = when (event.toTimelineDisplayType()) {
    TimelineDisplayType.PENDING_ALARM -> TimelineEventStyle(
        icon = Icons.Default.Alarm,
        iconColor = DenDenColors.warning,
        labelRes = R.string.event_pending
    )
    TimelineDisplayType.RING_ALARM -> TimelineEventStyle(
        icon = Icons.Default.NotificationsActive,
        iconColor = DenDenColors.error,
        labelRes = R.string.event_ringing
    )
    TimelineDisplayType.STOP_RECORD -> TimelineEventStyle(
        icon = Icons.Default.CheckCircle,
        iconColor = DenDenColors.success,
        labelRes = R.string.event_stopped
    )
    TimelineDisplayType.QUIET_NOTIFICATION -> TimelineEventStyle(
        icon = Icons.Default.NotificationsOff,
        iconColor = DenDenColors.onSurfaceVariant,
        labelRes = R.string.event_quiet
    )
    TimelineDisplayType.NORMAL_NOTIFICATION -> TimelineEventStyle(
        icon = Icons.Default.Notifications,
        iconColor = DenDenColors.primary,
        labelRes = R.string.event_normal
    )
    TimelineDisplayType.MISSED_ALARM -> TimelineEventStyle(
        icon = Icons.Default.NotificationsActive,
        iconColor = DenDenColors.warning,
        labelRes = R.string.event_missed
    )
}

@Composable
private fun timelineFilterLabel(filter: TimelineFilter): String = stringResource(
    when (filter) {
        TimelineFilter.ALL -> R.string.filter_all
        TimelineFilter.NORMAL -> R.string.filter_normal
        TimelineFilter.QUIET -> R.string.filter_quiet
        TimelineFilter.PENDING -> R.string.filter_pending
        TimelineFilter.RING -> R.string.filter_ring
        TimelineFilter.STOP -> R.string.filter_stop
        TimelineFilter.MISSED -> R.string.filter_missed
    }
)
