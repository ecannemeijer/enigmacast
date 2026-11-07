package com.enigmacast.data.network

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks "does the receiver answer at all?" before the first channel load, so an unreachable
 * receiver shows a clear notice (retry, receiver setup, IPTV) instead of a loading bar that
 * hangs at 15 % until every request has timed out.
 *
 * A plain TCP connect to the web port of the current route (home, remote or tunnel), whatever
 * network the TV is on: Wi-Fi, Ethernet or a VPN such as OpenVPN into the home network. The
 * second try waits longer for slow VPN links.
 */
@Singleton
class ReceiverReachability @Inject constructor(
    private val router: ReceiverRouter,
) {
    /** The receiver did not answer; [host] is the address that was tried. */
    data class Unreachable(val host: String, val port: Int)

    private val _state = MutableStateFlow<Unreachable?>(null)
    val state: StateFlow<Unreachable?> = _state.asStateFlow()
    private val _retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** "Retry" on the notice: the screens load again. */
    val retries: SharedFlow<Unit> = _retries.asSharedFlow()

    suspend fun check(config: ReceiverConfig): Boolean {
        val target = runCatching { router.effective(config) }.getOrDefault(config)
        val host = target.host.trim()
        if (host.isBlank()) return true
        val port = target.httpPort.takeIf { it in 1..65535 } ?: if (target.useHttps) 443 else 80
        for (timeout in TIMEOUTS_MS) {
            if (connect(host, port, timeout)) {
                _state.value = null
                return true
            }
        }
        EnigmaLog.connection("receiver not reachable on its web port")
        router.onRequestFailed(config)
        _state.value = Unreachable(host, port)
        return false
    }

    fun retry() {
        _state.value = null
        _retries.tryEmit(Unit)
    }

    fun dismiss() {
        _state.value = null
    }

    private suspend fun connect(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    private companion object {
        /** Home Wi-Fi answers in milliseconds; an OpenVPN link can need a few seconds. */
        val TIMEOUTS_MS = listOf(3_000, 6_000)
    }
}
