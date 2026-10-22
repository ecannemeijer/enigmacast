package com.enigmacast.data.enigmasync

import android.content.Context
import com.enigmacast.data.db.IptvLibraryDao
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
import com.enigmacast.domain.model.StartupLive
import com.enigmacast.domain.profile.HomeBouquetCloud
import com.enigmacast.domain.profile.ProfileCloud
import com.enigmacast.domain.sync.SyncApply
import com.enigmacast.domain.sync.SyncConflict
import com.enigmacast.domain.sync.LibrarySync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import com.enigmacast.sync.SyncScheduler
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** An M3U or Xtream login stored on the sync server, offered once in the wizard. */
data class RemoteIptvOffer(
    val kind: IptvPlaylistKind,
    val login: IptvPlaylistLogin,
    val updatedMs: Long,
    val changeId: String,
)

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
    private val activity: com.enigmacast.activity.BackgroundActivityTracker,
    private val tombstones: com.enigmacast.data.xtream.LibraryTombstones,
    private val watchNext: com.enigmacast.data.xtream.WatchNextPublisher,
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

    /** One sync at a time: saves nudge it every few seconds during playback, and cursors must not race. */
    private val running = kotlinx.coroutines.sync.Mutex()
    private val marks by lazy { context.getSharedPreferences("enigma_sync_library", Context.MODE_PRIVATE) }

    suspend fun sync() = withContext(Dispatchers.IO) {
        running.withLock {
            // Unpaired devices have nothing to do; a paired one reports to the status window (only slow syncs show).
            if (client.config() == null) return@withLock
            activity.track(com.enigmacast.activity.ActivityKind.SYNC_SERVER) { syncOnce() }
        }
    }

    private suspend fun syncOnce() {
        val config = client.config() ?: return
        EnigmaLog.service("sync: start host=${config.host}")
        // Progress has priority: send the outbox before the slower profile/settings pass so
        // stopping a film or episode reaches the receiver's API immediately.
        runCatching { push(config) }
            .onFailure { EnigmaLog.service("sync: push vooraf mislukt: ${it.message}") }
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
        }.onFailure { EnigmaLog.service("sync: profiel/pull mislukt: ${it.message}") }
        runCatching { syncPlaylist(config) }
            .onFailure { EnigmaLog.service("sync: playlist mislukt: ${it.message}") }
        runCatching { backfill(config) }
            .onFailure { EnigmaLog.service("sync: backfill mislukt: ${it.message}") }
        runCatching { push(config) }
            .onFailure { EnigmaLog.service("sync: push mislukt: ${it.message}") }
        EnigmaLog.service("sync: klaar")
    }

    /**
     * Favorites and positions saved before this device synced them (older versions, or before
     * pairing) go to the server once per profile and server. Their own times are kept, so a
     * newer change on the server is not overwritten.
     */
    private suspend fun backfill(config: SyncServerConfig) {
        val uuids = profiles.state.value.profiles.map { it.uuid }.filter { it.isNotBlank() }
        val todo = uuids.filter { !marks.getBoolean(backfillMark(config, it), false) }
        if (todo.isEmpty()) return
        val favorites = library.favorites().first()
        val resumes = library.resumes().first()
        todo.forEach { uuid ->
            LibrarySync.backfill(uuid, favorites, resumes) { "backfill:${java.util.UUID.randomUUID()}" }.forEach { outbox.put(it) }
            marks.edit().putBoolean(backfillMark(config, uuid), true).apply()
        }
    }

    private fun backfillMark(config: SyncServerConfig, uuid: String) = "library-v1:${config.deviceId}:$uuid"

    /**
     * Sends local profile names to the receiver.
     * A profile is removed on the server only after the user deleted it on this TV.
     * One that this TV has never stored is left alone, so another viewer's profile is not wiped.
     */
    private fun publishProfiles(config: SyncServerConfig) {
        val local = profiles.state.value.profiles.filter { it.uuid.isNotBlank() }
        val now = System.currentTimeMillis()
        local.forEach { profile ->
            val signature = "${profile.name}|${profile.avatar}|${profile.language.orEmpty()}"
            val key = "profile-published:${config.deviceId}:${profile.uuid}"
            if (marks.getString(key, null) == signature) return@forEach
            runCatching { postProfile(config, profile.uuid, profile.name, profile.avatar, profile.language, false, now) }
                .onSuccess { marks.edit().putString(key, signature).apply() }
        }
        val removed = profiles.deletedUuids()
        if (removed.isEmpty()) return
        val text = runCatching { client.request(config, "GET", "/api/v1/profiles") }.getOrNull() ?: return
        val remote = JSONObject(text).optJSONArray("profiles") ?: return
        for (index in 0 until remote.length()) {
            val row = remote.getJSONObject(index)
            val uuid = row.optString("uuid")
            if (uuid.isBlank() || uuid !in removed) continue
            runCatching { postProfile(config, uuid, row.optString("name"), 0, null, true, now) }
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

    /** The newest change per record, in chunks; the outbox is cleared only after the server took them. */
    private suspend fun push(config: SyncServerConfig) {
        val pending = outbox.pending()
        if (pending.isEmpty()) return
        val batch = LibrarySync.batch(pending)
        batch.send.chunked(LibrarySync.PUSH_CHUNK).forEach { chunk ->
            client.request(config, "POST", "/api/v1/sync/push", LibrarySync.pushBody(chunk).toByteArray())
        }
        batch.settled.forEach { outbox.delete(it) }
    }

    /** One pull page, retried because the receiver's old TLS stack occasionally drops a connection. */
    private suspend fun fetchPullPage(config: SyncServerConfig, encodedProfile: String, cursor: Int): String {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return client.request(
                    config,
                    "GET",
                    "/api/v1/sync/pull?profileId=$encodedProfile&cursor=$cursor&limit=${LibrarySync.PULL_CHUNK}",
                )
            } catch (error: Exception) {
                last = error
                EnigmaLog.service("sync: pull pagina mislukt (poging ${attempt + 1}): ${error.message}")
                kotlinx.coroutines.delay(400)
            }
        }
        throw last ?: IllegalStateException("pull failed")
    }

    /** @return true when this pull carried a settings document for the profile. */
    private suspend fun pull(config: SyncServerConfig, profileId: String): Boolean {
        val encoded = java.net.URLEncoder.encode(profileId, "UTF-8")
        var cursor = outbox.cursor(profileId)?.cursor?.toIntOrNull() ?: 0
        var sawSettings = false
        // The receiver returns at most 500 changes per request. Keep pulling pages until the
        // cursor stops moving; the old single huge TLS response was the reason a TV pull died
        // with BrokenPipe and never reached the local database.
        while (true) {
            val text = fetchPullPage(config, encoded, cursor)
            val root = JSONObject(text)
            val changes = root.optJSONArray("changes")
            if (changes != null) {
                for (i in 0 until changes.length()) {
                    val change = changes.getJSONObject(i)
                    if (change.optString("entity") == "settings") sawSettings = true
                    apply(profileId, change)
                }
            }
            val next = root.optInt("cursor", cursor)
            outbox.putCursor(SyncCursorRow(profileId, next.toString()))
            if (next <= cursor || changes == null || changes.length() < LibrarySync.PULL_CHUNK) break
            cursor = next
        }
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

    /**
     * The server login, without saving it. Null when there is nothing to offer, or the wizard
     * already declined this revision.
     */
    suspend fun peekPlaylist(): RemoteIptvOffer? = withContext(Dispatchers.IO) {
        val config = client.config() ?: return@withContext null
        val text = runCatching { client.request(config, "GET", "/api/v1/playlists") }.getOrNull() ?: return@withContext null
        val remote = JSONObject(text).optJSONObject("playlist") ?: return@withContext null
        offerOf(remote)?.takeUnless { playlist.isDeclined(it.changeId) }
    }

    /** Wizard said yes: store the login and fetch the channel list only after bouquets. */
    suspend fun takePlaylist(offer: RemoteIptvOffer) = withContext(Dispatchers.IO) {
        playlist.clearDecline()
        applyPlaylist(
            JSONObject()
                .put("m3uUrl", offer.login.m3uUrl)
                .put("server", offer.login.server)
                .put("username", offer.login.username)
                .put("password", offer.login.password)
                .put("kind", offer.kind.name)
                .put("updatedMs", offer.updatedMs)
                .put("changeId", offer.changeId),
        )
    }

    fun declinePlaylist(changeId: String) {
        playlist.decline(changeId)
    }

    /** The next pass after bouquets should download this device's IPTV channels. */
    fun armIptvLoad() {
        iptv.arm()
    }

    /**
     * Settings just saved an M3U or Xtream login. Write that row to the server now.
     * A missing sync server leaves the login on this TV.
     */
    suspend fun publishPlaylist(login: IptvPlaylistLogin, kind: IptvPlaylistKind) = withContext(Dispatchers.IO) {
        val config = client.config() ?: return@withContext
        if (!login.configured(kind)) return@withContext
        playlist.clearDecline()
        val rev = playlist.markDirty()
        runCatching { postPlaylist(config, login, kind, rev) }
    }

    /** One IPTV login for this device. A profile switch does not call this again. */
    private suspend fun syncPlaylist(config: SyncServerConfig) {
        val text = runCatching { client.request(config, "GET", "/api/v1/playlists") }.getOrNull()
        val remote = text?.let { JSONObject(it).optJSONObject("playlist") }
        val local = playlist.revision()
        if (remote != null) {
            val decision = SyncConflict.decide(local?.updatedMs, local?.changeId, remote.optLong("updatedMs"), remote.optString("changeId"))
            val changeId = remote.optString("changeId")
            if (decision == SyncApply.ACCEPT) {
                // The wizard asks first. After setup, a declined revision stays declined until a newer one arrives.
                if (!settings.current().setupCompleted || playlist.isDeclined(changeId)) return
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
        runCatching { postPlaylist(config, login, now.iptvPlaylistKind, rev) }
    }

    private fun postPlaylist(config: SyncServerConfig, login: IptvPlaylistLogin, kind: IptvPlaylistKind, rev: com.enigmacast.data.xtream.PlaylistRevision) {
        val body = JSONObject()
            .put("m3uUrl", login.m3uUrl)
            .put("server", login.server)
            .put("username", login.username)
            .put("password", login.password)
            .put("kind", kind.name)
            .put("updatedMs", rev.updatedMs)
            .put("changeId", rev.changeId)
        val posted = client.request(config, "POST", "/api/v1/playlists", body.toString().toByteArray())
        val result = JSONObject(posted).optString("result")
        if ((result == "accept" || result == "replay") && playlist.revision()?.changeId == rev.changeId) playlist.clearDirty()
    }

    private fun offerOf(remote: JSONObject): RemoteIptvOffer? {
        val kind = runCatching { IptvPlaylistKind.valueOf(remote.optString("kind")) }.getOrDefault(IptvPlaylistKind.M3U)
        val login = IptvPlaylistLogin(
            remote.optString("m3uUrl"), remote.optString("server"), remote.optString("username"), remote.optString("password"),
        )
        if (!login.configured(kind)) return null
        return RemoteIptvOffer(kind, login, remote.optLong("updatedMs"), remote.optString("changeId"))
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
        val raw = change.optString("payload")
        val decoded = runCatching { ProfileCloud.decode(raw) }.getOrNull() ?: return
        val profile = profiles.state.value.profiles.firstOrNull { it.uuid == profileUuid } ?: return
        val keepStartup = if (profile.id == profiles.state.value.activeId) settings.current().startupLive
        else profiles.state.value.scopes[profile.id]?.startupLive ?: StartupLive.OFF
        val snapshot = ProfileCloud.keepStartupWhenMissing(decoded, raw, keepStartup)
        val startupWasMissing = !ProfileCloud.includesStartupLive(raw) && snapshot.scope.startupLive != decoded.scope.startupLive
        meta.applying = true
        meta.quietFingerprint = ProfileCloud.encode(snapshot)
        try {
            if (profile.id == profiles.state.value.activeId) settings.update { snapshot.scope.applyTo(it) }
            profiles.rememberScope(profile.id, snapshot.scope, snapshot.language)
            bouquets.import(profile.id, HomeBouquetCloud(snapshot.homeBouquets, snapshot.homeAll))
            hidden.import(profile.id, snapshot.hiddenCategories)
            meta.accept(profileUuid, updated, changeId)
            if (startupWasMissing) meta.markDirty(profileUuid)
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
        if (entity != LibrarySync.FAVORITE && entity != LibrarySync.PROGRESS) return
        val op = change.optString("op")
        val updated = change.optLong("updated_ms")
        val changeId = change.optString("change_id")
        val payload = change.optString("payload")
        // Deletes are stored with an empty payload: the record key names what to remove.
        val key = LibrarySync.keyOfChange(change.optString("record_key"), payload) ?: return
        val (source, kind, id) = key
        val recordKey = LibrarySync.recordKey(source, kind, id)
        val tombstone = tombstones.get(profileId, entity, recordKey)
        if (entity == LibrarySync.FAVORITE) {
            val current = library.favorite(profileId, source, kind, id)
            when (com.enigmacast.domain.sync.LibraryMerge.decide(current?.addedMs, tombstone, updated, changeId, op)) {
                com.enigmacast.domain.sync.LibraryMergeResult.IGNORE -> return
                com.enigmacast.domain.sync.LibraryMergeResult.DELETE -> {
                    library.deleteFavorite(profileId, source, kind, id)
                    tombstones.put(profileId, entity, recordKey, com.enigmacast.domain.sync.LibraryMerge.tombstoneAfterDelete(tombstone, updated))
                }
                com.enigmacast.domain.sync.LibraryMergeResult.UPSERT -> {
                    library.putFavorite(LibrarySync.favoriteFrom(profileId, key, payload, updated, current))
                    tombstones.clear(profileId, entity, recordKey)
                }
            }
        } else {
            val current = library.resume(profileId, source, kind, id)
            when (com.enigmacast.domain.sync.LibraryMerge.decide(current?.updatedMs, tombstone, updated, changeId, op)) {
                com.enigmacast.domain.sync.LibraryMergeResult.IGNORE -> return
                com.enigmacast.domain.sync.LibraryMergeResult.DELETE -> {
                    library.deleteResume(profileId, source, kind, id)
                    tombstones.put(profileId, entity, recordKey, com.enigmacast.domain.sync.LibraryMerge.tombstoneAfterDelete(tombstone, updated))
                    // The Android TV home row kept removed items too.
                    watchNext.remove(source, kind, id)
                }
                com.enigmacast.domain.sync.LibraryMergeResult.UPSERT -> {
                    library.putResume(LibrarySync.progressFrom(profileId, key, payload, updated, current))
                    tombstones.clear(profileId, entity, recordKey)
                }
            }
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


