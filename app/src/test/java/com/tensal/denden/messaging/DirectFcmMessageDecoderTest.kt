package com.tensal.denden.messaging

import com.tensal.denden.protocol.DirectFcmProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class DirectFcmMessageDecoderTest {
    private val pairing = ActivePairingKeys(
        pairingId = "AAECAwQFBgcICQoLDA0ODw",
        eventKeyId = "event-key-000001",
        eventKey = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8",
        brandKeyId = "brand-key-000001",
        brandKey = "YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8"
    )

    @Test
    fun `valid encrypted event becomes local DenDen event`() {
        val data = encryptedEvent()
        val decoded = decodeDirectFcmMessage(data, pairing, NOW) as DecodedDirectMessage.Event

        assertEquals("event-0000000001", decoded.event.eventId)
        assertEquals("notify", decoded.event.action)
        assertEquals("normal", decoded.event.notificationMode)
        assertEquals("pending", decoded.event.state)
        assertEquals(NOW, decoded.event.issuedAtMillis)
    }

    @Test
    fun `wrong pairing key id tampering and expiry fail before side effects`() {
        val data = encryptedEvent()
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(data, pairing.copy(pairingId = "EBESExQVFhcYGRobHB0eHw"), NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(data + ("keyId" to "another-key-0001"), pairing, NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(data + ("messageId" to "message-99999999"), pairing, NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(data, pairing, NOW + 10 * 60 * 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(encryptedEvent("backendUrl" to "https://invalid.example"), pairing, NOW)
        }
    }

    @Test
    fun `event key cannot authorize brand control`() {
        val data = DirectFcmProtocol.encrypt(
            pairing.pairingId,
            "brand-reset",
            "brand",
            pairing.brandKeyId,
            "message-brand-001",
            pairing.brandKey,
            "kJGSk5SVlpeYmZqb",
            "{\"version\":2,\"type\":\"brand-reset\",\"generation\":1," +
                "\"issuedAtMillis\":$NOW,\"expiresAtMillis\":${NOW + 60_000}}"
        ).asFcmData()
        assertEquals("brand-reset", (decodeDirectFcmMessage(data, pairing, NOW) as DecodedDirectMessage.Brand).kind)
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(data + ("keyKind" to "event") + ("keyId" to pairing.eventKeyId), pairing, NOW)
        }
    }

    @Test
    fun `overlong lifetime and incomplete brand payload are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(encryptedEvent("expiresAtMillis" to NOW + 6 * 60_000), pairing, NOW)
        }
        val incompleteReset = DirectFcmProtocol.encrypt(
            pairing.pairingId,
            "brand-reset",
            "brand",
            pairing.brandKeyId,
            "message-brand-bad1",
            pairing.brandKey,
            "uLm6u7y9vr_AwcLD",
            "{\"version\":2,\"issuedAtMillis\":$NOW,\"expiresAtMillis\":${NOW + 60_000}}"
        ).asFcmData()
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(incompleteReset, pairing, NOW)
        }
    }

    @Test
    fun `text limits count Unicode code points like the sender`() {
        val emoji = "😀"
        val acceptedTitle = decodeDirectFcmMessage(
            encryptedEvent("title" to emoji.repeat(200)), pairing, NOW
        ) as DecodedDirectMessage.Event
        assertEquals(200, acceptedTitle.event.title?.codePointCount(0, acceptedTitle.event.title!!.length))

        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(encryptedEvent("title" to emoji.repeat(201)), pairing, NOW)
        }
        decodeDirectFcmMessage(
            encryptedEvent("tags" to org.json.JSONArray().put(emoji.repeat(100))), pairing, NOW
        )
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(
                encryptedEvent("tags" to org.json.JSONArray().put(emoji.repeat(101))), pairing, NOW
            )
        }
    }

    @Test
    fun `brand and optional background colors remain separate`() {
        val modern = brandManifest(JSONObject().put("brandColor", "#123456"), "message-brand-new1", "wMHCw8TFxsfIycrL")
        val decoded = decodeDirectFcmMessage(modern, pairing, NOW) as DecodedDirectMessage.Brand
        assertEquals("#123456", decoded.payload.getString("brandColor"))
        assertEquals(false, decoded.payload.has("backgroundColor"))

        val mixed = brandManifest(
            JSONObject().put("brandColor", "#123456").put("primaryColor", "#FFFFFF"),
            "message-brand-bad2",
            "zM3Oz9DR0tPU1dbX"
        )
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(mixed, pairing, NOW)
        }
    }

    @Test
    fun `binary brand chunk stays under the JSON expansion and authenticates transfer context`() {
        val context = "transfer-0000001.3.0.1"
        val plaintext = "${NOW},${NOW + 60_000}\n".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1, 2, 3, 4)
        val data = DirectFcmProtocol.encryptBytes(
            pairing.pairingId,
            "brand-chunk",
            "brand",
            pairing.brandKeyId,
            "message-brand-chunk",
            pairing.brandKey,
            "oKGio6Slpqeoqaqr",
            plaintext,
            context
        ).asFcmData()
        val decoded = decodeDirectFcmMessage(data, pairing, NOW) as DecodedDirectMessage.Brand
        assertEquals("brand-chunk", decoded.kind)
        assertEquals(3, decoded.payload.getLong("generation"))
        assertEquals("AQIDBA", decoded.payload.getString("data"))
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(data + ("context" to "transfer-0000001.3.1.1"), pairing, NOW)
        }
        val invalidTimes = DirectFcmProtocol.encryptBytes(
            pairing.pairingId,
            "brand-chunk",
            "brand",
            pairing.brandKeyId,
            "message-brand-times",
            pairing.brandKey,
            "sLGys7S1tre4ubq7",
            "${NOW + 60_000},$NOW\n".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1),
            context
        ).asFcmData()
        assertThrows(IllegalArgumentException::class.java) {
            decodeDirectFcmMessage(invalidTimes, pairing, NOW)
        }
    }

    private fun encryptedEvent(extra: Pair<String, Any>? = null): Map<String, String> {
        val payload = JSONObject().apply {
            put("version", 2)
            put("type", "event")
            put("eventId", "event-0000000001")
            put("mode", "notify")
            put("channelId", "main")
            put("title", "測試完成")
            put("issuedAtMillis", NOW)
            put("expiresAtMillis", NOW + 5 * 60_000)
            extra?.let { put(it.first, it.second) }
        }.toString()
        return DirectFcmProtocol.encrypt(
            pairing.pairingId,
            "event",
            "event",
            pairing.eventKeyId,
            "message-00000001",
            pairing.eventKey,
            "gIGCg4SFhoeIiYqL",
            payload
        ).asFcmData()
    }

    private fun brandManifest(colors: JSONObject, messageId: String, nonce: String): Map<String, String> {
        val payload = JSONObject().apply {
            put("version", 2)
            put("type", "brand-manifest")
            put("transferId", "brand-transfer-000001")
            put("generation", 1)
            put("issuedAtMillis", NOW)
            put("expiresAtMillis", NOW + 60_000)
            put("byteLength", 1024)
            put("sha256", "0".repeat(64))
            put("chunkCount", 1)
            colors.keys().forEach { name -> put(name, colors.get(name)) }
        }.toString()
        return DirectFcmProtocol.encrypt(
            pairing.pairingId,
            "brand-manifest",
            "brand",
            pairing.brandKeyId,
            messageId,
            pairing.brandKey,
            nonce,
            payload
        ).asFcmData()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
