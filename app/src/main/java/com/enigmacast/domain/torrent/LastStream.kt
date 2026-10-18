package com.enigmacast.domain.torrent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The stream last played for a title or episode, so a resume can start it again without the
 * scraper. Only torrents (info hash + file index + public trackers): direct links can carry a
 * debrid key and are never stored.
 */
object LastStream {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_TRACKERS = 20

    /** Preference key of a title (movie) or episode: the Continue watching row's kind and id. */
    fun key(kind: String, id: String) = "$kind:$id"

    fun encode(s: TorrentStream): String? {
        val hash = s.infoHash?.takeIf { it.isNotBlank() } ?: return null
        if (s.url != null) return null
        return buildJsonObject {
            put("infoHash", hash)
            s.fileIdx?.let { put("fileIdx", it) }
            put("name", s.name)
            put("title", s.title)
            s.filename?.let { put("filename", it) }
            s.sizeBytes?.let { put("size", it) }
            put("resolution", s.resolution)
            putJsonArray("trackers") { s.trackers.take(MAX_TRACKERS).forEach { add(JsonPrimitive(it)) } }
        }.toString()
    }

    fun decode(text: String?): TorrentStream? {
        val o = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val hash = s("infoHash")?.takeIf { it.isNotBlank() } ?: return null
        return TorrentStream(
            name = s("name").orEmpty(),
            title = s("title").orEmpty(),
            infoHash = hash,
            fileIdx = (o["fileIdx"] as? JsonPrimitive)?.intOrNull,
            url = null,
            trackers = (o["trackers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            seeders = null,
            sizeBytes = (o["size"] as? JsonPrimitive)?.longOrNull,
            resolution = (o["resolution"] as? JsonPrimitive)?.intOrNull ?: 0,
            filename = s("filename"),
        )
    }
}
