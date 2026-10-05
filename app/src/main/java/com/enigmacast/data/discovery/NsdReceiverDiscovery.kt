package com.enigmacast.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class NsdReceiverDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun discoverHosts(timeoutMs: Long = 4_000): List<String> = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(NsdManager::class.java) ?: return@withContext emptyList()
        val found = linkedSetOf<String>()
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                resolved.host?.hostAddress?.let { found += it }
                            }
                        })
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                }
                try {
                    manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.stopServiceDiscovery(listener) }
                }
            }
        }
        found.toList()
    }
}
