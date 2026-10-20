package com.enigmacast.data.profile

import com.enigmacast.domain.model.SkinDefaults
import android.content.Context
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileRules
import com.enigmacast.domain.profile.ProfileScope
import com.enigmacast.domain.profile.ProfileState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profiles ("Who's watching?"), kept in their own preferences file. The active profile's own
 * settings live in [SettingsRepository] as usual; on a switch they are swapped (see [ProfileRules.switch]).
 */
@Singleton
class ProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val gate = Mutex()
    private val initial = load()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    /** Live TV startup waits until the viewer has picked a profile. */
    @Volatile var holdLiveStart: Boolean = ProfileRules.askOnStart(initial)
        private set

    private val _switched = MutableSharedFlow<Profile>(extraBufferCapacity = 2)
    private val _entered = MutableSharedFlow<Profile>(extraBufferCapacity = 2)
    /** Fired when a profile is chosen, including when the already active one is confirmed. */
    val entered: SharedFlow<Profile> = _entered.asSharedFlow()
    /** After a switch: screens reload, and the app applies the profile's language. */
    val switched: SharedFlow<Profile> = _switched.asSharedFlow()

    val activeId: String get() = _state.value.activeId

    private fun load(): ProfileState {
        val raw = prefs.getString(KEY, null)
        val parsed = raw?.let { runCatching { SkinDefaults.decodeScopes(json, ProfileState.serializer(), it) }.getOrNull() } ?: ProfileState()
        val next = ProfileRules.ensureDefault(parsed, context.getString(R.string.profile_default_name))
        // The one-time skin move is written back at once, so it never runs twice for a scope.
        if (next != parsed || (raw != null && SkinDefaults.pendingIn(json, raw))) prefs.edit().putString(KEY, json.encodeToString(ProfileState.serializer(), next)).apply()
        return next
    }

    private fun save(next: ProfileState) {
        _state.value = next
        prefs.edit().putString(KEY, json.encodeToString(ProfileState.serializer(), next)).apply()
    }

    suspend fun switchTo(id: String) = gate.withLock {
        val current = _state.value
        var nextState = current
        settingsRepository.update { settings ->
            val (s, applied) = ProfileRules.switch(current, settings, id)
            nextState = s
            applied
        }
        save(nextState)
        releaseHold()
        _switched.tryEmit(nextState.active)
        _entered.tryEmit(nextState.active)
    }

    /** Picker closed on the profile already active: remember that a choice was made. */
    fun confirmActive() {
        if (!_state.value.chosenOnce) save(_state.value.copy(chosenOnce = true))
        releaseHold()
        _entered.tryEmit(_state.value.active)
    }

    /** Uuids the user removed on this TV. A profile that was never here is not in this set. */
    fun deletedUuids(): Set<String> = prefs.getStringSet(DELETED, emptySet()).orEmpty()

    /** A profile that already exists on the sync server, matched by uuid. */
    fun importRemote(uuid: String, name: String, avatar: Int) {
        if (uuid in deletedUuids()) return
        val trimmed = name.trim().ifBlank { return }
        val state = _state.value
        val existing = state.profiles.firstOrNull { it.uuid == uuid || it.id == uuid }
        if (existing == null) save(ProfileRules.add(state, uuid, trimmed, avatar, uuid = uuid))
        else if (existing.name != trimmed || existing.avatar != avatar) save(ProfileRules.edit(state, existing.id, trimmed, avatar))
    }

    private fun releaseHold() {
        holdLiveStart = false
    }

    fun add(name: String, avatar: Int): String {
        val id = java.util.UUID.randomUUID().toString()
        save(ProfileRules.add(_state.value, id, name, avatar, uuid = id))
        return id
    }

    fun edit(id: String, name: String, avatar: Int) = save(ProfileRules.edit(_state.value, id, name, avatar))

    /** A phone photo was stored for [id]: show it (the stamp also refreshes cached images). */
    fun setPhoto(id: String, stamp: Long) {
        val s = _state.value
        save(s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(photo = stamp) else it }))
    }

    fun clearPhoto(id: String) = setPhoto(id, 0)

    fun delete(id: String) {
        val uuid = _state.value.profiles.firstOrNull { it.id == id }?.uuid.orEmpty()
        save(ProfileRules.delete(_state.value, id))
        if (uuid.isNotBlank()) prefs.edit().putStringSet(DELETED, deletedUuids() + uuid).apply()
    }

    fun seesPlaylist(uuid: String = _state.value.activeUuid): Boolean = ProfileRules.seesPlaylist(_state.value, uuid)

    fun setPlaylistAccess(uuid: String, allowed: Boolean) = save(ProfileRules.playlistAccess(_state.value, uuid, allowed))

    /** Keeps a profile's saved settings in step with a copy from the sync server. */
    suspend fun rememberScope(profileId: String, scope: ProfileScope, language: String?) = gate.withLock {
        val current = _state.value
        val profiles = current.profiles.map { profile ->
            if (profile.id != profileId || language.isNullOrBlank() || profile.language == language) profile
            else profile.copy(language = language)
        }
        val next = current.copy(profiles = profiles, scopes = current.scopes + (profileId to scope))
        save(next)
        if (profileId == current.activeId) {
            val updated = next.profiles.first { it.id == profileId }
            if (updated.language != current.active.language && !updated.language.isNullOrBlank()) _switched.tryEmit(updated)
        }
    }

    /** The app language chosen while this profile is active. */
    fun setLanguage(tag: String) {
        val s = _state.value
        save(s.copy(profiles = s.profiles.map { if (it.id == s.activeId) it.copy(language = tag) else it }))
    }

    private companion object {
        const val KEY = "state"
        const val DELETED = "deleted_uuids"
    }
}
