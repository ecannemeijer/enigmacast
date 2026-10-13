package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heads of the real playlists (Free-TV, iptv-org categories / countries), end to end to the files on the box. */
class RealPlaylistTest {
    private fun load(name: String) = javaClass.classLoader!!.getResource("m3u/$name")!!.readText()

    private fun checkAll(name: String) {
        val entries = M3uParser.parse(load(name))
        assertTrue(name, entries.size >= 4)
        entries.forEach { assertTrue(it.url, it.url.startsWith("http")) }
        val groups = M3uParser.groupCounts(entries).keys.toList()
        assertFalse(name, groups == listOf(M3uParser.NO_GROUP))
        val bouquets = IptvBouquets.build(entries, groups)
        bouquets.forEach { b ->
            assertTrue(b.fileName, IptvBouquets.isIptvFile(b.fileName))
            val lines = b.content.trimEnd().lines()
            assertTrue(lines[0], lines[0].startsWith("#NAME IPTV · "))
            val services = lines.filter { it.startsWith("#SERVICE ") }
            assertEquals(b.channels, services.size)
            services.forEach { line ->
                val ref = line.removePrefix("#SERVICE ")
                val fields = ref.split(':')
                // 10 numeric fields, then the encoded URL (no raw ':'), then the name.
                assertEquals(line, 12, fields.size)
                assertEquals("4097", fields[0])
                assertTrue(line, fields[10].startsWith("http%3a//") || fields[10].startsWith("https%3a//"))
            }
            assertEquals(services.size, lines.count { it.startsWith("#DESCRIPTION ") })
            assertFalse(b.content.contains('\r'))
        }
        val tv = BouquetsTvMerge.merge("#NAME Bouquets (TV)\n#SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sat.tv\" ORDER BY bouquet\n", bouquets.map { it.fileName })
        val tvLines = tv.trimEnd().lines()
        assertTrue(tvLines[1].contains("userbouquet.sat.tv"))
        assertEquals(bouquets.size + 2, tvLines.size)
    }

    @Test fun freeTv() = checkAll("freetv-sample.m3u8")
    @Test fun iptvOrgCategories() = checkAll("iptvorg-categories-sample.m3u")
    @Test fun iptvOrgCountries() = checkAll("iptvorg-countries-sample.m3u")

    @Test fun prefixIsNotDoubled() {
        assertEquals("IPTV · News", IptvBouquets.bouquetName("News"))
        assertEquals("IPTV · News", IptvBouquets.bouquetName("IPTV · News"))
        assertEquals("IPTV · News", IptvBouquets.bouquetName("IPTV - News"))
    }
}
