package com.enigmacast.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class AudioSyncSinkTest {
    private var basePositionUs = 5_000_000L

    /** An AudioSink that only answers the position; every other call is a no-op. */
    private val fakeSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getCurrentPositionUs" -> basePositionUs
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Long.TYPE -> 0L
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }
    } as AudioSink

    private fun configure(sink: AudioSyncSink, mime: String) {
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
    }

    @Test
    fun decodedAudioUsesThePcmDelay() {
        val offsets = AudioSyncOffsets().apply { set(pcmMs = 100, passthroughMs = 300) }
        val sink = AudioSyncSink(fakeSink, offsets)
        configure(sink, MimeTypes.AUDIO_RAW)
        assertEquals(false, offsets.passthroughActive.value)
        assertEquals(4_900_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun undecodedAc3UsesTheHdmiDelay() {
        val offsets = AudioSyncOffsets().apply { set(pcmMs = 100, passthroughMs = 300) }
        val sink = AudioSyncSink(fakeSink, offsets)
        configure(sink, MimeTypes.AUDIO_AC3)
        assertEquals(true, offsets.passthroughActive.value)
        assertEquals(4_700_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun aChangedDelayAppliesWithoutReconfiguring() {
        val offsets = AudioSyncOffsets()
        val sink = AudioSyncSink(fakeSink, offsets)
        assertNull(offsets.passthroughActive.value)
        configure(sink, MimeTypes.AUDIO_RAW)
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))
        offsets.set(pcmMs = -60, passthroughMs = 0)
        assertEquals(5_060_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun unsetPositionStaysUnset() {
        basePositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        val offsets = AudioSyncOffsets().apply { set(pcmMs = 200, passthroughMs = 200) }
        val sink = AudioSyncSink(fakeSink, offsets)
        configure(sink, MimeTypes.AUDIO_RAW)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
    }
}
