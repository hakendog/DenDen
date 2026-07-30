package com.tensal.denden

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAttemptGuardTest {
    @Test
    fun onlyLatestAttemptRemainsCurrent() {
        val guard = PairingAttemptGuard()
        val first = guard.begin()
        val second = guard.begin()

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
        guard.invalidate()
        assertFalse(guard.isCurrent(second))
    }
}
