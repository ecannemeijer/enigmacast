package com.enigmacast.domain.sync

/** What a server or client does with one incoming change. */
enum class SyncApply { ACCEPT, REPLAY, REJECT }

/**
 * A change is applied only when it is newer than the stored one.
 * The same change id is a replay and must not write again.
 * Rewinding or restarting a film is a newer [incomingMs], so it wins.
 */
object SyncConflict {
    fun decide(storedMs: Long?, storedChange: String?, incomingMs: Long, incomingChange: String): SyncApply {
        if (storedChange != null && storedChange == incomingChange) return SyncApply.REPLAY
        if (storedMs == null) return SyncApply.ACCEPT
        if (incomingMs > storedMs) return SyncApply.ACCEPT
        if (incomingMs == storedMs && incomingChange > storedChange.orEmpty()) return SyncApply.ACCEPT
        return SyncApply.REJECT
    }
}
