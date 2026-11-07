package com.enigmacast.domain.player

import com.enigmacast.domain.subsource.SubSourceHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesSubtitlesTest {
    private val tracks = listOf(
        LiveSubtitles.Track("0:0", "eng", "English", "text/x-ssa"),
        LiveSubtitles.Track("1:0", "dut", "Nederlands", "application/x-subrip"),
        LiveSubtitles.Track("2:0", "nl", "Nederlands SDH", "application/x-subrip", hearing = true),
    )

    @Test fun picksTheSameLanguageByWhatItIsNotByIndex() {
        val pref = LiveSubtitles.of(LiveSubtitles.Track("5:0", "nld", "Nederlands", "application/x-subrip"))
        assertEquals("1:0", SeriesSubtitles.pickEmbedded(pref, tracks)?.id)
        assertEquals("2:0", SeriesSubtitles.pickEmbedded(pref.copy(hearing = true, label = "Nederlands SDH"), tracks)?.id)
    }

    @Test fun noOtherLanguageAndOffPicksNothing() {
        val pref = LiveSubtitles(LiveSubtitles.Mode.ON, "fr")
        assertNull(SeriesSubtitles.pickEmbedded(pref, tracks))
        assertNull(SeriesSubtitles.pickEmbedded(LiveSubtitles.OFF, tracks))
        assertNull(SeriesSubtitles.pickExternal(LiveSubtitles.OFF, listOf(SubSourceHit(1, "dutch"))))
    }

    @Test fun subSourceChoiceIsFoundByLanguage() {
        val hits = listOf(SubSourceHit(1, "english"), SubSourceHit(2, "dutch", hearingImpaired = true), SubSourceHit(3, "dutch"))
        val pref = SeriesSubtitles.ofExternal(SubSourceHit(9, "dutch"))
        assertEquals("nl", pref.language)
        assertEquals(3L, SeriesSubtitles.pickExternal(pref, hits)?.id)
        assertNull(SeriesSubtitles.pickEmbedded(pref, tracks))
        // An embedded Dutch choice also finds a Dutch file when the next episode has no track.
        assertEquals(3L, SeriesSubtitles.pickExternal(LiveSubtitles(LiveSubtitles.Mode.ON, "nl"), hits)?.id)
    }

    @Test fun rememberKeepsTheNewestAndIsBounded() {
        var map = emptyMap<String, LiveSubtitles>()
        repeat(SeriesSubtitles.MAX_SERIES + 5) { map = SeriesSubtitles.remember(map, "s$it", LiveSubtitles.OFF) }
        assertEquals(SeriesSubtitles.MAX_SERIES, map.size)
        assertNull(map["s0"])
        map = SeriesSubtitles.remember(map, "s10", LiveSubtitles(LiveSubtitles.Mode.ON, "nl"))
        assertEquals("s10", map.keys.last())
    }
}
