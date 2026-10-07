package com.enigmacast.data.discovery

import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds Enigma2 receivers on the LAN for the AutoDetect dialog: the TV's own subnet is scanned
 * on ports 80 and 8080 (40 at a time, 400 ms connect, at most 8 s), mDNS runs alongside, and
 * every host found is asked `/api/about`. See [AutodetectEngine]; logcat tag `EnigmaDiscovery`.
 */
@Singleton
class ReceiverAutodetect @Inject constructor(
    private val nsd: NsdReceiverDiscovery,
    private val localNetworks: LocalNetworks,
) {
    private val engine by lazy {
        AutodetectEngine(
            scanner = TcpPortScanner(parallel = 40, connectTimeoutMs = 400),
            probe = AboutProbe(log = EnigmaLog::discovery),
            log = EnigmaLog::discovery,
        )
    }

    /** [formHost] (the address already typed in, if any) is checked as well. */
    suspend fun find(formHost: DiscoveredHost? = null): AutodetectResult {
        val candidates = localNetworks.candidates()
        candidates.forEach {
            EnigmaLog.discovery("interface ${it.interfaceName.ifBlank { "?" }} ${it.address}/${it.prefix} via ${it.source}${if (it.vpn) " (VPN, skipped)" else ""}")
        }
        val chosen = NetworkChoice.choose(candidates)
        EnigmaLog.discovery(
            if (chosen.isEmpty()) "no private IPv4 LAN found among ${candidates.size} address(es)"
            else "scanning ${chosen.joinToString { "${it.label} (${it.interfaceName} ${it.address})" }}",
        )
        val extra = listOfNotNull(formHost?.takeIf { it.host.isNotBlank() })
        return engine.run(chosen, extra) { nsd.discoverHosts() }
    }
}
