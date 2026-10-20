package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hans 13E/19.2E/23.5E/28.2E: the receiver must get bouquets.tv/.radio in Hans' order. */
class BouquetOrderImportTest {
    private fun line(f: String, radio: Boolean = false) =
        "#SERVICE 1:7:${if (radio) 2 else 1}:0:0:0:0:0:0:0:FROM BOUQUET \"$f\" ORDER BY bouquet"

    private val tvOrder = listOf(
        "userbouquet.gemaakt__datum_.tv",
        "userbouquet.nederland_cd___favo_buitenland.tv",
        "userbouquet.belgi_.tv",
        "userbouquet.duitsland.tv",
        "userbouquet.engeland.tv",
        "userbouquet.film.tv",
        "userbouquet.san_marino.tv",
    )
    private val radioOrder = listOf("userbouquet.nederland.radio", "userbouquet.belgi_.radio")
    // In the Hans folder but not in its bouquets.tv: Enigma2 would put these on top.
    private val orphans = listOf("userbouquet.stream_nl_nationaal_politiek.tv", "userbouquet.stream_abgazi_.tv", "userbouquet.samsung_tv_plus__gb_.tv")
    private val tv = (listOf("#NAME User - Bouquets (TV)") + tvOrder.map { line(it) }).joinToString("\n", postfix = "\n")
    private val radio = (listOf("#NAME User - Bouquets (Radio)") + radioOrder.map { line(it, true) }).joinToString("\n", postfix = "\n")

    private fun pkg(): Map<String, ByteArray> {
        // Download order is whatever parallel requests give: deliberately scrambled.
        val files = LinkedHashMap<String, ByteArray>()
        (orphans + tvOrder.reversed() + radioOrder.reversed()).sortedBy { it.hashCode() }.forEach { files[it] = "#NAME $it\n".toByteArray() }
        files["bouquets.radio"] = radio.toByteArray()
        files["lamedb"] = byteArrayOf(1)
        files["bouquets.tv"] = tv.toByteArray()
        return files
    }

    @Test fun bouquetsTvAndRadioAreWrittenVerbatim() {
        val plan = ImportPlans.settingsImport(pkg(), listOf("bouquets.tv", "lamedb"), "#NAME x\n")
        assertArrayEquals(tv.toByteArray(), plan.uploads.getValue("bouquets.tv"))
        assertArrayEquals(radio.toByteArray(), plan.uploads.getValue("bouquets.radio"))
        assertEquals(tvOrder, BouquetLinks.referenced(String(plan.uploads.getValue("bouquets.tv"))))
    }

    @Test fun unlinkedUserbouquetsAreNotWrittenAndRemovedFromTheBox() {
        val box = listOf("bouquets.tv", "lamedb") + orphans + "userbouquet.favourites.tv"
        val plan = ImportPlans.settingsImport(pkg(), box, "#NAME x\n")
        orphans.forEach { assertFalse(it, it in plan.uploads) }
        assertTrue((tvOrder + radioOrder).all { it in plan.uploads })
        assertEquals(orphans + "userbouquet.favourites.tv", plan.deletions)
    }

    @Test fun iptvBouquetsOnTheBoxGoAtTheEndInOrder() {
        val iptv = listOf("userbouquet.enigmacast_iptv_news.tv", "userbouquet.enigmacast_iptv_kids.tv")
        val plan = ImportPlans.settingsImport(pkg(), listOf("bouquets.tv") + iptv, BouquetsTvMerge.merge("#NAME x\n", iptv))
        assertEquals(tvOrder + iptv, BouquetLinks.referenced(String(plan.uploads.getValue("bouquets.tv"))))
        assertTrue(plan.deletions.none { it in iptv })
    }

    @Test fun subBouquetsAndMissingIndexesStayLinked() {
        val files = mapOf(
            "bouquets.tv" to (line("userbouquet.main.tv") + "\n").toByteArray(),
            "userbouquet.main.tv" to ("#NAME main\n" + line("subbouquet.x.tv") + "\n" + line("userbouquet.sub.tv") + "\n").toByteArray(),
            "userbouquet.sub.tv" to byteArrayOf(1),
            "userbouquet.loose.tv" to byteArrayOf(2),
            "userbouquet.any.radio" to byteArrayOf(3),
        )
        assertEquals(setOf("userbouquet.main.tv", "userbouquet.sub.tv", "userbouquet.any.radio"), BouquetLinks.linked(files))
    }
}
