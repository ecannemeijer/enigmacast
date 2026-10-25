package com.enigmacast.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.enigmacast.domain.player.AudioBoost
import com.enigmacast.domain.player.BoostLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BoostAudioProcessorTest {
    private fun pcm(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    private fun run(processor: BoostAudioProcessor, input: ByteBuffer): ShortArray {
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    private fun configured(boost: AudioBoostGain) = BoostAudioProcessor(boost).apply {
        configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    @Test fun offPassesSamplesThrough() {
        val processor = configured(AudioBoostGain())
        assertTrue(processor.isActive)
        val out = run(processor, pcm(100, -200, 30_000))
        assertEquals(listOf<Short>(100, -200, 30_000), out.toList())
    }

    @Test fun levelMakesItLouderButNeverClips() {
        val boost = AudioBoostGain().apply { set(4) }
        val processor = configured(boost)
        val quiet = run(processor, pcm(*ShortArray(4_800) { 1_000 }))
        assertEquals(1_000 * AudioBoost.gain(4), quiet.last().toFloat(), 2f)
        val loud = run(processor, pcm(*ShortArray(4_800) { 30_000 }))
        assertTrue(loud.all { it <= (BoostLimiter.CEILING * 32768f).toInt() + 1 })
    }

    @Test fun encodedAudioIsLeftAlone() {
        val processor = BoostAudioProcessor(AudioBoostGain())
        processor.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_AC3))
        assertEquals(false, processor.isActive)
    }

    @Test fun pcm16RoundsAndSaturates() {
        assertEquals(0.toShort(), BoostAudioProcessor.toPcm16(0f))
        assertEquals(Short.MAX_VALUE, BoostAudioProcessor.toPcm16(1.5f))
        assertEquals(Short.MIN_VALUE, BoostAudioProcessor.toPcm16(-1.5f))
        assertEquals(16_384.toShort(), BoostAudioProcessor.toPcm16(0.5f))
    }

    @Test fun gainHolderClampsTheLevel() {
        val boost = AudioBoostGain()
        boost.set(9)
        assertEquals(AudioBoost.MAX, boost.level)
        boost.set(-1)
        assertEquals(0, boost.level)
    }
}
