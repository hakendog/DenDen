package com.tensal.denden.messaging

import com.tensal.denden.data.DenDenEvent
import com.tensal.denden.protocol.DirectFcmProtocol
import org.json.JSONArray
import org.json.JSONObject

data class ActivePairingKeys(
    val pairingId: String,
    val eventKeyId: String,
    val eventKey: String,
    val brandKeyId: String,
    val brandKey: String
)

sealed interface DecodedDirectMessage {
    val messageId: String
    val issuedAtMillis: Long
    val expiresAtMillis: Long

    data class Event(
        override val messageId: String,
        override val issuedAtMillis: Long,
        override val expiresAtMillis: Long,
        val event: DenDenEvent
    ) : DecodedDirectMessage

    data class Stop(
        override val messageId: String,
        override val issuedAtMillis: Long,
        override val expiresAtMillis: Long,
        val targetEventId: String
    ) : DecodedDirectMessage

    data class Brand(
        override val messageId: String,
        override val issuedAtMillis: Long,
        override val expiresAtMillis: Long,
        val kind: String,
        val payload: JSONObject
    ) : DecodedDirectMessage
}

fun decodeDirectFcmMessage(
    data: Map<String, String>,
    pairing: ActivePairingKeys,
    nowMillis: Long = System.currentTimeMillis()
): DecodedDirectMessage {
    require(data["pairingId"] == pairing.pairingId) { "pairingId 不相符" }
    val keyKind = data["keyKind"]
    val expectedKeyId = when (keyKind) {
        "event" -> pairing.eventKeyId
        "brand" -> pairing.brandKeyId
        else -> throw IllegalArgumentException("金鑰種類無效")
    }
    require(data["keyId"] == expectedKeyId) { "金鑰編號不相符" }
    val key = if (keyKind == "event") pairing.eventKey else pairing.brandKey
    if (data["kind"] == "brand-chunk") return decodeBrandChunk(data, key, nowMillis)
    val payload = JSONObject(DirectFcmProtocol.decrypt(data, key))
    val issuedAtMillis = payload.requiredMillis("issuedAtMillis")
    val expiresAtMillis = payload.requiredMillis("expiresAtMillis")
    require(expiresAtMillis > issuedAtMillis) { "訊息期限無效" }
    require(issuedAtMillis <= nowMillis + MAX_CLOCK_SKEW_MILLIS) { "訊息建立時間在未來" }
    require(expiresAtMillis >= nowMillis - MAX_CLOCK_SKEW_MILLIS) { "訊息已過期" }
    val kind = data["kind"] ?: throw IllegalArgumentException("訊息類型無效")
    val maxLifetimeMillis = when (kind) {
        "event" -> EVENT_MAX_LIFETIME_MILLIS
        "stop" -> STOP_MAX_LIFETIME_MILLIS
        "brand-manifest", "brand-reset" -> BRAND_MAX_LIFETIME_MILLIS
        else -> throw IllegalArgumentException("訊息類型無效")
    }
    require(expiresAtMillis - issuedAtMillis <= maxLifetimeMillis) { "訊息期限過長" }
    val messageId = requireNotNull(data["messageId"])
    return when (kind) {
        "event" -> decodeEvent(messageId, payload, issuedAtMillis, expiresAtMillis, nowMillis)
        "stop" -> decodeStop(messageId, payload, issuedAtMillis, expiresAtMillis)
        "brand-manifest", "brand-reset" -> {
            validateBrandPayload(kind, payload)
            DecodedDirectMessage.Brand(
                messageId, issuedAtMillis, expiresAtMillis, kind, payload
            )
        }
        else -> throw IllegalArgumentException("訊息類型無效")
    }
}

