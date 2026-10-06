package com.enigmacast.domain.network

/** Something missing from a pasted or imported WireGuard config. */
enum class WireGuardIssue { EMPTY, NO_INTERFACE, NO_PRIVATE_KEY, NO_ADDRESS, NO_PEER, NO_PUBLIC_KEY, NO_ENDPOINT, NO_ALLOWED_IPS }

/**
 * What the connection screen can tell about a WireGuard config without the tunnel library:
 * whether the usual fields are there, where it connects to and which addresses it carries.
 * Keys are never part of the summary.
 */
data class WireGuardSummary(
    val issues: List<WireGuardIssue>,
    val endpoint: String,
    val allowedIps: List<String>,
    val peers: Int,
) {
    val valid: Boolean get() = issues.isEmpty()

    /** AllowedIPs 0.0.0.0/0 or ::/0: everything the app sends goes through the tunnel. */
    val fullTunnel: Boolean get() = allowedIps.any { it == "0.0.0.0/0" || it == "::/0" }
}

object WireGuardText {
    fun summarize(text: String): WireGuardSummary {
        if (text.isBlank()) return WireGuardSummary(listOf(WireGuardIssue.EMPTY), "", emptyList(), 0)
        val sections = sections(text)
        val interfaces = sections.filter { it.first == "interface" }.map { it.second }
        val peers = sections.filter { it.first == "peer" }.map { it.second }
        val issues = buildList {
            if (interfaces.isEmpty()) {
                add(WireGuardIssue.NO_INTERFACE)
            } else {
                if (interfaces.none { it["privatekey"].isNullOrBlank().not() }) add(WireGuardIssue.NO_PRIVATE_KEY)
                if (interfaces.none { it["address"].isNullOrBlank().not() }) add(WireGuardIssue.NO_ADDRESS)
            }
            if (peers.isEmpty()) {
                add(WireGuardIssue.NO_PEER)
            } else {
                if (peers.any { it["publickey"].isNullOrBlank() }) add(WireGuardIssue.NO_PUBLIC_KEY)
                if (peers.none { it["endpoint"].isNullOrBlank().not() }) add(WireGuardIssue.NO_ENDPOINT)
                if (peers.none { it["allowedips"].isNullOrBlank().not() }) add(WireGuardIssue.NO_ALLOWED_IPS)
            }
        }
        val allowed = peers.flatMap { peer -> peer["allowedips"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        val endpoint = peers.firstNotNullOfOrNull { it["endpoint"]?.takeIf { value -> value.isNotBlank() } }.orEmpty()
        return WireGuardSummary(issues, endpoint, allowed.distinct(), peers.size)
    }

    /**
     * Whether [host] (an IPv4 address) falls inside one of [allowedIps]; null when [host] is a
     * name or IPv6, which cannot be judged before the tunnel resolves it.
     */
    fun covers(allowedIps: List<String>, host: String): Boolean? {
        val address = ipv4(host.trim()) ?: return null
        return allowedIps.any { entry ->
            val parts = entry.split('/')
            val network = ipv4(parts[0]) ?: return@any false
            val bits = parts.getOrNull(1)?.toIntOrNull() ?: 32
            if (bits !in 0..32) return@any false
            val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            (address and mask) == (network and mask)
        }
    }

    private fun sections(text: String): List<Pair<String, Map<String, String>>> {
        val result = mutableListOf<Pair<String, MutableMap<String, String>>>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("[") && line.endsWith("]") ->
                    result += line.removePrefix("[").removeSuffix("]").trim().lowercase() to mutableMapOf()
                '=' in line && result.isNotEmpty() -> {
                    val key = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=').trim()
                    val values = result.last().second
                    // Repeated keys (several Address or AllowedIPs lines) add up, as in wg-quick.
                    values[key] = values[key]?.let { "$it, $value" } ?: value
                }
            }
        }
        return result
    }

    private fun ipv4(value: String): Long? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val number = part.toIntOrNull()?.takeIf { it in 0..255 && part.isNotEmpty() && part.length <= 3 } ?: return null
            result = (result shl 8) or number.toLong()
        }
        return result
    }
}
