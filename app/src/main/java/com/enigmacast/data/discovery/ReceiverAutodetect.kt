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
    private val settings: com.enigmacast.data.settings.SettingsRepository,
) {
    /** Over a VPN (OpenVPN, WireGuard) a home subnet answers slower than the LAN: longer connects. */
    private val vpnEngine by lazy {
        AutodetectEngine(
            scanner = TcpPortScanner(parallel = 60, connectTimeoutMs = 1_200),
            probe = AboutProbe(log = EnigmaLog::discovery),
            log = EnigmaLog::discovery,
        )
    }

    private val engine by lazy {
        AutodetectEngine(
            scanner = TcpPortScanner(parallel = 40, connectTimeoutMs = 400),
            probe = AboutProbe(log = EnigmaLog::discovery),
            log = EnigmaLog::discovery,
        )
    }

    /**
     * [formHost] (the address already typed in, if any) and every saved receiver address are
     * asked directly, whatever network the TV is on. On a VPN the TV's own subnet is not the
     * home LAN, so the subnets of saved private receiver addresses are scanned as well.
     */
    suspend fun find(formHost: DiscoveredHost? = null): AutodetectResult {
        val candidates = localNetworks.candidates()
        candidates.forEach {
            EnigmaLog.discovery("interface ${it.interfaceName.ifBlank { "?" }} ${it.address}/${it.prefix} via ${it.source}${if (it.vpn) " (VPN)" else ""}")
        }
        val chosen = NetworkChoice.choose(candidates)
        val onVpn = candidates.any { it.vpn || NetworkChoice.isVpnName(it.interfaceName) }
        val saved = runCatching { settings.current().receivers }.getOrDefault(emptyList())
            .map { it.host.trim() to it.httpPort }
            .filter { it.first.isNotBlank() }
        val savedHosts = saved.map { (host, port) -> DiscoveredHost(host, port.takeIf { it in 1..65535 } ?: 80) }
        val fallback = if (onVpn) {
            saved.map { it.first }
                .filter { LanRange.isPrivate(it) }
                .map { LocalNetwork(it, 24, "saved", "saved") }
                .filter { net -> chosen.none { it.label == net.label } }
                .distinctBy { it.label }
                .take(2)
        } else {
            emptyList()
        }
        val networks = chosen + fallback
        EnigmaLog.discovery(
            if (networks.isEmpty()) "no private IPv4 LAN found among ${candidates.size} address(es)"
            else "scanning ${networks.joinToString { "${it.label} (${it.interfaceName} ${it.address})" }}",
        )
        val extra = (listOfNotNull(formHost?.takeIf { it.host.isNotBlank() }) + savedHosts).distinctBy { it.host to it.port }
        val run = if (fallback.isNotEmpty()) vpnEngine else engine
        return run.run(networks, extra) { nsd.discoverHosts() }
    }
}
