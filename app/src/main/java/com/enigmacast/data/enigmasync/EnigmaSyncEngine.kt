package com.enigmacast.data.enigmasync

import com.enigmacast.data.db.IptvLibraryDao
import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.db.SyncCursorRow
import com.enigmacast.data.db.SyncOutboxDao
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.domain.sync.SyncApply
import com.enigmacast.domain.sync.SyncConflict
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Pushes the local outbox and pulls newer rows. A missing server leaves Room as it is. */
@Singleton
class EnigmaSyncEngine @Inject constructor(
    private val client: EnigmaSyncClient,
    private val outbox: SyncOutboxDao,
    private val library: IptvLibraryDao,
    private val profiles: ProfileStore,
) {
    suspend fun sync() = withContext(Dispatchers.IO) {
        val config = client.config() ?: return@withContext
        runCatching {
            push(config)
            profiles.state.value.profiles.forEach { profile ->
                val uuid = profile.uuid.ifBlank { return@forEach }
                pull(config, uuid)
            }
        }
    }

    private suspend fun push(config: SyncServerConfig) {
        val pending = outbox.pending()
        if (pending.isEmpty()) return
        val changes = pending.joinToString(",") { row ->
            val payload = runCatching { JSONObject(row.payload) }.getOrElse { JSONObject() }
            val key = listOf(payload.optString("source"), payload.optString("kind"), payload.optString("id")).filter { it.isNotBlank() }.joinToString("/")
            """{"profileId":${json(row.profileId)},"entity":${json(row.entity)},"key":${json(key.ifBlank { row.changeId })},"op":${json(row.op)},"payload":${row.payload},"updatedMs":${row.updatedMs},"changeId":${json(row.changeId)}}"""
        }
        client.request(config, "POST", "/api/v1/sync/push", """{"changes":[$changes]}""".toByteArray())
        pending.forEach { outbox.delete(it.changeId) }
    }

    private suspend fun pull(config: SyncServerConfig, profileId: String) {
        val cursor = outbox.cursor(profileId)?.cursor?.toIntOrNull() ?: 0
        val text = client.request(config, "GET", "/api/v1/sync/pull?profileId=${java.net.URLEncoder.encode(profileId, "UTF-8")}&cursor=$cursor")
        val root = JSONObject(text)
        val changes = root.optJSONArray("changes") ?: return
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            apply(profileId, change)
        }
        outbox.putCursor(SyncCursorRow(profileId, root.optInt("cursor", cursor).toString()))
    }

    private suspend fun apply(profileId: String, change: JSONObject) {
        val entity = change.optString("entity")
        val op = change.optString("op")
        val updated = change.optLong("updated_ms")
        val changeId = change.optString("change_id")
        val payload = runCatching { JSONObject(change.optString("payload")) }.getOrElse { JSONObject() }
        val source = payload.optString("source")
        val kind = payload.optString("kind")
        val id = payload.optString("id")
        if (entity == "favorite") {
            val current = library.favorite(profileId, source, kind, id)
            val decision = SyncConflict.decide(current?.addedMs, null, updated, changeId)
            if (decision == SyncApply.REJECT) return
            if (op == "delete") library.deleteFavorite(profileId, source, kind, id)
            else library.putFavorite(
                IptvLibraryRow(source, kind, id, payload.optString("title"), null, null, null, "", "", "", payload.optLong("addedMs", updated), profileId),
            )
        } else if (entity == "progress") {
            val current = library.resume(profileId, source, kind, id)
            val decision = SyncConflict.decide(current?.updatedMs, null, updated, changeId)
            if (decision == SyncApply.REJECT) return
            if (op == "delete") library.deleteResume(profileId, source, kind, id)
            else library.putResume(
                IptvResumeRow(
                    source, kind, id, payload.optLong("positionMs"), payload.optLong("durationMs"), updated,
                    payload.optString("title"), null, "", "", "", payload.optString("seriesId").ifBlank { null },
                    null, payload.optInt("season"), payload.optInt("episode"), profileId,
                ),
            )
        }
    }

    private fun json(value: String) = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}
