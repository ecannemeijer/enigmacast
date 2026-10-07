package com.enigmacast.data.discovery

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
