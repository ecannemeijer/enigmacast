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

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            profiles.state.map { it.activeId }.distinctUntilChanged().drop(1).collect { _changes.value++ }
        }
    }

    private fun key(source: String, section: XtreamSection) =
        ProfileRules.scopedKey(profiles.activeId, "$source/${section.name}")

    fun hidden(source: String, section: XtreamSection): Set<String> = prefs.getStringSet(key(source, section), emptySet()).orEmpty().toSet()

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
}
