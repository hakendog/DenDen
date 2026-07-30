package com.tensal.denden.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPairingAttemptTest {
    @Test
    fun timedOutAttemptReleasesRuntimeForRetry() = runTest {
        var timedOut = false
        try {
            runDirectPairingAttempt(timeoutMillis = 1) { awaitCancellation() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)

        var retried = false
        runDirectPairingAttempt { retried = true }
        assertTrue(retried)
    }

    @Test
    fun pairingAttemptsAreSerialized() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            runDirectPairingAttempt {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        var secondEntered = false
        val second = launch { runDirectPairingAttempt { secondEntered = true } }
        yield()
        assertFalse(secondEntered)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
    }
}
