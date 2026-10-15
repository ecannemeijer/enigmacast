package com.enigmacast.data.enigmasync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileRevision(val updatedMs: Long, val changeId: String, val dirty: Boolean)

/** Local revision of each profile's settings, so a login only applies a newer server copy. */
@Singleton
class ProfileSyncMeta @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True while a server snapshot is being written, so that write is not uploaded again. */
    @Volatile var applying: Boolean = false

    /** Fingerprint of the snapshot just applied. The same local read is not treated as an edit. */
    @Volatile var quietFingerprint: String? = null

    fun revision(uuid: String): ProfileRevision? {
        val changeId = prefs.getString(changeKey(uuid), null) ?: return null
        return ProfileRevision(prefs.getLong(msKey(uuid), 0L), changeId, prefs.getBoolean(dirtyKey(uuid), false))
    }

    fun markDirty(uuid: String, now: Long = System.currentTimeMillis()): ProfileRevision {
        val changeId = "$now-${java.util.UUID.randomUUID()}"
        prefs.edit().putLong(msKey(uuid), now).putString(changeKey(uuid), changeId).putBoolean(dirtyKey(uuid), true).apply()
        return ProfileRevision(now, changeId, true)
    }

    fun accept(uuid: String, updatedMs: Long, changeId: String) {
        prefs.edit().putLong(msKey(uuid), updatedMs).putString(changeKey(uuid), changeId).putBoolean(dirtyKey(uuid), false).apply()
    }

    fun clearDirty(uuid: String) {
        prefs.edit().putBoolean(dirtyKey(uuid), false).apply()
    }

    private fun msKey(uuid: String) = "$uuid|ms"
    private fun changeKey(uuid: String) = "$uuid|id"
    private fun dirtyKey(uuid: String) = "$uuid|dirty"

    private companion object {
        const val PREFS = "profile_sync_meta"
    }
}
