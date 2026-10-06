package com.enigmacast.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.AccessMode
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Brings the app's VPN tunnel up for the tunnel route (implemented by the WireGuard support). */
interface TunnelControl {
    fun enabledFor(config: ReceiverConfig): Boolean

    /** True once the tunnel is up; false when it cannot be (no consent, bad config, error). */
    suspend fun up(config: ReceiverConfig): Boolean

    suspend fun down()
}

/** The route in use, for the stream info and the player's warnings. */
data class RouteStatus(
    val receiverKey: String = "",
    val route: ReceiverRoute = ReceiverRoute.HOME,
    val homeReachable: Boolean? = null,
    val checkedAtMs: Long = 0L,
    val generation: Int = -1,
    val network: Int = -1,
)

/**
 * Chooses between the home address, the remote profile and the WireGuard tunnel
 * ([ReceiverRouting.choose]) and rewrites OpenWebif URLs for the chosen route.
 *
 * The home address is checked with a short TCP connect only when there is an alternative.
 * The answer is kept for five minutes, until the network changes, or until a request at
 * home fails. Host names are not logged.
 *
 * While the tunnel is up the home address answers through it, so the router keeps the tunnel
 * until the network changes instead of probing (which would flip back and forth); on a network
 * change the tunnel goes down first so the check sees the real network. The app's own VPN
 * network appearing or going away is not a network change.
 */
@Singleton
class ReceiverRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val endpoints: OpenWebifEndpoints,
    private val tunnelControl: Provider<TunnelControl>,
) {
    private val _status = MutableStateFlow(RouteStatus())
    val status: StateFlow<RouteStatus> = _status.asStateFlow()
    private val mutex = Mutex()
    private val generation = AtomicInteger(0)
    private val networkGeneration = AtomicInteger(0)
    private val vpnNetworks = ConcurrentHashMap.newKeySet<Network>()

    init {
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val vpn = runCatching {
                        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    }.getOrDefault(false)
                    if (vpn) vpnNetworks += network else networkChanged()
                }

                override fun onLost(network: Network) {
                    if (!vpnNetworks.remove(network)) networkChanged()
                }
            })
        }
    }

    suspend fun route(config: ReceiverConfig): ReceiverRoute = mutex.withLock {
        val key = key(config)
        val now = SystemClock.elapsedRealtime()
        val current = _status.value
        val gen = generation.get()
        val network = networkGeneration.get()
        if (current.receiverKey == key && current.generation == gen && now - current.checkedAtMs < TTL_MS) {
            return@withLock current.route
        }
        val control = tunnelControl.get()
        val tunnelEnabled = control.enabledFor(config)
        val sameNetwork = current.receiverKey == key && current.network == network
        if (current.route == ReceiverRoute.TUNNEL && sameNetwork && tunnelEnabled && config.accessMode != AccessMode.HOME) {
            if (control.up(config)) {
                _status.value = current.copy(checkedAtMs = now, generation = gen)
                return@withLock ReceiverRoute.TUNNEL
            }
        }
        if (current.route == ReceiverRoute.TUNNEL) control.down()
        val homeReachable = if (ReceiverRouting.needsProbe(config, tunnelEnabled)) probe(config.host, config.httpPort) else null
        var route = ReceiverRouting.choose(
            mode = config.accessMode,
            homeReachable = homeReachable ?: true,
            remoteConfigured = ReceiverRouting.remoteConfigured(config),
            tunnelEnabled = tunnelEnabled,
        )
        if (route == ReceiverRoute.TUNNEL && !control.up(config)) {
            route = if (ReceiverRouting.remoteConfigured(config)) ReceiverRoute.REMOTE else ReceiverRoute.HOME
        }
        if (route != current.route || key != current.receiverKey) EnigmaLog.connection("route ${route.name.lowercase()}")
        _status.value = RouteStatus(key, route, homeReachable, now, gen, network)
        route
    }

    suspend fun effective(config: ReceiverConfig): ReceiverConfig = ReceiverRouting.effective(config, route(config))

    /** [url] built for the home address, pointed at the address of the current route. */
    suspend fun rewrite(config: ReceiverConfig, url: String): String {
        val effective = effective(config)
        if (effective == config) return url
        val home = endpoints.webifBase(config)
        return if (url.startsWith(home)) endpoints.webifBase(effective) + url.removePrefix(home) else url
    }

    /** A request failed: check the route again next time when there is another way in. */
    fun onRequestFailed(config: ReceiverConfig) {
        if (config.accessMode != AccessMode.HOME) invalidate()
    }

    fun invalidate() {
        generation.incrementAndGet()
    }

    private fun networkChanged() {
        networkGeneration.incrementAndGet()
        invalidate()
    }

    private suspend fun probe(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)
    }

    private fun key(config: ReceiverConfig) =
        listOf(config.id, config.host, config.httpPort, config.remoteHost, config.accessMode, config.wireGuard).joinToString("|")

    private companion object {
        const val TTL_MS = 5 * 60 * 1000L
        const val PROBE_TIMEOUT_MS = 1_200
    }
}
