package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.enigmacast.R
import com.enigmacast.util.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Audio delay correction in milliseconds. A positive value means the sound is heard
 * too late, so the picture waits for it. A negative value means the sound comes too
 * early, so the picture is moved forward.
 */
internal object AudioSync {
    const val MIN_MS = -500
    const val MAX_MS = 500
    const val STEP_MS = 20

    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    /** Moves [ms] one step in [direction] (negative or positive) and snaps it to the step grid. */
    fun step(ms: Int, direction: Int): Int {
        val snapped = (ms.toDouble() / STEP_MS).roundToInt() * STEP_MS
        val delta = when {
            direction > 0 -> STEP_MS
            direction < 0 -> -STEP_MS
            else -> 0
        }
        return clamp(snapped + delta)
    }

    /**
     * The player's clock is the audio position. Reporting it [delayMs] earlier makes
     * every video frame and subtitle wait that long, which lines them up with sound
     * that reaches the speakers late. The audio data is untouched, so this also works
     * when AC3 or E-AC3 goes out over HDMI undecoded.
     */
    fun shiftPositionUs(positionUs: Long, delayMs: Int): Long {
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || delayMs == 0) return positionUs
        return positionUs - delayMs * 1_000L
    }

    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms"
        else -> "$ms ms"
    }

    fun describe(ms: Int): UiText = when {
        ms > 0 -> UiText.res(R.string.audio_sync_wait, ms)
        ms < 0 -> UiText.res(R.string.audio_sync_ahead, -ms)
        else -> UiText.res(R.string.audio_sync_none)
    }
}

/**
 * The audio delay values the running player reads. They live outside the player so a
 * change applies at once, without rebuilding the player or restarting the stream.
 */
class AudioSyncOffsets {
    @Volatile
    var pcmMs: Int = 0
        private set

    @Volatile
    var passthroughMs: Int = 0
        private set

    private val _passthroughActive = MutableStateFlow<Boolean?>(null)

    /** Whether the current track goes out undecoded over HDMI; null before the first track. */
    val passthroughActive: StateFlow<Boolean?> = _passthroughActive.asStateFlow()

    fun set(pcmMs: Int, passthroughMs: Int) {
        this.pcmMs = AudioSync.clamp(pcmMs)
        this.passthroughMs = AudioSync.clamp(passthroughMs)
    }

    fun delayFor(passthrough: Boolean): Int = if (passthrough) passthroughMs else pcmMs

    internal fun onOutputConfigured(passthrough: Boolean) {
        _passthroughActive.value = passthrough
    }
}

/**
 * Applies [AudioSyncOffsets] to the position the audio renderer uses as the clock.
 * Raw PCM input means the sound was decoded (stereo, or a track the HDMI output cannot
 * pass through); any other input is sent to the TV or amplifier undecoded.
 */
@OptIn(UnstableApi::class)
internal class AudioSyncSink(
    sink: AudioSink,
    private val offsets: AudioSyncOffsets,
) : ForwardingAudioSink(sink) {
    @Volatile
    private var passthrough = false

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        super.configure(audioSinkConfig)
        passthrough = audioSinkConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW
        offsets.onOutputConfigured(passthrough)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        return AudioSync.shiftPositionUs(super.getCurrentPositionUs(sourceEnded), offsets.delayFor(passthrough))
    }
}
