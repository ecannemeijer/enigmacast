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

class TorrentNativeTest {
    @Test
    fun namesTheAbi() {
        assertEquals("arm64-v8a", TorrentNative.abiOfLibDir("arm64"))
        assertEquals("armeabi-v7a", TorrentNative.abiOfLibDir("arm"))
        assertEquals("x86", TorrentNative.abiOfLibDir("x86"))
        assertNull(TorrentNative.abiOfLibDir("mips"))
        assertEquals("armeabi-v7a", TorrentNative.abiOfEntry("lib/armeabi-v7a/libjlibtorrent-2.0.12.9.so"))
        assertNull(TorrentNative.abiOfEntry("lib/x86/libavcodec.so"))
        assertNull(TorrentNative.abiOfEntry("assets/lib/x86/libjlibtorrent.so"))
        // The packaged file is lib<name>.so for the first name tried.
        assertEquals("jlibtorrent-${TorrentNative.VERSION}", TorrentNative.LIBRARY_NAMES.first())
    }
}

class TorrentInfoTest {
    @Test
    fun ratingOnCards() {
        val page = TorrentTmdb.parsePage("""{"results":[{"id":1,"title":"A","vote_average":7.8,"backdrop_path":"/b.jpg"},{"id":2,"title":"B","vote_average":0}]}""", TorrentKind.MOVIE)
        assertEquals(7.8, page.items[0].rating, 0.001)
        assertEquals(0.0, page.items[1].rating, 0.001)
        val film = com.enigmacast.ui.torrent.TorrentCards.film(page.items[0])
        assertEquals(7.8, film.movie.voteAverage, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", film.movie.backdropPath)
        // Full TMDB details win, card data fills the gaps.
        val details = com.enigmacast.domain.films.TmdbMovie(id = 1, title = "A", runtimeMin = 120, genres = listOf("Drama"))
        val merged = com.enigmacast.ui.torrent.TorrentCards.film(page.items[0], details).movie
        assertEquals(120, merged.runtimeMin)
        assertEquals(listOf("Drama"), merged.genres)
        assertEquals(7.8, merged.voteAverage, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", merged.backdropPath)
    }

    @Test
    fun seriesPlayStartsAtTheChosenSeason() {
        val seasons = listOf(com.enigmacast.domain.torrent.TmdbSeason(1, "S1", 8), com.enigmacast.domain.torrent.TmdbSeason(2, "S2", 10))
        val eps = listOf(com.enigmacast.domain.torrent.TmdbEpisode(1, "Pilot", null, ""), com.enigmacast.domain.torrent.TmdbEpisode(2, "Two", null, ""))
        assertEquals(2 to 1, TorrentTmdb.playTarget(seasons, 2, eps))
        assertEquals(1 to 1, TorrentTmdb.playTarget(seasons, null, emptyList()))
        assertEquals(1 to 1, TorrentTmdb.playTarget(seasons, 9, eps))
        assertNull(TorrentTmdb.playTarget(emptyList(), 1, eps))
    }
}
