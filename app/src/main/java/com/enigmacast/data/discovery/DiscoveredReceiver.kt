package com.enigmacast.data.discovery

import com.enigmacast.domain.model.ReceiverConfig

/** An Enigma2 receiver that answered `/api/about` during Autodetect. */
data class DiscoveredReceiver(
    val host: String,
    val httpPort: Int,
    val useHttps: Boolean = false,
    val model: String = "",
    val image: String = "",
    val loginRequired: Boolean = false,
) {
    /** `192.168.1.209:80`: always with the port, as shown in the result list. */
    val address: String get() = "$host:$httpPort"

    /** `SF8008 4K (192.168.1.209:80)`, or just the address when the model is unknown. */
    val label: String get() = if (model.isNotBlank()) "$model ($address)" else address
}

/** Drops boxes already stored, matched on the host name or IP, so Save cannot add them again. */
fun List<DiscoveredReceiver>.withoutSaved(saved: List<ReceiverConfig>): List<DiscoveredReceiver> {
    val hosts = saved.map { it.host.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (hosts.isEmpty()) return this
    return filter { it.host.trim().lowercase() !in hosts }
}
