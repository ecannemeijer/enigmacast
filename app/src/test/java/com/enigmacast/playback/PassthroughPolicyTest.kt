package com.enigmacast.playback

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughPolicyTest {
    @Test
    fun settingOffDecodesEverything() {
        assertTrue(PassthroughPolicy.decodeOnly(passthroughSetting = false, softwareForced = false))
        assertTrue(PassthroughPolicy.refuses(MimeTypes.AUDIO_E_AC3, decodeOnly = true))
        assertTrue(PassthroughPolicy.refuses(MimeTypes.AUDIO_DTS, decodeOnly = true))
        assertFalse(PassthroughPolicy.refuses(MimeTypes.AUDIO_RAW, decodeOnly = true))
    }

    @Test
    fun settingOnKeepsPassthroughUntilAudioFailed() {
        assertFalse(PassthroughPolicy.decodeOnly(passthroughSetting = true, softwareForced = false))
        assertFalse(PassthroughPolicy.refuses(MimeTypes.AUDIO_AC3, decodeOnly = false))
        assertTrue(PassthroughPolicy.decodeOnly(passthroughSetting = true, softwareForced = true))
    }
}
