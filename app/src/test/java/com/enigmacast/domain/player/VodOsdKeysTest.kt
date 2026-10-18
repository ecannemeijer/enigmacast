package com.enigmacast.domain.player

import android.view.KeyEvent
import com.enigmacast.domain.player.VodOsdKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class VodOsdKeysTest {
    private fun hidden(code: Int, repeat: Int = 0) = VodOsdKeys.action(code, repeat, osdShown = false)
    private fun shown(code: Int, repeat: Int = 0) = VodOsdKeys.action(code, repeat, osdShown = true)

    @Test fun okPausesAndShowsWhenHidden() {
        assertEquals(Action.TogglePause(show = true), hidden(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(Action.TogglePause(show = true), hidden(KeyEvent.KEYCODE_ENTER))
        // Holding OK does not toggle again and again.
        assertEquals(Action.Show, hidden(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 3))
    }

    @Test fun leftRightSeekTenSecondsAtOnceWhenHidden() {
        assertEquals(Action.Scrub(-10_000L), hidden(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Action.Scrub(10_000L), hidden(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test fun upDownOnlyShow() {
        assertEquals(Action.Show, hidden(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Action.Show, hidden(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test fun shownTheFocusedControlGetsTheDpad() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            .forEach { assertEquals(Action.Pass, shown(it)) }
    }

    @Test fun mediaKeysAlwaysWork() {
        assertEquals(Action.TogglePause(show = true), shown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(Action.Scrub(30_000L), shown(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(Action.Scrub(-30_000L), hidden(KeyEvent.KEYCODE_MEDIA_REWIND))
    }

    @Test fun otherKeysAreNotTheOsds() {
        assertEquals(Action.Pass, hidden(KeyEvent.KEYCODE_BACK))
        assertEquals(Action.Pass, hidden(KeyEvent.KEYCODE_INFO))
    }

    @Test fun holdingSeeksFaster() {
        assertEquals(10_000L, VodOsdKeys.scrubStep(0))
        assertEquals(30_000L, VodOsdKeys.scrubStep(8))
        assertEquals(60_000L, VodOsdKeys.scrubStep(20))
    }

    @Test fun timesAsInStremio() {
        assertEquals("0:04 / 1:45:45", VodOsdKeys.times(4_000, 6_345_000))
        assertEquals("12:03 / 42:00", VodOsdKeys.times(723_000, 2_520_000))
        assertEquals("0:00", VodOsdKeys.times(0, 0))
    }

    @Test fun titles() {
        assertEquals("Häxan (1922)", VodOsdKeys.title("Häxan", 1922))
        assertEquals("Häxan (1922)", VodOsdKeys.title("Häxan (1922)", 1922))
        assertEquals("Dark · S1E2 · Lies", VodOsdKeys.title("Dark", 2017, 1, 2, "Lies"))
        assertEquals("Dark · S1E2", VodOsdKeys.title("Dark", null, 1, 2, null))
    }

    @Test fun speedLabels() {
        assertEquals("1×", VodOsdKeys.speedLabel(1f))
        assertEquals("1.25×", VodOsdKeys.speedLabel(1.25f))
        assertEquals("0.5×", VodOsdKeys.speedLabel(0.5f))
    }
}
