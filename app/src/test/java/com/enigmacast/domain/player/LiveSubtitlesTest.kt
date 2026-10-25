package com.enigmacast.domain.player

import com.enigmacast.domain.player.LiveSubtitles.Decision
import com.enigmacast.domain.player.LiveSubtitles.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSubtitlesTest {
    private val dutchDvb = Track("0:0", "dut", "", "application/dvbsubs")
    private val dutchTeletext = Track("1:0", "nl", "Nederlands", "application/x-teletext")
    private val english = Track("2:0", "eng", "English", "application/dvbsubs")
    private val hearing = Track("3:0", "nld", "NL doven", "application/x-teletext", hearing = true)

    @Test fun languageCodesAreNormalized() {
        assertEquals("nl", LiveSubtitles.normalizeLanguage("dut"))
        assertEquals("nl", LiveSubtitles.normalizeLanguage("nld"))
        assertEquals("nl", LiveSubtitles.normalizeLanguage("nl-NL"))
        assertEquals("de", LiveSubtitles.normalizeLanguage("ger"))
        assertEquals("fr", LiveSubtitles.normalizeLanguage("fre"))
        assertEquals("en", LiveSubtitles.normalizeLanguage("ENG"))
        assertEquals("", LiveSubtitles.normalizeLanguage("und"))
        assertEquals("", LiveSubtitles.normalizeLanguage(null))
    }

    @Test fun choiceIsRememberedByWhatTheTrackIs() {
        val choice = LiveSubtitles.of(dutchTeletext)
        assertEquals(LiveSubtitles.Mode.ON, choice.mode)
        assertEquals("nl", choice.language)
        assertEquals("Nederlands", choice.label)
        assertEquals("application/x-teletext", choice.mime)
        assertEquals(LiveSubtitles.OFF, LiveSubtitles.of(null))
    }

    @Test fun nextChannelGetsTheSameLanguageInAnotherOrder() {
        val choice = LiveSubtitles.of(Track("5:0", "nl", "", "application/dvbsubs"))
        val next = listOf(english, dutchTeletext, dutchDvb)
        assertEquals(Decision.Show("0:0"), LiveSubtitles.decide(choice, next))
    }

    @Test fun kindAndHearingFlagBreakTies() {
        val teletext = LiveSubtitles.of(Track("x", "nl", "", "application/x-teletext"))
        assertEquals("1:0", LiveSubtitles.pick(teletext, listOf(dutchDvb, dutchTeletext, hearing))?.id)
        val deaf = LiveSubtitles.of(Track("x", "nl", "", "application/x-teletext", hearing = true))
        assertEquals("3:0", LiveSubtitles.pick(deaf, listOf(dutchDvb, dutchTeletext, hearing))?.id)
    }

    @Test fun equalTracksKeepTheStreamOrder() {
        val choice = LiveSubtitles.of(Track("x", "nl"))
        val a = Track("0:0", "nl")
        val b = Track("0:1", "nl")
        assertEquals("0:0", LiveSubtitles.pick(choice, listOf(a, b))?.id)
    }

    @Test fun missingLanguageStillShowsATrack() {
        val choice = LiveSubtitles.of(Track("x", "de"))
        assertEquals(Decision.Show("2:0"), LiveSubtitles.decide(choice, listOf(english, dutchDvb)))
        assertEquals(Decision.NoTrack, LiveSubtitles.decide(choice, emptyList()))
        assertNull(LiveSubtitles.pick(choice, emptyList()))
    }

    @Test fun offAndNoChoiceYet() {
        assertEquals(Decision.Off, LiveSubtitles.decide(LiveSubtitles.OFF, listOf(english)))
        assertEquals(Decision.Default, LiveSubtitles.decide(LiveSubtitles(), listOf(english)))
    }

    @Test fun trackWithoutLanguagePrefersOneWithoutLanguage() {
        val choice = LiveSubtitles.of(Track("x", "und", "", "application/x-teletext"))
        val tracks = listOf(english, Track("4:0", "", "", "application/x-teletext"))
        assertEquals("4:0", LiveSubtitles.pick(choice, tracks)?.id)
    }
}
