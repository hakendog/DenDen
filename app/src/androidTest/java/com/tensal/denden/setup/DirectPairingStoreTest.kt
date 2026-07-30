package com.tensal.denden.setup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tensal.denden.protocol.DirectFcmInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DirectPairingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun pairingSecretsAreEncryptedAndRevisionGuardsEveryCommit() {
        val name = "direct-pairing-test-${UUID.randomUUID()}"
        val store = DirectPairingStore(context, name)
        val first = invite("AAECAwQFBgcICQoLDA0ODw")

        val revision = store.stage(first)
        assertEquals(PairingState.PENDING, store.snapshot().state)
        assertEquals(PairingPhase.SUBSCRIBE, store.snapshot().phase)
        assertFalse(store.markActive(revision + 1))
        assertTrue(store.markActive(revision))
        assertEquals(first.pairingId, store.snapshot().active?.pairingId)
        assertFalse(store.markError(revision, "晚到的配對錯誤"))
        assertEquals(PairingState.ACTIVE, store.snapshot().state)
        val raw = context.getSharedPreferences(name, 0).all.toString()
        assertFalse(raw.contains(first.eventKey))
        assertFalse(raw.contains(first.brandKey))

        val rotated = invite("EBESExQVFhcYGRobHB0eHw")
        val rotatedRevision = store.stage(rotated)
        assertEquals(PairingPhase.CLEANUP, store.snapshot().phase)
        assertNotNull(store.snapshot().cleanup)
        assertNull(store.snapshot().activeKeys())
        assertTrue(store.markError(rotatedRevision, "暫時離線"))
        assertEquals(PairingState.ERROR, store.snapshot().state)
        assertTrue(store.retryError(rotatedRevision))
        assertEquals(PairingState.PENDING, store.snapshot().state)
        assertTrue(store.markCleanupComplete(rotatedRevision))
        assertTrue(store.markActive(rotatedRevision))
        assertEquals(rotated.pairingId, store.snapshot().active?.pairingId)

        val clearRevision = store.beginClear()
        assertNull(store.snapshot().activeKeys())
        assertEquals(PairingPhase.CLEANUP, store.snapshot().phase)
        assertTrue(store.markCleanupComplete(clearRevision))
        assertEquals(PairingState.UNPAIRED, store.snapshot().state)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias("$name.cleanup"))
    }

    @Test
    fun missingKeystoreKeyFailsClosedInsteadOfCreatingAReplacementKey() {
        val name = "direct-pairing-missing-key-${UUID.randomUUID()}"
        val store = DirectPairingStore(context, name)
        val revision = store.stage(invite("AAECAwQFBgcICQoLDA0ODw"))
        assertTrue(store.markActive(revision))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "$name.secrets.$revision"
        assertTrue(keyStore.containsAlias(alias))
        keyStore.deleteEntry(alias)

        val snapshot = store.snapshot()
        assertEquals(PairingState.ERROR, snapshot.state)
        assertNull(snapshot.active)
        assertFalse(store.retryError(snapshot.localPairingRevision))
        assertFalse(keyStore.containsAlias(alias))
        assertTrue(snapshot.error?.contains("重新配對") == true)
        store.beginClear()
    }

    @Test
    fun missingCleanupKeyCannotBeMistakenForCompletedCleanup() {
        val name = "direct-pairing-missing-cleanup-${UUID.randomUUID()}"
        val store = DirectPairingStore(context, name)
        val revision = store.stage(invite("AAECAwQFBgcICQoLDA0ODw"))
        assertTrue(store.markActive(revision))
        store.beginClear()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cleanupAlias = "$name.cleanup"
        assertTrue(keyStore.containsAlias(cleanupAlias))
        keyStore.deleteEntry(cleanupAlias)

        val snapshot = store.snapshot()
        assertEquals(PairingState.ERROR, snapshot.state)
        assertEquals(PairingPhase.CLEANUP, snapshot.phase)
        assertNull(snapshot.cleanup)
        assertFalse(store.retryError(snapshot.localPairingRevision))
        assertTrue(snapshot.error?.contains("重新配對") == true)
        store.beginClear()
    }

    @Test
    fun separateStoreInstancesSerializePairingRevisions() {
        val name = "direct-pairing-concurrent-${UUID.randomUUID()}"
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val revisions = (1..20).map { index ->
                workers.submit<Long> {
                    start.await()
                    DirectPairingStore(context, name).stage(invite("pairing-$index"))
                }
            }
            start.countDown()
            assertEquals((1L..20L).toList(), revisions.map { it.get() }.sorted())
            val store = DirectPairingStore(context, name)
            assertEquals(20L, store.snapshot().localPairingRevision)
            val clearRevision = store.beginClear()
            assertTrue(store.markCleanupComplete(clearRevision))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun stateObserverReportsBackgroundActivation() {
        val name = "direct-pairing-observer-${UUID.randomUUID()}"
        val store = DirectPairingStore(context, name)
        val activated = CountDownLatch(1)
        val stop = store.observeState {
            if (store.snapshot().state == PairingState.ACTIVE) activated.countDown()
        }
        val revision = store.stage(invite("AAECAwQFBgcICQoLDA0ODw"))

        assertTrue(store.markActive(revision))
        assertTrue(activated.await(2, TimeUnit.SECONDS))

        stop()
        val clearRevision = store.beginClear()
        assertTrue(store.markCleanupComplete(clearRevision))
    }

    private fun invite(pairingId: String) = DirectFcmInvite(
        projectId = "denden-demo-123",
        firebaseAppId = "1:123456789012:android:0123456789abcdef",
        apiKey = "AIzaSyDendenProtocolTestOnly000000000",
        gcmSenderId = "123456789012",
        androidPackageName = context.packageName,
        pairingId = pairingId,
        topic = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
        eventKeyId = "event-key-000001",
        eventKey = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8",
        brandKeyId = "brand-key-000001",
        brandKey = "YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8",
        createdAtMillis = 1_800_000_000_000,
        displayExpiresAtMillis = 1_800_000_600_000
    )
}
