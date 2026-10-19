package com.enigmacast.domain.sync

import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.xtream.IptvLibraryRows
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMergeTest {
    private val up = "upsert"
    private val del = "delete"

    @Test
    fun anOlderCopyCannotBringARemovedItemBack() {
        // Removed here at 2000; another device's progress from 1500 is pulled afterwards.
        assertEquals(LibraryMergeResult.IGNORE, LibraryMerge.decide(null, 2000, 1500, "b", up))
        // Same time as the removal: the removal wins.
        assertEquals(LibraryMergeResult.IGNORE, LibraryMerge.decide(null, 2000, 2000, "zzz", up))
    }

    @Test
    fun watchingAgainAfterTheRemovalWins() {
        assertEquals(LibraryMergeResult.UPSERT, LibraryMerge.decide(null, 2000, 2500, "b", up))
    }

    @Test
    fun aRemovalFromAnotherDeviceDeletesAnOlderRow() {
        assertEquals(LibraryMergeResult.DELETE, LibraryMerge.decide(1000, null, 2000, "a", del))
        assertEquals(LibraryMergeResult.IGNORE, LibraryMerge.decide(3000, null, 2000, "a", del))
        assertEquals(2000L, LibraryMerge.tombstoneAfterDelete(null, 2000))
        assertEquals(2500L, LibraryMerge.tombstoneAfterDelete(2500, 2000))
    }

    @Test
    fun mergeOrderDoesNotMatter() {
        // Upsert 1500, delete 2000, upsert 1800 in any order ends deleted; with a 2500 upsert it ends present.
        fun run(changes: List<Pair<Long, String>>): Boolean {
            var row: Long? = null
            var tomb: Long? = null
            changes.forEachIndexed { i, (ms, op) ->
                when (LibraryMerge.decide(row, tomb, ms, "c$i", op)) {
                    LibraryMergeResult.UPSERT -> { row = ms; tomb = null }
                    LibraryMergeResult.DELETE -> { row = null; tomb = LibraryMerge.tombstoneAfterDelete(tomb, ms) }
                    LibraryMergeResult.IGNORE -> Unit
                }
            }
            return row != null
        }
        val base = listOf(1500L to up, 2000L to del, 1800L to up)
        for (order in permutations(base)) assertEquals(false, run(order))
        for (order in permutations(base + (2500L to up))) assertEquals(true, run(order))
    }

    @Test
    fun noTombstoneKeepsTheOldRules() {
        assertEquals(LibraryMergeResult.UPSERT, LibraryMerge.decide(null, null, 1, "a", up))
        assertEquals(LibraryMergeResult.UPSERT, LibraryMerge.decide(1000, null, 2000, "a", up))
        assertEquals(LibraryMergeResult.IGNORE, LibraryMerge.decide(3000, null, 2000, "a", up))
    }

    @Test
    fun removingASeriesCardRemovesEveryEpisodeOfIt() {
        fun ep(id: String, series: String?, source: String = "acc") =
            IptvResumeRow(source, "e", id, 1, 2, 3, "t", null, "", "", "", series, null, 1, 1, "p")
        val rows = listOf(ep("e5", "s1"), ep("e4", "s1"), ep("x1", "s2"), ep("e3", "s1", source = "other"))
        assertEquals(listOf("e" to "e5", "e" to "e4"), IptvLibraryRows.continueGroup(rows, "acc", "e", "e5"))
        assertEquals(listOf("m" to "f1"), IptvLibraryRows.continueGroup(rows, "acc", "m", "f1"))
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list) else list.indices.flatMap { i ->
            permutations(list.filterIndexed { j, _ -> j != i }).map { listOf(list[i]) + it }
        }
}
