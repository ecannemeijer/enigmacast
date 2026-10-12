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
class HomeBouquetStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("home_bouquets", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    /** Bumps on every change, so screens can reload. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun selected(receiverKey: String): Set<String>? =
        if (prefs.contains(key(receiverKey))) prefs.getStringSet(key(receiverKey), emptySet())!!.toSet() else null

    fun set(receiverKey: String, selection: Set<String>?) {
        prefs.edit().apply { if (selection == null) remove(key(receiverKey)) else putStringSet(key(receiverKey), selection) }.apply()
        _changes.value++
    }

    private fun key(receiverKey: String) = "sel|$receiverKey"
}
