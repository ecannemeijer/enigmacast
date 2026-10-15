package com.enigmacast.data.enigmasync

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds an EnigmaCast sync server on the home network by probing port 8740. */
@Singleton
class SyncDiscovery @Inject constructor(
    private val client: EnigmaSyncClient,
) {
    suspend fun find(preferred: String?): String? = withContext(Dispatchers.IO) {
        val hinted = preferred?.trim().orEmpty()
        if (hinted.isNotEmpty() && isSyncServer(hinted)) return@withContext hinted
        val prefix = localPrefix() ?: return@withContext null
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(24)
        try {
            val tasks = (1..254).map { host ->
                pool.submit {
                    if (found.get() != null) return@submit
                    val address = "$prefix.$host"
                    if (portOpen(address) && isSyncServer(address)) found.compareAndSet(null, address)
                }
            }
            tasks.forEach { runCatching { it.get(8, TimeUnit.SECONDS) } }
            found.get()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isSyncServer(host: String): Boolean =
        runCatching { client.capturePin(host, connectSeconds = 2, readSeconds = 2) }.isSuccess

    private fun portOpen(host: String): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, 8740), 200) }
    }.isSuccess

    private fun localPrefix(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    val parts = address.hostAddress?.split('.').orEmpty()
                    if (parts.size == 4) return parts.take(3).joinToString(".")
                }
            }
        }
        return null
    }
}
