package com.tensal.denden.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["channelId", "receivedAt", "id"])
    ]
)
data class DenDenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val action: String,
    val title: String? = null,
    val message: String? = null,
    val durationSeconds: Int? = null,
    val tagsJson: String? = null,
    val channelId: String = "default",
    val channelName: String? = null,
    val notificationMode: String = "normal",
    val ringUntilMillis: Long? = null,
    val kind: String = "notification",
    val state: String = "pending",
    val receivedAt: Long = System.currentTimeMillis(),
    val issuedAtMillis: Long = receivedAt,
    val startedAtMillis: Long? = null,
    val stoppedAtMillis: Long? = null,
    val stopReason: String? = null
) {
    fun tags(): List<String> = parseEventTags(tagsJson)

    val channelDisplayName: String
        get() = channelName?.takeIf { it.isNotBlank() } ?: channelId

    val computedSummary: String
        get() {
            val text = message ?: return title ?: "DenDen"
            return if (text.length <= 120) text else text.take(120) + "…"
        }

    companion object {
        const val MAX_DURATION = 300
    }
}

fun parseEventTags(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val tag = array.optString(index).trim()
                if (tag.isNotEmpty() && tag.length <= 100 && tag !in this) add(tag)
                if (size == 20) break
            }
        }
    }.getOrDefault(emptyList())
}
