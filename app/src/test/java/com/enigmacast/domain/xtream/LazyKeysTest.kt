package com.enigmacast.domain.xtream

import com.enigmacast.ui.xtream.IptvCard
import org.junit.Assert.assertEquals
import org.junit.Test

class LazyKeysTest {
    @Test fun keysAreUniqueEvenForRepeatedOrBlankIds() {
        val keys = LazyKeys.keyed(listOf("1", "2", "1", "", ""), "x") { it }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("x:1", keys[0])
        assertEquals("x:1#2", keys[2])
    }

    /** Regression: the grid's keys came from a second list whose size differed (216 vs 216+). */
    @Test fun cardsCarryTheirOwnKeyAcrossCategoryChanges() {
        fun cat(n: Int, c: String) = (1..n).map { XtreamItem("$it", "Film $it", XtreamSection.MOVIES, c) }
        val first = IptvCard.of(cat(216, "1"))
        val second = IptvCard.of(cat(40, "2"))
        // Every key is read from the card itself: no index into another list.
        first.forEachIndexed { i, card -> assertEquals(card.item, first[i].item); assertEquals("vod:MOVIES:${i + 1}", card.key) }
        assertEquals(216, first.map { it.key }.toSet().size)
        assertEquals(40, second.size)
        val dup = IptvCard.of(listOf(XtreamItem("5", "A", XtreamSection.MOVIES, "1"), XtreamItem("5", "A", XtreamSection.MOVIES, "1")))
        assertEquals(2, dup.map { it.key }.toSet().size)
    }
}
