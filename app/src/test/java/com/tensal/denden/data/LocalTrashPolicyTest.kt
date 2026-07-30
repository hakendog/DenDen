package com.tensal.denden.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTrashPolicyTest {
    @Test
    fun purgeIsExactlyThirtyDaysAfterTrash() {
        assertEquals(1_000L + TRASH_RETENTION_MILLIS, trashPurgeAt(1_000L))
    }

    @Test
    fun purgeBoundaryIncludesExactExpiry() {
        val purgeAt = trashPurgeAt(1_000L)
        assertFalse(isTrashExpired(purgeAt, purgeAt - 1))
        assertTrue(isTrashExpired(purgeAt, purgeAt))
        assertTrue(isTrashExpired(purgeAt, purgeAt + 1))
    }

    @Test
    fun onlyAnEventIssuedAfterTrashRestoresTheChannel() {
        assertTrue(shouldRestoreTrashedChannel(11, 10))
        assertFalse(shouldRestoreTrashedChannel(10, 10))
        assertFalse(shouldRestoreTrashedChannel(9, 10))
    }
}
