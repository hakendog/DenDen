package com.tensal.denden.protocol

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DDC_PREFIX = "DDC."
private const val PROTOCOL_HEADER = "DENDEN-FCM-V2"
private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,86}$")
private val PROJECT_PATTERN = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val FIREBASE_APP_PATTERN = Regex("^1:(\\d{6,20}):android:[A-Za-z0-9_-]{8,100}$")
private val API_KEY_PATTERN = Regex("^AIza[A-Za-z0-9_-]{28,60}$")
private val EVENT_KINDS = setOf("event", "stop")
private val BRAND_KINDS = setOf("brand-manifest", "brand-chunk", "brand-reset")
private val INVITE_FIELDS = setOf(
    "v", "projectId", "firebaseAppId", "apiKey", "gcmSenderId", "androidPackageName",
    "pairingId", "topic", "eventKeyId", "eventKey", "brandKeyId", "brandKey",
    "createdAtMillis", "displayExpiresAtMillis"
)
private val ENVELOPE_FIELDS = setOf(
    "v", "pairingId", "kind", "keyKind", "keyId", "messageId", "context", "nonce", "ciphertext"
)

data class DirectFcmInvite(
    val projectId: String,
    val firebaseAppId: String,
    val apiKey: String,
    val gcmSenderId: String,
    val androidPackageName: String,
    val pairingId: String,
    val topic: String,
    val eventKeyId: String,
    val eventKey: String,
    val brandKeyId: String,
    val brandKey: String,
    val createdAtMillis: Long,
    val displayExpiresAtMillis: Long
)

data class DirectFcmEnvelope(
    val pairingId: String,
    val kind: String,
    val keyKind: String,
    val keyId: String,
    val messageId: String,
    val context: String? = null,
    val nonce: String,
    val ciphertext: String
) {
    fun asFcmData(): Map<String, String> = linkedMapOf(
        "v" to "2",
        "pairingId" to pairingId,
        "kind" to kind,
        "keyKind" to keyKind,
        "keyId" to keyId,
        "messageId" to messageId,
        *(context?.let { arrayOf("context" to it) } ?: emptyArray()),
        "nonce" to nonce,
        "ciphertext" to ciphertext
    )
}

fun parseDirectFcmInvite(
    input: String,
    nowMillis: Long = System.currentTimeMillis(),
    allowExpired: Boolean = false
): DirectFcmInvite {
    val value = input.trim()
    if (!value.startsWith(DDC_PREFIX)) {
        require(!Regex("^DD\\d+\\.").containsMatchIn(value)) { "不支援的 DenDen 配對版本" }
        throw IllegalArgumentException("配對碼必須使用 DDC 格式")
    }
    val json = runCatching {
        val decoded = decodeCanonical(value.removePrefix(DDC_PREFIX), "配對碼 payload")
        JSONObject(String(decoded, StandardCharsets.UTF_8))
    }.getOrElse { throw IllegalArgumentException("配對碼 payload 無法解碼") }
    require(json.keys().asSequence().all(INVITE_FIELDS::contains)) { "配對碼包含未知欄位" }
    require(json.optInt("v", -1) == 2) { "不支援的 DenDen 配對版本" }
    val projectId = json.requiredString("projectId").also { require(PROJECT_PATTERN.matches(it)) { "projectId 無效" } }
    val gcmSenderId = json.requiredString("gcmSenderId").also {
        require(Regex("^\\d{6,20}$").matches(it)) { "gcmSenderId 無效" }
    }
    val firebaseAppId = json.requiredString("firebaseAppId").also {
        val match = FIREBASE_APP_PATTERN.matchEntire(it)
        require(match != null && match.groupValues[1] == gcmSenderId) {
            "firebaseAppId 無效或與 gcmSenderId 不一致"
        }
    }
    val apiKey = json.requiredString("apiKey").also {
        require(API_KEY_PATTERN.matches(it)) { "apiKey 無效" }
    }
    val androidPackageName = json.requiredString("androidPackageName").also {
        require(PACKAGE_PATTERN.matches(it)) { "androidPackageName 無效" }
    }
    val pairingId = json.canonicalSecret("pairingId", 16)
    val topic = json.canonicalSecret("topic", 32)
    val eventKeyId = json.canonicalId("eventKeyId")
    val eventKey = json.canonicalSecret("eventKey", 32)
    val brandKeyId = json.canonicalId("brandKeyId")
    val brandKey = json.canonicalSecret("brandKey", 32)
    require(eventKeyId != brandKeyId) { "事件與品牌金鑰編號不得相同" }
    val createdAtMillis = json.safeMillis("createdAtMillis")
    val displayExpiresAtMillis = json.safeMillis("displayExpiresAtMillis")
    require(displayExpiresAtMillis > createdAtMillis) { "顯示期限無效" }
    require(createdAtMillis <= nowMillis + 2 * 60_000 && displayExpiresAtMillis - createdAtMillis <= 15 * 60_000) {
        "配對碼時間範圍無效"
    }
    require(allowExpired || displayExpiresAtMillis > nowMillis) { "配對碼顯示期限已過" }
    return DirectFcmInvite(
        projectId, firebaseAppId, apiKey, gcmSenderId, androidPackageName, pairingId, topic,
        eventKeyId, eventKey, brandKeyId, brandKey, createdAtMillis, displayExpiresAtMillis
    )
}

