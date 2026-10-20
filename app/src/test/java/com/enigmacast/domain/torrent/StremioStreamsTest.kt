package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioStreamsTest {
    private val hashA = "a".repeat(40)
    private val hashB = "B".repeat(40)

    @Test
    fun buildsStreamUrls() {
        assertEquals("https://x.example/cfg/stream/movie/tt0111161.json", StremioStreams.url("https://x.example/cfg/", "tt0111161"))
        assertEquals("https://x.example/stream/series/tt0944947:1:2.json", StremioStreams.url("https://x.example", "tt0944947", 1, 2))
        assertNull(StremioStreams.url("https://x.example", "0111161"))
        assertNull(StremioStreams.url("https://x.example", "tt12/../x"))
    }

    @Test
    fun readsAddonTitles() {
        val t = "Movie.2019.2160p.WEB-DL.HDR\n👤 1520 💾 14.3 GB ⚙️ Site"
        assertEquals(1520, StremioStreams.seedersOf(t))
        assertEquals((14.3 * (1L shl 30)).toLong(), StremioStreams.sizeOf(t))
        assertEquals(2160, StremioStreams.resolutionOf(t))
        assertEquals(720, StremioStreams.resolutionOf("Addon\n720p"))
        assertEquals(2160, StremioStreams.resolutionOf("Addon\n4k DV"))
        assertEquals(0, StremioStreams.resolutionOf("Some.Release.CAM"))
        assertEquals(800L shl 20, StremioStreams.sizeOf("💾 800 MB"))
        assertEquals((1.5 * (1L shl 30)).toLong(), StremioStreams.sizeOf("💾 1,5 GB"))
        assertNull(StremioStreams.seedersOf("no seeders"))
        assertNull(StremioStreams.sizeOf("no size"))
    }

    @Test
    fun parsesAddonStreams() {
        val body = """{"streams":[
            {"name":"Addon\n4k HDR","title":"The.Shawshank.Redemption.1994.2160p.UHD.BluRay.x265-HDR\n👤 100 💾 6.91 GB ⚙️ YTS","infoHash":"$hashA","fileIdx":0,"behaviorHints":{"bingeGroup":"addon|4k|HDR","filename":"The.Shawshank.Redemption.1994.2160p.mkv"}},
            {"name":"Addon\n1080p","title":"The.Shawshank.Redemption.1994.1080p.BluRay\n👤 250 💾 2.1 GB ⚙️ ThePirateBay","infoHash":"${"d".repeat(40)}","fileIdx":1}
        ]}"""
        val s = StremioStreams.sorted(StremioStreams.parse(body))
        assertEquals(listOf(2160, 1080), s.map { it.resolution })
        assertEquals(100, s[0].seeders)
        assertEquals((6.91 * (1L shl 30)).toLong(), s[0].sizeBytes)
        assertEquals(0, s[0].fileIdx)
        assertEquals(1, s[1].fileIdx)
        assertEquals("The.Shawshank.Redemption.1994.2160p.mkv", s[0].filename)
        assertEquals("The.Shawshank.Redemption.1994.2160p.UHD.BluRay.x265-HDR", StremioStreams.release(s[0]))
    }

    @Test
    fun parsesAndSortsStreams() {
        val body = """{"streams":[
            {"name":"Addon\n1080p","title":"Rel.1080p\n👤 10 💾 2 GB","infoHash":"$hashA","fileIdx":3,"sources":["tracker:udp://t.example:80/announce","dht:$hashA"],"behaviorHints":{"filename":"Rel.mkv"}},
            {"name":"Addon\n2160p","title":"Rel.2160p\n👤 5 💾 20 GB","infoHash":"$hashB"},
            {"name":"Addon\n1080p","title":"Other.1080p\n👤 99 💾 3 GB","infoHash":"${"c".repeat(40)}"},
            {"name":"Direct 720p","title":"Web","url":"https://cdn.example/v.mp4"},
            {"name":"broken","title":"no hash, no url"},
            {"name":"bad","infoHash":"xyz"}
        ]}"""
        val streams = StremioStreams.parse(body)
        assertEquals(4, streams.size)
        val first = streams.first()
        assertEquals(hashA, first.infoHash)
        assertEquals(3, first.fileIdx)
        assertEquals(listOf("udp://t.example:80/announce"), first.trackers)
        assertEquals("Rel.mkv", first.filename)
        assertEquals(hashB.lowercase(), streams[1].infoHash)
        assertEquals("https://cdn.example/v.mp4", streams[3].url)
        assertNull(streams[3].infoHash)

        val sorted = StremioStreams.sorted(streams)
        assertEquals(listOf(2160, 1080, 1080, 720), sorted.map { it.resolution })
        assertEquals(99, sorted[1].seeders)
        assertEquals("Rel.1080p", StremioStreams.release(first))
        assertEquals(emptyList<TorrentStream>(), StremioStreams.parse("not json"))
    }

    @Test
    fun buildsMagnets() {
        val s = StremioStreams.parse("""{"streams":[{"name":"n","title":"t","infoHash":"$hashA","sources":["tracker:udp://t.example:80/announce"]}]}""").single()
        val magnet = StremioStreams.magnet(s)!!
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:$hashA"))
        assertTrue(magnet.contains("&tr=udp%3A%2F%2Ft.example%3A80%2Fannounce"))
        assertNull(StremioStreams.magnet(s.copy(infoHash = null)))
    }
}
