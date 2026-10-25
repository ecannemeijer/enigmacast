package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.enigmacast.domain.player.AudioBoost
import com.enigmacast.domain.player.BoostLimiter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The live audio boost of one player. The quick bar (main thread) sets [level]; the audio
 * thread reads it per buffer, so a change is heard at once without rebuilding the player.
 */
class AudioBoostGain {
    @Volatile
    var level: Int = 0
        private set

    fun set(level: Int) {
        this.level = AudioBoost.clamp(level)
    }
}

/**
 * Raises decoded PCM (16-bit or float) by the [AudioBoostGain] level, with a peak limiter so
 * loud passages do not clip. Always active for PCM, so switching the boost on or off does not
 * reconfigure the sink. Encoded audio (HDMI passthrough) never reaches audio processors, so
 * there the TV/amplifier volume stays in charge.
 */
@OptIn(UnstableApi::class)
class BoostAudioProcessor(private val boost: AudioBoostGain) : BaseAudioProcessor() {
    private var limiter: BoostLimiter? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        limiter = BoostLimiter(inputAudioFormat.sampleRate).also { it.target = AudioBoost.gain(boost.level); it.reset() }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val limiter = limiter
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        limiter?.target = AudioBoost.gain(boost.level)
        if (limiter == null || limiter.idle) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val samples = size / bytesPerSample
        if (scratch.size < samples) scratch = FloatArray(samples)
        val source = inputBuffer.order(ByteOrder.nativeOrder())
        val start = source.position()
        for (i in 0 until samples) {
            scratch[i] = if (float) source.getFloat(start + i * 4) else source.getShort(start + i * 2) / 32768f
        }
        val frames = samples / channels
        limiter.process(scratch, channels, frames)
        for (i in 0 until samples) {
            if (float) output.putFloat(scratch[i]) else output.putShort(toPcm16(scratch[i]))
        }
        // A trailing partial sample (never expected) is passed on as it came.
        for (i in samples * bytesPerSample until size) output.put(source.get(start + i))
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        limiter?.reset()
    }

    override fun onReset() {
        limiter = null
        scratch = FloatArray(0)
    }

    companion object {
        internal fun toPcm16(sample: Float): Short = (sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
