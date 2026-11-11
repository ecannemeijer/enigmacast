package com.enigmacast.domain.xtream

/**
 * HTTP headers for IPTV playlist fetches and playback.
 *
 * Order: the app default user-agent, then the provider's user-agent and headers, then
 * per-stream `#EXTVLCOPT` / `#KODIPROP` values (stream wins when both set the same name).
 */
data class IptvRequestHeaders(
    val userAgent: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun applyTo(builder: okhttp3.Request.Builder): okhttp3.Request.Builder {
        builder.header("User-Agent", userAgent)
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && !name.equals("User-Agent", true)) builder.header(name, value)
        }
        return builder
    }

    fun asMap(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && !name.equals("User-Agent", true)) put(name, value)
        }
    }
}

object IptvHeaders {
    const val DEFAULT_USER_AGENT = "EnigmaCast"

    fun resolve(
        defaultUserAgent: String = DEFAULT_USER_AGENT,
        providerUserAgent: String = "",
        providerHeaders: Map<String, String> = emptyMap(),
        streamUserAgent: String = "",
        streamHeaders: Map<String, String> = emptyMap(),
    ): IptvRequestHeaders {
        val userAgent = streamUserAgent.trim().ifBlank { providerUserAgent.trim().ifBlank { defaultUserAgent } }
        val merged = LinkedHashMap<String, String>()
        fun putAll(source: Map<String, String>) {
            source.forEach { (rawName, rawValue) ->
                val name = rawName.trim()
                val value = rawValue.trim()
                if (name.isBlank() || value.isBlank()) return@forEach
                if (name.equals("User-Agent", true)) return@forEach
                val existing = merged.keys.firstOrNull { it.equals(name, true) }
                if (existing != null) merged.remove(existing)
                merged[canonical(name)] = value
            }
        }
        putAll(providerHeaders)
        putAll(streamHeaders)
        return IptvRequestHeaders(userAgent, merged)
    }

    fun canonical(name: String): String = when {
        name.equals("referer", true) || name.equals("referrer", true) || name.equals("http-referrer", true) || name.equals("http-referer", true) -> "Referer"
        name.equals("origin", true) -> "Origin"
        name.equals("user-agent", true) || name.equals("http-user-agent", true) -> "User-Agent"
        else -> name
    }

    fun parseLines(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val split = line.indexOf(':')
            if (split <= 0) return@forEach
            val name = canonical(line.substring(0, split).trim())
            val value = line.substring(split + 1).trim()
            if (name.isNotBlank() && value.isNotBlank()) out[name] = value
        }
        return out
    }

    fun formatLines(headers: Map<String, String>): String =
        headers.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("\n") { "${it.key}: ${it.value}" }

    fun userAgentOf(headers: Map<String, String>): String =
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()

    fun withoutUserAgent(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals("User-Agent", true) }

    fun parseKodiStreamHeaders(value: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        value.split('&').forEach { part ->
            val split = part.indexOf('=')
            if (split <= 0) return@forEach
            val name = canonical(part.substring(0, split).trim())
            val raw = part.substring(split + 1).trim()
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            if (name.isNotBlank() && decoded.isNotBlank()) out[name] = decoded
        }
        return out
    }

    fun fromPlaylistOptions(options: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        options.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase()
            val value = rawValue.trim()
            if (value.isBlank()) return@forEach
            when {
                key == "http-user-agent" || key == "user-agent" -> out["User-Agent"] = value
                key == "http-referrer" || key == "http-referer" || key == "referrer" || key == "referer" -> out["Referer"] = value
                key == "http-origin" || key == "origin" -> out["Origin"] = value
                key.endsWith("stream_headers") || key.endsWith("manifest_headers") -> out += parseKodiStreamHeaders(value)
                key.startsWith("http-") -> out[canonical(key.removePrefix("http-"))] = value
                else -> Unit
            }
        }
        return out
    }
}
