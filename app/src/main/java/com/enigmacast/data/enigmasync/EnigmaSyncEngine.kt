package com.enigmacast.data.enigmasync

import android.content.Context
import com.enigmacast.data.db.IptvLibraryDao
import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.db.SyncCursorRow
import com.enigmacast.data.db.SyncOutboxDao
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.data.xtream.IptvHiddenCategories
import com.enigmacast.data.xtream.IptvPlaylistLogin
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.profile.HomeBouquetCloud
import com.enigmacast.domain.profile.ProfileCloud
import com.enigmacast.domain.sync.SyncApply
import com.enigmacast.domain.sync.SyncConflict
import com.enigmacast.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Pushes the local outbox and pulls newer rows. A missing server leaves Room as it is. */
@Singleton
class EnigmaSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: EnigmaSyncClient,
    private val outbox: SyncOutboxDao,
    private val library: IptvLibraryDao,
    private val profiles: ProfileStore,
    private val settings: SettingsRepository,
    private val snapshots: ProfileSnapshotBuilder,
    private val meta: ProfileSyncMeta,
    private val bouquets: HomeBouquetStore,
    private val hidden: IptvHiddenCategories,
    private val playlist: IptvPlaylistStore,
    private val iptv: IptvPlaylistSync,
) {
    /** Copies server profiles onto this device so the picker can show them. */
    suspend fun importProfiles() = withContext(Dispatchers.IO) {
        val config = client.config() ?: return@withContext
        val text = runCatching { client.request(config, "GET", "/api/v1/profiles") }.getOrNull() ?: return@withContext
        val profilesJson = JSONObject(text).optJSONArray("profiles") ?: return@withContext
        for (index in 0 until profilesJson.length()) {
            val profile = profilesJson.getJSONObject(index)
            val uuid = profile.optString("uuid")
            if (uuid.isBlank()) continue
            profiles.importRemote(uuid, profile.optString("name"), profile.optInt("avatar"))
        }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        val config = client.config() ?: return@withContext
        runCatching {
            publishProfiles(config)
            val current = settings.current()
            val state = profiles.state.value
            state.profiles.forEach { profile ->
                val uuid = profile.uuid.ifBlank { return@forEach }
                val sawSettings = pull(config, uuid)
                val snapshot = snapshots.forUuid(uuid, current, state)
                if (meta.revision(uuid) == null && !sawSettings && snapshot != null && !ProfileCloud.untouched(snapshot)) {
                    meta.markDirty(uuid)
                }
            }
            pushSettings(config)
            syncPlaylist(config)
            push(config)
        }
    }

    /** Sends local profile names to the receiver and removes profiles that no longer exist here. */
    private fun publishProfiles(config: SyncServerConfig) {
        val local = profiles.state.value.profiles.filter { it.uuid.isNotBlank() }
        val now = System.currentTimeMillis()
        local.forEach { profile ->
            runCatching { postProfile(config, profile.uuid, profile.name, profile.avatar, profile.language, false, now) }
        }
        val text = runCatching { client.request(config, "GET", "/api/v1/profiles") }.getOrNull() ?: return
        val remote = JSONObject(text).optJSONArray("profiles") ?: return
        val keep = local.map { it.uuid }.toSet()
        for (index in 0 until remote.length()) {
            val uuid = remote.getJSONObject(index).optString("uuid")
            if (uuid.isBlank() || uuid in keep) continue
            runCatching { postProfile(config, uuid, remote.getJSONObject(index).optString("name"), 0, null, true, now) }
        }
    }

    private fun postProfile(
        config: SyncServerConfig,
        uuid: String,
        name: String,
        avatar: Int,
        language: String?,
        deleted: Boolean,
        updatedMs: Long,
    ) {
        val body = JSONObject()
            .put("uuid", uuid)
            .put("name", name)
            .put("avatar", avatar)
            .put("updatedMs", updatedMs)
            .put("changeId", "$uuid:$updatedMs:$deleted")
            .put("deleted", deleted)
        if (!language.isNullOrBlank()) body.put("language", language)
        client.request(config, "POST", "/api/v1/profiles", body.toString().toByteArray())
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

    /** @return true when this pull carried a settings document for the profile. */
    private suspend fun pull(config: SyncServerConfig, profileId: String): Boolean {
        val cursor = outbox.cursor(profileId)?.cursor?.toIntOrNull() ?: 0
        val text = client.request(config, "GET", "/api/v1/sync/pull?profileId=${java.net.URLEncoder.encode(profileId, "UTF-8")}&cursor=$cursor")
        val root = JSONObject(text)
        val changes = root.optJSONArray("changes")
        var sawSettings = false
        if (changes != null) {
            for (i in 0 until changes.length()) {
                val change = changes.getJSONObject(i)
                if (change.optString("entity") == "settings") sawSettings = true
                apply(profileId, change)
            }
        }
        outbox.putCursor(SyncCursorRow(profileId, root.optInt("cursor", cursor).toString()))
        return sawSettings
    }

    private suspend fun pushSettings(config: SyncServerConfig) {
        val current = settings.current()
        val state = profiles.state.value
        val bodies = ArrayList<String>()
        val pushed = ArrayList<Pair<String, String>>()
        state.profiles.forEach { profile ->
            val uuid = profile.uuid.ifBlank { return@forEach }
            val rev = meta.revision(uuid) ?: return@forEach
            if (!rev.dirty) return@forEach
            val snapshot = snapshots.forUuid(uuid, current, state) ?: return@forEach
            bodies += """{"profileId":${json(uuid)},"entity":"settings","key":"scope","op":"upsert","payload":${ProfileCloud.encode(snapshot)},"updatedMs":${rev.updatedMs},"changeId":${json(rev.changeId)}}"""
            pushed += uuid to rev.changeId
        }
        if (bodies.isEmpty()) return
        val text = client.request(config, "POST", "/api/v1/sync/push", """{"changes":[${bodies.joinToString(",")}]}""".toByteArray())
        val results = JSONObject(text).optJSONArray("results") ?: return
        pushed.forEachIndexed { index, (uuid, changeId) ->
            val result = results.optString(index)
            if ((result == "accept" || result == "replay") && meta.revision(uuid)?.changeId == changeId) meta.clearDirty(uuid)
        }
    }

    /** One IPTV login for this device. A profile switch does not call this again. */
    private suspend fun syncPlaylist(config: SyncServerConfig) {
        val text = runCatching { client.request(config, "GET", "/api/v1/playlists") }.getOrNull() ?: return
        val remote = JSONObject(text).optJSONObject("playlist")
        val local = playlist.revision()
        if (remote != null) {
            val decision = SyncConflict.decide(local?.updatedMs, local?.changeId, remote.optLong("updatedMs"), remote.optString("changeId"))
            if (decision == SyncApply.ACCEPT) {
                applyPlaylist(remote)
                return
            }
        }
        val now = settings.current()
        val login = playlist.login.value
        if (remote == null && playlist.revision() == null && now.iptvSource == IptvSourceMode.PLAYLIST && login.configured(now.iptvPlaylistKind)) {
            playlist.markDirty()
        }
        val rev = playlist.revision() ?: return
        if (!rev.dirty) return
        if (now.iptvSource != IptvSourceMode.PLAYLIST || !login.configured(now.iptvPlaylistKind)) return
        val body = JSONObject()
            .put("m3uUrl", login.m3uUrl)
            .put("server", login.server)
            .put("username", login.username)
            .put("password", login.password)
            .put("kind", now.iptvPlaylistKind.name)
            .put("updatedMs", rev.updatedMs)
            .put("changeId", rev.changeId)
        val posted = client.request(config, "POST", "/api/v1/playlists", body.toString().toByteArray())
        val result = JSONObject(posted).optString("result")
        if ((result == "accept" || result == "replay") && playlist.revision()?.changeId == rev.changeId) playlist.clearDirty()
    }

    private suspend fun applyPlaylist(remote: JSONObject) {
        val kind = runCatching { IptvPlaylistKind.valueOf(remote.optString("kind")) }.getOrDefault(IptvPlaylistKind.M3U)
        val incoming = IptvPlaylistLogin(
            remote.optString("m3uUrl"), remote.optString("server"), remote.optString("username"), remote.optString("password"),
        )
        if (!incoming.configured(kind)) return
        val beforeLogin = playlist.login.value
        val before = settings.current()
        val changed = beforeLogin != incoming || before.iptvPlaylistKind != kind || before.iptvSource != IptvSourceMode.PLAYLIST
        playlist.applyingRemote = true
        try {
            playlist.save(incoming)
            settings.update { it.copy(iptvSource = IptvSourceMode.PLAYLIST, iptvPlaylistKind = kind) }
            playlist.acceptRevision(remote.optLong("updatedMs"), remote.optString("changeId"))
        } finally {
            playlist.applyingRemote = false
        }
        if (!changed) return
        iptv.arm()
        if (settings.current().setupCompleted) SyncScheduler.runIptvTail(context)
    }

    private suspend fun applySettings(profileUuid: String, change: JSONObject) {
        val updated = change.optLong("updated_ms")
        val changeId = change.optString("change_id")
        val rev = meta.revision(profileUuid)
        if (SyncConflict.decide(rev?.updatedMs, rev?.changeId, updated, changeId) != SyncApply.ACCEPT) return
        val snapshot = runCatching { ProfileCloud.decode(change.optString("payload")) }.getOrNull() ?: return
        val profile = profiles.state.value.profiles.firstOrNull { it.uuid == profileUuid } ?: return
        meta.applying = true
        meta.quietFingerprint = ProfileCloud.encode(snapshot)
        try {
            if (profile.id == profiles.state.value.activeId) settings.update { snapshot.scope.applyTo(it) }
            profiles.rememberScope(profile.id, snapshot.scope, snapshot.language)
            bouquets.import(profile.id, HomeBouquetCloud(snapshot.homeBouquets, snapshot.homeAll))
            hidden.import(profile.id, snapshot.hiddenCategories)
            meta.accept(profileUuid, updated, changeId)
        } finally {
            meta.applying = false
        }
    }

    private suspend fun apply(profileId: String, change: JSONObject) {
        val entity = change.optString("entity")
        if (entity == "settings") {
            applySettings(profileId, change)
            return
        }
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
