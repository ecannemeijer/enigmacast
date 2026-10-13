package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BouquetsTvMergeTest {
    private val satellite = """
        #NAME User - Bouquets (TV)
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.favourites.tv" ORDER BY bouquet
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.canal_digitaal.tv" ORDER BY bouquet
    """.trimIndent() + "\n"

    @Test fun appendsAfterSatelliteBouquets() {
        val merged = BouquetsTvMerge.merge(satellite, listOf("userbouquet.enigmacast_iptv_news.tv"))
        val lines = merged.trimEnd().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[1].contains("favourites"))
        assertTrue(lines[2].contains("canal_digitaal"))
        assertEquals(BouquetsTvMerge.line("userbouquet.enigmacast_iptv_news.tv"), lines[3])
    }

    @Test fun idempotentOnRefresh() {
        val files = listOf("userbouquet.enigmacast_iptv_news.tv", "userbouquet.enigmacast_iptv_kids.tv")
        val once = BouquetsTvMerge.merge(satellite, files)
        assertEquals(once, BouquetsTvMerge.merge(once, files))
    }

    @Test fun refreshReplacesOnlyOurLines() {
        val first = BouquetsTvMerge.merge(satellite, listOf("userbouquet.enigmacast_iptv_old.tv"))
        // The user added a bouquet on the box after our IPTV lines.
        val edited = first + "#SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.mine.tv\" ORDER BY bouquet\n"
        val second = BouquetsTvMerge.merge(edited, listOf("userbouquet.enigmacast_iptv_new.tv"))
        val lines = second.trimEnd().lines()
        assertTrue(lines.none { it.contains("enigmacast_iptv_old") })
        assertTrue(lines[3].contains("userbouquet.mine.tv"))
        assertTrue(lines.last().contains("enigmacast_iptv_new"))
    }

    @Test fun emptyListRemovesOurLines() {
        val with = BouquetsTvMerge.merge(satellite, listOf("userbouquet.enigmacast_iptv_x.tv"))
        assertEquals(satellite, BouquetsTvMerge.merge(with, emptyList()))
    }

    @Test fun emptyFileGetsAName() {
        assertEquals("#NAME User - Bouquets (TV)\n", BouquetsTvMerge.merge("", emptyList()))
    }

    @Test fun crlfIsNormalised() {
        val merged = BouquetsTvMerge.merge(satellite.replace("\n", "\r\n"), emptyList())
        assertEquals(satellite, merged)
    }

    @Test fun iptvFilesAreRead() {
        val merged = BouquetsTvMerge.merge(satellite, listOf("userbouquet.enigmacast_iptv_a.tv", "userbouquet.enigmacast_iptv_b.tv"))
        assertEquals(listOf("userbouquet.enigmacast_iptv_a.tv", "userbouquet.enigmacast_iptv_b.tv"), BouquetsTvMerge.iptvFiles(merged))
    }

    @Test fun settingsImportKeepsIptvAndRemovesStaleFiles() {
        val box = listOf("lamedb", "lamedb5", "bouquets.tv", "userbouquet.old.tv", "userbouquet.enigmacast_iptv_news.tv", "userbouquet.favourites.tv")
        val boxTv = BouquetsTvMerge.merge(satellite, listOf("userbouquet.enigmacast_iptv_news.tv"))
        val pkg = mapOf(
            "lamedb" to byteArrayOf(1),
            "bouquets.tv" to satellite.toByteArray(),
            "userbouquet.favourites.tv" to byteArrayOf(2),
            "satellites.xml" to byteArrayOf(3),
            "README.md" to byteArrayOf(4),
        )
        val plan = ImportPlans.settingsImport(pkg, box, boxTv)
        assertEquals(listOf("userbouquet.favourites.tv", "bouquets.tv", "satellites.xml", "lamedb"), plan.uploads.keys.toList())
        assertTrue(String(plan.uploads.getValue("bouquets.tv")).trimEnd().lines().last().contains("enigmacast_iptv_news"))
        assertEquals(listOf("lamedb5", "userbouquet.old.tv"), plan.deletions)
    }

    @Test fun iptvPlanTouchesOnlyOwnFiles() {
        val box = listOf("bouquets.tv", "userbouquet.favourites.tv", "userbouquet.enigmacast_iptv_old.tv")
        val bouquet = IptvBouquets.Bouquet("News", "userbouquet.enigmacast_iptv_news.tv", "#NAME IPTV - News\n", 0)
        val plan = ImportPlans.iptv(listOf(bouquet), box, satellite)
        assertEquals(listOf("userbouquet.enigmacast_iptv_old.tv"), plan.deletions)
        assertArrayEquals("#NAME IPTV - News\n".toByteArray(), plan.uploads.getValue("userbouquet.enigmacast_iptv_news.tv"))
        assertEquals(BouquetsTvMerge.merge(satellite, listOf(bouquet.fileName)), String(plan.uploads.getValue("bouquets.tv")))
    }
}
