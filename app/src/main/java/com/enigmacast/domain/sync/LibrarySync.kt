package com.enigmacast.domain.sync

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.db.SyncOutboxRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Favorites ("favorite") and resume positions ("progress") on the sync server, for IPTV films,
 * series and episodes and for Torrent titles (source "torrent").
 *
 * The record key is "<source>/<kind>/<id>". Payloads only grow: older apps read the fields
 * they know and ignore the rest, and a field missing in an older app's payload keeps the
 * value this device already has. Deletes arrive with an empty payload, so the key comes
 * from the record key.
 */
object LibrarySync {
    const val FAVORITE = "favorite"
    const val PROGRESS = "progress"
    /** Most changes sent in one push request. */
    const val PUSH_CHUNK = 100

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun recordKey(source: String, kind: String, id: String) = "$source/$kind/$id"

    /** "<source>/<kind>/<id>" → its parts; the id may itself contain "/". */
    fun parts(recordKey: String): Triple<String, String, String>? {
        val p = recordKey.split('/', limit = 3)
        if (p.size != 3 || p.any { it.isBlank() }) return null
        return Triple(p[0], p[1], p[2])
    }

    fun favoritePayload(row: IptvLibraryRow): String = buildJsonObject {
        put("source", row.source); put("kind", row.kind); put("id", row.id)
        put("title", row.title)
        put("addedMs", row.addedMs)
        // Since this version (older apps sent only the fields above).
        put("poster", row.poster.orEmpty())
        put("backdrop", row.backdrop.orEmpty())
        row.year?.let { put("year", it) }
        put("categoryId", row.categoryId)
        put("extension", row.extension)
        put("url", row.url)
    }.toString()

    fun progressPayload(row: IptvResumeRow): String = buildJsonObject {
        put("source", row.source); put("kind", row.kind); put("id", row.id)
        put("positionMs", row.positionMs); put("durationMs", row.durationMs); put("updatedMs", row.updatedMs)
        put("title", row.title); put("poster", row.poster.orEmpty())
        put("extension", row.extension); put("url", row.url); put("categoryId", row.categoryId)
        put("seriesId", row.seriesId.orEmpty()); put("seriesTitle", row.seriesTitle.orEmpty())
        put("season", row.season); put("episode", row.episode)
    }.toString()

    fun deletePayload(source: String, kind: String, id: String): String = buildJsonObject {
        put("source", source); put("kind", kind); put("id", id)
    }.toString()

    private fun obj(text: String?): JsonObject? =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    /** The record key of an outbox payload (blank when it has none). */
    fun keyOf(payload: String): String {
        val o = obj(payload) ?: return ""
        return listOf(o.str("source"), o.str("kind"), o.str("id")).mapNotNull { it?.takeIf(String::isNotBlank) }
            .takeIf { it.size == 3 }?.joinToString("/").orEmpty()
    }

    /** source/kind/id of a pulled change: from its payload, else (deletes) its record key. */
    fun keyOfChange(recordKey: String, payload: String?): Triple<String, String, String>? {
        val o = obj(payload)
        val source = o?.str("source").orEmpty()
        val kind = o?.str("kind").orEmpty()
        val id = o?.str("id").orEmpty()
        if (source.isNotBlank() && kind.isNotBlank() && id.isNotBlank()) return Triple(source, kind, id)
        return parts(recordKey)
    }

    /** A pulled favorite upsert merged over what this device has. */
    fun favoriteFrom(
        profileId: String,
        key: Triple<String, String, String>,
        payload: String?,
        updatedMs: Long,
        current: IptvLibraryRow?,
    ): IptvLibraryRow {
        val o = obj(payload) ?: JsonObject(emptyMap())
        fun text(k: String, old: String?) = o.str(k)?.takeIf { it.isNotBlank() } ?: old
        return IptvLibraryRow(
            source = key.first, kind = key.second, id = key.third,
            title = text("title", current?.title).orEmpty(),
            poster = text("poster", current?.poster),
            backdrop = text("backdrop", current?.backdrop),
            year = o.int("year") ?: current?.year,
            categoryId = text("categoryId", current?.categoryId).orEmpty(),
            extension = text("extension", current?.extension).orEmpty(),
            url = text("url", current?.url).orEmpty(),
            addedMs = o.long("addedMs") ?: updatedMs,
            profileId = profileId,
        )
    }

