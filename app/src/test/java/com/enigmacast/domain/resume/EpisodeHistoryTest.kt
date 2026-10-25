package com.enigmacast.domain.resume

import com.enigmacast.data.db.EpisodeHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeHistoryTest {
    private fun row(pos: Long, dur: Long, watched: Boolean, at: Long, series: String? = "s") =
        EpisodeHistoryRow("src", "e", "1", series, pos, dur, watched, at, "p")

    @Test fun newerPositionWinsWatchedStays() {
        val watched = row(3_500_000, 3_600_000, true, 10)
        val rewatch = row(60_000, 3_600_000, false, 20, series = null)
        val merged = EpisodeHistory.merge(watched, rewatch)
        assertEquals(60_000L, merged.positionMs)
        assertTrue(merged.watched)
        assertEquals("s", merged.seriesId)
        assertEquals(20L, merged.updatedMs)
        // An older incoming row (sync) does not move the position back.
        val older = EpisodeHistory.merge(row(100_000, 0, false, 30), row(50_000, 3_600_000, true, 5))
        assertEquals(100_000L, older.positionMs)
        assertEquals(3_600_000L, older.durationMs)
        assertTrue(older.watched)
        assertEquals(30L, older.updatedMs)
    }

    @Test fun firstEntryIsKeptAsIs() {
        val r = row(1_000, 0, false, 1)
        assertEquals(r, EpisodeHistory.merge(null, r))
    }

    @Test fun oneSecondIsWorthKeeping() {
        assertTrue(EpisodeHistory.worthKeeping(1_000, 3_600_000))
        assertFalse(EpisodeHistory.worthKeeping(0, 3_600_000))
        assertFalse(EpisodeHistory.worthKeeping(200, 0))
        assertTrue(EpisodeHistory.worthKeeping(3_590_000, 3_600_000))
    }

    @Test fun barFraction() {
        assertEquals(0f, EpisodeHistory.fraction(null), 0f)
        assertEquals(0.5f, EpisodeHistory.fraction(row(1_800_000, 3_600_000, false, 1)), 0.001f)
        assertEquals(1f, EpisodeHistory.fraction(row(0, 0, true, 1)), 0f)
        assertEquals(0f, EpisodeHistory.fraction(row(5_000, 0, false, 1)), 0f)
    }

    @Test fun entryMarksTheEndWatched() {
        assertTrue(EpisodeHistory.entry("src", "e", "1", "s", 3_590_000, 3_600_000, 9, "p").watched)
        assertFalse(EpisodeHistory.entry("src", "e", "1", "s", 1_000, 3_600_000, 9, "p").watched)
    }
}
