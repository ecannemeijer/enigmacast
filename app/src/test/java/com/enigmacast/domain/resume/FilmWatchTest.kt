package com.enigmacast.domain.resume

import com.enigmacast.data.db.EpisodeHistoryRow
import com.enigmacast.data.db.IptvResumeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmWatchTest {
    private fun history(pos: Long, watched: Boolean, at: Long) =
        EpisodeHistoryRow("A", "m", "7", null, pos, 6_000_000, watched, at, "p")
    private fun resume(pos: Long, at: Long, dur: Long = 6_000_000) =
        IptvResumeRow("A", "m", "7", pos, dur, at, "Film", null, "mkv", "", "c", null, null, 0, 0)

    @Test fun neverPlayedIsNothing() = assertNull(FilmWatch.row(null, null))

    @Test fun historyAloneIsUsed() {
        val h = history(5_900_000, true, 10)
        assertSame(h, FilmWatch.row(h, null))
        assertEquals(1f, EpisodeHistory.fraction(FilmWatch.row(h, null)), 0f)
    }

    @Test fun savedPlaceWithoutHistoryGivesABarNoCheck() {
        val row = FilmWatch.row(null, resume(3_000_000, 10))!!
        assertFalse(row.watched)
        assertEquals(0.5f, EpisodeHistory.fraction(row), 0.001f)
    }

    @Test fun newerSavedPlaceMovesTheBarWatchedStays() {
        val row = FilmWatch.row(history(5_900_000, true, 10), resume(600_000, 20))!!
        assertTrue(row.watched)
        assertEquals(600_000L, row.positionMs)
        assertEquals(20L, row.updatedMs)
    }

    @Test fun olderSavedPlaceLosesToHistory() {
        val h = history(1_200_000, false, 30)
        assertSame(h, FilmWatch.row(h, resume(600_000, 20)))
    }

    @Test fun unknownDurationKeepsTheHistoryOne() {
        val row = FilmWatch.row(history(100, false, 1), resume(600_000, 20, dur = 0))!!
        assertEquals(6_000_000L, row.durationMs)
    }
}
