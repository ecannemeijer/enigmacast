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
 * Finds Enigma2 receivers on the LAN: mDNS `_http._tcp`, then `/api/about` on each host so
 * printers and other HTTP devices are dropped. Manual host entry stays the fallback when
 * nothing is advertised or nothing answers.
 */
@Singleton
class ReceiverAutodetect @Inject constructor(
    private val nsd: NsdReceiverDiscovery,
    private val api: OpenWebifApi,
) {
    /**
     * Browses the LAN and returns only hosts that look like OpenWebif. Tries the advertised
     * port first, then the usual OpenWebif ports when that fails. HTTPS is tried after HTTP.
     */
    suspend fun find(timeoutMs: Long = 4_000): List<DiscoveredReceiver> {
        val hosts = nsd.discoverHosts(timeoutMs)
        EnigmaLog.connection("autodetect: ${hosts.size} mDNS http host(s)")
        if (hosts.isEmpty()) return emptyList()
        return coroutineScope {
            hosts.map { host ->
                async { identifyHost(host) }
            }.awaitAll().filterNotNull().distinctBy { "${it.host}:${it.httpPort}:${it.useHttps}" }
        }
    }

    private suspend fun identifyHost(found: DiscoveredHost): DiscoveredReceiver? {
        val ports = linkedSetOf(found.port, 80, 443, 8080).filter { it in 1..65535 }
        for (port in ports) {
            for (https in listOf(false, true)) {
                if (port == 80 && https) continue
                if (port == 443 && !https) continue
                val config = ReceiverConfig(host = found.host, httpPort = port, useHttps = https)
                val caps = runCatching { api.identify(config) }.getOrNull() ?: continue
                EnigmaLog.connection("autodetect: OpenWebif at ${found.host}:$port https=$https model=${caps.model}")
                val image = listOf(caps.imageDistro, caps.imageVersion).filter { it.isNotBlank() }.joinToString(" ")
                return DiscoveredReceiver(
                    host = found.host,
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
