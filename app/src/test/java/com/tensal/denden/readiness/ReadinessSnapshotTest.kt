package com.tensal.denden.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessSnapshotTest {
    @Test
    fun loadingDoesNotClaimFailure() {
        assertTrue(ReadinessSnapshot.Loading.isLoading)
        assertFalse(ReadinessSnapshot.Loading.isReady)
        assertTrue(ReadinessSnapshot.Loading.evidence.all { it.detail == "檢查中" })
    }

    @Test
    fun readyRequiresPairingPermissionAndAlarmChannel() {
        val ready = buildDirectReadinessSnapshot(true, true, true, 10, 30, "local_pass", null)
        assertTrue(ready.isReady)
        assertEquals(5, ready.evidence.size)
    }

    @Test
    fun missingRequirementsAreExplicit() {
        val result = buildDirectReadinessSnapshot(false, false, false, 0, 0, null, "messages_deleted")
        assertFalse(result.isReady)
        assertEquals(listOf("fcm_pairing", "notification_permission", "alarm_channel"), result.blockingReasons)
        assertEquals("messages_deleted", result.lastDegradedReason)
    }
}
