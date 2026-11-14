package com.enigmacast.data.radio

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ResolvedRadioStream(
    val url: String,
    val mimeType: String? = null
)

data class ParsedRadioNowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val raw: String? = null
)

object RadioNowPlayingParser {
    fun parse(
        raw: String?,
        artistHint: String? = null,
        titleHint: String? = null
    ): ParsedRadioNowPlaying {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() && artistHint.isNullOrBlank() && titleHint.isNullOrBlank()) {
            return ParsedRadioNowPlaying()
        }
        if (!artistHint.isNullOrBlank() || !titleHint.isNullOrBlank()) {
            return ParsedRadioNowPlaying(
                title = titleHint?.trim()?.takeIf { it.isNotEmpty() },
                artist = artistHint?.trim()?.takeIf { it.isNotEmpty() },
                raw = text.ifBlank { listOfNotNull(artistHint, titleHint).joinToString(" - ") }
            )
        }
        val separators = listOf(" - ", " – ", " — ", " | ")
        for (separator in separators) {
            val index = text.indexOf(separator)
            if (index > 0) {
                val artist = text.substring(0, index).trim()
                val title = text.substring(index + separator.length).trim()
                if (artist.isNotEmpty() && title.isNotEmpty()) {
                    return ParsedRadioNowPlaying(artist = artist, title = title, raw = text)
                }
            }
        }
        return ParsedRadioNowPlaying(title = text, raw = text)
    }
}

object RadioStreamFormats {
    const val MIME_HLS = "application/x-mpegURL"
    const val MIME_MPEG = "audio/mpeg"
    const val MIME_AAC = "audio/aac"
    const val MIME_OGG = "audio/ogg"
    const val MIME_FLAC = "audio/flac"

