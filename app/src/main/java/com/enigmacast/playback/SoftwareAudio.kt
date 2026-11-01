package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.enigmacast.util.EnigmaLog

/** When to give up on the device's audio path (hardware decoder or HDMI passthrough) for FFmpeg. */
object SoftwareAudioPolicy {
    private val audioOutputErrors = setOf(
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
    )
    private val decoderErrors = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    /**
     * An error of the audio side: the audio output failed, or a decoder failed on an audio
     * format ([rendererMime], null when not a renderer error). Video errors stay with the
     * picture rescue: video keeps its hardware decoder.
     */
    fun isAudioFailure(errorCode: Int, rendererMime: String?): Boolean =
        errorCode in audioOutputErrors || (errorCode in decoderErrors && MimeTypes.isAudio(rendererMime))

    /** Retry once with software audio; a second audio failure is a real error. */
    fun retry(errorCode: Int, rendererMime: String?, alreadySoftware: Boolean): Boolean =
        !alreadySoftware && isAudioFailure(errorCode, rendererMime)

    /** The device's audio decoders, or none (so the FFmpeg renderer takes the track) in software mode. */
    fun <T> audioDecoders(mime: String, software: Boolean, device: () -> List<T>): List<T> =
        if (software && MimeTypes.isAudio(mime)) emptyList() else device()
}

/**
 * Software audio for one player: once the device's audio path fails, every audio format is
 * decoded by FFmpeg to PCM (no hardware audio decoder, no passthrough); video is untouched.
 */
@OptIn(UnstableApi::class)
class SoftwareAudio(private val tag: String) {
    @Volatile
    var forced: Boolean = false
        private set

    /** Wraps [base]: audio decoders disappear in software mode. */
    fun selector(base: MediaCodecSelector = MediaCodecSelector.DEFAULT): MediaCodecSelector =
        MediaCodecSelector { mime, secure, tunneling ->
            SoftwareAudioPolicy.audioDecoders(mime, forced) { base.getDecoderInfos(mime, secure, tunneling) }
        }

    /** An audio failure: switches to software and prepares [player] again (same position). True when handled. */
    fun recover(error: PlaybackException, player: Player?): Boolean {
        val exo = player ?: return false
        val mime = (error as? ExoPlaybackException)
            ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            ?.rendererFormat?.sampleMimeType
        if (!SoftwareAudioPolicy.retry(error.errorCode, mime, forced)) return false
        forced = true
        EnigmaLog.player("$tag audio failed (${error.errorCodeName} ${mime ?: "-"}): software audio (FFmpeg)")
        exo.prepare()
        exo.playWhenReady = true
        return true
    }
}

/** When encoded audio may go out over HDMI as is (passthrough / offload), and when it must be decoded. */
object PassthroughPolicy {
    /** Passthrough setting off, or the device's audio path already failed: decode everything to PCM. */
    fun decodeOnly(passthroughSetting: Boolean, softwareForced: Boolean): Boolean = !passthroughSetting || softwareForced

    /** The sink refuses this format as is (anything but PCM) when [decodeOnly]. */
    fun refuses(mime: String?, decodeOnly: Boolean): Boolean = decodeOnly && mime != null && mime != MimeTypes.AUDIO_RAW
}

/**
 * Audio output that refuses encoded audio (no passthrough or offload) while [decodeOnly] says so,
 * read on every format check: the device decoder or FFmpeg then decodes the track to PCM.
 */
@OptIn(UnstableApi::class)
class DecodeOnlyAudioOutputProvider(
    delegate: androidx.media3.exoplayer.audio.AudioOutputProvider,
    private val decodeOnly: () -> Boolean,
) : androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider(delegate) {
    override fun getFormatSupport(
        formatConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig,
    ): androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport {
        if (PassthroughPolicy.refuses(formatConfig.format.sampleMimeType, decodeOnly())) {
            return androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return super.getFormatSupport(formatConfig)
    }
}
