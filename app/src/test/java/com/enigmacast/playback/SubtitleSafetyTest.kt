package com.enigmacast.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSafetyTest {
    private val midEpisode = PlayheadSnapshot(positionMs = 1_200_000, durationMs = 3_000_000, seekable = true)

    @Test
    fun aSwitchThatRestartsTheEpisodeIsSentBack() {
        assertEquals(1_200_000L, SubtitleSafety.restoreTo(midEpisode, 0, nowSeekable = true))
        assertEquals(1_200_000L, SubtitleSafety.restoreTo(midEpisode, 300_000, nowSeekable = true))
    }

    @Test
    fun aNormalRebufferIsLeftAlone() {
        assertNull(SubtitleSafety.restoreTo(midEpisode, 1_198_000, nowSeekable = true))
        assertNull(SubtitleSafety.restoreTo(midEpisode, 1_205_000, nowSeekable = true))
        // Near the start a re-read from 0 costs nothing.
        assertNull(SubtitleSafety.restoreTo(PlayheadSnapshot(4_000, 3_000_000, true), 0, true))
        // Cannot seek back on an unseekable stream: nothing to do.
        assertNull(SubtitleSafety.restoreTo(midEpisode, 0, nowSeekable = false))
    }

    @Test
    fun embeddedSwitchesAreRefusedOnlyWhereTheyWouldRestart() {
        assertTrue(SubtitleSafety.canSwitchEmbedded(seekable = true, positionMs = 1_200_000))
        assertTrue(SubtitleSafety.canSwitchEmbedded(seekable = false, positionMs = 3_000))
        assertFalse(SubtitleSafety.canSwitchEmbedded(seekable = false, positionMs = 1_200_000))
    }

    @Test
    fun seekKeysNeverSendAnUnseekableStreamToZero() {
        assertTrue(SubtitleSafety.canSeek(seekable = true, live = false))
        assertFalse(SubtitleSafety.canSeek(seekable = false, live = false))
        assertFalse(SubtitleSafety.canSeek(seekable = true, live = true))
    }

    @Test
    fun theBarKeepsItsLengthWhenThePlayerLosesIt() {
        assertEquals(3_000_000L, SubtitleSafety.durationFor(3_000_000, 0))
        assertEquals(3_000_000L, SubtitleSafety.durationFor(C.TIME_UNSET, 3_000_000))
        assertEquals(60_000L, SubtitleSafety.durationFor(C.TIME_UNSET, 0, 60_000))
        assertEquals(0L, SubtitleSafety.durationFor(C.TIME_UNSET, 0))
    }

    @Test
    fun dutchFromSettingsBecomesThePreferredTextLanguage() {
        val iso = mapOf("dutch" to "nl", "english" to "en", "brazilian_portuguese" to "pt-BR")
        assertEquals(listOf("nl", "en", "pt"), SubtitleSafety.preferredTextLanguages(listOf("dutch", "english", "brazilian_portuguese", "unknown")) { iso[it] })
        assertEquals(emptyList<String>(), SubtitleSafety.preferredTextLanguages(null) { iso[it] })
    }
}
