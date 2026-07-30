package com.tensal.denden.data

import android.content.Context

class ChannelReadStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastReadAtByChannel(): Map<String, Long> =
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is Long) return@mapNotNull null
            key.removePrefix(KEY_PREFIX) to value
        }.toMap()

    fun markRead(channelId: String, receivedAt: Long) {
        if (receivedAt <= (getLastReadAtByChannel()[channelId] ?: 0L)) return
        prefs.edit().putLong(KEY_PREFIX + channelId, receivedAt).apply()
    }

    fun getArchivedChannelIds(): Set<String> =
        prefs.getStringSet(ARCHIVED_CHANNELS_KEY, emptySet()).orEmpty().toSet()

    fun isArchived(channelId: String): Boolean = channelId in getArchivedChannelIds()

    fun setArchived(channelId: String, archived: Boolean) {
        val channelIds = getArchivedChannelIds().toMutableSet()
        if (archived) channelIds += channelId else channelIds -= channelId
        prefs.edit().putStringSet(ARCHIVED_CHANNELS_KEY, channelIds).apply()
    }

    fun removeChannel(channelId: String): Boolean {
        val channelIds = getArchivedChannelIds() - channelId
        return prefs.edit()
            .remove(KEY_PREFIX + channelId)
            .putStringSet(ARCHIVED_CHANNELS_KEY, channelIds)
            .commit()
    }

    suspend fun importInto(dao: MessageQueryDao) {
        if (prefs.getBoolean(ROOM_IMPORT_COMPLETE, false)) return
        val read = getLastReadAtByChannel()
        val archived = getArchivedChannelIds()
        val channelIds = read.keys + archived
        dao.importChannelStates(
            channelIds.map { channelId ->
                ChannelState(
                    channelId = channelId,
                    lastReadAt = read[channelId] ?: 0,
                    archived = channelId in archived
                )
            }
        )
        prefs.edit().putBoolean(ROOM_IMPORT_COMPLETE, true).commit()
    }

    private companion object {
        const val PREFS_NAME = "denden_channel_read_state"
        const val KEY_PREFIX = "channel:"
        const val ARCHIVED_CHANNELS_KEY = "archived_channels"
        const val ROOM_IMPORT_COMPLETE = "room_import_v8_complete"
    }
}
