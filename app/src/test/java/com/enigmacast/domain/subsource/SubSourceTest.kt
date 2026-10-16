package com.enigmacast.domain.subsource

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubSourceTest {
    @Test fun cleanNameDropsYearAndQuality() {
        val (title, year) = SubSourceMatch.cleanName("The Matrix (1999) 1080p NL")
        assertEquals("The Matrix", title)
        assertEquals(1999, year)
    }

    @Test fun pickTitlePrefersTheYear() {
        val results = listOf(
            SubSourceTitle(1, "The Matrix", 2003),
            SubSourceTitle(2, "The Matrix", 1999),
        )
        assertEquals(2L, SubSourceMatch.pickTitle(results, "The Matrix", 1999)?.movieId)
    }

    @Test fun pickTitleFallsBackWhenTheYearIsUnknown() {
        val results = listOf(SubSourceTitle(9, "Inception", 2010, "Inception: The IMAX Experience"))
        assertEquals(9L, SubSourceMatch.pickTitle(results, "inception", null)?.movieId)
        assertNull(SubSourceMatch.pickTitle(results, "Dune", 2021))
    }

    @Test fun episodeFilterKeepsTheEpisodeAndTheSeasonPack() {
        val hits = listOf(
            SubSourceHit(1, "dutch", listOf("Show.S01E02.1080p")),
            SubSourceHit(2, "dutch", listOf("Show.S01E03.1080p")),
            SubSourceHit(3, "dutch", listOf("Show.S01.Season.Pack")),
            SubSourceHit(4, "dutch", listOf("1x02")),
        )
        val kept = SubSourceMatch.forEpisode(hits, 1, 2).map { it.id }
        assertEquals(listOf(1L, 3L, 4L), kept)
        assertFalse(2L in kept)
    }

    @Test fun parsesSearchAndSubtitles() {
        val titles = SubSourceJson.titles(
            """{"data":[{"movieId":44,"title":"Show","releaseYear":2015,"alternateTitle":"The Show"}]}""",
        )
        assertEquals(44L, titles.single().movieId)
        assertEquals(2015, titles.single().year)
        val hits = SubSourceJson.subtitles(
            """{"data":[{"subtitleId":"7","language":"Dutch","releaseInfo":["A.S01E01"],"hearingImpaired":1}]}""",
        )
        assertEquals(7L, hits.single().id)
        assertEquals("dutch", hits.single().language)
        assertTrue(hits.single().hearingImpaired)
        assertTrue(SubSourceJson.subtitles("""{"success":false}""").isEmpty())
    }

    @Test fun archivePrefersSrtInsideTheZip() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("notes.txt"))
            out.write("nope".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("subs/episode.srt"))
            out.write("1\n00:00:01,000 --> 00:00:02,000\nHello".toByteArray())
            out.closeEntry()
        }
        val file = SubSourceArchive.extract(zip.toByteArray())
        assertEquals("srt", file?.extension)
        assertTrue(file!!.bytes.decodeToString().contains("Hello"))
    }
}
