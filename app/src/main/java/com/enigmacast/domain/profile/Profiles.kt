package com.enigmacast.domain.profile

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.LastIptvChannel
import com.enigmacast.domain.model.LastLiveSource
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.domain.model.StartupLive
import com.enigmacast.domain.player.PictureMode
import kotlinx.serialization.Serializable

/** One viewer. [avatar] indexes the bundled avatars (0-based). [uuid] never changes and is the sync id. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: Int = 0,
    val language: String? = null,
    val uuid: String = "",
    /** When a photo from the phone was set (millis); 0 = the bundled [avatar]. The photo stays on this TV. */
    val photo: Long = 0,
)

/**
 * The settings that belong to a profile. Everything else (receivers, viewing mode, streams,
 * recordings, series rules, the guide cache) is shared by all profiles.
 */
@Serializable
data class ProfileScope(
    val lastChannels: Map<String, LastChannel> = emptyMap(),
    val recentByReceiver: Map<String, List<LastChannel>> = emptyMap(),
    val lastLiveSource: LastLiveSource = LastLiveSource.ENIGMA2,
    val lastIptvChannels: Map<String, LastIptvChannel> = emptyMap(),
    val pictureModes: Map<String, PictureMode> = emptyMap(),
    /** Always written, like [AppSettings.osdSkin]. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val osdSkin: OsdSkinId = OsdSkinId.DEFAULT,
    val osdInfo: OsdInfoFields = OsdInfoFields(),
    val navigationSounds: Boolean = true,
    val motionQuality: MotionQuality = MotionQuality.PREMIUM,
    /** Settings › Playback: open fullscreen live TV on this source when the app starts. */
    val startupLive: StartupLive = StartupLive.OFF,
    /** [SkinDefaults.MARKER] of this scope; also travels with synced profiles. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val skinDefaults: Int = com.enigmacast.domain.model.SkinDefaults.CURRENT,
) {
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        lastChannels = lastChannels,
        recentByReceiver = recentByReceiver,
        lastLiveSource = lastLiveSource,
        lastIptvChannels = lastIptvChannels,
        pictureModes = pictureModes,
        osdSkin = osdSkin,
        osdInfo = osdInfo,
        navigationSounds = navigationSounds,
        motionQuality = motionQuality,
        startupLive = startupLive,
    )

    companion object {
        fun of(settings: AppSettings) = ProfileScope(
            settings.lastChannels, settings.recentByReceiver, settings.lastLiveSource, settings.lastIptvChannels,
            settings.pictureModes, settings.osdSkin, settings.osdInfo, settings.navigationSounds, settings.motionQuality,
            settings.startupLive,
        )
    }
}

@Serializable
data class ProfileState(
    val profiles: List<Profile> = emptyList(),
    val activeId: String = DEFAULT_ID,
    /** Saved per-profile settings, by profile id (the active profile's live values are in AppSettings). */
    val scopes: Map<String, ProfileScope> = emptyMap(),
    /** False until a profile was picked once (first run shows the picker). */
    val chosenOnce: Boolean = false,
    /**
     * Profile uuids that may use the shared IPTV playlist. Empty means every profile.
     * XStreamity logins stay on the receiver and are not assigned here.
     */
    val playlistAudience: List<String> = emptyList(),
) {
    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull() ?: Profile(DEFAULT_ID, "", uuid = LEGACY_UUID)
    val activeUuid: String get() = active.uuid.ifBlank { LEGACY_UUID }

    companion object {
        const val DEFAULT_ID = "default"
        const val AVATAR_COUNT = 16
        /** Rows saved before profiles were real data belong to the original profile. */
        const val LEGACY_UUID = "00000000-0000-4000-8000-000000000001"
    }
}

/** Pure profile rules, unit-tested. */
object ProfileRules {
    /** "Who's watching?" at start: on the first run, and whenever there is more than one profile. */
    fun askOnStart(state: ProfileState): Boolean = !state.chosenOnce || state.profiles.size > 1