object DirectFcmProtocol {
    fun encrypt(
        pairingId: String,
        kind: String,
        keyKind: String,
        keyId: String,
        messageId: String,
        key: String,
        nonce: String,
        plaintextJson: String,
        context: String? = null
    ): DirectFcmEnvelope = encryptBytes(
        pairingId, kind, keyKind, keyId, messageId, key, nonce,
        plaintextJson.toByteArray(StandardCharsets.UTF_8), context
    )

    fun encryptBytes(
        pairingId: String,
        kind: String,
        keyKind: String,
        keyId: String,
        messageId: String,
        key: String,
        nonce: String,
        plaintext: ByteArray,
        context: String? = null
    ): DirectFcmEnvelope {
        val meta = normalizeMeta(pairingId, kind, keyKind, keyId, messageId, context)
        val nonceBytes = decodeSized(nonce, 12, "nonce")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(decodeSized(key, 32, "加密金鑰"), "AES"),
            GCMParameterSpec(128, nonceBytes)
        )
        cipher.updateAAD(aad(meta))
        val encrypted = cipher.doFinal(plaintext)
        return DirectFcmEnvelope(
            meta.pairingId, meta.kind, meta.keyKind, meta.keyId, meta.messageId, meta.context,
            encodeCanonical(nonceBytes), encodeCanonical(encrypted)
        )
    }

    fun decrypt(data: Map<String, String>, key: String): String =
        String(decryptBytes(data, key), StandardCharsets.UTF_8).also { JSONObject(it) }

    fun decryptBytes(data: Map<String, String>, key: String): ByteArray {
        require(data.keys.all(ENVELOPE_FIELDS::contains) && data.keys.containsAll(ENVELOPE_FIELDS - "context")) {
            "FCM 資料欄位無效"
        }
        require(data["v"] == "2") { "不支援的 FCM 協定版本" }
        val meta = normalizeMeta(
            data.getValue("pairingId"), data.getValue("kind"), data.getValue("keyKind"),
            data.getValue("keyId"), data.getValue("messageId"), data["context"]
        )
        val nonce = decodeSized(data.getValue("nonce"), 12, "nonce")
        val encrypted = decodeCanonical(data.getValue("ciphertext"), "ciphertext")
        require(encrypted.size >= 17) { "ciphertext 無效" }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(decodeSized(key, 32, "解密金鑰"), "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(aad(meta))
            cipher.doFinal(encrypted)
        }.getOrElse { throw IllegalArgumentException("FCM 資料驗證失敗") }
    }

    fun dataSizeBytes(data: Map<String, String>): Int = data.entries.sumOf {
        it.key.toByteArray(StandardCharsets.UTF_8).size + it.value.toByteArray(StandardCharsets.UTF_8).size
    }

    private data class Meta(
        val pairingId: String,
        val kind: String,
        val keyKind: String,
        val keyId: String,
        val messageId: String,
        val context: String?
    )

    private fun normalizeMeta(
        pairingId: String,
        kind: String,
        keyKind: String,
        keyId: String,
        messageId: String,
        context: String? = null
    ): Meta {
        canonicalSecret(pairingId, 16, "pairingId")
        val expectedKeyKind = when (kind) {
            in EVENT_KINDS -> "event"
            in BRAND_KINDS -> "brand"
            else -> throw IllegalArgumentException("訊息類型無效")
        }
        require(keyKind == expectedKeyKind) { "訊息類型與金鑰種類不一致" }
        canonicalId(keyId, "keyId")
        canonicalId(messageId, "messageId")
        require(context == null || (context.length <= 200 && Regex("^[A-Za-z0-9_.:-]+$").matches(context))) { "context 無效" }
        require((kind == "brand-chunk") == (context != null)) { "訊息類型與 context 不一致" }
        return Meta(pairingId, kind, keyKind, keyId, messageId, context)
    }

    private fun aad(meta: Meta): ByteArray = buildList {
        addAll(listOf(PROTOCOL_HEADER, meta.pairingId, meta.kind, meta.keyKind, meta.keyId, meta.messageId))
        meta.context?.let(::add)
    }.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

private fun JSONObject.requiredString(name: String): String = optString(name).also {
    require(it.isNotEmpty() && it == it.trim()) { "$name 無效" }
}

private fun JSONObject.canonicalId(name: String): String = requiredString(name).also {
    canonicalId(it, name)
}

private fun JSONObject.canonicalSecret(name: String, size: Int): String = requiredString(name).also {
    canonicalSecret(it, size, name)
}

private fun JSONObject.safeMillis(name: String): Long = optLong(name, -1).also {
    require(it > 0) { "$name 無效" }
}

private fun canonicalId(value: String, name: String) {
    require(ID_PATTERN.matches(value)) { "$name 無效" }
}

private fun canonicalSecret(value: String, size: Int, name: String) {
    decodeSized(value, size, name)
}

private fun decodeSized(value: String, size: Int, name: String): ByteArray =
    decodeCanonical(value, name).also { require(it.size == size) { "$name 長度無效" } }

private fun decodeCanonical(value: String, name: String): ByteArray {
    require(Regex("^[A-Za-z0-9_-]+$").matches(value)) { "$name 無法解碼" }
    val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
        .getOrElse { throw IllegalArgumentException("$name 無法解碼") }
    require(encodeCanonical(decoded) == value) { "$name 無法解碼" }
    return decoded
}

private fun encodeCanonical(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
