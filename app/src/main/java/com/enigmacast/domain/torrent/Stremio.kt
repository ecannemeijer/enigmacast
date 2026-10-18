package com.enigmacast.domain.torrent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A Stremio addon (scraper) as configured: the manifest URL and the base its resources hang off. */
data class ScraperUrl(val manifest: String, val base: String)

data class ScraperManifest(val id: String, val name: String, val version: String, val types: List<String>)

enum class ManifestError { URL, HTTP, JSON, MISSING_FIELDS, NO_STREAM, NO_TYPES }

object StremioManifest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Normalises a pasted address: stremio:// becomes https://, whitespace is trimmed, and
     * "/manifest.json" is added when missing. Null when it is not an http(s) address.
     */
    fun normalize(input: String): ScraperUrl? {
        var url = input.trim()
        if (url.isEmpty()) return null
        if (url.startsWith("stremio://", ignoreCase = true)) url = "https://" + url.substring("stremio://".length)
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        url = url.substringBefore('#').trimEnd('/')
        if (!url.endsWith("/manifest.json")) url = "$url/manifest.json"
        return ScraperUrl(url, url.removeSuffix("/manifest.json"))
    }

    /**
     * For the screen and logs: scheme and host only, the configuration path (which can hold a
     * debrid key) is masked: "https://torrentio.strem.fun/•••/manifest.json".
     */
    fun mask(url: String): String {
        val scheme = url.substringBefore("://", "")
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/')
        val path = rest.substringAfter('/', "").removeSuffix("manifest.json").trim('/')
        return buildString {
            if (scheme.isNotEmpty()) append(scheme).append("://")
            append(host)
            if (path.isNotEmpty()) append("/•••")
            append("/manifest.json")
        }
    }

    /**
     * The address as OkHttp sends it: characters not allowed in a path ('|' in Torrentio's
     * "sort=seeders|qualityfilter=…" config, spaces, …) percent-encoded; ',' '=' ':' stay.
     */
    fun httpUrl(address: String): okhttp3.HttpUrl = address.toHttpUrl()

    fun parse(body: String): Result<ScraperManifest> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Result.failure(ManifestException(ManifestError.JSON))
        fun str(k: String) = (root[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val id = str("id"); val name = str("name"); val version = str("version")
        if (id.isEmpty() || name.isEmpty() || version.isEmpty()) return Result.failure(ManifestException(ManifestError.MISSING_FIELDS))
        val resources = (root["resources"] as? JsonArray).orEmpty().mapNotNull { r ->
            when (r) {
                is JsonPrimitive -> r.contentOrNull
                is JsonObject -> (r["name"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }
        if ("stream" !in resources) return Result.failure(ManifestException(ManifestError.NO_STREAM))
        val types = (root["types"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (types.none { it == "movie" || it == "series" }) return Result.failure(ManifestException(ManifestError.NO_TYPES))
        return Result.success(ScraperManifest(id, name, version, types))
    }
}

class ManifestException(val error: ManifestError) : Exception(error.name)

/** One playable stream offered by the addon. */
data class TorrentStream(
    val name: String,
    val title: String,
    val infoHash: String?,
    val fileIdx: Int?,
    val url: String?,
    val trackers: List<String>,
    val seeders: Int?,
    val sizeBytes: Long?,
    val resolution: Int,
    val filename: String?,
) {
    val isTorrent get() = infoHash != null
}

object StremioStreams {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val imdbId = Regex("^tt\\d{5,10}$")

    /** [base]/stream/movie/tt123.json or [base]/stream/series/tt123:1:2.json; null for a bad IMDb id. */
    fun url(base: String, imdb: String, season: Int? = null, episode: Int? = null): String? {
        if (!imdbId.matches(imdb)) return null
        val b = base.trimEnd('/')
        return if (season != null && episode != null) "$b/stream/series/$imdb:$season:$episode.json" else "$b/stream/movie/$imdb.json"
    }

    private val seedRe = Regex("👤\\s*(\\d+)")
    private val sizeRe = Regex("💾\\s*([\\d.,]+)\\s*(TB|GB|MB|KB)", RegexOption.IGNORE_CASE)
    private val resRe = Regex("(?i)\\b(2160p|4k|uhd|1440p|1080p|720p|576p|480p|360p)\\b")

    fun resolutionOf(text: String): Int = when (resRe.find(text)?.value?.lowercase()) {
        "2160p", "4k", "uhd" -> 2160
        "1440p" -> 1440
        "1080p" -> 1080
        "720p" -> 720
        "576p" -> 576
        "480p" -> 480
        "360p" -> 360
        else -> 0
    }

    fun seedersOf(text: String): Int? = seedRe.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun sizeOf(text: String): Long? {
        val m = sizeRe.find(text) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].uppercase()) { "TB" -> 1L shl 40; "GB" -> 1L shl 30; "MB" -> 1L shl 20; else -> 1L shl 10 }
        return (n * unit).toLong()
    }

    fun parse(body: String): List<TorrentStream> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return (root["streams"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val hash = s("infoHash")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            val url = s("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (hash == null && url == null) return@mapNotNull null
            val name = s("name").orEmpty()
            val title = s("title") ?: s("description") ?: ""
            val text = "$name\n$title"
            val hints = o["behaviorHints"] as? JsonObject
            TorrentStream(
                name = name,
                title = title,
                infoHash = hash,
                fileIdx = (o["fileIdx"] as? JsonPrimitive)?.intOrNull,
                url = if (hash == null) url else null,
                trackers = (o["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
                seeders = seedersOf(title),
                sizeBytes = sizeOf(title),
                resolution = resolutionOf(text),
                filename = (hints?.get("filename") as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /** Best first: higher resolution, then more seeders (direct links count as well seeded). */
    fun sorted(streams: List<TorrentStream>): List<TorrentStream> =
        streams.sortedWith(compareByDescending<TorrentStream> { it.resolution }.thenByDescending { it.seeders ?: if (it.url != null) Int.MAX_VALUE else -1 })

    /** Well-known public trackers, added after the addon's own (more peers sooner). */
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
    )

    /** magnet:?xt=urn:btih:<hash>&tr=… with the addon's trackers, then the public ones. */
    fun magnet(stream: TorrentStream): String? {
        val hash = stream.infoHash ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            (stream.trackers + PUBLIC_TRACKERS).distinct().take(40).forEach { append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
    }

    /** First line of a Torrentio title (the release name), for the list. */
    fun release(stream: TorrentStream): String = stream.title.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: stream.name
}

/** An HTTP Range request resolved against a file of [size] bytes: [start]..[end] inclusive. */
data class ByteRange(val start: Long, val end: Long) {
    val length get() = end - start + 1
}

object HttpRange {
    /**
     * "bytes=0-", "bytes=100-199", "bytes=-500" (last 500). Null when there is no usable
     * header (send the whole file); [UNSATISFIABLE] when the start is past the end.
     */
    fun parse(header: String?, size: Long): ByteRange? {
        if (header.isNullOrBlank() || size <= 0) return null
        val spec = header.trim()
        if (!spec.startsWith("bytes=", ignoreCase = true)) return null
        val first = spec.substring(6).split(',').first().trim()
        val dash = first.indexOf('-')
        if (dash < 0) return null
        val a = first.substring(0, dash).trim()
        val b = first.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                if (n <= 0) UNSATISFIABLE else ByteRange((size - n).coerceAtLeast(0), size - 1)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start >= size) return UNSATISFIABLE
                val end = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                if (end < start) UNSATISFIABLE else ByteRange(start, end)
            }
        }
    }

    val UNSATISFIABLE = ByteRange(-1, -2)
}

/** Which pieces a file covers, and which must be there before playback starts. */
object PieceMath {
    fun pieceAt(fileOffset: Long, pos: Long, pieceLength: Int): Int = ((fileOffset + pos) / pieceLength).toInt()

    fun range(fileOffset: Long, fileSize: Long, pieceLength: Int): IntRange =
        pieceAt(fileOffset, 0, pieceLength)..pieceAt(fileOffset, (fileSize - 1).coerceAtLeast(0), pieceLength)

    /** First ~[headBytes] of the file plus the last [tailPieces] pieces (mp4 'moov' often sits at the end). */
    fun startupPieces(fileOffset: Long, fileSize: Long, pieceLength: Int, headBytes: Long = 8L shl 20, tailPieces: Int = 2): List<Int> {
        val all = range(fileOffset, fileSize, pieceLength)
        val head = all.first..pieceAt(fileOffset, (headBytes - 1).coerceAtMost(fileSize - 1).coerceAtLeast(0), pieceLength)
        val tail = (all.last - tailPieces + 1).coerceAtLeast(all.first)..all.last
        return (head + tail).distinct().sorted()
    }

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "webm", "wmv", "mpg", "mpeg")
}
