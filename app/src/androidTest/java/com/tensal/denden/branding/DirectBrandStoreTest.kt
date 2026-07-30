package com.tensal.denden.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class DirectBrandStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    @Before
    @After
    fun clearStore() {
        File(context.filesDir, "direct-branding").deleteRecursively()
        context.getSharedPreferences("direct_branding", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun completedAppearanceWaitsForConfirmationAndResetUsesTheSameFlow() {
        val bytes = transparentPng()
        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        val transferId = "AAECAwQFBgcICQoLDA0ODw"
        val store = DirectBrandStore(context)

        chunks.indices.reversed().forEach { index ->
            assertTrue(!store.accept(PAIRING_ID, "brand-chunk", chunk(transferId, index, chunks, chunks[index]), now))
        }
        assertTrue(store.accept(PAIRING_ID, "brand-manifest", manifest(transferId, bytes, chunks.size), now))
        assertNull(store.load(PAIRING_ID))
        assertEquals(1L, DirectBrandStore(context).candidate(PAIRING_ID)?.generation)
        assertTrue(store.applyCandidate(PAIRING_ID))
        val branding = store.load(PAIRING_ID)
        assertNotNull(branding)
        assertEquals(Color.parseColor("#123456"), branding?.brandColor)
        assertNull(branding?.backgroundColor)
        assertEquals(Color.parseColor("#123456"), store.activeBrandColor(PAIRING_ID))
        assertEquals(512, branding?.mascot?.width)
        assertNotNull(store.activeStatusMask(PAIRING_ID))

        assertTrue(store.accept(PAIRING_ID, "brand-reset", JSONObject()
            .put("version", 2)
            .put("type", "brand-reset")
            .put("generation", 2), now))
        assertNotNull(store.load(PAIRING_ID))
        assertTrue(store.candidate(PAIRING_ID)?.isReset == true)
        assertTrue(store.rejectCandidate(PAIRING_ID))
        assertNotNull(store.load(PAIRING_ID))

        assertTrue(store.accept(PAIRING_ID, "brand-reset", JSONObject()
            .put("version", 2)
            .put("type", "brand-reset")
            .put("generation", 3), now))
        assertTrue(store.applyCandidate(PAIRING_ID))
        assertNull(store.load(PAIRING_ID))
        assertNull(store.activeStatusMask(PAIRING_ID))
    }

    @Test
    fun legacyPrimaryColorIsRejected() {
        val bytes = transparentPng()
        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        val transferId = "legacy-color-00000001"
        val store = DirectBrandStore(context)

        chunks.indices.forEach { index ->
            assertFalse(store.accept(PAIRING_ID, "brand-chunk", chunk(transferId, index, chunks, chunks[index]), now))
        }
        val legacyManifest = manifest(transferId, bytes, chunks.size).put("primaryColor", "#F5F2EB")
        assertTrue(runCatching { store.accept(PAIRING_ID, "brand-manifest", legacyManifest, now) }.isFailure)
        assertNull(store.load(PAIRING_ID))
    }

    @Test
    fun manifestAndChunksMustUseTheSameGenerationAndExpiry() {
        val bytes = transparentPng()
        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        val store = DirectBrandStore(context)
        val transferId = "EBESExQVFhcYGRobHB0eHw"

        assertTrue(!store.accept(PAIRING_ID, "brand-chunk", chunk(transferId, 0, chunks, chunks[0]), now))
        val mismatch = manifest(transferId, bytes, chunks.size, generation = 2)
        assertTrue(runCatching { store.accept(PAIRING_ID, "brand-manifest", mismatch, now) }.isFailure)
        assertNull(store.status(PAIRING_ID).receivingTransferFingerprint)

        val expiryMismatchId = "ICEiIyQlJicoKSorLC0uLw"
        assertTrue(!store.accept(PAIRING_ID, "brand-chunk", chunk(expiryMismatchId, 0, chunks, chunks[0]), now))
        val expiryMismatch = manifest(expiryMismatchId, bytes, chunks.size, expiresAtMillis = now + 120_000)
        assertTrue(runCatching { store.accept(PAIRING_ID, "brand-manifest", expiryMismatch, now) }.isFailure)
        assertNull(store.status(PAIRING_ID).receivingTransferFingerprint)
    }

    @Test
    fun invalidCompletedTransferIsDiscardedWithoutReplacingActiveBrand() {
        val bytes = transparentPng()
        val store = DirectBrandStore(context)
        acceptTransfer(store, "MDEyMzQ1Njc4OUFCQ0RFRg", bytes, generation = 1)
        assertTrue(store.applyCandidate(PAIRING_ID))
        val activeRevision = store.status(PAIRING_ID).activeRevisionFingerprint

        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        val transferId = "RkdISUpLTE1OT1BRUlNUVQ"
        chunks.indices.forEach { index ->
            assertTrue(!store.accept(PAIRING_ID, "brand-chunk", chunk(transferId, index, chunks, chunks[index], generation = 2), now))
        }
        val wrongDigest = manifest(transferId, bytes, chunks.size, generation = 2).put("sha256", "0".repeat(64))
        assertTrue(runCatching { store.accept(PAIRING_ID, "brand-manifest", wrongDigest, now) }.isFailure)
        assertEquals(activeRevision, store.status(PAIRING_ID).activeRevisionFingerprint)
        assertEquals(1L, store.status(PAIRING_ID).generation)
        assertNull(store.status(PAIRING_ID).receivingTransferFingerprint)
    }

    @Test
    fun resetDoesNotLowerHighWaterAndNewPairingStartsANewNamespace() {
        val store = DirectBrandStore(context)
        acceptTransfer(store, "VldYWVphYmNkZWZnaGlqaw", transparentPng(), generation = 1)
        assertTrue(store.applyCandidate(PAIRING_ID))
        assertTrue(store.accept(PAIRING_ID, "brand-reset", JSONObject()
            .put("version", 2)
            .put("type", "brand-reset")
            .put("generation", 2), now))
        assertTrue(store.applyCandidate(PAIRING_ID))
        assertEquals(2L, store.status(PAIRING_ID).generation)
        assertTrue(runCatching {
            store.accept(PAIRING_ID, "brand-manifest", manifest("bG1ub3BxcnN0dXZ3eHl6ew", transparentPng(), 1), now)
        }.isFailure)

        val newPairing = "fH1-f4CBgoOEhYaHiImKiw"
        val bytes = transparentPng()
        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        assertTrue(!store.accept(newPairing, "brand-chunk", chunk("jI2Oj5CRkpOUlZaXmJmamw", 0, chunks, chunks[0]), now))
        assertEquals(0L, store.status(newPairing).generation)
        assertNull(store.load(PAIRING_ID))
    }

    @Test
    fun staleShortcutCompletionCannotClearANewerBrandUpdate() {
        val store = DirectBrandStore(context)
        acceptTransfer(store, "shortcut-stale-000001", transparentPng(), generation = 1)
        assertTrue(store.applyCandidate(PAIRING_ID))
        val oldUpdate = requireNotNull(store.pendingShortcutUpdate(PAIRING_ID))

        assertTrue(store.accept(PAIRING_ID, "brand-reset", JSONObject()
            .put("version", 2)
            .put("type", "brand-reset")
            .put("generation", 2), now))
        assertTrue(store.applyCandidate(PAIRING_ID))
        val newUpdate = requireNotNull(store.pendingShortcutUpdate(PAIRING_ID))
        assertFalse(store.markShortcutUpdated(oldUpdate))
        assertTrue(store.shortcutUpdatePending(PAIRING_ID))
        assertTrue(store.markShortcutUpdated(newUpdate))
        assertFalse(store.shortcutUpdatePending(PAIRING_ID))
    }

    @Test
    fun stagingAllowsOnlyTwoTransfersAndExpiredTransfersReleaseTheBound() {
        val store = DirectBrandStore(context)
        val bytes = transparentPng()
        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        val first = "staging-transfer-0001"
        val second = "staging-transfer-0002"
        val third = "staging-transfer-0003"

        assertFalse(store.accept(PAIRING_ID, "brand-chunk", chunk(first, 0, chunks, chunks[0]), now))
        assertFalse(store.accept(PAIRING_ID, "brand-chunk", chunk(second, 0, chunks, chunks[0]), now))
        assertTrue(runCatching {
            store.accept(PAIRING_ID, "brand-chunk", chunk(third, 0, chunks, chunks[0]), now)
        }.isFailure)

        val later = now + 2 * 60_000
        assertFalse(store.accept(
            PAIRING_ID,
            "brand-chunk",
            chunk(third, 0, chunks, chunks[0], expiresAtMillis = later + 60_000),
            later
        ))
        assertNotNull(store.status(PAIRING_ID).receivingTransferFingerprint)

        store.clearPairing()
        store.activatePairing(PAIRING_ID)
        val occupied = File(context.filesDir, "direct-branding/staging/occupied").apply { mkdirs() }
        File(occupied, "expires").writeText((later + 60_000).toString())
        File(occupied, "payload").writeBytes(ByteArray(128 * 1024))
        assertFalse(store.accept(
            PAIRING_ID,
            "brand-chunk",
            chunk(first, 0, chunks, chunks[0], expiresAtMillis = later + 60_000),
            later
        ))
    }

    @Test
    fun newerCandidateReplacesOlderAndRejectedGenerationCannotPromptAgain() {
        val store = DirectBrandStore(context)
        val bytes = transparentPng()
        acceptTransfer(store, "candidate-active-0001", bytes, generation = 1)
        assertTrue(store.applyCandidate(PAIRING_ID))
        val activeRevision = store.status(PAIRING_ID).activeRevisionFingerprint

        acceptTransfer(store, "candidate-second-0002", bytes, generation = 2)
        assertEquals(2L, store.candidate(PAIRING_ID)?.generation)
        acceptTransfer(store, "candidate-third-00003", bytes, generation = 3)
        assertEquals(3L, store.candidate(PAIRING_ID)?.generation)
        assertTrue(store.rejectCandidate(PAIRING_ID))
        assertNull(store.candidate(PAIRING_ID))
        assertEquals(activeRevision, store.status(PAIRING_ID).activeRevisionFingerprint)

        assertTrue(runCatching {
            acceptTransfer(store, "candidate-replay-0003", bytes, generation = 3)
        }.isFailure)
        assertNull(store.candidate(PAIRING_ID))
    }

    @Test
    fun sameGenerationAllowsExactReplayButRejectsDifferentContent() {
        val store = DirectBrandStore(context)
        val bytes = transparentPng()
        val transferId = "same-generation-0001"

        acceptTransfer(store, transferId, bytes, generation = 1)
        acceptTransfer(store, transferId, bytes, generation = 1)
        val candidateRevision = store.candidate(PAIRING_ID)?.revision

        assertTrue(runCatching {
            acceptTransfer(store, "same-generation-0002", bytes, generation = 1, brandColor = "#654321")
        }.isFailure)
        assertEquals(candidateRevision, store.candidate(PAIRING_ID)?.revision)
    }

    @Test
    fun missingCandidateFilesReportErrorAndAllowSameGenerationRetry() {
        val store = DirectBrandStore(context)
        val bytes = transparentPng()
        acceptTransfer(store, "candidate-active-1001", bytes, generation = 1)
        assertTrue(store.applyCandidate(PAIRING_ID))
        acceptTransfer(store, "candidate-retry-2002", bytes, generation = 2)
        val revision = requireNotNull(store.candidate(PAIRING_ID)?.revision)
        File(context.filesDir, "direct-branding/revision-$revision").deleteRecursively()

        assertTrue(runCatching { store.applyCandidate(PAIRING_ID) }.isFailure)
        assertNull(store.candidate(PAIRING_ID))
        assertNotNull(store.status(PAIRING_ID).lastError)
        assertEquals(1L, store.status(PAIRING_ID).generation)

        acceptTransfer(store, "candidate-retry-2002", bytes, generation = 2)
        assertTrue(store.applyCandidate(PAIRING_ID))
        assertEquals(2L, store.status(PAIRING_ID).generation)
    }

    private fun acceptTransfer(
        store: DirectBrandStore,
        transferId: String,
        bytes: ByteArray,
        generation: Long,
        brandColor: String = "#123456"
    ) {
        val chunks = bytes.asList().chunked(1024).map { it.toByteArray() }
        chunks.indices.forEach { index ->
            assertTrue(!store.accept(PAIRING_ID, "brand-chunk", chunk(transferId, index, chunks, chunks[index], generation), now))
        }
        assertTrue(store.accept(
            PAIRING_ID,
            "brand-manifest",
            manifest(transferId, bytes, chunks.size, generation, brandColor = brandColor),
            now
        ))
    }

    private fun chunk(
        transferId: String,
        index: Int,
        chunks: List<ByteArray>,
        bytes: ByteArray,
        generation: Long = 1,
        expiresAtMillis: Long = now + 60_000
    ) = JSONObject()
        .put("version", 2)
        .put("type", "brand-chunk")
        .put("transferId", transferId)
        .put("generation", generation)
        .put("expiresAtMillis", expiresAtMillis)
        .put("index", index)
        .put("chunkCount", chunks.size)
        .put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))

    private fun manifest(
        transferId: String,
        bytes: ByteArray,
        chunkCount: Int,
        generation: Long = 1,
        expiresAtMillis: Long = now + 60_000,
        brandColor: String = "#123456"
    ) = JSONObject()
        .put("version", 2)
        .put("transferId", transferId)
        .put("generation", generation)
        .put("issuedAtMillis", now)
        .put("expiresAtMillis", expiresAtMillis)
        .put("type", "brand-manifest")
        .put("byteLength", bytes.size)
        .put("sha256", sha256(bytes))
        .put("brandColor", brandColor)
        .put("chunkCount", chunkCount)

    private fun transparentPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        for (y in 128 until 384) for (x in 128 until 384) bitmap.setPixel(x, y, Color.RED)
        return ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PAIRING_ID = "AAECAwQFBgcICQoLDA0ODw"
    }
}
