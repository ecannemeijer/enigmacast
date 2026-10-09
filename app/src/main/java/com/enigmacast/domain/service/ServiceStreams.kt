package com.enigmacast.domain.service

/**
 * Separates normal Enigma2 (DVB) services from internet / IPTV entries in a bouquet.
 *
 * An Enigma2 IPTV reference has ten identity fields, then the stream URL as the 11th field
 * with its own `:` written as `%3a`, then a raw `:` and the channel name:
 *
 * `4097:0:1:0:0:0:0:0:0:0:https%3a//host/live.m3u8:Dance TV - Best of Dance TV`
 *
 * So the reference is split on raw `:` first, and only the 11th field is decoded. Decoding
 * the whole path first would turn `%3a` into `:` and glue the name onto the URL. Common
 * player types are 4097 (GStreamer), 5001 / 5002 (ServiceApp / exteplayer3) and 8193; a DVB
 * type (`1:…`) can also carry a URL. These streams come from the internet, not from the
 * receiver's stream ports, so the app opens the URL itself: no zap, no tuner check, no
 * transcoding.
 */
object ServiceStreams {
    /** Service-type field values that Enigma2 uses for stream players (not DVB tuners). */
    private val STREAM_PLAYER_TYPES = setOf("4097", "5001", "5002", "8193")
    private const val URL_FIELD = 10

    /** True when [reference] embeds an HTTP(S) stream URL. */
    fun isInternet(reference: String): Boolean = internetUrl(reference) != null

    /**
     * The direct stream URL in [reference], or null for a normal Enigma2 service. Credentials
     * (`user:pass@host`) and ports are kept; Enigma2 player options after `#` (`#User-Agent=…`)
     * are dropped so ExoPlayer gets a plain http(s) address.
     */
    fun internetUrl(reference: String): String? {
        val parts = unwrap(reference).split(':')
        if (parts.size <= URL_FIELD) return null
        val field = parts[URL_FIELD].trim()
        val url = when {
            field.isEmpty() -> return null
            // An unencoded URL: its own `:` became separators, so glue scheme, host and port back.
            field.equals("http", true) || field.equals("https", true) -> rawUrl(parts)
            else -> decodeField(restoreMissingColon(field))
        } ?: return null
        return clean(url)
    }

    /** True when the service type is an IPTV player, even if no URL could be read. */
    fun isStreamPlayer(reference: String): Boolean =
        unwrap(reference).substringBefore(':') in STREAM_PLAYER_TYPES

    /**
     * A reference that was percent-encoded as a whole (as in an `sRef=` query parameter) has no
     * raw `:` at all; decode it once so its fields can be split.
     */
    private fun unwrap(reference: String): String {
        val trimmed = reference.trim()
        if (trimmed.contains(':')) return trimmed
        if (!trimmed.contains("%3a", ignoreCase = true)) return trimmed
        return percentDecode(trimmed)
    }

    /**
     * Some lists store the scheme as `https3a//` after the `%` in `%3a` was dropped.
     * Put the colon back so the rest of the decoder sees a normal URL.
     */
    private fun restoreMissingColon(field: String): String = when {
        field.startsWith("https3a//", ignoreCase = true) -> "https://" + field.substring("https3a//".length)
        field.startsWith("http3a//", ignoreCase = true) -> "http://" + field.substring("http3a//".length)
        else -> field
    }

    /** `https%3a//h%3a8080/x` → `https://h:8080/x`, also when double-encoded (`https%253a//…`). */
    private fun decodeField(field: String): String? {
        var current = field
        repeat(MAX_DECODE_ROUNDS) {
            if (hasScheme(current)) return current
            // Enigma2 only escapes `:` in this field (and `%` itself when it was encoded twice), so
            // other escapes such as `%2F` in a query string stay as they are.
            val next = when {
                COLON.containsMatchIn(current) -> current.replace(COLON, ":")
                current.contains("%25") -> current.replace("%25", "%")
                else -> return null
            }
            if (next == current) return null
            current = next
        }
        return current.takeIf(::hasScheme)
    }

    /** `…:http://host:8080/live.ts:Name` split on every `:`: rebuild scheme, host and an optional port. */
    private fun rawUrl(parts: List<String>): String? {
        val rest = parts.getOrNull(URL_FIELD + 1) ?: return null
        if (!rest.startsWith("//")) return null
        var url = parts[URL_FIELD] + ":" + rest
        val next = parts.getOrNull(URL_FIELD + 2)
        if (next != null && PORT_THEN_PATH.matches(next)) url += ":$next"
        return url
    }

    private fun clean(decoded: String): String? {
        var url = decoded.trim()
        val hash = url.indexOf('#')
        if (hash >= 0) url = url.substring(0, hash)
        url = url.trim()
        if (!hasScheme(url)) return null
        val host = url.substringAfter("://").substringBefore('/').substringAfterLast('@')
        if (host.isBlank()) return null
        return url
    }

    private fun hasScheme(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    /** Decodes `%XX` only; unlike URLDecoder it leaves `+` alone, which is literal in URLs. */
    private fun percentDecode(value: String): String {
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    index += 3
                    continue
                }
            }
            out.write(char.toString().toByteArray(Charsets.UTF_8))
            index += 1
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val MAX_DECODE_ROUNDS = 3
    private val PORT_THEN_PATH = Regex("\\d{1,5}(/.*)?")
    private val COLON = Regex("%3a", RegexOption.IGNORE_CASE)
}
