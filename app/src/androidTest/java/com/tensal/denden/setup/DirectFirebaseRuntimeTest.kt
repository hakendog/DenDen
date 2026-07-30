package com.tensal.denden.setup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.tensal.denden.protocol.DirectFcmInvite
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DirectFirebaseRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var ownsDefaultApp = false

    @After
    fun deleteTestFirebaseApp() {
        if (ownsDefaultApp) runCatching { FirebaseApp.getInstance().delete() }
    }

    @Test
    fun activePairingRestoresDynamicDefaultFirebaseAfterRuntimeLoss() {
        assumeTrue("測試程序已有非測試 Firebase App", runCatching { FirebaseApp.getInstance() }.getOrNull() == null)
        val store = DirectPairingStore(context, "direct-firebase-runtime-${UUID.randomUUID()}")
        val revision = store.stage(invite())
        assertTrue(store.markActive(revision))

        assertTrue(initializeDirectFirebaseRuntime(context, store))
        ownsDefaultApp = true
        assertTrue(defaultFirebaseMatches(store.snapshot()))
        FirebaseApp.getInstance().delete()
        ownsDefaultApp = false

        assertTrue(initializeDirectFirebaseRuntime(context, store))
        ownsDefaultApp = true
        assertTrue(defaultFirebaseMatches(store.snapshot()))
        store.beginClear()
    }

    private fun invite() = DirectFcmInvite(
        projectId = "denden-demo-123",
        firebaseAppId = "1:123456789012:android:0123456789abcdef",
        apiKey = "AIzaSyDendenProtocolTestOnly000000000",
        gcmSenderId = "123456789012",
        androidPackageName = context.packageName,
        pairingId = "AAECAwQFBgcICQoLDA0ODw",
        topic = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
        eventKeyId = "event-key-000001",
        eventKey = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8",
        brandKeyId = "brand-key-000001",
        brandKey = "YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8",
        createdAtMillis = 1_800_000_000_000,
        displayExpiresAtMillis = 1_800_000_600_000
    )
}
