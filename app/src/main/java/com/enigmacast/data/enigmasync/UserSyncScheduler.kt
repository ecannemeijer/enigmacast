package com.enigmacast.data.enigmasync

import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.data.xtream.IptvHiddenCategories
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.profile.ProfileCloud
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Lets writers wake a sync without depending on the engine. */
@Singleton
class SyncTrigger @Inject constructor() {
    @Volatile var run: (() -> Unit)? = null
    fun nudge() { run?.invoke() }
}

@Singleton
class UserSyncScheduler @Inject constructor(
    private val engine: EnigmaSyncEngine,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
    private val trigger: SyncTrigger,
    private val settings: SettingsRepository,
    private val snapshots: ProfileSnapshotBuilder,
    private val meta: ProfileSyncMeta,
    private val bouquets: HomeBouquetStore,
    private val hidden: IptvHiddenCategories,
    private val playlist: IptvPlaylistStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        trigger.run = { scope.launch { engine.sync() } }
        scope.launch { profiles.entered.collect { engine.sync() } }
        scope.launch {
            profiles.state.map { state ->
                state.profiles.map { profile -> listOf(profile.uuid, profile.name, profile.avatar, profile.language) }
            }.distinctUntilChanged().collect { engine.sync() }
        }
        watchSettings()
        watchPlaylist()
        scope.launch {
            runCatching { engine.importProfiles() }
            engine.sync()
        }
    }

    /** A profile login and later edits upload that profile's settings. A switch does not reload IPTV. */
    private fun watchSettings() {
        scope.launch {
            var primed = false
            var lastProfileId: String? = null
            var lastFingerprint: String? = null
            var pendingUuid: String? = null
            var debounce: Job? = null
            combine(settings.settings, profiles.state, bouquets.changes, hidden.changes) { current, state, _, _ ->
                snapshots.forUuid(state.activeUuid, current, state) to state.activeId
            }.collect { (snapshot, profileId) ->
                if (snapshot == null) return@collect
                val fingerprint = ProfileCloud.encode(snapshot)
                val uuid = profiles.state.value.activeUuid
                if (!primed) {
                    primed = true
                    lastProfileId = profileId
                    lastFingerprint = fingerprint
                    return@collect
                }
                if (meta.applying) return@collect
                if (profileId != lastProfileId) {
                    debounce?.cancel()
                    val previous = pendingUuid
                    pendingUuid = null
                    lastProfileId = profileId
                    lastFingerprint = fingerprint
                    if (previous != null) {
                        meta.markDirty(previous)
                        engine.sync()
                    }
                    return@collect
                }
                if (fingerprint == meta.quietFingerprint || fingerprint == lastFingerprint) return@collect
                lastFingerprint = fingerprint
                pendingUuid = uuid
                debounce?.cancel()
                debounce = scope.launch {
                    delay(1_500)
                    if (meta.applying || profiles.state.value.activeId != profileId) return@launch
                    val fresh = snapshots.current(uuid) ?: return@launch
                    if (ProfileCloud.encode(fresh) == meta.quietFingerprint) return@launch
                    meta.markDirty(uuid)
                    pendingUuid = null
                    engine.sync()
                }
            }
        }
    }

    /** The IPTV login is saved for the device. Changing profile does not mark it dirty. */
    private fun watchPlaylist() {
        scope.launch {
            var primed = false
            combine(
                settings.settings.map { it.iptvSource to it.iptvPlaylistKind },
                playlist.login,
            ) { mode, login -> Triple(mode.first, mode.second, login) }
                .distinctUntilChanged()
                .collect { (source, kind, login) ->
                    if (!primed) {
                        primed = true
                        return@collect
                    }
                    if (playlist.applyingRemote) return@collect
                    if (source == IptvSourceMode.PLAYLIST && login.configured(kind)) {
                        playlist.markDirty()
                        engine.sync()
                    }
                }
        }
    }
}
