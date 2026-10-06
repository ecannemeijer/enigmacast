package com.enigmacast.domain.tuner

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnTunerKeysTest {
    @Test
    fun channelKeysZapTheLocalStream() {
        assertEquals(OwnKeyAction.NEXT, OwnTunerKeys.action(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(OwnKeyAction.NEXT, OwnTunerKeys.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(OwnKeyAction.PREVIOUS, OwnTunerKeys.action(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(OwnKeyAction.PREVIOUS, OwnTunerKeys.action(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun overlayKeys() {
        assertEquals(OwnKeyAction.OK, OwnTunerKeys.action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(OwnKeyAction.BACK, OwnTunerKeys.action(KeyEvent.KEYCODE_BACK))
        assertEquals(OwnKeyAction.DETAILS, OwnTunerKeys.action(KeyEvent.KEYCODE_INFO))
        assertEquals(OwnKeyAction.DETAILS, OwnTunerKeys.action(KeyEvent.KEYCODE_GUIDE))
        assertEquals(OwnKeyAction.LIST, OwnTunerKeys.action(KeyEvent.KEYCODE_MENU))
        assertEquals(OwnKeyAction.CONSUME, OwnTunerKeys.action(KeyEvent.KEYCODE_PROG_RED))
        assertEquals(OwnKeyAction.PASS, OwnTunerKeys.action(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun digitsFromBothRows() {
        assertEquals(OwnKeyAction.DIGIT, OwnTunerKeys.action(KeyEvent.KEYCODE_7))
        assertEquals(7, OwnTunerKeys.digit(KeyEvent.KEYCODE_7))
        assertEquals(OwnKeyAction.DIGIT, OwnTunerKeys.action(KeyEvent.KEYCODE_NUMPAD_3))
        assertEquals(3, OwnTunerKeys.digit(KeyEvent.KEYCODE_NUMPAD_3))
        assertEquals(-1, OwnTunerKeys.digit(KeyEvent.KEYCODE_DPAD_UP))
    }
}
