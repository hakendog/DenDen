package com.tensal.denden.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class DirectFcmProtocolTest {
    private val vectors = JSONObject(
        requireNotNull(javaClass.classLoader?.getResource("direct-fcm-v2-test-vectors.json")).readText()
    )

    @Test
    fun `Node DDC vector parses with the same fields`() {
        val invite = parseDirectFcmInvite(
            vectors.getString("encodedInvite"),
            vectors.getLong("nowMillis")
        )
        val expected = vectors.getJSONObject("invite")
        assertEquals(expected.getString("projectId"), invite.projectId)
        assertEquals(expected.getString("firebaseAppId"), invite.firebaseAppId)
        assertEquals(expected.getString("pairingId"), invite.pairingId)
        assertEquals(expected.getString("topic"), invite.topic)
        assertEquals(expected.getString("eventKey"), invite.eventKey)
        assertEquals(expected.getString("brandKey"), invite.brandKey)
    }

    @Test
    fun `Node AES-GCM vector decrypts and Kotlin reproduces it`() {
        val encryption = vectors.getJSONObject("encryption")
        val input = encryption.getJSONObject("input")
        val expected = encryption.getJSONObject("envelope").toStringMap()
        val plaintextJson = encryption.getString("plaintextJson")

        assertEquals(plaintextJson, DirectFcmProtocol.decrypt(expected, input.getString("key")))
        val actual = DirectFcmProtocol.encrypt(
            pairingId = input.getString("pairingId"),
            kind = input.getString("kind"),
            keyKind = input.getString("keyKind"),
            keyId = input.getString("keyId"),
            messageId = input.getString("messageId"),
            key = input.getString("key"),
            nonce = input.getString("nonce"),
            plaintextJson = plaintextJson
        ).asFcmData()
        assertEquals(expected, actual)
        assertEquals(encryption.getInt("dataSizeBytes"), DirectFcmProtocol.dataSizeBytes(actual))
    }

    @Test
    fun `AAD and separate keys reject tampering`() {
        val encryption = vectors.getJSONObject("encryption")
        val input = encryption.getJSONObject("input")
        val envelope = encryption.getJSONObject("envelope").toStringMap()
        val brandKey = vectors.getJSONObject("invite").getString("brandKey")

        assertThrows(IllegalArgumentException::class.java) {
            DirectFcmProtocol.decrypt(envelope + ("kind" to "stop"), input.getString("key"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectFcmProtocol.decrypt(envelope, brandKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectFcmProtocol.encrypt(
                input.getString("pairingId"), "event", "brand", input.getString("keyId"),
                input.getString("messageId"), brandKey, input.getString("nonce"), "{}"
            )
        }
    }

    @Test
    fun `DDC rejects inconsistent Firebase public configuration`() {
        val invite = JSONObject(vectors.getJSONObject("invite").toString())
        invite.put("firebaseAppId", "1:999999999999:android:abcdef123456")
        assertThrows(IllegalArgumentException::class.java) {
            parseDirectFcmInvite(invite.asDdc(), vectors.getLong("nowMillis"))
        }

        invite.put("firebaseAppId", vectors.getJSONObject("invite").getString("firebaseAppId"))
        invite.put("apiKey", "public-but-invalid")
        assertThrows(IllegalArgumentException::class.java) {
            parseDirectFcmInvite(invite.asDdc(), vectors.getLong("nowMillis"))
        }
    }
}

private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith(::getString)

private fun JSONObject.asDdc(): String = "DDC." + Base64.getUrlEncoder().withoutPadding()
    .encodeToString(toString().toByteArray(StandardCharsets.UTF_8))
