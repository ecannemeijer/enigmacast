package com.enigmacast.domain.profile

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.domain.player.PictureMode
import kotlinx.serialization.Serializable

/** One viewer. [avatar] indexes the bundled avatars (0-based). */
@Serializable
data class Profile(val id: String, val name: String, val avatar: Int = 0, val language: String? = null)

/**
 * The settings that belong to a profile. Everything else (receivers, viewing mode, streams,
 * recordings, series rules, the guide cache) is shared by all profiles.
 */
@Serializable
data class ProfileScope(
    val lastChannels: Map<String, LastChannel> = emptyMap(),
    val recentByReceiver: Map<String, List<LastChannel>> = emptyMap(),
    val pictureModes: Map<String, PictureMode> = emptyMap(),
    val osdSkin: OsdSkinId = OsdSkinId.STANDARD,
    val osdInfo: OsdInfoFields = OsdInfoFields(),
    val navigationSounds: Boolean = true,
    val motionQuality: MotionQuality = MotionQuality.PREMIUM,
) {
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        lastChannels = lastChannels,
        recentByReceiver = recentByReceiver,
        pictureModes = pictureModes,
        osdSkin = osdSkin,
        osdInfo = osdInfo,
        navigationSounds = navigationSounds,
        motionQuality = motionQuality,
    )

    companion object {
        fun of(settings: AppSettings) = ProfileScope(
            settings.lastChannels, settings.recentByReceiver, settings.pictureModes,
            settings.osdSkin, settings.osdInfo, settings.navigationSounds, settings.motionQuality,
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
) {
    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull() ?: Profile(DEFAULT_ID, "")

    companion object {
        const val DEFAULT_ID = "default"
        const val AVATAR_COUNT = 16
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

    private fun looksScoped(key: String) = key.substringBefore('|', "").startsWith(PROFILE_PREFIX)

    const val PROFILE_PREFIX = "p-"

    fun ensureDefault(state: ProfileState, defaultName: String): ProfileState =
        if (state.profiles.isNotEmpty()) state
        else state.copy(profiles = listOf(Profile(ProfileState.DEFAULT_ID, defaultName, 0)), activeId = ProfileState.DEFAULT_ID)

    /** Switch: the current profile's live settings are saved, the next one's are put in place. */
    fun switch(state: ProfileState, settings: AppSettings, toId: String): Pair<ProfileState, AppSettings> {
        if (state.profiles.none { it.id == toId }) return state to settings
        val saved = state.scopes + (state.activeId to ProfileScope.of(settings))
        val next = saved[toId] ?: ProfileScope()
        return state.copy(activeId = toId, scopes = saved, chosenOnce = true) to next.applyTo(settings)
    }

    fun add(state: ProfileState, id: String, name: String, avatar: Int): ProfileState =
        state.copy(profiles = state.profiles + Profile(id, name.trim(), avatar.coerceIn(0, ProfileState.AVATAR_COUNT - 1)))

    fun edit(state: ProfileState, id: String, name: String, avatar: Int): ProfileState =
        state.copy(profiles = state.profiles.map { if (it.id == id) it.copy(name = name.trim(), avatar = avatar) else it })

    /** The last profile cannot be deleted; deleting the active one is done after switching away. */
    fun canDelete(state: ProfileState, id: String): Boolean = state.profiles.size > 1 && id != state.activeId

    fun delete(state: ProfileState, id: String): ProfileState =
        if (!canDelete(state, id)) state else state.copy(profiles = state.profiles.filterNot { it.id == id }, scopes = state.scopes - id)
}
