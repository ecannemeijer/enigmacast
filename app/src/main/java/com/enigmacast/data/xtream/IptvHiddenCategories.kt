package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.domain.xtream.XtreamSection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hidden IPTV category ids per source and section (app-private prefs, ids only). */
@Singleton
class IptvHiddenCategories @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("iptv_hidden_categories", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun key(source: String, section: XtreamSection) = "$source/${section.name}"

    fun hidden(source: String, section: XtreamSection): Set<String> = prefs.getStringSet(key(source, section), emptySet()).orEmpty().toSet()

    fun set(source: String, section: XtreamSection, ids: Set<String>) {
        prefs.edit().apply { if (ids.isEmpty()) remove(key(source, section)) else putStringSet(key(source, section), ids) }.apply()
        _changes.value++
    }
}
