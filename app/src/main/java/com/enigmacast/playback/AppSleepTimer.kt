package com.enigmacast.playback

import com.enigmacast.domain.player.SleepTimerChoices
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class SleepEvent { WARNING, EXPIRED }

/**
 * The app's own sleep timer (quick options bar). It does not touch the receiver, which
 * may be in use by others; when it runs out EnigmaCast closes and its stream stops.
 */
@Singleton
class AppSleepTimer @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _endsAtMs = MutableStateFlow(0L)

    /** Wall-clock time the timer runs out, or 0 when it is off. */
    val endsAtMs: StateFlow<Long> = _endsAtMs.asStateFlow()
    private val _events = MutableSharedFlow<SleepEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SleepEvent> = _events.asSharedFlow()

    fun set(minutes: Int) {
        job?.cancel()
        if (minutes <= 0) {
            _endsAtMs.value = 0L
            EnigmaLog.player("sleep timer off")
            return
        }
        val totalMs = minutes * 60_000L
        _endsAtMs.value = System.currentTimeMillis() + totalMs
        EnigmaLog.player("sleep timer $minutes min")
        job = scope.launch {
            val warnAfter = (totalMs - SleepTimerChoices.WARNING_MS).coerceAtLeast(0L)
            delay(warnAfter)
            _events.tryEmit(SleepEvent.WARNING)
            delay(totalMs - warnAfter)
            _endsAtMs.value = 0L
            _events.tryEmit(SleepEvent.EXPIRED)
        }
    }
}
