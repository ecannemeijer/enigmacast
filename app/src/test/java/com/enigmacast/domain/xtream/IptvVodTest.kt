package com.enigmacast.domain.xtream

import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.data.xtream.IptvVod
import com.enigmacast.domain.films.TmdbMovie
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvVodTest {
    private val item = XtreamItem("7", "NL: The Matrix (1999)", XtreamSection.MOVIES, "3", icon = "http://cover.invalid/m.jpg")

    @Test fun titleAndYearFromPlaylistName() {
        assertEquals("The Matrix" to 1999, IptvVod.title(item.name))
        assertEquals("Dune", IptvVod.title("[4K] Dune").first)
    }

    @Test fun playlistDataFillsTheGaps() {
        val info = XtreamVodInfo("Neo wakes up.", 8.1, 1999, "Action, Sci-Fi", null, "http://b.invalid/x.jpg", 136, "Wachowski", 603)
        val alone = IptvVod.merge(item, null, info)!!
        assertEquals("Neo wakes up.", alone.overview)
        assertEquals("http://cover.invalid/m.jpg", alone.posterPath)
        assertEquals(listOf("Action", "Sci-Fi"), alone.genres)
        val tmdb = IptvVod.merge(item, TmdbMovie(id = 603, title = "The Matrix", posterPath = "/p.jpg"), info)!!
        assertEquals("/p.jpg", tmdb.posterPath)
        assertEquals("Neo wakes up.", tmdb.overview)
    }

    @Test fun fullUrlsPassThrough() {
        assertEquals("http://cover.invalid/m.jpg", TmdbJson.image("http://cover.invalid/m.jpg", "w342"))
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", TmdbJson.image("/p.jpg", "w342"))
        assertNull(TmdbJson.image("", "w342"))
    }

    @Test fun vodInfoParses() {
        val body = """{"info":{"plot":"P","rating":"7.5","releasedate":"2010-07-16","backdrop_path":["http://b.invalid/1.jpg"],"duration_secs":"8880","tmdb_id":"27205"}}"""
        val info = XtreamJson.vodInfo(body)!!
        assertEquals(2010, info.year); assertEquals(148, info.runtimeMin); assertEquals("http://b.invalid/1.jpg", info.backdrop); assertEquals(27205, info.tmdbId)
        Json.parseToJsonElement(body)
    }
}
