package com.enigmacast.torrent

import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.torrent.TorrentTmdb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentEngineTest {
    private val names = listOf("sample.mkv", "movie.mkv", "subs.srt", "huge.iso")
    private val sizes = listOf(50L, 4_000L, 1L, 9_000L)

    @Test
    fun choosesTheFile() {
        assertEquals(0, TorrentEngine.chooseFile(0, 4, names::get, sizes::get))
        assertEquals(1, TorrentEngine.chooseFile(null, 4, names::get, sizes::get))
        assertEquals(1, TorrentEngine.chooseFile(9, 4, names::get, sizes::get))
        assertEquals(1, TorrentEngine.chooseFile(null, 2, listOf("a.txt", "b.bin")::get, listOf(1L, 2L)::get))
        assertNotEquals(TorrentEngine.newToken(), TorrentEngine.newToken())
    }

    @Test
    fun parsesTmdb() {
        val page = TorrentTmdb.parsePage(
            """{"page":2,"total_pages":900,"results":[{"id":1,"title":"Film","release_date":"2019-05-01","poster_path":"/p.jpg"},{"id":2},{"id":3,"title":"No date"}]}""",
            TorrentKind.MOVIE,
        )
        assertEquals(2, page.page)
        assertEquals(500, page.totalPages)
        assertEquals(listOf(1, 3), page.items.map(TmdbTitle::id))
        assertEquals(2019, page.items[0].year)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", page.items[0].poster)
        assertNull(page.items[1].year)
        val tv = TorrentTmdb.parsePage("""{"results":[{"id":5,"name":"Show","first_air_date":"2011-04-17"}]}""", TorrentKind.TV)
        assertEquals("Show", tv.items.single().title)
        assertEquals("TV:5", tv.items.single().key)

        assertEquals("tt0944947", TorrentTmdb.parseImdb("""{"imdb_id":"tt0944947"}"""))
        assertNull(TorrentTmdb.parseImdb("""{"imdb_id":null}"""))
        val seasons = TorrentTmdb.parseSeasons("""{"seasons":[{"season_number":2,"episode_count":10,"name":"Season 2"},{"season_number":0,"episode_count":5},{"season_number":1,"episode_count":10},{"season_number":3,"episode_count":0}]}""")
        assertEquals(listOf(1, 2), seasons.map { it.number })
        val eps = TorrentTmdb.parseEpisodes("""{"episodes":[{"episode_number":2,"name":"B"},{"episode_number":1,"name":"A"}]}""")
        assertEquals(listOf("A", "B"), eps.map { it.name })
    }

    @Test
    fun pagesNearTheEnd() {
        assertNull(TorrentTmdb.nextPage(loaded = 1, total = 5, focusedIndex = 3, itemCount = 20, loading = false))
        assertEquals(2, TorrentTmdb.nextPage(loaded = 1, total = 5, focusedIndex = 9, itemCount = 20, loading = false))
        assertNull(TorrentTmdb.nextPage(loaded = 1, total = 5, focusedIndex = 19, itemCount = 20, loading = true))
        assertNull(TorrentTmdb.nextPage(loaded = 5, total = 5, focusedIndex = 99, itemCount = 100, loading = false))
    }
}
