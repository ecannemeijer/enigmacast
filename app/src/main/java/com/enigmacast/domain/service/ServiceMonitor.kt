package com.enigmacast.domain.service

import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ServiceMonitor(
    private val source: ServiceStateSource,
    private val configProvider: () -> ReceiverConfig?,
    private val idleIntervalMs: Long = 1_500,
    private val burstDelaysMs: List<Long> = listOf(50, 100, 200, 350, 500, 1_000),
) {
    private val detector = ServiceChangeDetector()
    private val nudge = Channel<Unit>(Channel.CONFLATED)
    private val burst = ArrayDeque<Long>()
    private val _service = MutableStateFlow<EnigmaService?>(null)
    val service: StateFlow<EnigmaService?> = _service.asStateFlow()
    private val _changes = MutableSharedFlow<EnigmaService>(extraBufferCapacity = 8)
    val changes: SharedFlow<EnigmaService> = _changes.asSharedFlow()

    fun onRemoteCommand() {
        synchronized(burst) {
            burst.clear()
            burst.addAll(burstDelaysMs)
        }
        nudge.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var immediate = true
        while (isActive) {
            val wait = if (immediate) {
                immediate = false
                0L
            } else {
                synchronized(burst) {
                    if (burst.isEmpty()) idleIntervalMs else burst.removeFirst()
                }
            }
            if (wait > 0) {
                withTimeoutOrNull(wait) { nudge.receive() }
            }
            val config = configProvider() ?: continue
            val current = try {
                source.currentService(config)
            } catch (error: Exception) {
                EnigmaLog.service("status failed: ${error.javaClass.simpleName}")
                continue
            }
            if (current.reference.isBlank()) continue
            _service.value = current
            val change = detector.onSample(current) ?: continue
            EnigmaLog.service("service ${change.previousReference} -> ${change.current.reference}")
            _changes.emit(change.current)
        }
    }
}
