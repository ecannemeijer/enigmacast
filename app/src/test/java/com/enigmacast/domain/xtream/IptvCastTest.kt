package com.enigmacast.domain.xtream

import com.enigmacast.data.xtream.IptvVod
import com.enigmacast.domain.films.TmdbCastMember
import com.enigmacast.domain.films.TmdbMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvCastTest {
    private val film = XtreamItem("7", "NL: The Matrix (1999)", XtreamSection.MOVIES, "1", "")

    @Test fun vodInfoReadsCastAndTmdbId() {
        val info = XtreamJson.vodInfo("""{"info":{"plot":"x","cast":"Keanu Reeves, Carrie-Anne Moss ,  , Keanu Reeves","tmdb_id":"603"}}""")!!
        assertEquals(listOf("Keanu Reeves", "Carrie-Anne Moss"), info.cast)
        assertEquals(603, info.tmdbId)
    }

    @Test fun actorsKeyAndNoCast() {
        assertEquals(listOf("A B"), XtreamJson.vodInfo("""{"info":{"actors":"A B"}}""")!!.cast)
        assertTrue(XtreamJson.vodInfo("""{"info":{"plot":"x"}}""")!!.cast.isEmpty())
        assertTrue(XtreamJson.castNames("N/A").isEmpty())
    }

    @Test fun playlistCastWhenNoTmdbMatch() {
        val info = XtreamJson.vodInfo("""{"info":{"cast":"Keanu Reeves, Laurence Fishburne"}}""")!!
        val merged = IptvVod.merge(film, null, info)!!
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne"), merged.cast.map { it.name })
        assertTrue(merged.cast.all { it.id == 0 }) // the actor page looks them up by name
    }

    @Test fun tmdbCastWinsAndEmptyTmdbCastIsFilled() {
        val info = XtreamJson.vodInfo("""{"info":{"cast":"Someone Else"}}""")!!
        val tmdb = TmdbMovie(603, "The Matrix", cast = listOf(TmdbCastMember("Keanu Reeves", "Neo", null, 6384)))
        assertEquals(listOf(6384), IptvVod.merge(film, tmdb, info)!!.cast.map { it.id })
        val bare = TmdbMovie(603, "The Matrix")
        assertEquals(listOf("Someone Else"), IptvVod.merge(film, bare, info)!!.cast.map { it.name })
    }

    @Test fun oldCachedPayloadWithoutCastIdStillHasCast() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"id":603,"title":"The Matrix","cast":[{"name":"Keanu Reeves","character":"Neo","profilePath":null}]}"""
        val movie = json.decodeFromString(TmdbMovie.serializer(), old)
        assertEquals(1, movie.cast.size)
        assertEquals(0, movie.cast[0].id)
    }
}
