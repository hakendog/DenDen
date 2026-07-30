package com.tensal.denden.alarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface AudioPlayer {
    var isPlaying: Boolean
    fun play()
    fun stop()
}

interface VibratorController {
    var isVibrating: Boolean
    fun vibrate()
    fun cancel()
}

interface Clock {
    fun currentTimeMillis(): Long
}

sealed interface AlarmRuntimeSnapshot {
    data object Unknown : AlarmRuntimeSnapshot
    data class Active(val eventId: String) : AlarmRuntimeSnapshot
    data class Terminal(val eventId: String, val reason: String) : AlarmRuntimeSnapshot
}

class AlarmRuntimeStore(initial: AlarmRuntimeSnapshot = AlarmRuntimeSnapshot.Unknown) {
    private val mutableSnapshot = MutableStateFlow(initial)
    val snapshot: StateFlow<AlarmRuntimeSnapshot> = mutableSnapshot.asStateFlow()

    fun markActive(eventId: String) {
        require(eventId.isNotBlank())
        mutableSnapshot.update { current ->
            if (current is AlarmRuntimeSnapshot.Active && current.eventId != eventId) current
            else AlarmRuntimeSnapshot.Active(eventId)
        }
    }

    fun markTerminal(eventId: String, reason: String) {
        require(eventId.isNotBlank())
        mutableSnapshot.update { current ->
            if (current is AlarmRuntimeSnapshot.Active && current.eventId != eventId) current
            else AlarmRuntimeSnapshot.Terminal(eventId, reason)
        }
    }
}

object AlarmRuntime {
    private val store = AlarmRuntimeStore()
    val snapshot: StateFlow<AlarmRuntimeSnapshot> = store.snapshot

    fun markActive(eventId: String) = store.markActive(eventId)
    fun markTerminal(eventId: String, reason: String) = store.markTerminal(eventId, reason)
}

fun AlarmRuntimeSnapshot.isActiveFor(eventId: String?): Boolean =
    eventId != null && this is AlarmRuntimeSnapshot.Active && this.eventId == eventId

fun AlarmRuntimeSnapshot.isTerminalFor(eventId: String?): Boolean =
    eventId != null && this is AlarmRuntimeSnapshot.Terminal && this.eventId == eventId

class ActiveAlarmRegistry {
    var eventId: String? = null
        private set

    fun start(newEventId: String): Boolean {
        if (newEventId.isBlank() || eventId != null) return false
        eventId = newEventId
        return true
    }

    fun stop(targetEventId: String?): Boolean {
        if (targetEventId == null || targetEventId != eventId) return false
        eventId = null
        return true
    }
}

enum class StopIntentDisposition {
    STOP_ACTIVE,
    STOP_IDLE_SERVICE,
    IGNORE_ACTIVE_MISMATCH
}

fun stopIntentDisposition(targetEventId: String?, activeEventId: String?): StopIntentDisposition =
    when {
        activeEventId == null -> StopIntentDisposition.STOP_IDLE_SERVICE
        targetEventId == activeEventId -> StopIntentDisposition.STOP_ACTIVE
        else -> StopIntentDisposition.IGNORE_ACTIVE_MISMATCH
    }

class AlarmController(
    private val audioPlayer: AudioPlayer,
    private val vibratorController: VibratorController,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val onStopped: ((reason: String) -> Unit)? = null
) {
    var startedAt: Long? = null
        private set
    var stoppedAt: Long? = null
        private set

    val isPlaying: Boolean get() = startedAt != null && stoppedAt == null

    private var autoStopJob: Job? = null

    fun start(durationSeconds: Int?) {
        if (isPlaying) stop("degraded")
        val autoStopSeconds = when {
            durationSeconds == null || durationSeconds <= 0 -> DEFAULT_DURATION
            else -> minOf(durationSeconds, MAX_DURATION)
        }
        vibratorController.vibrate()
        audioPlayer.play()
        startedAt = clock.currentTimeMillis()
        stoppedAt = null
        autoStopJob = autoStopSeconds?.let { seconds ->
            scope.launch {
                delay(seconds * 1000L)
                stop("timeout")
            }
        }
    }

    fun stop(reason: String = "manual") {
        if (startedAt == null) return
        if (stoppedAt != null) return
        autoStopJob?.cancel()
        autoStopJob = null
        vibratorController.cancel()
        audioPlayer.stop()
        stoppedAt = clock.currentTimeMillis()
        onStopped?.invoke(reason)
    }

    fun dispose() {
        autoStopJob?.cancel()
        autoStopJob = null
        vibratorController.cancel()
        audioPlayer.stop()
        if (startedAt != null && stoppedAt == null) stoppedAt = clock.currentTimeMillis()
    }

    companion object {
        const val DEFAULT_DURATION = 30
        const val MAX_DURATION = 300
    }
}
