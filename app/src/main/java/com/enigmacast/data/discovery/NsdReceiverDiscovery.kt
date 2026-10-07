package com.enigmacast.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** One host before it is checked with `/api/about`: from mDNS, the scan or the form. */
data class DiscoveredHost(val host: String, val port: Int)

/**
 * Browses the LAN for HTTP services (mDNS / NSD). Many Enigma2 images do not advertise
 * OpenWebif, so the subnet scan does most of the work; this adds receivers on other ports.
 * A Wi-Fi multicast lock is held while browsing (Wi-Fi drivers drop mDNS without it), and
 * services are resolved one at a time (older Android fails parallel resolves).
 */
@Singleton
class NsdReceiverDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun discoverHosts(browseMs: Long = 3_000): List<DiscoveredHost> {
        val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val lock = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("EnigmaCast-autodetect")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            val services = browse(manager, browseMs)
            EnigmaLog.discovery("mDNS: ${services.size} _http._tcp service(s)${if (lock == null) " (no Wi-Fi multicast lock)" else ""}")
            return services.take(MAX_RESOLVES).mapNotNull { resolve(manager, it) }.distinct()
        } finally {
            runCatching { lock?.release() }
        }
    }

    private suspend fun browse(manager: NsdManager, browseMs: Long): List<NsdServiceInfo> = withContext(Dispatchers.Main) {
        val found = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                EnigmaLog.discoveryError("mDNS start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found += serviceInfo
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        val started = runCatching { manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { EnigmaLog.discoveryError("mDNS: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
            .isSuccess
        if (!started) return@withContext emptyList()
        try {
            delay(browseMs)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        synchronized(found) { found.distinctBy { it.serviceName }.toList() }
    }

    private suspend fun resolve(manager: NsdManager, service: NsdServiceInfo): DiscoveredHost? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            runCatching {
                @Suppress("DEPRECATION")
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val address = resolved.host?.hostAddress?.takeIf { it.isNotBlank() && !it.contains(':') }
                        val port = resolved.port.takeIf { it in 1..65535 } ?: 80
                        if (continuation.isActive) continuation.resume(address?.let { DiscoveredHost(it, port) })
                    }
                })
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }

    private companion object {
        const val MAX_RESOLVES = 8
        const val RESOLVE_TIMEOUT_MS = 1_500L
    }
}
