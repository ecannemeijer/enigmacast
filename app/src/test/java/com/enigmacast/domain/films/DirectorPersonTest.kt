package com.enigmacast.domain.films

import com.enigmacast.data.tmdb.TmdbJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorPersonTest {
    private val director = """
        {"id":525,"name":"Christopher Nolan","known_for_department":"Directing",
         "combined_credits":{
           "cast":[{"media_type":"movie","id":9,"title":"Cameo","character":"Man","popularity":1.0}],
           "crew":[
             {"media_type":"movie","id":27205,"title":"Inception","job":"Director","popularity":90.0},
             {"media_type":"movie","id":27205,"title":"Inception","job":"Writer","popularity":90.0},
             {"media_type":"movie","id":155,"title":"The Dark Knight","job":"Producer","popularity":80.0},
             {"media_type":"tv","id":7,"name":"A Series","job":"Director","popularity":5.0,"episode_count":2},
             {"media_type":"movie","id":1124,"title":"The Prestige","job":"Director","popularity":50.0}
           ]}}
    """.trimIndent()

    @Test
    fun directedComesFromCrewDirectorOnly() {
        val person = TmdbJson.parsePerson(director)!!
        assertEquals(listOf("movie:27205", "movie:1124", "tv:7"), person.directed.map { it.key })
        assertEquals(listOf("movie:9"), person.credits.map { it.key })
        assertTrue(person.isDirector)
    }

    @Test
    fun directorPageLeadsWithDirectedActorPageWithActing() {
        val person = TmdbJson.parsePerson(director)!!
        assertEquals(listOf(true, false), PersonCredits.rows(person, asDirector = false).map { it.first })
        val actor = person.copy(knownFor = "Acting")
        assertEquals(listOf(false, true), PersonCredits.rows(actor, asDirector = false).map { it.first })
        assertEquals(listOf(true, false), PersonCredits.rows(actor, asDirector = true).map { it.first })
        assertEquals(listOf(false), PersonCredits.rows(actor.copy(directed = emptyList()), asDirector = true).map { it.first })
    }

    @Test
    fun searchFindsDirectorsAndMarksThem() {
        val body = """{"results":[
            {"id":1,"name":"Some Writer","known_for_department":"Writing","popularity":99.0},
            {"id":525,"name":"Christopher Nolan","known_for_department":"Directing","popularity":10.0},
            {"id":2,"name":"An Actor","known_for_department":"Acting","popularity":20.0}]}"""
        val people = TmdbJson.parsePeople(body)
        assertEquals(listOf(2, 525, 1), people.map { it.id })
        assertTrue(people[1].director)
        assertFalse(people[0].director)
    }
}
