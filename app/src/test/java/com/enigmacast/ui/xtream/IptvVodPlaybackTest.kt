package com.enigmacast.ui.xtream

import android.view.KeyEvent
import com.enigmacast.ui.remote.SeekSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvVodPlaybackTest {
    @Test fun resumeStoredLikeRecordings() {
        assertNull(IptvResume.toStore(30_000, 6_000_000))          // too early
        assertEquals(600_000L, IptvResume.toStore(600_000, 6_000_000))
        assertNull(IptvResume.toStore(5_950_000, 6_000_000))       // near the end: watched
        assertEquals(600_000L, IptvResume.toStore(600_000, 0))     // unknown length: keep
        assertNull(IptvResume.offer(0))
        assertEquals(90_000L, IptvResume.offer(90_000))
    }

    @Test fun keysWithoutBar() {
        assertEquals(VodBarKeys.Action.SHOW, VodBarKeys.action(KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertEquals(VodBarKeys.Action.SHOW, VodBarKeys.action(KeyEvent.KEYCODE_DPAD_RIGHT, false))
        assertEquals(VodBarKeys.Action.PLAYER, VodBarKeys.action(KeyEvent.KEYCODE_DPAD_DOWN, false))
        assertEquals(VodBarKeys.Action.FORWARD, VodBarKeys.action(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, false))
        assertEquals(VodBarKeys.Action.REWIND, VodBarKeys.action(KeyEvent.KEYCODE_MEDIA_REWIND, true))
        assertEquals(VodBarKeys.Action.PAUSE, VodBarKeys.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true))
    }

    @Test fun barOwnsDpad() {
        assertEquals(VodBarKeys.Action.BAR, VodBarKeys.action(KeyEvent.KEYCODE_DPAD_DOWN, true))
        assertEquals(VodBarKeys.Action.BAR, VodBarKeys.action(KeyEvent.KEYCODE_DPAD_LEFT, true))
    }

    @Test fun holdingGoesFaster() {
        assertEquals(10_000L, SeekSteps.scrub(0))
        assertEquals(30_000L, SeekSteps.scrub(10))
        assertEquals(60_000L, SeekSteps.scrub(25))
    }
}
