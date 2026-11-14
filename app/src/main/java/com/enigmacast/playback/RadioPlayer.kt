package com.enigmacast.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.enigmacast.data.radio.RadioNowPlayingParser
import com.enigmacast.data.radio.RadioStreamFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class RadioNowPlayingInfo(
    val artist: String? = null,
    val title: String? = null,
    val raw: String? = null,
)

sealed class RadioPlayerState {
    data object Idle : RadioPlayerState()
    data object Buffering : RadioPlayerState()
    data object Ready : RadioPlayerState()
    data class Error(val message: String?) : RadioPlayerState()
}

/**
 * A dedicated audio player for internet radio, so radio can keep playing while the
 * dashboard is on another page, and so it shares [PlaybackOwner] with live TV / IPTV
 * (only one of them sounds at a time).
 */
@OptIn(UnstableApi::class)
@Singleton
class RadioPlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val playbackOwner: PlaybackOwner,
) {
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _streamNowPlaying = MutableStateFlow(RadioNowPlayingInfo())
    val streamNowPlaying: StateFlow<RadioNowPlayingInfo> = _streamNowPlaying.asStateFlow()

    private val _playerState = MutableStateFlow<RadioPlayerState>(RadioPlayerState.Idle)
    val playerState: StateFlow<RadioPlayerState> = _playerState.asStateFlow()

    init {
        playbackOwner.register(PlaybackOwner.Owner.RADIO) { stop() }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playerState.value = when (playbackState) {
                    Player.STATE_BUFFERING -> RadioPlayerState.Buffering
                    Player.STATE_READY -> RadioPlayerState.Ready
                    Player.STATE_ENDED -> {
                        _isPlaying.value = false
                        RadioPlayerState.Idle
                    }
                    else -> if (_playerState.value is RadioPlayerState.Error) _playerState.value else RadioPlayerState.Idle
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isPlaying.value = false
                _playerState.value = RadioPlayerState.Error(error.message ?: error.errorCodeName)
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata[i]
                    if (entry is IcyInfo) {
                        val parsed = RadioNowPlayingParser.parse(entry.title)
                        _streamNowPlaying.value = RadioNowPlayingInfo(
                            artist = parsed.artist,
                            title = parsed.title,
                            raw = parsed.raw,
                        )
                    }
                }
            }
        })
    }

    fun isCurrentlyPlaying(): Boolean = player.isPlaying

    fun playStream(url: String, mimeType: String? = null) {
        playbackOwner.claim(PlaybackOwner.Owner.RADIO)
        _streamNowPlaying.value = RadioNowPlayingInfo()
        _playerState.value = RadioPlayerState.Buffering
        val mime = mimeType ?: if (RadioStreamFormats.isHlsUrl(url)) RadioStreamFormats.MIME_HLS else null
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                when {
                    mime == RadioStreamFormats.MIME_HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    !mime.isNullOrBlank() -> setMimeType(mime)
                }
            }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            pausePlayback()
        } else {
            playbackOwner.claim(PlaybackOwner.Owner.RADIO)
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        }
    }

    fun pausePlayback() {
        player.playWhenReady = false
        player.pause()
        _isPlaying.value = false
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _isPlaying.value = false
        _streamNowPlaying.value = RadioNowPlayingInfo()
        _playerState.value = RadioPlayerState.Idle
        playbackOwner.release(PlaybackOwner.Owner.RADIO)
    }

    private companion object {
        const val USER_AGENT = "EnigmaCast/1.0 (Linux; Android) Media3"
    }
}
