package com.enigmacast.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareAudioPolicyTest {
    @Test
    fun audioOutputAndAudioDecoderFailuresSwitchToSoftwareOnce() {
        assertTrue(SoftwareAudioPolicy.retry(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null, alreadySoftware = false))
        assertTrue(SoftwareAudioPolicy.retry(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, MimeTypes.AUDIO_DTS, false))
        assertTrue(SoftwareAudioPolicy.retry(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, MimeTypes.AUDIO_E_AC3, false))
        assertFalse(SoftwareAudioPolicy.retry(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null, alreadySoftware = true))
    }

    @Test
    fun videoAndNetworkErrorsAreNotAudio() {
        assertFalse(SoftwareAudioPolicy.isAudioFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, MimeTypes.VIDEO_H264))
        assertFalse(SoftwareAudioPolicy.isAudioFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertFalse(SoftwareAudioPolicy.isAudioFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }

    @Test
    fun softwareModeHidesOnlyAudioDecoders() {
        val device = { listOf("hw") }
        assertEquals(emptyList<String>(), SoftwareAudioPolicy.audioDecoders(MimeTypes.AUDIO_AC3, software = true, device = device))
        assertEquals(listOf("hw"), SoftwareAudioPolicy.audioDecoders(MimeTypes.VIDEO_H265, software = true, device = device))
        assertEquals(listOf("hw"), SoftwareAudioPolicy.audioDecoders(MimeTypes.AUDIO_AC3, software = false, device = device))
    }
}
