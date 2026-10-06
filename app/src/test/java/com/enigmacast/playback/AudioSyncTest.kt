package com.enigmacast.playback

import androidx.media3.exoplayer.audio.AudioSink
import com.enigmacast.R
import com.enigmacast.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSyncTest {
    @Test
    fun clampKeepsTheDelayWithinHalfASecond() {
        assertEquals(-500, AudioSync.clamp(-900))
        assertEquals(500, AudioSync.clamp(720))
        assertEquals(140, AudioSync.clamp(140))
    }

    @Test
    fun stepMovesOneStepAndStopsAtTheEnds() {
        assertEquals(20, AudioSync.step(0, 1))
        assertEquals(-20, AudioSync.step(0, -1))
        assertEquals(500, AudioSync.step(500, 1))
        assertEquals(-500, AudioSync.step(-500, -1))
    }

    @Test
    fun stepSnapsOddValuesToTheGrid() {
        assertEquals(60, AudioSync.step(35, 1))
        assertEquals(20, AudioSync.step(35, -1))
        assertEquals(40, AudioSync.step(35, 0))
    }

    @Test
    fun positiveDelayMakesTheClockReportAnEarlierPosition() {
        assertEquals(1_000_000L - 120_000L, AudioSync.shiftPositionUs(1_000_000L, 120))
        assertEquals(1_000_000L + 80_000L, AudioSync.shiftPositionUs(1_000_000L, -80))
        assertEquals(1_000_000L, AudioSync.shiftPositionUs(1_000_000L, 0))
    }

    @Test
    fun unsetPositionIsPassedThrough() {
        assertEquals(
            AudioSink.CURRENT_POSITION_NOT_SET,
            AudioSync.shiftPositionUs(AudioSink.CURRENT_POSITION_NOT_SET, 200),
        )
    }

    @Test
    fun labelsShowTheSign() {
        assertEquals("+120 ms", AudioSync.label(120))
        assertEquals("-40 ms", AudioSync.label(-40))
        assertEquals("0 ms", AudioSync.label(0))
        assertEquals(UiText.res(R.string.audio_sync_wait, 120), AudioSync.describe(120))
        assertEquals(UiText.res(R.string.audio_sync_ahead, 40), AudioSync.describe(-40))
        assertEquals(UiText.res(R.string.audio_sync_none), AudioSync.describe(0))
    }

    @Test
    fun offsetsAreClampedAndChosenPerOutput() {
        val offsets = AudioSyncOffsets()
        offsets.set(pcmMs = 60, passthroughMs = 900)
        assertEquals(60, offsets.delayFor(passthrough = false))
        assertEquals(500, offsets.delayFor(passthrough = true))
    }
}
