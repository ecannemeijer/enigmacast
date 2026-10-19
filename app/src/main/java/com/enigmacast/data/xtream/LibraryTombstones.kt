package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.domain.sync.LibraryMerge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When a favorite or resume position was removed, per profile and record, so an older copy
 * from another device cannot bring it back ([LibraryMerge]). Called from IO only.
 */
@Singleton
class LibraryTombstones @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences("library_tombstones", Context.MODE_PRIVATE).also { prune(it) }
    }

    private fun key(profileId: String, entity: String, recordKey: String) = "$profileId|$entity|$recordKey"

    fun get(profileId: String, entity: String, recordKey: String): Long? =
        prefs.getLong(key(profileId, entity, recordKey), -1L).takeIf { it >= 0 }

    fun put(profileId: String, entity: String, recordKey: String, deletedMs: Long) {
        val k = key(profileId, entity, recordKey)
        if (prefs.getLong(k, -1L) >= deletedMs) return
        prefs.edit().putLong(k, deletedMs).apply()
    }

    /** A newer local save of the same record. */
    fun clear(profileId: String, entity: String, recordKey: String) {
        val k = key(profileId, entity, recordKey)
        if (prefs.contains(k)) prefs.edit().remove(k).apply()
    }

    fun clearProfile(profileId: String) {
        val stale = prefs.all.keys.filter { it.startsWith("$profileId|") }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private fun prune(p: android.content.SharedPreferences) {
        val cutoff = System.currentTimeMillis() - LibraryMerge.TOMBSTONE_KEEP_MS
        val old = p.all.filter { (_, v) -> (v as? Long ?: 0L) < cutoff }.keys
        if (old.isNotEmpty()) p.edit().apply { old.forEach { remove(it) } }.apply()
    }
}
