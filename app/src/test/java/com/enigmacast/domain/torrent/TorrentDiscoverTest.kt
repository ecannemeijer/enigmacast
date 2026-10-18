package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentDiscoverTest {
    @Test fun popularWithoutGenreUsesThePopularList() {
        assertFalse(TorrentTmdb.needsDiscover(null, TorrentSort.POPULAR))
        assertTrue(TorrentTmdb.needsDiscover(28, TorrentSort.POPULAR))
        assertTrue(TorrentTmdb.needsDiscover(null, TorrentSort.SCORE))
    }

    @Test fun scoreNeedsEnoughVotes() {
        val p = TorrentTmdb.discoverParams(TorrentKind.MOVIE, 28, TorrentSort.SCORE, 2, "nl-NL").toMap()
        assertEquals("vote_average.desc", p["sort_by"])
        assertEquals("200", p["vote_count.gte"])
        assertEquals("28", p["with_genres"])
        assertEquals("2", p["page"])
        assertEquals("nl-NL", p["language"])
    }

    @Test fun yearUsesTheDateOfTheKind() {
        assertEquals("primary_release_date.desc", TorrentTmdb.discoverParams(TorrentKind.MOVIE, null, TorrentSort.YEAR, 1, "en").toMap()["sort_by"])
        val tv = TorrentTmdb.discoverParams(TorrentKind.TV, null, TorrentSort.YEAR, 1, "en").toMap()
        assertEquals("first_air_date.desc", tv["sort_by"])
        assertTrue(tv.containsKey("first_air_date.lte"))
        assertFalse(tv.containsKey("with_genres"))
    }

    @Test fun genresParseSorted() {
        val g = TorrentTmdb.parseGenres("""{"genres":[{"id":35,"name":"Komedie"},{"id":28,"name":"Actie"},{"name":"x"}]}""")
        assertEquals(listOf(TmdbGenre(28, "Actie"), TmdbGenre(35, "Komedie")), g)
        assertEquals(emptyList<TmdbGenre>(), TorrentTmdb.parseGenres(null))
    }
}
