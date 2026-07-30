package com.tensal.denden.readiness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTestVerificationTest {
    @Test
    fun waitsUntilServiceLifecycleIsObservable() = runTest {
        val states = ArrayDeque(listOf("pending", "pending", "ringing"))

        assertTrue(awaitLocalTestConfirmation(
            loadState = { states.removeFirst() },
            pause = {},
            maxAttempts = 3
        ))
    }

    @Test
    fun pendingStateNeverClaimsSuccess() = runTest {
        assertFalse(awaitLocalTestConfirmation(
            loadState = { "pending" },
            pause = {},
            maxAttempts = 3
        ))
    }

    @Test
    fun stoppedStateCanDriveTimelineRefresh() = runTest {
        val states = ArrayDeque(listOf("ringing", "stopped"))

        assertTrue(awaitLifecycleState(
            loadState = { states.removeFirst() },
            acceptedStates = setOf("stopped", "missed"),
            pause = {},
            maxAttempts = 2
        ))
    }
}
