package com.enigmacast.data.discovery

/**
 * A host on the LAN that answered as OpenWebif / Enigma2 during autodetection.
 * [label] is what the D-pad list shows (model when known, else the address).
 */
data class DiscoveredReceiver(
    val host: String,
    val httpPort: Int,
    val useHttps: Boolean = false,
    val model: String = "",
    val image: String = "",
) {
    val label: String
        get() {
            val address = if (httpPort == 80 && !useHttps || httpPort == 443 && useHttps) host else "$host:$httpPort"
            return if (model.isNotBlank()) "$model ($address)" else address
        }
}
