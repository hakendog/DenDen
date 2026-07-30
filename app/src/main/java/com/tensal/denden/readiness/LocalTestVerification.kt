package com.tensal.denden.readiness

import kotlinx.coroutines.delay

suspend fun awaitLocalTestConfirmation(
    loadState: suspend () -> String?,
    pause: suspend () -> Unit = { delay(100) },
    maxAttempts: Int = 50
): Boolean = awaitLifecycleState(loadState, setOf("ringing", "stopped"), pause, maxAttempts)

suspend fun awaitLifecycleState(
    loadState: suspend () -> String?,
    acceptedStates: Set<String>,
    pause: suspend () -> Unit = { delay(100) },
    maxAttempts: Int = 50
): Boolean {
    repeat(maxAttempts) {
        if (loadState() in acceptedStates) return true
        pause()
    }
    return false
}
