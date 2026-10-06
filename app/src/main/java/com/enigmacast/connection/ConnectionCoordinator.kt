package com.enigmacast.connection

import com.enigmacast.domain.model.ConnectionState
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionCoordinator(
    private val probe: suspend () -> ReceiverCapabilities,
    private val heartbeat: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    fun nudge() {
        nudge.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var connected = false
        var needsFullProbe = true
        while (isActive) {
            try {
                if (needsFullProbe) {
                    _state.value = if (connected) {
                        ConnectionState.Reconnecting("")
                    } else {
                        ConnectionState.Connecting
                    }
                    val capabilities = probe()
                    _state.value = ConnectionState.Connected(capabilities)
                    connected = true
                    needsFullProbe = false
                    EnigmaLog.connection("receiver online ${capabilities.model}")
                } else {
                    heartbeat()
                    val current = _state.value
                    if (current !is ConnectionState.Connected) {
                        needsFullProbe = true
                        continue
                    }
                }
                withTimeoutOrNull(8_000) { nudge.receive() }?.let {
                    needsFullProbe = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Raw system text (not translated); the screen adds its own translated wording.
                val reason = error.message.orEmpty()
                EnigmaLog.connection("receiver unreachable")
                _state.value = if (connected) ConnectionState.Reconnecting(reason) else ConnectionState.Failed(reason)
                needsFullProbe = true
                delay(2_000)
            }
        }
    }
}