    fun isPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.US)
        return path.endsWith(".pls") ||
            path.endsWith(".asx") ||
            path.endsWith(".xspf") ||
            path.endsWith(".wax") ||
            (path.endsWith(".m3u") && !path.endsWith(".m3u8"))
    }

    fun isHlsUrl(url: String, codec: String? = null, hls: Boolean = false): Boolean {
        if (hls) return true
        val codecNorm = codec.orEmpty().uppercase(Locale.US)
        if (codecNorm.contains("HLS")) return true
        val path = url.substringBefore('?').lowercase(Locale.US)
        return path.endsWith(".m3u8") || path.contains(".m3u8")
    }

    fun mimeFromCodec(codec: String?, url: String, hls: Boolean = false): String? {
        if (isHlsUrl(url, codec, hls)) return MIME_HLS
        return when (codec.orEmpty().uppercase(Locale.US).replace(" ", "")) {
            "MP3", "MP2", "MP1" -> MIME_MPEG
            "AAC", "AAC+", "HEAAC", "HE-AAC", "AACLC" -> MIME_AAC
            "OGG", "VORBIS", "OPUS" -> MIME_OGG
            "FLAC" -> MIME_FLAC
            else -> null
        }
    }

    fun detectFromPreview(bytes: ByteArray, contentType: String?): RadioPreviewKind {
        val type = contentType.orEmpty().lowercase(Locale.US)
        when {
            type.contains("mpegurl") || type.contains("m3u8") -> return RadioPreviewKind.HLS
            type.contains("scpls") || type.contains("x-scpls") || type.contains("pls") ->
                return RadioPreviewKind.PLAYLIST
            type.contains("xspf") || type.contains("asx") || type.contains("x-ms-asf") ->
                return RadioPreviewKind.PLAYLIST
            type.contains("html") -> return RadioPreviewKind.HTML
            type.contains("audio/mpeg") || type.contains("audio/mp3") -> return RadioPreviewKind.AUDIO_MPEG
            type.contains("audio/aac") || type.contains("audio/aacp") || type.contains("audio/mp4") ->
                return RadioPreviewKind.AUDIO_AAC
            type.contains("audio/ogg") || type.contains("application/ogg") -> return RadioPreviewKind.AUDIO_OGG
            type.contains("audio/flac") -> return RadioPreviewKind.AUDIO_FLAC
        }
        if (bytes.isEmpty()) return RadioPreviewKind.UNKNOWN
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return RadioPreviewKind.AUDIO_MPEG
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
            val layer = (bytes[1].toInt() shr 1) and 0x03
            return if (layer == 0) RadioPreviewKind.AUDIO_AAC else RadioPreviewKind.AUDIO_MPEG
        }
        if (bytes.size >= 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()
        ) {
            return RadioPreviewKind.AUDIO_OGG
        }
        val text = peekAsText(bytes)
        val trimmed = text.trimStart()
        when {
            trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                return if (trimmed.contains("#EXT-X-", ignoreCase = true)) {
                    RadioPreviewKind.HLS
                } else {
                    RadioPreviewKind.PLAYLIST
                }
            }
            trimmed.startsWith("[playlist]", ignoreCase = true) -> return RadioPreviewKind.PLAYLIST
            trimmed.contains("<asx", ignoreCase = true) ||
                trimmed.contains("<playlist", ignoreCase = true) ||
                trimmed.contains("<location", ignoreCase = true) -> return RadioPreviewKind.PLAYLIST
            trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) -> return RadioPreviewKind.HTML
        }
        return RadioPreviewKind.UNKNOWN
    }

    fun parsePlaylist(body: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        val fileLine = Regex("""(?im)^File\d+\s*=\s*(.+)$""")
        fileLine.findAll(body).forEach { match ->
            resolvePlaylistEntry(match.groupValues[1].trim(), baseUrl)?.let(urls::add)
        }
        val refHref = Regex("""(?is)<ref\s+[^>]*href\s*=\s*["']([^"']+)["']""")
        refHref.findAll(body).forEach { match ->
            resolvePlaylistEntry(match.groupValues[1].trim(), baseUrl)?.let(urls::add)
        }
        val location = Regex("""(?is)<location>\s*([^<]+)\s*</location>""")
        location.findAll(body).forEach { match ->
            resolvePlaylistEntry(match.groupValues[1].trim(), baseUrl)?.let(urls::add)
        }
        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) return@forEach
            if (line.contains('=') && line.substringBefore('=').startsWith("File", ignoreCase = true)) {
                return@forEach
            }
            resolvePlaylistEntry(line.substringBefore(' ').ifBlank { line }, baseUrl)?.let(urls::add)
        }
        return urls.toList()
    }

    fun streamTheWorldHlsCandidates(url: String): List<String> {
        val normalized = upgradeStreamTheWorldHttps(url)
        val host = hostOf(normalized) ?: return emptyList()
        if (!host.contains("streamtheworld.com", ignoreCase = true)) return emptyList()
        val path = normalized.substringBefore('?')
        if (!path.contains("livestream-redirect", ignoreCase = true)) return emptyList()
        val candidates = linkedSetOf<String>()
        if (path.endsWith(".mp3", ignoreCase = true)) {
            candidates += path.replace(Regex("""\.mp3$""", RegexOption.IGNORE_CASE), ".m3u8")
            val file = path.substringAfterLast('/')
            val station = file.removeSuffix(".mp3").removeSuffix(".MP3")
            if (!station.endsWith("AAC", ignoreCase = true)) {
                candidates += path.replace(file, station + "AAC.m3u8")
            }
        }
        return candidates.filter { it != normalized }
    }

    fun upgradeStreamTheWorldHttps(url: String): String {
        val host = hostOf(url) ?: return url
        if (!host.contains("streamtheworld.com", ignoreCase = true)) return url
        return if (url.startsWith("http://", ignoreCase = true)) {
            "https://" + url.removePrefix("http://").removePrefix("HTTP://")
        } else {
            url
        }
    }

    fun mimeForKind(kind: RadioPreviewKind): String? = when (kind) {
        RadioPreviewKind.HLS -> MIME_HLS
        RadioPreviewKind.AUDIO_MPEG -> MIME_MPEG
        RadioPreviewKind.AUDIO_AAC -> MIME_AAC
        RadioPreviewKind.AUDIO_OGG -> MIME_OGG
        RadioPreviewKind.AUDIO_FLAC -> MIME_FLAC
        else -> null
    }

    private fun resolvePlaylistEntry(raw: String, baseUrl: String): String? {
        val value = raw.trim().trim('"').trim('\'')
        if (value.isEmpty()) return null
        val absolute = when {
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("//") -> {
                val scheme = baseUrl.substringBefore("://", "https")
                "$scheme:$value"
            }
            else -> return null
        }
        return absolute.takeIf { it.contains("://") }
    }

    private fun peekAsText(bytes: ByteArray): String {
        val end = bytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: bytes.size
        return String(bytes, 0, end.coerceAtMost(bytes.size), Charset.forName("UTF-8"))
            .replace("\u0000", "")
    }

    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) return null
        return withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':')
    }
}

enum class RadioPreviewKind {
    HLS,
    PLAYLIST,
    HTML,
    AUDIO_MPEG,
    AUDIO_AAC,
    AUDIO_OGG,
    AUDIO_FLAC,
    UNKNOWN
}

