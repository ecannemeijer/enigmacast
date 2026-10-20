package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastStreamTest {
    private val torrent = TorrentStream("Addon\n1080p", "Movie.2024.1080p.mkv\n👤 50", "abcdef0123", 3, null, listOf("udp://t:1/announce"), 50, 2_000_000_000, 1080, "Movie.2024.1080p.mkv")

    @Test fun torrentRoundTrips() {
        val back = LastStream.decode(LastStream.encode(torrent))!!
        assertEquals(torrent.copy(seeders = null), back)
    }

    @Test fun directLinksAreNeverStored() {
        assertNull(LastStream.encode(torrent.copy(url = "https://debrid.example/key123/file.mkv")))
        assertNull(LastStream.encode(torrent.copy(infoHash = null, url = "https://x")))
    }

    @Test fun badTextIsNoStream() {
        assertNull(LastStream.decode(null))
        assertNull(LastStream.decode("{"))
        assertNull(LastStream.decode("{\"fileIdx\":1}"))
    }

    @Test fun keyIsPerEpisode() {
        assertEquals("e:1399:1:2", LastStream.key("e", "1399:1:2"))
    }
}
