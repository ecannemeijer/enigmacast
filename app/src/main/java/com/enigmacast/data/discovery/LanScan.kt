package com.enigmacast.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** One IPv4 address of the TV with its prefix, as reported by Android, before filtering. */
data class LocalNetwork(
    val address: String,
    val prefix: Int,
    val interfaceName: String = "",
    val source: String = "",
    val vpn: Boolean = false,
) {
    /** `192.168.1.0/24`: the range the scan covers for this address. */
    val label: String get() = LanRange.subnetLabel(address, prefix)
}

/** Which addresses to scan for an IPv4 address and prefix. Pure, so it is unit-tested. */
object LanRange {
    /** Networks wider than /24 (or a missing / odd prefix) are scanned as the TV's own /24. */
    fun effectivePrefix(prefix: Int): Int = if (prefix in 24..30) prefix else 24

    /**
     * Every host address in the subnet of [address] except the TV itself, the network and the
     * broadcast address. A /24 gives 253 addresses (.1–.254 without the TV).
     */
    fun hosts(address: String, prefix: Int): List<String> {
        val ip = parse(address) ?: return emptyList()
        val p = effectivePrefix(prefix)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return (network + 1 until broadcast).filter { it != ip }.map(::format)
    }

    fun subnetLabel(address: String, prefix: Int): String {
        val ip = parse(address) ?: return address
        val p = effectivePrefix(prefix)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return "${format(ip and mask)}/$p"
    }

    /** RFC 1918 home / office ranges. */
    fun isPrivate(address: String): Boolean {
        val ip = parse(address) ?: return false
        val a = (ip shr 24).toInt()
        val b = ((ip shr 16) and 0xFF).toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    fun parse(address: String): Long? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    fun format(ip: Long): String = "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
}

/** Picks the LAN(s) to scan from everything Android reports. */
object NetworkChoice {
    private val VPN_NAMES = listOf("tun", "wg", "ppp", "ipsec", "rmnet", "clat", "dummy", "lo")

    fun isVpnName(name: String): Boolean = VPN_NAMES.any { name.startsWith(it, ignoreCase = true) }

    /**
     * Private IPv4 addresses that are not on a VPN (WireGuard, other VPN apps), one per subnet,
     * Ethernet first, then Wi-Fi; at most two subnets.
     */
    fun choose(candidates: List<LocalNetwork>): List<LocalNetwork> =
        candidates
            .filter { !it.vpn && !isVpnName(it.interfaceName) && LanRange.isPrivate(it.address) }
            .distinctBy { it.label }
            .sortedBy { rank(it.interfaceName) }
            .take(2)

    private fun rank(name: String): Int = when {
        name.startsWith("eth", true) -> 0
        name.startsWith("wlan", true) || name.startsWith("wifi", true) -> 1
        else -> 2
    }
}

data class ScanOutcome(val hits: List<DiscoveredHost>, val checked: Int, val total: Int, val timedOut: Boolean)

/**
 * Tries a TCP connection to [ports] on every host, [parallel] at a time with a
 * [connectTimeoutMs] connect timeout, and stops after a total budget; hits found before the
 * budget ran out are kept. Ports are tried in order for all hosts (all port 80 first).
 */
class TcpPortScanner(
    private val parallel: Int = 40,
    private val connectTimeoutMs: Int = 400,
    private val connect: (host: String, port: Int, timeoutMs: Int) -> Boolean = ::tcpConnect,
) {
    suspend fun scan(hosts: List<String>, ports: List<Int>, budgetMs: Long): ScanOutcome {
        val jobs = ports.flatMap { port -> hosts.map { host -> host to port } }
        val queue = Channel<Pair<String, Int>>(Channel.UNLIMITED)
        jobs.forEach { queue.trySend(it) }
        queue.close()
        val hits = ConcurrentLinkedQueue<DiscoveredHost>()
        val checked = AtomicInteger()
        val finished = withTimeoutOrNull(budgetMs) {
            coroutineScope {
                repeat(parallel.coerceAtLeast(1)) {
                    launch(Dispatchers.IO) {
                        for ((host, port) in queue) {
                            // Taking from a filled channel never suspends, so check the budget here.
                            ensureActive()
                            if (connect(host, port, connectTimeoutMs)) hits += DiscoveredHost(host, port)
                            checked.incrementAndGet()
                        }
                    }
                }
            }
            true
        } ?: false
        val sorted = hits.toList().sortedWith(compareBy({ AutodetectPlan.sortKey(it.host) }, { it.port }))
        return ScanOutcome(sorted, checked.get(), jobs.size, timedOut = !finished)
    }
}

fun tcpConnect(host: String, port: Int, timeoutMs: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    true
} catch (_: Exception) {
    false
}
