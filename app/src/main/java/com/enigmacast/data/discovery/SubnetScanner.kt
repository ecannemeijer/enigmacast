package com.enigmacast.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback for receivers that do not announce themselves over mDNS: tries a TCP connection to
 * the usual OpenWebif ports on every address of the TV's own /24 LAN. Only addresses that
 * accept a connection are passed on to the `/api/about` check. VPN networks (WireGuard) are
 * skipped, so this always scans the local network.
 */
@Singleton
class SubnetScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun openHosts(): List<DiscoveredHost> {
        val own = localAddresses()
        if (own.isEmpty()) {
            EnigmaLog.connection("autodetect: no local IPv4 network to scan")
            return emptyList()
        }
        val targets = own.flatMap { SubnetScan.candidates(it) }.distinct()
        EnigmaLog.connection("autodetect: scanning ${targets.size} address(es) on ${own.map(SubnetScan::subnetLabel).distinct()}")
        val limit = Semaphore(PARALLEL)
        return coroutineScope {
            targets.flatMap { host -> SubnetScan.PORTS.map { port -> host to port } }
                .map { (host, port) ->
                    async(Dispatchers.IO) {
                        limit.withPermit { if (isOpen(host, port)) DiscoveredHost(host, port) else null }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }.also { EnigmaLog.connection("autodetect: ${it.size} open web port(s) found by the scan") }
    }

    private suspend fun isOpen(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        }.getOrDefault(false)
    }

    /** The TV's own private IPv4 addresses on non-VPN networks (Ethernet, Wi-Fi). */
    private fun localAddresses(): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching {
            @Suppress("DEPRECATION")
            manager.allNetworks.flatMap { network ->
                val caps = manager.getNetworkCapabilities(network)
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
                manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                    .map { it.address }
                    .filter { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PARALLEL = 48
        const val CONNECT_TIMEOUT_MS = 350
    }
}

/** Pure helpers for [SubnetScanner]. */
object SubnetScan {
    /** OpenWebif's usual web ports. HTTPS-only receivers are found by mDNS or entered by hand. */
    val PORTS = listOf(80, 8080)

    /** Every other host address in the /24 of [ownIp], or nothing for a non-private address. */
    fun candidates(ownIp: String): List<String> {
        val parts = ownIp.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 } || !isPrivate(parts)) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).filter { it != parts[3] }.map { "$prefix.$it" }
    }

    fun subnetLabel(ownIp: String): String = ownIp.substringBeforeLast('.') + ".0/24"

    private fun isPrivate(p: List<Int>): Boolean =
        p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
}