    /** A pulled resume position merged over what this device has. */
    fun progressFrom(
        profileId: String,
        key: Triple<String, String, String>,
        payload: String?,
        updatedMs: Long,
        current: IptvResumeRow?,
    ): IptvResumeRow {
        val o = obj(payload) ?: JsonObject(emptyMap())
        fun text(k: String, old: String?) = o.str(k)?.takeIf { it.isNotBlank() } ?: old
        return IptvResumeRow(
            source = key.first, kind = key.second, id = key.third,
            positionMs = o.long("positionMs") ?: 0L,
            durationMs = o.long("durationMs") ?: 0L,
            updatedMs = updatedMs,
            title = text("title", current?.title).orEmpty(),
            poster = text("poster", current?.poster),
            extension = text("extension", current?.extension).orEmpty(),
            url = text("url", current?.url).orEmpty(),
            categoryId = text("categoryId", current?.categoryId).orEmpty(),
            seriesId = text("seriesId", current?.seriesId),
            seriesTitle = text("seriesTitle", current?.seriesTitle),
            season = o.int("season") ?: current?.season ?: 0,
            episode = o.int("episode") ?: current?.episode ?: 0,
            profileId = profileId,
        )
    }

    /** What one push sends: the newest change per record, and the outbox rows it settles. */
    data class Batch(val send: List<SyncOutboxRow>, val settled: List<String>)

    /**
     * Resume positions are saved every few seconds; only the newest change of each record is
     * sent. Rows with an unreadable payload are settled without sending, so one bad row can
     * no longer block every later change.
     */
    fun batch(pending: List<SyncOutboxRow>): Batch {
        val readable = pending.filter { obj(it.payload) != null }
        val newest = readable
            .groupBy { Triple(it.profileId, it.entity, keyOf(it.payload).ifBlank { it.changeId }) }
            .values.map { rows -> rows.maxWith(compareBy<SyncOutboxRow>({ it.updatedMs }, { it.changeId })) }
            .sortedWith(compareBy({ it.updatedMs }, { it.changeId }))
        return Batch(newest, pending.map { it.changeId })
    }

    /** The push body, with proper JSON escaping (control characters in titles included). */
    fun pushBody(rows: List<SyncOutboxRow>): String = buildJsonObject {
        put("changes", buildJsonArray {
            rows.forEach { row ->
                val payload: JsonElement = obj(row.payload) ?: JsonObject(emptyMap())
                add(buildJsonObject {
                    put("profileId", row.profileId)
                    put("entity", row.entity)
                    put("key", keyOf(row.payload).ifBlank { row.changeId })
                    put("op", row.op)
                    put("payload", payload)
                    put("updatedMs", row.updatedMs)
                    put("changeId", row.changeId)
                })
            }
        })
    }.toString()

    /**
     * Favorites and positions that were saved before this device synced them (older versions,
     * or before the receiver was paired): sent once per profile and server, with their own
     * times, so newer changes on the server still win.
     */
    fun backfill(
        profileId: String,
        favorites: List<IptvLibraryRow>,
        resumes: List<IptvResumeRow>,
        changeId: (String) -> String,
    ): List<SyncOutboxRow> =
        favorites.filter { it.profileId == profileId }.map { row ->
            SyncOutboxRow(changeId(recordKey(row.source, row.kind, row.id)), profileId, FAVORITE, "upsert", favoritePayload(row), row.addedMs, "")
        } + resumes.filter { it.profileId == profileId && it.title.isNotBlank() }.map { row ->
            SyncOutboxRow(changeId(recordKey(row.source, row.kind, row.id)), profileId, PROGRESS, "upsert", progressPayload(row), row.updatedMs, "")
        }
}
