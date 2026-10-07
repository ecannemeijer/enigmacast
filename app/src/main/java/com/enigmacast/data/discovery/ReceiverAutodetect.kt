package com.enigmacast.data.discovery

import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds Enigma2 receivers on the LAN: mDNS `_http._tcp` and, at the same time, a scan of the
 * TV's own /24 on ports 80 and 8080 (many receivers do not announce themselves). Every host
 * found is then asked `/api/about`, so printers, routers and other web devices are dropped.
 * Manual host entry stays the fallback when nothing answers.
 */
@Singleton
class ReceiverAutodetect @Inject constructor(
    private val nsd: NsdReceiverDiscovery,
    private val api: OpenWebifApi,
    private val scanner: SubnetScanner,
) {
    /**
     * Browses and scans the LAN and returns only hosts that look like OpenWebif, sorted by
     * address. mDNS hosts are tried on their advertised port, then the usual OpenWebif ports
     * (HTTPS after HTTP); scanned hosts on the port that was open.
     */
    suspend fun find(timeoutMs: Long = 4_000): List<DiscoveredReceiver> = coroutineScope {
        val advertised = async { runCatching { nsd.discoverHosts(timeoutMs) }.getOrDefault(emptyList()) }
        val scanned = async { runCatching { scanner.openHosts() }.getOrDefault(emptyList()) }
        val mdns = advertised.await()
        val open = scanned.await()
        EnigmaLog.connection("autodetect: ${mdns.size} mDNS http host(s), ${open.size} open port(s) from the scan")
        val probes = AutodetectPlan.probes(mdns, open)
        probes.map { (host, ports) -> async { identifyHost(host, ports) } }
            .awaitAll()
            .filterNotNull()
            .distinctBy { it.host }
            .sortedWith(compareBy({ AutodetectPlan.sortKey(it.host) }, { it.httpPort }))
    }

    private suspend fun identifyHost(host: String, ports: List<Int>): DiscoveredReceiver? {
        for (port in ports) {
            for (https in listOf(false, true)) {
                if (port == 80 && https) continue
                if (port == 443 && !https) continue
                val config = ReceiverConfig(host = host, httpPort = port, useHttps = https)
                val caps = runCatching { api.identify(config) }.getOrNull() ?: continue
                EnigmaLog.connection("autodetect: OpenWebif at $host:$port https=$https model=${caps.model}")
                val image = listOf(caps.imageDistro, caps.imageVersion).filter { it.isNotBlank() }.joinToString(" ")
                return DiscoveredReceiver(
                    host = host,
                    httpPort = port,
                    useHttps = https,
                    model = caps.model.ifBlank { caps.brand },
                    image = image,
                )
            }
        }
        return null
    }
}

/** Which host to ask on which ports; pure so it can be tested. */
object AutodetectPlan {
    /**
     * One entry per host: an mDNS host gets its advertised port, then 80, 443 and 8080; a host
     * that only the scan found gets just the port(s) that accepted a connection.
     */
    fun probes(mdns: List<DiscoveredHost>, scanned: List<DiscoveredHost>): List<Pair<String, List<Int>>> {
        val plan = linkedMapOf<String, LinkedHashSet<Int>>()
        mdns.forEach { plan.getOrPut(it.host) { linkedSetOf() }.addAll(listOf(it.port, 80, 443, 8080)) }
        scanned.forEach { plan.getOrPut(it.host) { linkedSetOf() }.add(it.port) }
        return plan.map { (host, ports) -> host to ports.filter { it in 1..65535 } }
    }

    /** Numeric order for IPv4 (`192.168.1.9` before `192.168.1.10`); names after addresses. */
    fun sortKey(host: String): String {
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) return "~$host"
        return parts.joinToString(".") { it.toString().padStart(3, '0') }
    }
}
