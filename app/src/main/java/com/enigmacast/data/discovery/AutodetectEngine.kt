package com.enigmacast.data.discovery

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The outcome of one Autodetect run, for the dialog and the log. */
data class AutodetectResult(
    val receivers: List<DiscoveredReceiver>,
    /** The subnets that were scanned, e.g. `192.168.1.0/24`; empty when the TV has no LAN address. */
    val subnets: List<String>,
    val scannedAddresses: Int,
    val elapsedMs: Long,
    val timedOut: Boolean = false,
)

/**
 * Autodetect without Android: scan the chosen subnets on [ports], ask every open port and every
 * mDNS host `/api/about`, keep the Enigma2 answers. Every step goes to [log] (logcat tag
 * `EnigmaDiscovery` in the app).
 */
class AutodetectEngine(
    private val scanner: TcpPortScanner = TcpPortScanner(),
    private val probe: AboutProbe = AboutProbe(),
    private val ports: List<Int> = DEFAULT_PORTS,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        networks: List<LocalNetwork>,
        extraHosts: List<DiscoveredHost> = emptyList(),
        scanBudgetMs: Long = SCAN_BUDGET_MS,
        mdns: suspend () -> List<DiscoveredHost> = { emptyList() },
    ): AutodetectResult = coroutineScope {
        val started = now()
        val advertised = async {
            try {
                mdns()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                log("mDNS failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
                emptyList()
            }
        }
        val hosts = networks.flatMap { LanRange.hosts(it.address, it.prefix) }.distinct()
        val subnets = networks.map { it.label }.distinct()
        if (hosts.isEmpty()) {
            log("no LAN subnet to scan")
        } else {
            log("scanning ${hosts.size} addresses on ${subnets.joinToString()} ports ${ports.joinToString("/")} (budget $scanBudgetMs ms)")
        }
        val scan = if (hosts.isEmpty()) ScanOutcome(emptyList(), 0, 0, false) else scanner.scan(hosts, ports, scanBudgetMs)
        val scanMs = now() - started
        scan.hits.forEach { log("open ${it.host}:${it.port}") }
        log(
            "scan done in $scanMs ms: ${scan.hits.size} open port(s), ${scan.checked}/${scan.total} checked" +
                if (scan.timedOut) " (time cap reached)" else "",
        )
        val mdnsHosts = advertised.await()
        mdnsHosts.forEach { log("mDNS ${it.host}:${it.port}") }
        extraHosts.forEach { log("also checking the address in the form: ${it.host}:${it.port}") }
        val plan = AutodetectPlan.probes(mdnsHosts, scan.hits + extraHosts)
        val receivers = plan.map { (host, hostPorts) -> async { identify(host, hostPorts) } }
            .awaitAll()
            .filterNotNull()
            .distinctBy { it.host }
            .sortedWith(compareBy({ AutodetectPlan.sortKey(it.host) }, { it.httpPort }))
        val elapsed = now() - started
        log("autodetect finished in $elapsed ms: ${receivers.size} receiver(s)${receivers.joinToString(prefix = " ") { it.label }.trimEnd()}")
        AutodetectResult(receivers, subnets, hosts.size, elapsed, scan.timedOut)
    }

    private suspend fun identify(host: String, hostPorts: List<Int>): DiscoveredReceiver? {
        for (port in hostPorts) {
            probe.probe(host, port, https = port == 443)?.let { return it }
        }
        return null
    }

    companion object {
        val DEFAULT_PORTS = listOf(80, 8080)
        const val SCAN_BUDGET_MS = 8_000L
    }
}

/** Which host to ask on which ports; pure so it can be tested. */
object AutodetectPlan {
    /**
     * One entry per host: an mDNS host gets its advertised port, then 80 and 8080 (and 443 if
     * advertised); a host from the scan or the form gets the port(s) that are open / entered.
     */
    fun probes(mdns: List<DiscoveredHost>, scanned: List<DiscoveredHost>): List<Pair<String, List<Int>>> {
        val plan = linkedMapOf<String, LinkedHashSet<Int>>()
        scanned.forEach { plan.getOrPut(it.host) { linkedSetOf() }.add(it.port) }
        mdns.forEach { plan.getOrPut(it.host) { linkedSetOf() }.addAll(listOf(it.port, 80, 8080)) }
        return plan.map { (host, ports) -> host to ports.filter { it in 1..65535 } }
    }

    /** Numeric order for IPv4 (`192.168.1.9` before `192.168.1.10`); names after addresses. */
    fun sortKey(host: String): String {
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) return "~$host"
        return parts.joinToString(".") { it.toString().padStart(3, '0') }
    }
}
