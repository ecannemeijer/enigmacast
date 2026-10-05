package com.enigmacast.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnigmaKeyMapperTest {
    @Test
    fun mapsDirectionAndOk() {
        assertEquals(EnigmaKey.UP, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.DPAD_UP))
        assertEquals(EnigmaKey.DOWN, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.DPAD_DOWN))
        assertEquals(EnigmaKey.LEFT, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.DPAD_LEFT))
        assertEquals(EnigmaKey.RIGHT, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.DPAD_RIGHT))
        assertEquals(EnigmaKey.OK, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.DPAD_CENTER))
        assertEquals(EnigmaKey.OK, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.ENTER))
    }

    @Test
    fun mapsBackToExitAndGuideToEpg() {
        assertEquals(EnigmaKey.EXIT, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.BACK))
        assertEquals(103, EnigmaKey.UP.code)
        assertEquals(352, EnigmaKey.OK.code)
        assertEquals(174, EnigmaKey.EXIT.code)
        assertEquals(EnigmaKey.EPG, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.GUIDE))
        assertEquals(EnigmaKey.MENU, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.MENU))
        assertEquals(EnigmaKey.MENU, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.TV_CONTENTS_MENU))
        assertEquals(EnigmaKey.MENU, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.TV_MEDIA_CONTEXT_MENU))
        assertEquals(EnigmaKey.INFO, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.INFO))
    }

    @Test
    fun mapsMediaAndColorKeys() {
        assertEquals(EnigmaKey.CHANNEL_UP, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.CHANNEL_UP))
        assertEquals(EnigmaKey.CHANNEL_DOWN, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.CHANNEL_DOWN))
        assertEquals(EnigmaKey.PLAY, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.MEDIA_PLAY))
        assertEquals(EnigmaKey.PAUSE, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.MEDIA_PAUSE))
        assertEquals(EnigmaKey.PLAY_PAUSE, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.MEDIA_PLAY_PAUSE))
        assertEquals(EnigmaKey.STOP, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.MEDIA_STOP))
        assertEquals(EnigmaKey.RECORD, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.MEDIA_RECORD))
        assertEquals(EnigmaKey.RED, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.PROG_RED))
        assertEquals(EnigmaKey.GREEN, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.PROG_GREEN))
        assertEquals(EnigmaKey.YELLOW, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.PROG_YELLOW))
        assertEquals(EnigmaKey.BLUE, EnigmaKeyMapper.fromAndroidKeyCode(AndroidKeyCodes.PROG_BLUE))
    }

    @Test
    fun ignoresUnmappedKeys() {
        assertNull(EnigmaKeyMapper.fromAndroidKeyCode(24))
    }
}