@Singleton
class RadioStreamResolver @Inject constructor(
    @Named("radioStream") private val client: OkHttpClient
) {
    fun resolve(url: String, codec: String? = null, hls: Boolean = false): ResolvedRadioStream {
        return resolve(url = url, codec = codec, hls = hls, depth = 0)
    }

    private fun resolve(url: String, codec: String?, hls: Boolean, depth: Int): ResolvedRadioStream {
        val seed = RadioStreamFormats.upgradeStreamTheWorldHttps(url.trim())
        if (seed.isBlank()) return ResolvedRadioStream(url)
        val inferred = RadioStreamFormats.mimeFromCodec(codec, seed, hls)
        if (depth > MAX_PLAYLIST_DEPTH) return ResolvedRadioStream(seed, inferred)
        if (depth == 0 && !shouldPeek(seed, codec, hls)) {
            return ResolvedRadioStream(seed, inferred)
        }
        val peeked = peek(seed)
        if (peeked != null) {
            val kind = RadioStreamFormats.detectFromPreview(peeked.body, peeked.contentType)
            when (kind) {
                RadioPreviewKind.PLAYLIST -> {
                    val entries = RadioStreamFormats.parsePlaylist(peeked.bodyAsText(), peeked.finalUrl)
                    val next = entries.firstOrNull()
                    if (!next.isNullOrBlank() && !next.equals(seed, ignoreCase = true)) {
                        return resolve(url = next, codec = codec, hls = false, depth = depth + 1)
                    }
                }
                RadioPreviewKind.HLS -> return ResolvedRadioStream(
                    url = peeked.finalUrl.takeIf { RadioStreamFormats.isHlsUrl(it) } ?: seed,
                    mimeType = RadioStreamFormats.MIME_HLS
                )
                RadioPreviewKind.HTML -> {
                    val hlsFallback = RadioStreamFormats.streamTheWorldHlsCandidates(seed)
                        .firstNotNullOfOrNull { candidate ->
                            peek(candidate)?.takeIf {
                                RadioStreamFormats.detectFromPreview(it.body, it.contentType) == RadioPreviewKind.HLS
                            }?.finalUrl
                        }
                    if (!hlsFallback.isNullOrBlank()) {
                        return ResolvedRadioStream(hlsFallback, RadioStreamFormats.MIME_HLS)
                    }
                }
                RadioPreviewKind.AUDIO_MPEG,
                RadioPreviewKind.AUDIO_AAC,
                RadioPreviewKind.AUDIO_OGG,
                RadioPreviewKind.AUDIO_FLAC -> {
                    return ResolvedRadioStream(
                        url = seed,
                        mimeType = RadioStreamFormats.mimeForKind(kind) ?: inferred
                    )
                }
                RadioPreviewKind.UNKNOWN -> {
                    if (inferred != null) {
                        return ResolvedRadioStream(seed, inferred)
                    }
                }
            }
            if (RadioStreamFormats.isHlsUrl(peeked.finalUrl, codec, hls) || RadioStreamFormats.isHlsUrl(seed, codec, hls)) {
                return ResolvedRadioStream(
                    url = if (RadioStreamFormats.isHlsUrl(peeked.finalUrl, codec, hls)) peeked.finalUrl else seed,
                    mimeType = RadioStreamFormats.MIME_HLS
                )
            }
            return ResolvedRadioStream(seed, inferred)
        }
        val hlsCandidate = RadioStreamFormats.streamTheWorldHlsCandidates(seed).firstOrNull()
        if (RadioStreamFormats.isPlaylistUrl(seed)) {
            return ResolvedRadioStream(seed, inferred)
        }
        if (hlsCandidate != null && (inferred == RadioStreamFormats.MIME_HLS || RadioStreamFormats.isHlsUrl(seed, codec, hls))) {
            return ResolvedRadioStream(hlsCandidate, RadioStreamFormats.MIME_HLS)
        }
        return ResolvedRadioStream(seed, inferred)
    }

    private fun shouldPeek(url: String, codec: String?, hls: Boolean): Boolean {
        if (RadioStreamFormats.isPlaylistUrl(url) || RadioStreamFormats.isHlsUrl(url, codec, hls)) return true
        val host = url.substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .lowercase(Locale.US)
        if (host.contains("streamtheworld.com")) return true
        return RadioStreamFormats.mimeFromCodec(codec, url, hls) == null
    }

    private fun peek(url: String): PeekResult? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", RADIO_STREAM_USER_AGENT)
                .header("Accept", "*/*")
                .header("Icy-MetaData", "1")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code !in 200..399) return@use null
                val body = response.body ?: return@use PeekResult(
                    finalUrl = response.request.url.toString(),
                    contentType = response.header("Content-Type"),
                    body = ByteArray(0)
                )
                val stream = body.byteStream()
                val buffer = ByteArray(PEEK_BYTES)
                val read = stream.read(buffer)
                PeekResult(
                    finalUrl = response.request.url.toString(),
                    contentType = response.header("Content-Type") ?: body.contentType()?.toString(),
                    body = if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                )
            }
        }.getOrNull()
    }

    private data class PeekResult(
        val finalUrl: String,
        val contentType: String?,
        val body: ByteArray
    ) {
        fun bodyAsText(): String = String(body, Charsets.UTF_8)
    }

    private companion object {
        const val PEEK_BYTES = 8192
        const val MAX_PLAYLIST_DEPTH = 3
        const val RADIO_STREAM_USER_AGENT = "EnigmaCast/1.0 (Linux; Android) Media3"
    }
}
