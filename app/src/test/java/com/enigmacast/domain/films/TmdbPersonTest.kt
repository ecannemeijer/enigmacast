package com.enigmacast.domain.films

import com.enigmacast.data.tmdb.TmdbJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbPersonTest {
    private val body = """
        {"id":192,"name":"Morgan Freeman","biography":"An actor.","birthday":"1937-06-01","deathday":null,
         "place_of_birth":"Memphis, Tennessee, USA","profile_path":"/p.jpg","known_for_department":"Acting",
         "combined_credits":{"cast":[
           {"media_type":"movie","id":278,"title":"The Shawshank Redemption","original_title":"The Shawshank Redemption","release_date":"1994-09-23","poster_path":"/s.jpg","character":"Ellis Boyd 'Red' Redding","popularity":80.0,"vote_count":28000,"vote_average":8.7},
           {"media_type":"movie","id":807,"title":"Se7en","release_date":"1995-09-22","character":"Somerset","popularity":60.0,"vote_count":21000},
           {"media_type":"tv","id":1,"name":"The Late Show","first_air_date":"2015-09-08","character":"Self","popularity":200.0,"episode_count":3},
           {"media_type":"tv","id":2,"name":"Through the Wormhole","first_air_date":"2010-06-09","character":"Narrator","popularity":10.0,"episode_count":2},
           {"media_type":"tv","id":2,"name":"Through the Wormhole","first_air_date":"2010-06-09","character":"Host","popularity":10.0,"episode_count":62},
           {"media_type":"movie","title":"No id"}
         ]}}
    """.trimIndent()

    @Test
    fun parsesPersonAndOrdersFilmography() {
        val person = TmdbJson.parsePerson(body)!!
        assertEquals(192, person.id)
        assertEquals("Morgan Freeman", person.name)
        assertEquals("Memphis, Tennessee, USA", person.birthPlace)
        assertNull(person.deathday)
        // Self appearance dropped, series once (the role with most episodes), most popular first.
        assertEquals(listOf("movie:278", "movie:807", "tv:2"), person.credits.map { it.key })
        assertEquals("Host", person.credits.last().character)
        assertEquals(true, person.credits.last().isSeries)
        assertEquals(1994, person.credits.first().year)
    }

    @Test
    fun castMemberIdParsedAndDefaultsToZero() {
        val movie = TmdbJson.parseMovie("""{"id":5,"title":"X","credits":{"cast":[{"id":192,"name":"Morgan Freeman","character":"Red","order":0}]}}""", "NL", "nl-NL")
        assertEquals(192, movie?.cast?.single()?.id)
        assertEquals(0, TmdbCastMember("A", "B", null).id)
    }

    @Test
    fun searchPicksExactName() {
        val search = """{"results":[{"id":1,"name":"Morgan Free"},{"id":192,"name":"Morgan Freeman"}]}"""
        assertEquals(192, TmdbJson.parsePersonSearch(search, "morgan freeman"))
        assertEquals(1, TmdbJson.parsePersonSearch(search, "Someone else"))
        assertNull(TmdbJson.parsePersonSearch("""{"results":[]}""", "x"))
    }

    @Test
    fun age() {
        assertEquals(89, PersonCredits.age("1937-06-01", "2026-10-31"))
        assertEquals(88, PersonCredits.age("1937-11-01", "2026-10-31"))
        assertNull(PersonCredits.age(null, "2026-10-31"))
    }
}