private fun decodeBrandChunk(data: Map<String, String>, key: String, nowMillis: Long): DecodedDirectMessage.Brand {
    val context = data["context"]?.split('.') ?: throw IllegalArgumentException("品牌分片 context 無效")
    require(context.size == 4) { "品牌分片 context 無效" }
    val generation = context[1].toLongOrNull() ?: throw IllegalArgumentException("品牌 generation 無效")
    val index = context[2].toIntOrNull() ?: throw IllegalArgumentException("品牌分片 index 無效")
    val chunkCount = context[3].toIntOrNull() ?: throw IllegalArgumentException("品牌分片數無效")
    require(generation > 0 && index in 0 until chunkCount && chunkCount in 1..64) { "品牌分片索引無效" }
    val decrypted = DirectFcmProtocol.decryptBytes(data, key)
    val separator = decrypted.indexOf('\n'.code.toByte())
    require(separator in 1..64) { "品牌分片內容無效" }
    val times = String(decrypted, 0, separator, Charsets.UTF_8).split(',')
    require(times.size == 2) { "品牌分片期限無效" }
    val issuedAtMillis = times[0].toLongOrNull() ?: throw IllegalArgumentException("品牌分片期限無效")
    val expiresAtMillis = times[1].toLongOrNull() ?: throw IllegalArgumentException("品牌分片期限無效")
    require(issuedAtMillis > 0 && expiresAtMillis > issuedAtMillis) { "品牌分片期限無效" }
    require(expiresAtMillis - issuedAtMillis <= BRAND_MAX_LIFETIME_MILLIS) { "品牌分片期限過長" }
    require(issuedAtMillis <= nowMillis + MAX_CLOCK_SKEW_MILLIS && expiresAtMillis >= nowMillis - MAX_CLOCK_SKEW_MILLIS) {
        "品牌分片已過期"
    }
    val chunk = decrypted.copyOfRange(separator + 1, decrypted.size)
    require(chunk.isNotEmpty() && chunk.size <= 1024) { "品牌分片大小無效" }
    val payload = JSONObject().apply {
        put("version", 2)
        put("type", "brand-chunk")
        put("transferId", context[0])
        put("generation", generation)
        put("index", index)
        put("chunkCount", chunkCount)
        put("data", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(chunk))
        put("issuedAtMillis", issuedAtMillis)
        put("expiresAtMillis", expiresAtMillis)
    }
    return DecodedDirectMessage.Brand(
        requireNotNull(data["messageId"]), issuedAtMillis, expiresAtMillis, "brand-chunk", payload
    )
}

private fun decodeEvent(
    messageId: String,
    payload: JSONObject,
    issuedAtMillis: Long,
    expiresAtMillis: Long,
    nowMillis: Long
): DecodedDirectMessage.Event {
    payload.requireOnlyFields(EVENT_FIELDS)
    require(payload.optInt("version", -1) == 2 && payload.optString("type") == "event") { "事件內容版本無效" }
    val eventId = payload.requiredId("eventId")
    val mode = payload.optString("mode")
    require(mode in setOf("quiet", "notify", "ring")) { "事件模式無效" }
    if (mode == "ring") {
        require(expiresAtMillis - issuedAtMillis <= STOP_MAX_LIFETIME_MILLIS) { "響鈴訊息期限過長" }
    }
    val duration = if (payload.has("durationSeconds")) payload.optInt("durationSeconds", -1) else null
    require(duration == null || duration in 0..DenDenEvent.MAX_DURATION) { "事件 duration 無效" }
    val ringUntil = if (payload.has("ringUntilMillis")) payload.optLong("ringUntilMillis", -1) else null
    require(mode != "ring" || (ringUntil != null && ringUntil > issuedAtMillis)) { "響鈴期限無效" }
    val tags = payload.optJSONArray("tags")?.toStringList().orEmpty()
    require(tags.size <= 20 && tags.all { it.length <= 100 }) { "事件標籤無效" }
    val action = if (mode == "ring") "ring" else "notify"
    val event = DenDenEvent(
        eventId = eventId,
        action = action,
        title = payload.optionalString("title", 200),
        message = payload.optionalString("message", 1000),
        durationSeconds = duration,
        tagsJson = tags.takeIf(List<String>::isNotEmpty)?.let { JSONArray(it).toString() },
        channelId = payload.requiredText("channelId", 200),
        channelName = payload.optionalString("channelName", 200),
        notificationMode = if (mode == "quiet") "quiet" else "normal",
        ringUntilMillis = ringUntil,
        kind = if (mode == "ring") "alarm" else "notification",
        state = "pending",
        issuedAtMillis = issuedAtMillis,
        receivedAt = nowMillis
    )
    return DecodedDirectMessage.Event(messageId, issuedAtMillis, expiresAtMillis, event)
}

