package com.enigmacast.domain.network

import com.enigmacast.domain.model.ReceiverConfig
import kotlinx.serialization.Serializable

/** How the app reaches a receiver: decide automatically, or always the home or remote address. */
@Serializable
enum class AccessMode { AUTO, HOME, REMOTE }

/** The way the receiver is reached right now. */
enum class ReceiverRoute {
    /** The receiver's address in the home network. */
    HOME,

    /** The remote profile: a DynDNS name or public address with forwarded ports. */
    REMOTE,

    /** The home address through the app's WireGuard tunnel. */
    TUNNEL,
}

/** What travels unencrypted over the internet on a route. */
enum class PlainHttpRisk { NONE, STREAM, WEB_AND_STREAM }

object ReceiverRouting {
    fun remoteConfigured(config: ReceiverConfig): Boolean = config.remoteHost.isNotBlank()

    /**
     * Auto uses the home address when it answers, else the tunnel when one is set up,
     * else the remote profile; with nothing else configured it stays home.
     */
    fun choose(mode: AccessMode, homeReachable: Boolean, remoteConfigured: Boolean, tunnelEnabled: Boolean): ReceiverRoute =
        when (mode) {
            AccessMode.HOME -> ReceiverRoute.HOME
            AccessMode.REMOTE -> when {
                tunnelEnabled -> ReceiverRoute.TUNNEL
                remoteConfigured -> ReceiverRoute.REMOTE
                else -> ReceiverRoute.HOME
            }
            AccessMode.AUTO -> when {
                homeReachable -> ReceiverRoute.HOME
                tunnelEnabled -> ReceiverRoute.TUNNEL
                remoteConfigured -> ReceiverRoute.REMOTE
                else -> ReceiverRoute.HOME
            }
        }

    /** Whether choosing a route needs a reachability check of the home address. */
    fun needsProbe(config: ReceiverConfig, tunnelEnabled: Boolean): Boolean =
        config.accessMode == AccessMode.AUTO && (remoteConfigured(config) || tunnelEnabled)

    /**
     * The address and ports to use on [route]. The remote profile replaces host, web port,
     * HTTPS and the stream ports; a port of 0 keeps the home value (or the HTTPS default
     * when the scheme differs). Home and tunnel use the home address unchanged.
     */
    fun effective(config: ReceiverConfig, route: ReceiverRoute): ReceiverConfig {
        if (route != ReceiverRoute.REMOTE || !remoteConfigured(config)) return config
        val httpPort = when {
            config.remoteHttpPort > 0 -> config.remoteHttpPort
            config.remoteUseHttps == config.useHttps -> config.httpPort
            config.remoteUseHttps -> 443
            else -> 80
        }
        return config.copy(
            host = config.remoteHost.trim(),
            httpPort = httpPort,
            useHttps = config.remoteUseHttps,
            streamingPort = config.remoteStreamingPort.takeIf { it > 0 } ?: config.streamingPort,
            transcodingPort = config.remoteTranscodingPort.takeIf { it > 0 } ?: config.transcodingPort,
        )
    }

    /**
     * Enigma2's stream ports only speak HTTP, so a stream over the internet is always
     * readable on the way unless it goes through the tunnel; the web interface (with the
     * password in every request) too when the remote profile does not use HTTPS.
     */
    fun plainHttpRisk(config: ReceiverConfig, route: ReceiverRoute): PlainHttpRisk {
        val internet = when (route) {
            ReceiverRoute.TUNNEL -> false
            ReceiverRoute.REMOTE -> true
            ReceiverRoute.HOME -> !isLocalAddress(config.host)
        }
        if (!internet) return PlainHttpRisk.NONE
        val webHttps = if (route == ReceiverRoute.REMOTE) config.remoteUseHttps else config.useHttps
        return if (webHttps) PlainHttpRisk.STREAM else PlainHttpRisk.WEB_AND_STREAM
    }

    /**
     * Private, loopback, link-local and carrier-grade NAT (Tailscale and the like) addresses,
     * `.local`/`.lan`/`.home.arpa` names and single-label host names count as local.
     */
    fun isLocalAddress(host: String): Boolean {
        val value = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (value.isEmpty()) return true
        if (value == "localhost") return true
        if (value.contains(':')) return value == "::1" || value.startsWith("fe80:") || value.startsWith("fc") || value.startsWith("fd")
        val parts = value.split('.')
        val octets = parts.map { it.toIntOrNull() }
        if (parts.size == 4 && octets.all { it != null && it in 0..255 }) {
            val (a, b) = octets[0]!! to octets[1]!!
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (!value.contains('.')) return true
        return value.endsWith(".local") || value.endsWith(".lan") || value.endsWith(".home.arpa") || value.endsWith(".fritz.box")
    }
}
