package com.enigmacast.data.tmdb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The Home bouquet choice per receiver key. Missing = all TV bouquets. */
@Singleton
class HomeBouquetStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
) {
    private val prefs = context.getSharedPreferences("home_bouquets", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    /** Bumps on every change, so screens can reload. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun selected(receiverKey: String): Set<String>? =
        if (prefs.contains(key(receiverKey))) prefs.getStringSet(key(receiverKey), emptySet())!!.toSet() else null

    fun set(receiverKey: String, selection: Set<String>?) {
        val cleared = prefs.getStringSet(clearedKey(profiles.activeId), emptySet())!!.toMutableSet()
        if (selection == null) cleared += receiverKey else cleared -= receiverKey
        prefs.edit().apply {
            if (selection == null) remove(key(receiverKey)) else putStringSet(key(receiverKey), selection)
            putStringSet(clearedKey(profiles.activeId), cleared)
        }.apply()
        _changes.value++
    }

    /** Explicit picks for [profileId]. Receivers left at "all bouquets" without a visit are omitted. */
    fun export(profileId: String): com.enigmacast.domain.profile.HomeBouquetCloud {
        val selected = LinkedHashMap<String, List<String>>()
        prefs.all.keys.forEach { stored ->
            if (!stored.startsWith(SEL)) return@forEach
            val receiverKey = com.enigmacast.domain.profile.ProfileRules.unscope(profileId, stored.removePrefix(SEL)) ?: return@forEach
            selected[receiverKey] = prefs.getStringSet(stored, emptySet()).orEmpty().sorted()
        }
        val all = prefs.getStringSet(clearedKey(profileId), emptySet()).orEmpty().filter { it !in selected }.sorted()
        return com.enigmacast.domain.profile.HomeBouquetCloud(selected, all)
    }

    /** Writes another device's bouquet picks for this profile. Does not touch receivers that are absent from [cloud]. */
    fun import(profileId: String, cloud: com.enigmacast.domain.profile.HomeBouquetCloud) {
        val cleared = prefs.getStringSet(clearedKey(profileId), emptySet())!!.toMutableSet()
        val editor = prefs.edit()
        cloud.selected.forEach { (receiverKey, ids) ->
            editor.putStringSet(storedKey(profileId, receiverKey), ids.toSet())
            cleared -= receiverKey
        }
        cloud.all.forEach { receiverKey ->
            editor.remove(storedKey(profileId, receiverKey))
            cleared += receiverKey
        }
        editor.putStringSet(clearedKey(profileId), cleared)
        editor.apply()
        if (profileId == profiles.activeId) _changes.value++
    }

    /** Per profile; the default profile keeps the key from before profiles. */
    private fun key(receiverKey: String) = storedKey(profiles.activeId, receiverKey)

    private fun storedKey(profileId: String, receiverKey: String) =
        SEL + com.enigmacast.domain.profile.ProfileRules.scopedKey(profileId, receiverKey)

    private fun clearedKey(profileId: String) = "cleared|$profileId"

    /** A profile switch changes the selection: let Home recompute. */
    fun profileChanged() { _changes.value++ }

    /** Removes bouquet picks stored under a non-default profile id. */
    fun purge(profileId: String) {
        if (profileId == com.enigmacast.domain.profile.ProfileState.DEFAULT_ID) return
        val marker = "|" + profileId + "|"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.contains(marker) }.forEach { editor.remove(it) }
        editor.apply()
        _changes.value++
    }

    private companion object {
        const val SEL = "sel|"
    }
}
