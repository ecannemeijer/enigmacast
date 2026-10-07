package com.enigmacast.domain.service

import java.net.URLDecoder

/**
 * Separates normal Enigma2 (DVB) services from internet / IPTV entries in a bouquet.
 *
 * Enigma2 writes IPTV as a service reference whose path (everything after the tenth `:`) is an
 * HTTP(S) URL, usually percent-encoded (`http%3a//…`). Common player types are 4097 (GStreamer),
 * 5001 / 5002 (ServiceApp / exteplayer3) and 8193; a plain DVB type (`1:…`) can also carry a URL
 * when an IPTV plugin rewrote it. Those streams come from the internet, not from the receiver's
 * stream ports (8001 / 8002), so the app must open the URL directly and must not zap, check
 * tuners or ask for a transcoded stream.
 */
object ServiceStreams {
    /** Service-type field values that Enigma2 uses for stream players (not DVB tuners). */
    private val STREAM_PLAYER_TYPES = setOf("4097", "5001", "5002", "8193")

    /** True when [reference] embeds an HTTP(S) stream URL. */
    fun isInternet(reference: String): Boolean = internetUrl(reference) != null

    /**
     * The direct stream URL embedded in [reference], or null when this is a normal Enigma2
     * service. Credentials in the URL (`user:pass@host`) are kept; Enigma2 header fragments
     * (`#User-Agent=…`) are dropped so ExoPlayer gets a clean http(s) address.
     */
    fun internetUrl(reference: String): String? {
        val path = pathOf(reference)?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodePath(path)
        return httpUrlIn(decoded) ?: httpUrlIn(path)
    }

    /** True when the service type is an IPTV player, even if the URL could not be read. */
    fun isStreamPlayer(reference: String): Boolean =
        reference.trim().substringBefore(':') in STREAM_PLAYER_TYPES

    /** Everything after the ten Enigma2 identity fields (`type:flags:…:unused`). */
    fun pathOf(reference: String): String? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size <= 10) return null
        return parts.drop(10).joinToString(":")
    }

    private fun decodePath(path: String): String {
        var current = path.trim()
        // OpenWebif and bouquet files often encode `:` as `%3a` (and sometimes double-encode).
        repeat(2) {
            val next = runCatching { URLDecoder.decode(current, Charsets.UTF_8.name()) }.getOrDefault(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun httpUrlIn(text: String): String? {
        val lower = text.lowercase()
        val start = listOf("https://", "http://")
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        var url = text.substring(start).trim()
        val hash = url.indexOf('#')
        if (hash >= 0 && url.indexOf('=', hash) >= 0) {
            // Enigma2 appends player headers as `#User-Agent=…&…`; ExoPlayer must not see them.
            url = url.substring(0, hash)
        }
        val end = url.indexOfFirst { it.isWhitespace() || it == '"' || it == '\'' }
        if (end >= 0) url = url.substring(0, end)
        url = url.trimEnd(':', '\\')
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return url
    }
}
