package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.data.profile.ProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Recent IPTV live channels, kept per profile and per playlist account. */
@Singleton
class RecentIptvStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileStore,
) {
    private val prefs = context.getSharedPreferences("recent_iptv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LastChannel.serializer())

    fun load(accountKey: String): List<LastChannel> {
        val raw = prefs.getString(key(accountKey), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun remember(accountKey: String, channel: LastChannel): List<LastChannel> {
        if (channel.reference.isBlank() || accountKey.isBlank()) return load(accountKey)
        val next = (listOf(channel) + load(accountKey).filter { it.reference != channel.reference }).take(MAX)
        prefs.edit().putString(key(accountKey), json.encodeToString(serializer, next)).apply()
        return next
    }

    fun clear(accountKey: String) {
        if (accountKey.isBlank()) return
        prefs.edit().remove(key(accountKey)).apply()
    }

    /** Hotel cleanup: every recent list of [profileUuid] (all playlists), nobody else's. */
    fun clearProfile(profileUuid: String) {
        if (profileUuid.isBlank()) return
        val keys = prefs.all.keys.filter { it.startsWith("$profileUuid|") }
        if (keys.isEmpty()) return
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    private fun key(accountKey: String): String =
        "${profiles.state.value.activeUuid}|$accountKey"

    private companion object {
        const val MAX = 12
    }
}
