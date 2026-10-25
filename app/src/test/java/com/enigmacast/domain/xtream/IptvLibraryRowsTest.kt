package com.enigmacast.domain.xtream

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.xtream.IptvResumeMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvLibraryRowsTest {
    private fun resume(kind: String, id: String, updated: Long, source: String = "A", series: String? = null, title: String = "T$id") =
        IptvResumeRow(source, kind, id, 600_000, 6_000_000, updated, title, null, "mkv", "", "c", series, series?.let { "Show $it" }, 1, id.toIntOrNull() ?: 0)

    @Test fun continueWatchingNewestFirstOneCardPerSeries() {
        val rows = listOf(resume("m", "1", 10), resume("e", "2", 30, series = "S"), resume("e", "3", 20, series = "S"), resume("m", "4", 40))
        val cards = IptvLibraryRows.continueWatching(rows, setOf("A"))
        assertEquals(listOf("4", "2", "1"), cards.map { it.id })
        val episode = cards[1]
        assertEquals("Show S", episode.title)
        assertTrue(episode.subtitle.startsWith("S1E2"))
        assertEquals(XtreamSection.SERIES, episode.open.section)
        assertEquals("S", episode.open.id)
        assertEquals(0.1f, cards[0].progress, 0.001f)
    }

    @Test fun inactiveSourcesAndUntitledAreHiddenNotDeleted() {
        val rows = listOf(resume("m", "1", 10, source = "B"), resume("m", "2", 20, title = ""))
        assertEquals(0, IptvLibraryRows.continueWatching(rows, setOf("A")).size)
        assertEquals(1, IptvLibraryRows.continueWatching(rows, setOf("A", "B")).size)
    }

    @Test fun favoritesWithFilmProgress() {
        val favs = listOf(
            IptvLibraryRow("A", "m", "1", "Film", null, null, 1999, "c", "mp4", "", 5),
            IptvLibraryRow("A", "s", "9", "Show", null, null, null, "c", "", "", 9),
            IptvLibraryRow("B", "m", "2", "Gone", null, null, null, "c", "", "", 7),
        )
        val cards = IptvLibraryRows.favorites(favs, mapOf("A/m/1" to resume("m", "1", 1)), setOf("A"))
        assertEquals(listOf("9", "1"), cards.map { it.id })
        assertEquals(XtreamSection.SERIES, cards[0].open.section)
        assertEquals("1999", cards[1].subtitle)
        assertTrue(cards[1].progress > 0f)
    }

    @Test fun oldPrefsPositionsMoveToRows() {
        val rows = IptvResumeMigration.rows(mapOf("acc/m/7" to 120_000L, "acc/m/7/d" to 6_000_000L, "acc/e/8" to 90_000L, "junk" to 1L), 5)
        assertEquals(2, rows.size)
        val film = rows.first { it.kind == "m" }
        assertEquals("acc", film.source)
        assertEquals(6_000_000L, film.durationMs)
    }

    @Test fun watchedFilmGetsCheckAndFullBarEpisodesUntouched() {
        val film = IptvLibraryCard("fav/A/m/1", "A", "m", "1", "F", "", null, 0f, XtreamItem("1", "F", XtreamSection.MOVIES, "c", ""))
        val started = film.copy(key = "fav/A/m/2", id = "2", progress = 0.25f)
        val series = film.copy(key = "fav/A/s/1", kind = "s")
        val history = mapOf(
            "A/m/1" to com.enigmacast.data.db.EpisodeHistoryRow("A", "m", "1", null, 5_900_000, 6_000_000, true, 10, "p"),
            "A/s/1" to com.enigmacast.data.db.EpisodeHistoryRow("A", "s", "1", null, 5_900_000, 6_000_000, true, 10, "p"),
        )
        val out = IptvLibraryRows.withWatch(listOf(film, started, series), emptyMap(), history)
        assertTrue(out[0].watched)
        assertEquals(1f, out[0].progress, 0f)
        assertEquals(started, out[1])
        assertEquals(series, out[2])
    }
}