    /**
     * Key for data stored per profile in a shared table or file (resume positions, Home bouquets).
     * The default profile keeps the plain key, so data from before profiles stays with it.
     */
    fun scopedKey(profileId: String, key: String): String =
        if (profileId == ProfileState.DEFAULT_ID) key else "$profileId|$key"

    /** The plain key of a scoped key that belongs to [profileId], or null when it belongs to another profile. */
    fun unscope(profileId: String, stored: String): String? {
        if (profileId == ProfileState.DEFAULT_ID) return stored.takeUnless { looksScoped(it) }
        val prefix = "$profileId|"
        return if (stored.startsWith(prefix)) stored.removePrefix(prefix) else null
    }

    private fun looksScoped(key: String): Boolean {
        val head = key.substringBefore('|', "")
        if (head.startsWith(PROFILE_PREFIX)) return true
        return head.length == 36 && head.count { it == '-' } == 4
    }

    const val PROFILE_PREFIX = "p-"

    /** Empty audience: every profile. Otherwise only the listed uuids. */
    fun seesPlaylist(state: ProfileState, uuid: String): Boolean =
        state.playlistAudience.isEmpty() || uuid in state.playlistAudience

    /**
     * Restrict or restore one profile. When every current profile is allowed, the list is cleared
     * so new profiles stay included until someone is explicitly left out.
     */
    fun playlistAccess(state: ProfileState, uuid: String, allowed: Boolean): ProfileState {
        val all = state.profiles.map { it.uuid.ifBlank { ProfileState.LEGACY_UUID } }.toSet()
        val current = if (state.playlistAudience.isEmpty()) all else state.playlistAudience.toSet()
        val next = if (allowed) current + uuid else current - uuid
        val stored = if (all.isNotEmpty() && next.containsAll(all)) emptyList() else next.toList()
        return state.copy(playlistAudience = stored)
    }

    fun ensureDefault(state: ProfileState, defaultName: String): ProfileState {
        val seeded = if (state.profiles.isNotEmpty()) state
        else state.copy(
            profiles = listOf(Profile(ProfileState.DEFAULT_ID, defaultName, 0, uuid = ProfileState.LEGACY_UUID)),
            activeId = ProfileState.DEFAULT_ID,
        )
        return seeded.copy(profiles = seeded.profiles.map { profile ->
            if (profile.uuid.isNotBlank()) profile
            else if (profile.id == ProfileState.DEFAULT_ID) profile.copy(uuid = ProfileState.LEGACY_UUID)
            else profile.copy(uuid = java.util.UUID.randomUUID().toString())
        })
    }

    /** Switch: the current profile's live settings are saved, the next one's are put in place. */
    fun switch(state: ProfileState, settings: AppSettings, toId: String): Pair<ProfileState, AppSettings> {
        if (state.profiles.none { it.id == toId }) return state to settings
        val saved = state.scopes + (state.activeId to ProfileScope.of(settings))
        val next = saved[toId] ?: ProfileScope()
        return state.copy(activeId = toId, scopes = saved, chosenOnce = true) to next.applyTo(settings)
    }

    fun add(state: ProfileState, id: String, name: String, avatar: Int, uuid: String = id): ProfileState =
        state.copy(profiles = state.profiles + Profile(id, name.trim(), avatar.coerceIn(0, ProfileState.AVATAR_COUNT - 1), uuid = uuid))

    fun edit(state: ProfileState, id: String, name: String, avatar: Int): ProfileState =
        state.copy(profiles = state.profiles.map { if (it.id == id) it.copy(name = name.trim(), avatar = avatar) else it })

    /** The last profile cannot be deleted; deleting the active one is done after switching away. */
    fun canDelete(state: ProfileState, id: String): Boolean = state.profiles.size > 1 && id != state.activeId

    fun delete(state: ProfileState, id: String): ProfileState =
        if (!canDelete(state, id)) state else state.copy(profiles = state.profiles.filterNot { it.id == id }, scopes = state.scopes - id)
}
