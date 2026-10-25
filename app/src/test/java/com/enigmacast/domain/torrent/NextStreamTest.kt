package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextStreamTest {
    private fun s(name: String, hash: String?, res: Int, seeders: Int?, url: String? = null) =
        TorrentStream(name, name, hash, 0, url, emptyList(), seeders, null, res, null)

    @Test fun sameTorrentFirst() {
        val prev = s("pack", "ABC", 1080, 10)
        val list = listOf(s("other", "def", 1080, 500), s("pack e2", "abc", 1080, 3))
        assertEquals("pack e2", NextStream.pick(prev, list)?.name)
    }

    @Test fun closestResolutionThenSeeders() {
        val prev = s("old", "x", 1080, 10)
        val list = listOf(s("4k", "a", 2160, 900), s("hd few", "b", 1080, 5), s("hd many", "c", 1080, 80), s("sd", "d", 480, 999))
        assertEquals("hd many", NextStream.pick(prev, list)?.name)
        // No 1080p: the nearest one.
        assertEquals("720", NextStream.pick(prev, listOf(s("4k", "a", 2160, 9), s("720", "b", 720, 1)))?.name)
    }

    @Test fun sameKindAsBefore() {
        val prev = s("link", null, 1080, null, url = "https://x")
        val list = listOf(s("torrent", "a", 1080, 900), s("link2", null, 1080, null, url = "https://y"))
        assertEquals("link2", NextStream.pick(prev, list)?.name)
    }

    @Test fun emptyOrNoPrevious() {
        assertNull(NextStream.pick(s("a", "a", 0, 0), emptyList()))
        assertEquals("first", NextStream.pick(null, listOf(s("first", "a", 720, 1), s("b", "b", 1080, 9)))?.name)
    }
}