private fun decodeStop(
    messageId: String,
    payload: JSONObject,
    issuedAtMillis: Long,
    expiresAtMillis: Long
): DecodedDirectMessage.Stop {
    payload.requireOnlyFields(STOP_FIELDS)
    require(payload.optInt("version", -1) == 2 && payload.optString("type") == "stop") { "停止內容版本無效" }
    return DecodedDirectMessage.Stop(
        messageId, issuedAtMillis, expiresAtMillis, payload.requiredId("targetEventId")
    )
}

private fun validateBrandPayload(kind: String, payload: JSONObject) {
    val expectedType = if (kind == "brand-manifest") "brand-manifest" else "brand-reset"
    payload.requireOnlyFields(if (kind == "brand-manifest") BRAND_MANIFEST_FIELDS else BRAND_RESET_FIELDS)
    require(payload.optInt("version", -1) == 2 && payload.optString("type") == expectedType) {
        "品牌內容版本或類型無效"
    }
    require(payload.optLong("generation", -1) > 0) { "品牌 generation 無效" }
    if (kind == "brand-manifest") {
        require(payload.requiredText("transferId", 86).matches(Regex("^[A-Za-z0-9_-]{16,86}$"))) {
            "品牌 transferId 無效"
        }
        require(payload.optInt("byteLength", -1) in 1..MAX_BRAND_IMAGE_BYTES) { "品牌圖片大小無效" }
        require(payload.optInt("chunkCount", -1) in 1..MAX_BRAND_CHUNKS) { "品牌分片數無效" }
        require(payload.optString("sha256").matches(Regex("^[a-f0-9]{64}$"))) { "品牌雜湊無效" }
        payload.optionalColor("brandColor")
        payload.optionalColor("backgroundColor")
    }
}

private fun JSONObject.requiredMillis(name: String): Long = optLong(name, -1).also {
    require(it > 0) { "$name 無效" }
}

private fun JSONObject.requiredId(name: String): String = requiredText(name, 200).also {
    require(Regex("^[A-Za-z0-9_-]+$").matches(it)) { "$name 無效" }
}

private fun JSONObject.requiredText(name: String, maxLength: Int): String = optString(name).also {
    require(it.isNotBlank() && it == it.trim() && it.length <= maxLength) { "$name 無效" }
}

private fun JSONObject.optionalString(name: String, maxLength: Int): String? {
    if (!has(name)) return null
    return optString(name).also { require(it.length <= maxLength) { "$name 無效" } }
}

private fun JSONObject.optionalColor(name: String): String? {
    if (!has(name)) return null
    return optString(name).also { require(it.matches(Regex("^#[0-9A-F]{6}$"))) { "$name 無效" } }
}

private fun JSONArray.toStringList(): List<String> = (0 until length()).map { index ->
    require(opt(index) is String) { "事件標籤無效" }
    getString(index)
}

private fun JSONObject.requireOnlyFields(allowed: Set<String>) {
    require(keys().asSequence().all(allowed::contains)) { "訊息內容包含未知欄位" }
}

private const val MAX_CLOCK_SKEW_MILLIS = 2 * 60 * 1000L
private const val STOP_MAX_LIFETIME_MILLIS = 60 * 1000L
private const val EVENT_MAX_LIFETIME_MILLIS = 5 * 60 * 1000L
private const val BRAND_MAX_LIFETIME_MILLIS = 10 * 60 * 1000L
private const val MAX_BRAND_IMAGE_BYTES = 64 * 1024
private const val MAX_BRAND_CHUNKS = 64
private val EVENT_FIELDS = setOf(
    "version", "type", "eventId", "mode", "channelId", "channelName", "title", "message", "tags",
    "durationSeconds", "ringUntilMillis", "issuedAtMillis", "expiresAtMillis"
)
private val STOP_FIELDS = setOf("version", "type", "targetEventId", "issuedAtMillis", "expiresAtMillis")
private val BRAND_MANIFEST_FIELDS = setOf(
    "version", "type", "transferId", "generation", "issuedAtMillis", "expiresAtMillis", "byteLength", "sha256",
    "brandColor", "backgroundColor", "chunkCount"
)
private val BRAND_RESET_FIELDS = setOf("version", "type", "generation", "issuedAtMillis", "expiresAtMillis")
