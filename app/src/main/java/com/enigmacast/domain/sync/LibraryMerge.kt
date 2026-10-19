package com.enigmacast.domain.sync

/** What a pulled favorite or progress change does on this device. */
enum class LibraryMergeResult { UPSERT, DELETE, IGNORE }

/**
 * Last write wins per record, with removals kept as tombstones (the time of the removal).
 *
 * Without a tombstone a removed row was simply gone, so any older upsert that arrived later
 * (another device's progress pulled after this device removed it, or an older copy pushed
 * by a device that was off) found nothing to compare with and brought the item back. Now
 * the newer of the row and the tombstone is what an incoming change must beat. Ties on time
 * fall back to the change id, as on the server ([SyncConflict]).
 */
object LibraryMerge {
    /** Tombstones older than this are pruned; the server keeps its own for good. */
    const val TOMBSTONE_KEEP_MS = 180L * 24 * 60 * 60 * 1000

    fun decide(rowMs: Long?, tombstoneMs: Long?, incomingMs: Long, incomingChange: String, op: String): LibraryMergeResult {
        val stored = listOfNotNull(rowMs, tombstoneMs).maxOrNull()
        // A tombstone has no change id, so on an equal time the removal wins.
        val storedChange = if (tombstoneMs != null && (rowMs == null || tombstoneMs >= rowMs)) "\uFFFF" else null
        if (SyncConflict.decide(stored, storedChange, incomingMs, incomingChange) != SyncApply.ACCEPT) return LibraryMergeResult.IGNORE
        return if (op == "delete") LibraryMergeResult.DELETE else LibraryMergeResult.UPSERT
    }

    /** Tombstone to keep after applying a pulled delete: the newest removal time seen. */
    fun tombstoneAfterDelete(tombstoneMs: Long?, incomingMs: Long): Long = maxOf(tombstoneMs ?: 0L, incomingMs)
}
