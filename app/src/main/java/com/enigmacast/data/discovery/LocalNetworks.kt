package com.enigmacast.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV's IPv4 addresses with prefix, from two sources: ConnectivityManager (all networks,
 * VPNs marked) and `java.net.NetworkInterface` (works on every Android TV, also when the
 * active network is a VPN or ConnectivityManager reports only IPv6). [NetworkChoice] picks
 * the LAN from these.
 */
@Singleton
class LocalNetworks @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun candidates(): List<LocalNetwork> {
        val out = mutableListOf<LocalNetwork>()
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            @Suppress("DEPRECATION")
            manager.allNetworks.forEach { network ->
                val vpn = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val properties = manager.getLinkProperties(network) ?: return@forEach
                properties.linkAddresses.forEach { link ->
                    val address = link.address
                    if (address is Inet4Address) {
                        out += LocalNetwork(address.hostAddress.orEmpty(), link.prefixLength, properties.interfaceName.orEmpty(), "connectivity", vpn)
                    }
                }
            }
        }.onFailure { EnigmaLog.discoveryError("ConnectivityManager: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .forEach { nic ->
                    nic.interfaceAddresses.forEach { entry ->
                        val address = entry.address
                        if (address is Inet4Address) {
                            out += LocalNetwork(address.hostAddress.orEmpty(), entry.networkPrefixLength.toInt(), nic.name, "interface", nic.isPointToPoint)
                        }
                    }
                }
        }.onFailure { EnigmaLog.discoveryError("NetworkInterface: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
        return out
    }
}
