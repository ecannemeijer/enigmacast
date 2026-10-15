package com.enigmacast.data.profile

import android.content.Context
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileRules
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
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProfileState> = _state.asStateFlow()
    private val _switched = MutableSharedFlow<Profile>(extraBufferCapacity = 2)
    /** After a switch: screens reload, and the app applies the profile's language. */
    val switched: SharedFlow<Profile> = _switched.asSharedFlow()

    val activeId: String get() = _state.value.activeId

    private fun load(): ProfileState {
        val raw = prefs.getString(KEY, null)
        val parsed = raw?.let { runCatching { json.decodeFromString(ProfileState.serializer(), it) }.getOrNull() } ?: ProfileState()
        val next = ProfileRules.ensureDefault(parsed, context.getString(R.string.profile_default_name))
        if (next != parsed) prefs.edit().putString(KEY, json.encodeToString(ProfileState.serializer(), next)).apply()
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
        _switched.tryEmit(nextState.active)
    }

    /** Picker closed on the profile already active: remember that a choice was made. */
    fun confirmActive() {
        if (!_state.value.chosenOnce) save(_state.value.copy(chosenOnce = true))
    }

    fun add(name: String, avatar: Int): String {
        val id = java.util.UUID.randomUUID().toString()
        save(ProfileRules.add(_state.value, id, name, avatar, uuid = id))
        return id
    }

    fun edit(id: String, name: String, avatar: Int) = save(ProfileRules.edit(_state.value, id, name, avatar))

    fun delete(id: String) = save(ProfileRules.delete(_state.value, id))

    fun seesPlaylist(uuid: String = _state.value.activeUuid): Boolean = ProfileRules.seesPlaylist(_state.value, uuid)

    fun setPlaylistAccess(uuid: String, allowed: Boolean) = save(ProfileRules.playlistAccess(_state.value, uuid, allowed))

    /** The app language chosen while this profile is active. */
    fun setLanguage(tag: String) {
        val s = _state.value
        save(s.copy(profiles = s.profiles.map { if (it.id == s.activeId) it.copy(language = tag) else it }))
    }

    private companion object {
        const val KEY = "state"
    }
}
