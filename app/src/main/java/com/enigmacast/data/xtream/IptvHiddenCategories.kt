package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.domain.profile.ProfileRules
import com.enigmacast.domain.xtream.XtreamSection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Hidden IPTV category ids per profile, source and section (app-private prefs, ids only). */
@Singleton
class IptvHiddenCategories @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileStore,
) {
    private val prefs = context.getSharedPreferences("iptv_hidden_categories", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()
    /** Only hidden channels, own order, picks or merge options changed (not the categories). */
    private val _channelChanges = MutableStateFlow(0)
    val channelChanges: StateFlow<Int> = _channelChanges.asStateFlow()
    private var channelOnly = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            profiles.state.map { it.activeId }.distinctUntilChanged().drop(1).collect { _changes.value++ }
        }
    }

    private fun key(source: String, section: XtreamSection) =
        ProfileRules.scopedKey(profiles.activeId, "$source/${section.name}")

    fun hidden(source: String, section: XtreamSection): Set<String> = prefs.getStringSet(key(source, section), emptySet()).orEmpty().toSet()

    fun export(profileId: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        prefs.all.keys.forEach { stored ->
            val plain = ProfileRules.unscope(profileId, stored) ?: return@forEach
            val ids = prefs.getStringSet(stored, emptySet()).orEmpty()
            if (ids.isNotEmpty()) out[plain] = ids.sorted()
        }
        return out.toSortedMap()
    }

    /** Replaces this profile's hidden categories with [values]. Keys are `source/section`. */
    fun import(profileId: String, values: Map<String, List<String>>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { ProfileRules.unscope(profileId, it) != null }.forEach { editor.remove(it) }
        values.forEach { (plain, ids) ->
            if (ids.isNotEmpty()) editor.putStringSet(ProfileRules.scopedKey(profileId, plain), ids.toSet())
        }
        editor.apply()
        if (profileId == profiles.activeId) _changes.value++
    }

    fun set(source: String, section: XtreamSection, ids: Set<String>) {
        prefs.edit().apply { if (ids.isEmpty()) remove(key(source, section)) else putStringSet(key(source, section), ids) }.apply()
        _changes.value++
    }

    /** Drops every hidden-category entry that belongs to [profileId] (the local profile id, not the uuid). */
    fun purge(profileId: String) {
        if (profileId == com.enigmacast.domain.profile.ProfileState.DEFAULT_ID) return
        val prefix = "$profileId|"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
        _changes.value++
    }

    // --- IPTV channels: hidden, own order, picked variant and the merge options. They live in the
    // same file under their own keys, so they sync with the profile like hidden categories do.
    // Keys never hold a stream address: `category|normalized name` (see ChannelVariants.key).

    private fun channelKey(source: String, kind: String) = ProfileRules.scopedKey(profiles.activeId, "$source/$kind")
    private fun read(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    private fun write(key: String, values: Set<String>) {
        prefs.edit().apply { if (values.isEmpty()) remove(key) else putStringSet(key, values) }.apply()
        channelOnly = true
        _channelChanges.value++
        _changes.value++
    }

    /** Read once by the IPTV screen: was the last change only about channels? */
    fun takeChannelOnly(): Boolean = channelOnly.also { channelOnly = false }

    /** Hidden channels of [source] as key → shown name. */
    fun hiddenChannels(source: String): Map<String, String> =
        read(channelKey(source, HIDDEN)).associate { entry ->
            val cut = entry.lastIndexOf(SEP)
            if (cut < 0) entry to entry else entry.substring(0, cut) to entry.substring(cut + 1)
        }

    fun hideChannel(source: String, key: String, name: String) {
        val all = read(channelKey(source, HIDDEN)).filterNot { it.startsWith(key + SEP) || it == key }.toSet()
        write(channelKey(source, HIDDEN), all + (key + SEP + name.replace(SEP, ' ')))
    }

    fun showChannel(source: String, key: String) =
        write(channelKey(source, HIDDEN), read(channelKey(source, HIDDEN)).filterNot { it.startsWith(key + SEP) || it == key }.toSet())

    fun showAllChannels(source: String) = write(channelKey(source, HIDDEN), emptySet())

    /** Every source that has hidden channels in the active profile. */
    fun sourcesWithHiddenChannels(): List<String> {
        val suffix = "/$HIDDEN"
        return prefs.all.keys.mapNotNull { ProfileRules.unscope(profiles.activeId, it) }
            .filter { it.endsWith(suffix) && read(ProfileRules.scopedKey(profiles.activeId, it)).isNotEmpty() }
            .map { it.removeSuffix(suffix) }.sorted()
    }

    /** The own order of a category: channel keys top to bottom (empty = the provider's order). */
    fun order(source: String, categoryId: String): List<String> =
        read(channelKey(source, "$ORDER$categoryId")).mapNotNull { entry ->
            val cut = entry.indexOf(SEP)
            entry.substring(0, cut.coerceAtLeast(0)).toIntOrNull()?.let { it to entry.substring(cut + 1) }
        }.sortedBy { it.first }.map { it.second }

    fun setOrder(source: String, categoryId: String, keys: List<String>) =
        write(channelKey(source, "$ORDER$categoryId"), keys.withIndex().map { "${it.index.toString().padStart(5, '0')}$SEP${it.value}" }.toSet())

    /** Drops every own order of the active profile. */
    fun resetOrders() {
        val editor = prefs.edit()
        prefs.all.keys.filter { stored -> ProfileRules.unscope(profiles.activeId, stored)?.contains("/$ORDER") == true }.forEach { editor.remove(it) }
        editor.apply()
        channelOnly = true
        _channelChanges.value++
        _changes.value++
    }

    fun hasOrders(): Boolean = prefs.all.keys.any { ProfileRules.unscope(profiles.activeId, it)?.contains("/$ORDER") == true }

    /** Picked variants of [source]: channel key → variant label. */
    fun picks(source: String): Map<String, String> = read(channelKey(source, PICK)).associate { entry ->
        val cut = entry.indexOf(PICK_SEP)
        if (cut < 0) entry to "" else entry.substring(0, cut) to entry.substring(cut + PICK_SEP.length)
    }

    fun setPick(source: String, key: String, label: String?) {
        val rest = read(channelKey(source, PICK)).filterNot { it.startsWith(key + PICK_SEP) }.toSet()
        write(channelKey(source, PICK), if (label == null) rest else rest + (key + PICK_SEP + label))
    }

    /** Merge channels with several qualities (default on). */
    fun mergeEnabled(): Boolean = OFF !in read(channelKey(OPTIONS, OPTIONS))
    fun setMergeEnabled(on: Boolean) = write(channelKey(OPTIONS, OPTIONS), options(merge = on, order = qualityOrder()))

    fun qualityOrder(): com.enigmacast.domain.xtream.ChannelVariants.QualityOrder {
        val raw = read(channelKey(OPTIONS, OPTIONS)).firstOrNull { it.startsWith(QUALITY) }?.removePrefix(QUALITY)
        return com.enigmacast.domain.xtream.ChannelVariants.QualityOrder.entries.firstOrNull { it.name == raw }
            ?: com.enigmacast.domain.xtream.ChannelVariants.QualityOrder.HIGHEST
    }
    fun setQualityOrder(order: com.enigmacast.domain.xtream.ChannelVariants.QualityOrder) =
        write(channelKey(OPTIONS, OPTIONS), options(merge = mergeEnabled(), order = order))

    private fun options(merge: Boolean, order: com.enigmacast.domain.xtream.ChannelVariants.QualityOrder): Set<String> =
        setOfNotNull(OFF.takeIf { !merge }, (QUALITY + order.name).takeIf { order != com.enigmacast.domain.xtream.ChannelVariants.QualityOrder.HIGHEST })

    private companion object {
        const val HIDDEN = "ch.hidden"
        const val ORDER = "ch.order."
        const val PICK = "ch.pick"
        const val OPTIONS = "ch.options"
        const val OFF = "merge=off"
        const val QUALITY = "quality="
        const val SEP = '\t'
        const val PICK_SEP = "\t=>"
    }
}
