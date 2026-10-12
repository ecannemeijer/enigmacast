package com.enigmacast.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeBouquetsTest {
    private val all = listOf("b1", "b2", "b3")
    private val npo1 = "1:0:19:1B6C:C88:3:EB0000:0:0:0:"

    @Test fun nothingStoredMeansAllBouquets() = assertEquals(all.toSet(), HomeBouquets.effective(null, all))
    @Test fun unknownStoredBouquetsAreDropped() = assertEquals(setOf("b2"), HomeBouquets.effective(setOf("b2", "gone"), all))
    @Test fun emptySelectionStaysEmpty() = assertEquals(emptySet<String>(), HomeBouquets.effective(emptySet(), all))

    @Test fun channelsOnlyFromSelectedBouquetsAndOnceEach() {
        val list = listOf(
            BouquetChannel("b1", npo1, "NPO 1", ""),
            BouquetChannel("b2", "$npo1", "NPO 1 dup", ""),
            BouquetChannel("b2", "1:0:19:1B6D:C88:3:EB0000:0:0:0:", "NPO 2", ""),
            BouquetChannel("b3", "1:0:19:1B6E:C88:3:EB0000:0:0:0:", "NPO 3", ""),
        )
        val result = HomeBouquets.channels(list, setOf("b1", "b2"))
        assertEquals(listOf("NPO 1", "NPO 2"), result.values.map { it.name })
        assertEquals(emptyMap<String, BouquetChannel>(), HomeBouquets.channels(list, emptySet()))
    }

    @Test fun toggleBackToEverythingStoresNull() {
        assertNull(HomeBouquets.toggle(setOf("b1", "b2"), "b3", all))
        assertEquals(setOf("b1", "b3"), HomeBouquets.toggle(setOf("b1", "b2", "b3"), "b2", all))
    }

    @Test fun cachePolicy() {
        val day = 86_400L
        assertEquals(GuideCachePolicy.Decision.CACHE, GuideCachePolicy.decide(3, 100, 200, 0, day))
        assertEquals(GuideCachePolicy.Decision.NETWORK_AND_SYNC, GuideCachePolicy.decide(0, 100, 200, 0, day))
        assertEquals(GuideCachePolicy.Decision.NETWORK, GuideCachePolicy.decide(0, day + 10, day + 100, 0, day))
        assertEquals(GuideCachePolicy.Decision.NETWORK, GuideCachePolicy.decide(0, -500, -100, 0, day))
    }
}
