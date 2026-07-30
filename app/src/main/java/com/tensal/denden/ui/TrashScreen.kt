package com.tensal.denden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tensal.denden.DenDenColors
import com.tensal.denden.R
import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.data.TrashedChannel
import java.util.concurrent.TimeUnit

@Composable
fun TrashScreen(
    channels: List<TrashedChannel>,
    events: List<DenDenEvent> = emptyList(),
    channelItems: List<ChannelInboxItem>? = null,
    onBack: () -> Unit,
    onChannelSelected: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    val inboxItems = (channelItems ?: events.toChannelInboxItems()).associateBy { it.channelId }
    val trashCountDescription = stringResource(R.string.trash_count, channels.size)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DenDenColors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_channels))
            }
            Text(
                stringResource(R.string.trash),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = DenDenColors.surfaceContainerLow,
                modifier = Modifier.semantics {
                    contentDescription = trashCountDescription
                }
            ) {
                Text(
                    channels.size.toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.trash_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DenDenColors.onSurfaceVariant
                )
            }
            if (channels.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                items(channels, key = { it.channelId }) { trashed ->
                    val item = inboxItems[trashed.channelId]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onChannelSelected(trashed.channelId) },
                        colors = CardDefaults.cardColors(containerColor = DenDenColors.surfaceContainerLowest),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DenDenColors.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item?.displayName ?: trashed.channelId, fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(
                                        R.string.trashed_channel_summary,
                                        item?.eventCount ?: 0,
                                        remainingTrashDays(trashed.purgeAtMillis)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DenDenColors.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onRestore(trashed.channelId) }) {
                                Icon(Icons.Default.Restore, contentDescription = null)
                                Text(stringResource(R.string.restore))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.note),
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = DenDenColors.onSurfaceVariant
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = DenDenColors.surfaceContainerLowest,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DenDenColors.outlineVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.local_only), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.other_devices_unaffected),
                            style = MaterialTheme.typography.bodySmall,
                            color = DenDenColors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

internal fun remainingTrashDays(purgeAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long {
    val remaining = (purgeAtMillis - nowMillis).coerceAtLeast(0)
    return (remaining + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)
}
