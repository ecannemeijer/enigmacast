package com.enigmacast.domain.series

import com.enigmacast.data.openwebif.AutoTimerXml
import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTest {
    private fun ev(title: String, begin: Long, min: Long = 45, desc: String = "", genreId: Int = -1, ref: String = "1:0:19:A:1:2:3:0:0:0:") =
        GuideEvent(1, ref, title, desc, "", begin, min * 60, genreId)

    @Test fun recurringTitleIsSeriesNewsIsNot() {
        val events = listOf(ev("Flikken Maastricht", 0), ev("Flikken Maastricht", 86400), ev("NOS Journaal", 0, 30), ev("NOS Journaal", 3600, 30))
        val c = SeriesDetector.candidates(events) { "NPO 1" to "" }
        assertEquals(listOf("Flikken Maastricht", "Flikken Maastricht"), c.map { it.searchTitle })
    }

    @Test fun episodeMarksCountAndAreStripped() {
        assertTrue(SeriesDetector.isSeries(ev("Grey's Anatomy S19E04", 0), 1))
        assertEquals("Grey's Anatomy", SeriesDetector.cleanTitle("Grey's Anatomy S19E04"))
        assertEquals("Baantjer", SeriesDetector.cleanTitle("Baantjer (3/12)"))
        assertFalse(SeriesDetector.isSeries(ev("Some Show", 0, 130), 5))
    }

    @Test fun episodeLabels() {
        assertEquals("S2 E5", EpisodeLabel.parse(ev("X", 0, desc = "Seizoen 2, aflevering 5. Text"))?.text())
        assertEquals("S1 E3", EpisodeLabel.parse(ev("X s1e3", 0))?.text())
        assertEquals("E7", EpisodeLabel.parse(ev("X", 0, desc = "Afl. 7: de val"))?.text())
        assertNull(EpisodeLabel.parse(ev("X", 0)))
    }

    @Test fun planSkipsTakenAndDuplicateSlots() {
        val a = SeriesPlan.Slot("S", 1000)
        val b = SeriesPlan.Slot("S", 2000)
        val c = SeriesPlan.Slot("S", 2100)
        assertEquals(listOf("b"), SeriesPlan.missing(listOf(a to "a", b to "b", c to "c"), listOf(SeriesPlan.Slot("S", 1100))))
    }

    @Test fun groupingMakesOneCardPerSeries() {
        fun r(id: String, title: String, begin: Long) = Recording(id, title, "NPO 1", "1:0:19:A:1:2:3:0:0:0:", 1, begin, begin + 60, RecordingPlace.TV, RecordingStatus.SCHEDULED, "r")
        val items = listOf(r("1", "Baantjer (1/12)", 100), r("2", "Baantjer (2/12)", 200), r("3", "Film X", 150))
        val (groups, singles) = SeriesGrouping.group(items, emptyMap())
        assertEquals(1, groups.size)
        assertEquals("Baantjer", groups[0].title)
        assertEquals(listOf("1", "2"), groups[0].episodes.map { it.id })
        assertEquals(listOf("3"), singles.map { it.id })
        val ruleOnly = SeriesGrouping.group(emptyList(), mapOf("x|S" to Pair("Show", "NPO 2"))).first.single()
        assertTrue(ruleOnly.hasRule && ruleOnly.episodes.isEmpty())
    }

    @Test fun autoTimerXml() {
        val xml = """<autotimer version="5"><timer name="Baantjer" match="Baantjer" enabled="yes" id="3"><e2service><e2servicereference>1:0:19:A:</e2servicereference></e2service></timer></autotimer>"""
        val list = AutoTimerXml.parse(xml)
        assertEquals("3", list.single().id)
        assertEquals(listOf("1:0:19:A:"), list.single().services)
        assertTrue(AutoTimerXml.success("<e2simplexmlresult><e2state>True</e2state></e2simplexmlresult>"))
        assertFalse(AutoTimerXml.success("<e2simplexmlresult><e2state>False</e2state></e2simplexmlresult>"))
    }

    @Test fun tvJson() {
        val hits = TmdbJson.parseTvSearch("""{"results":[{"id":42,"name":"Baantjer","original_name":"Baantjer","first_air_date":"1995-01-01","vote_count":50}]}""")
        assertEquals(1995, hits.single().year)
        val show = TmdbJson.parseTv("""{"id":42,"name":"Baantjer","number_of_seasons":12,"episode_run_time":[50],"created_by":[{"name":"A. C. Baantjer"}]}""", "NL", "nl-NL")!!
        assertEquals(-42, show.id)
        assertTrue(show.isSeries)
        assertEquals(12, show.seasons)
        assertEquals(50, show.runtimeMin)
        assertEquals(42, SeriesIds.tvId(show.id))
    }
}
