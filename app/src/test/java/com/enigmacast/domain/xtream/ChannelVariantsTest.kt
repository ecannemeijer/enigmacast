package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChannelVariantsTest {
    private fun live(id: String, name: String, archive: Int = 0, cat: String = "it") =
        XtreamItem(id, name, XtreamSection.LIVE, cat, archiveDays = archive, providerId = "p")

    @Test fun normalizeDropsQualityTags() {
        val n = ChannelVariants.normalize("IT - RAI 1 HD")
        assertEquals(n, ChannelVariants.normalize("IT - RAI 1 SD"))
        assertEquals(n, ChannelVariants.normalize("IT: Rai 1 UHD 4K"))
        assertEquals(n, ChannelVariants.normalize("IT | RAI 1 (HEVC) 50fps"))
        assertNotEquals(n, ChannelVariants.normalize("IT - RAI 2 HD"))
    }

    @Test fun qualityScores() {
        assertEquals(4, ChannelVariants.quality("RAI 1 UHD"))
        assertEquals(3, ChannelVariants.quality("RAI 1 FHD"))
        assertEquals(2, ChannelVariants.quality("RAI 1 HD"))
        assertEquals(1, ChannelVariants.quality("RAI 1 SD"))
    }

    @Test fun mergeKeepsOneRowAndPrefersArchive() {
        val list = listOf(live("1", "IT - RAI 1 SD"), live("2", "IT - RAI 1 HD", archive = 3), live("3", "IT - RAI 1 UHD"), live("4", "IT - RAI 2"))
        val m = ChannelVariants.merge(list)
        assertEquals(listOf("2", "4"), m.rows.map { it.id })
        assertEquals(listOf("2", "3", "1"), m.variants.values.single().map { it.id })
    }

    @Test fun mergeOnlyWithinCategory() {
        val m = ChannelVariants.merge(listOf(live("1", "RAI 1 HD", cat = "a"), live("2", "RAI 1 SD", cat = "b")))
        assertEquals(2, m.rows.size)
    }

    @Test fun qualityOrderAndPick() {
        val list = listOf(live("1", "X SD"), live("2", "X HD"), live("3", "X UHD"))
        assertEquals("3", ChannelVariants.merge(list).rows.single().id)
        assertEquals("1", ChannelVariants.merge(list, ChannelVariants.QualityOrder.SAVER).rows.single().id)
        val pick = mapOf(ChannelVariants.key(list[0]) to ChannelVariants.label(list[1]))
        assertEquals("2", ChannelVariants.merge(list, picks = pick).rows.single().id)
    }

    @Test fun backupLast() {
        val m = ChannelVariants.merge(listOf(live("1", "X HD backup"), live("2", "X HD")))
        assertEquals("2", m.rows.single().id)
    }

    @Test fun arrangeHidesAndOrders() {
        val rows = listOf(live("1", "A"), live("2", "B"), live("3", "C"))
        val key = { i: Int -> ChannelVariants.key(rows[i]) }
        assertEquals(listOf("3", "1"), ChannelVariants.arrange(rows, setOf(key(1)), listOf(key(2))).map { it.id })
    }

    @Test fun moveClamps() {
        assertEquals(listOf("b", "a", "c"), ChannelVariants.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "b", "c"), ChannelVariants.move(listOf("a", "b", "c"), 0, -1))
    }
}
