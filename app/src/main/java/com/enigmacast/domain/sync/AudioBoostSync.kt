package com.enigmacast.domain.sync

import com.enigmacast.domain.player.AudioBoost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The live TV audio boost per channel on the sync server, entity "audio_boost".
 *
 * It rides on the generic record store (/api/v1/sync/push and /pull) like favorites: the
 * record key is "boost/live/<channel key>" (LibrarySync.keyOf reads it from the payload's
 * source/kind/id), the server keeps the newest change per record, and a device applies a
 * pulled change only when it is newer than its own row (SyncConflict, last write wins).
 * Off is sent as level 0, never as a delete, so switching it off reaches other devices too.
 */
object AudioBoostSync {
    const val ENTITY = "audio_boost"
    private const val SOURCE = "boost"
    private const val KIND = "live"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Change(val channelKey: String, val level: Int)

    fun recordKey(channelKey: String) = LibrarySync.recordKey(SOURCE, KIND, channelKey)

    fun payload(channelKey: String, level: Int, updatedMs: Long): String = buildJsonObject {
        put("source", SOURCE)
        put("kind", KIND)
        put("id", channelKey)
        put("level", AudioBoost.clamp(level))
        put("updatedMs", updatedMs)
    }.toString()

    /** A pulled change; null when it is not a readable boost record. */
    fun parse(recordKey: String, payload: String?): Change? {
        val o = payload?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val fromPayload = (o?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val key = fromPayload ?: LibrarySync.parts(recordKey)?.takeIf { it.first == SOURCE && it.second == KIND }?.third
        if (key.isNullOrBlank()) return null
        val level = (o?.get("level") as? JsonPrimitive)?.intOrNull ?: 0
        return Change(key, AudioBoost.clamp(level))
    }
}
