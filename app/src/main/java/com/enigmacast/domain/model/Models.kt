package com.enigmacast.domain.model

import androidx.annotation.StringRes
import com.enigmacast.R
import com.enigmacast.domain.network.AccessMode
import com.enigmacast.domain.network.StreamProfile
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.util.UiText
import kotlinx.serialization.Serializable

@Serializable
data class ReceiverConfig(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val httpPort: Int = 80,
    val useHttps: Boolean = false,
    val username: String = "",
    val password: String = "",
    val streamingPort: Int = 8001,
    val timeoutSeconds: Int = 8,
    val allowInsecureCertificates: Boolean = false,
    /** Remote access from outside the home network: DynDNS name or public address; blank when not used. */
    val remoteHost: String = "",
    /** Forwarded web interface port; 0 keeps [httpPort]. */
    val remoteHttpPort: Int = 0,
    val remoteUseHttps: Boolean = false,
    /** Forwarded stream port; 0 keeps [streamingPort]. */
    val remoteStreamingPort: Int = 0,
    /** Forwarded transcoding port; 0 keeps [transcodingPort]. */
    val remoteTranscodingPort: Int = 0,
    val accessMode: AccessMode = AccessMode.AUTO,
    /** Port of the receiver's transcoder (Broadcom boxes with TranscodingSetup: usually 8002, others 8001). */
    val transcodingPort: Int = 8002,
    val streamProfile: StreamProfile = StreamProfile.AUTO,
    val transcodePreset: TranscodePreset = TranscodePreset.MEDIUM,
    /** Experimental: reach the receiver through the app's own WireGuard tunnel when away. */
    val wireGuard: Boolean = false,
    /** The wg-quick text of the tunnel; kept in the Keystore-backed secret store, never in the settings file. */
    val wireGuardConfig: String = "",
    /** Oscam web interface port on this receiver. The web interface is separate from OpenWebif. */
    val oscamPort: Int = 8888,
    val oscamUsername: String = "",
    /** Kept in the Keystore-backed secret store, never in the settings file. */
    val oscamPassword: String = "",
    /** FTP login for Settings import (channel lists and IPTV bouquets). Enigma2 images default to root without a password. */
    val ftpPort: Int = 21,
    val ftpUsername: String = "root",
    /** Kept in the Keystore-backed secret store, never in the settings file. */
    val ftpPassword: String = "",
) {
    /** Never prints passwords or tunnel keys, so a config that ends up in a log or crash report stays safe. */
    override fun toString(): String =
        "ReceiverConfig(id=$id, name=$name, host=$host, httpPort=$httpPort, useHttps=$useHttps, " +
            "user=${if (username.isEmpty()) "-" else "set"}, password=${if (password.isEmpty()) "-" else "***"}, " +
            "streamingPort=$streamingPort, remote=${if (remoteHost.isBlank()) "-" else "set"}, accessMode=$accessMode, " +
            "wireGuard=$wireGuard, wireGuardConfig=${if (wireGuardConfig.isEmpty()) "-" else "***"}, " +
            "oscamPort=$oscamPort, oscamUser=${if (oscamUsername.isEmpty()) "-" else "set"}, " +
            "oscamPassword=${if (oscamPassword.isEmpty()) "-" else "***"}, ftpPort=$ftpPort, " +
            "ftpPassword=${if (ftpPassword.isEmpty()) "-" else "***"})"
}

@Serializable
data class PlaybackSettings(
    val lowLatency: Boolean = false,
    val minBufferMs: Int = 1_500,
    val maxBufferMs: Int = 5_000,
    val bufferForPlaybackMs: Int = 500,
    val bufferForRebufferMs: Int = 1_000,
    val hardwareDecoder: Boolean = true,
    val autoReconnect: Boolean = true,
    val hdmiPassthrough: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    /** Audio delay correction for decoded (stereo/PCM) sound, -500..500 ms. */
    val audioDelayPcmMs: Int = 0,
    /** Audio delay correction for AC3/E-AC3 sent undecoded over HDMI, -500..500 ms. */
    val audioDelayPassthroughMs: Int = 0,
    /** Where Record stores the programme. Missing in older settings, so the TV is the default. */
    val recordingTarget: RecordingTarget = RecordingTarget.TV,
    /**
     * Size of a recording stored on this TV. Null is the original stream.
     * Missing in older settings, so those stay original. Receiver timers ignore this.
     */
    val recordingPreset: TranscodePreset? = null,
    /** Which video decoder to use. Missing in older settings, so Automatic applies. */
    val videoDecoder: VideoDecoderMode = VideoDecoderMode.AUTO,
    /** Safe video output for devices that garble the picture. Automatic turns it on for known devices. */
    val videoCompatibility: VideoCompatibility = VideoCompatibility.AUTO,
)

@Serializable
enum class VideoDecoderMode { AUTO, HARDWARE, SOFTWARE }

@Serializable
enum class VideoCompatibility { AUTO, ON, OFF }

@Serializable
data class OsdSettings(
    val format: String = "png",
    val fastPolling: Boolean = true,
    val idlePollMs: Long = 2_000,
    val hideTimeoutMs: Long = 4_000,
    val unchangedToSlowMs: Long = 750,
    val slowPollMs: Long = 400,
    val debugBorders: Boolean = false,
    /**
     * How see-through the channel banner, channel list and receiver OSD are.
     * 0 is solid, 100 is fully clear. Missing in older settings, so 10 applies (close to the old scrim).
     */
    val transparencyPercent: Int = 10,
)

@Serializable
data class RemoteSettings(
    val keyRepeatIntervalMs: Long = 120,
    val longPressMs: Long = 500,
    /** Own remote key choices, "<MODE>.<ACTION>" to encoded keys; missing actions use the defaults. */
    val keyBindings: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class DebugSettings(
    val overlay: Boolean = false,
    val verboseLogging: Boolean = false,
)

/**
 * Stored id of the OSD skin (the look of the OSD the app draws itself). The skins themselves
 * are in `ui.theme.Skins`, keyed by this id. Saved by name,
 * so renaming the class does not touch existing settings.
 */
@Serializable
enum class OsdSkinId(@StringRes val label: Int) {
    STANDARD(R.string.skin_enigmacast),
    OPENPLI(R.string.skin_openpli),
    MIDNIGHT(R.string.skin_midnight),
    METRIX(R.string.skin_metrix),
    AURORA(R.string.skin_aurora),
}

/** Which extra information the channel banner shows (Settings › OSD). */
@Serializable
data class OsdInfoFields(
    val signal: Boolean = true,
    val satellite: Boolean = true,
    val tuner: Boolean = true,
    val encryption: Boolean = true,
    /** Frequency, polarisation, symbol rate, FEC and modulation on a second line. */
    val technical: Boolean = false,
)

/**
 * How the app gets its picture. [OWN_TUNER] is the default, also for installs that saved
 * settings before this option existed (the field is missing there, so the default applies).
 */
@Serializable
enum class StreamMode {
    /** Each device streams its own channel from the stream port, and the receiver is put in standby. */
    OWN_TUNER,

    /** Zap the receiver and stream what it shows, with its OSD (the behaviour before 1.0.0-beta.4). */
    FOLLOW_RECEIVER,
}

/** Where a recording from the guide is stored. [TV] is the default. */
@Serializable
enum class RecordingTarget {
    TV,
    RECEIVER,
}

/** Visual motion of the TV interface. [PREMIUM] is the default for settings saved before this existed. */
@Serializable
enum class MotionQuality {
    PREMIUM,
    PERFORMANCE,
}

/** The channel this device last watched in [StreamMode.OWN_TUNER]. */
@Serializable
data class LastChannel(
    val reference: String = "",
    val name: String = "",
    val bouquetReference: String = "",
)

/** Which live picture was on last: the Enigma2 tuner, or an IPTV live channel. */
@Serializable
enum class LastLiveSource { ENIGMA2, IPTV }

/** The last live IPTV channel of one playlist (source = the account key). */
@Serializable
data class LastIptvChannel(
    val source: String = "",
    val categoryId: String = "",
    val streamId: String = "",
    val name: String = "",
    val url: String = "",
)

@Serializable
data class AppSettings(
    val receivers: List<ReceiverConfig> = emptyList(),
    /** The setup wizard was finished once (Done); a later start without receivers opens the connection screen. */
    val setupCompleted: Boolean = false,
    val activeReceiverId: String? = null,
    val playback: PlaybackSettings = PlaybackSettings(),
    val osd: OsdSettings = OsdSettings(),
    val remote: RemoteSettings = RemoteSettings(),
    val debug: DebugSettings = DebugSettings(),
    val streamMode: StreamMode = StreamMode.OWN_TUNER,
    /**
     * Last channel of the active receiver, from before channels were remembered per box.
     * [ChannelMemory] copies this onto [lastChannels] and then clears it.
     */
    val lastChannel: LastChannel = LastChannel(),
    /** Last channel per receiver id. */
    val lastChannels: Map<String, LastChannel> = emptyMap(),
    val osdSkin: OsdSkinId = OsdSkinId.STANDARD,
    /** Extra info in the channel banner; missing in older settings, so the defaults apply. */
    val osdInfo: OsdInfoFields = OsdInfoFields(),
    /** Picture mode per channel (key: `NowEvents.key` of the service); channels without one use AUTO. */
    val pictureModes: Map<String, PictureMode> = emptyMap(),
    /** Channels last watched with an own tuner, newest first. Legacy single list; see [recentByReceiver]. */
    val recentChannels: List<LastChannel> = emptyList(),
    /** Recent channels per receiver id, newest first. */
    val recentByReceiver: Map<String, List<LastChannel>> = emptyMap(),
    /** Live picture that was on last. Own tuner starts this one when the app opens. */
    val lastLiveSource: LastLiveSource = LastLiveSource.ENIGMA2,
    /** Last live IPTV channel per playlist source key. */
    val lastIptvChannels: Map<String, LastIptvChannel> = emptyMap(),
    /** Click sound when moving with the D-pad (Settings › General). On, as before, unless switched off. */
    val navigationSounds: Boolean = true,
    /**
     * How much motion the TV interface uses. Missing in older settings, so Premium applies.
     * Performance keeps the same layout with shorter motion and no glow.
     */
    val motionQuality: MotionQuality = MotionQuality.PREMIUM,
    /** Settings › IPTV: where the IPTV page gets its lists. Logins of a playlist are in the SecretStore. */
    val iptvSource: IptvSourceMode = IptvSourceMode.XSTREAMITY,
    val iptvPlaylistKind: IptvPlaylistKind = IptvPlaylistKind.M3U,
) {
    fun activeReceiver(): ReceiverConfig? {
        return receivers.firstOrNull { it.id == activeReceiverId } ?: receivers.firstOrNull()
    }
}

data class ReceiverCapabilities(
    val model: String = "",
    val brand: String = "",
    val imageDistro: String = "",
    val imageVersion: String = "",
    val enigmaVersion: String = "",
    val webifVersion: String = "",
    val grabPng: Boolean = false,
    val grabJpg: Boolean = false,
    val grabOsd: Boolean = false,
    val grabAll: Boolean = false,
    val authenticationRequired: Boolean = false,
    val https: Boolean = false,
    val streamingAvailable: Boolean = false,
    /** OpenWebif `transcoding`: the receiver has an encoder and the TranscodingSetup plugin. */
    val transcoding: Boolean = false,
)

data class EnigmaService(
    val name: String = "",
    val reference: String = "",
    val provider: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

data class EpgEvent(
    val title: String,
    val shortDescription: String,
    val plot: String,
    val beginLabel: String,
    val endLabel: String,
    val beginEpochSec: Long,
    val durationSec: Long,
    val current: Boolean,
    /** OpenWebif event id, for a receiver timer. -1 when the guide answer had none. */
    val eventId: Int = -1,
)

data class ChannelGuide(
    val channelName: String,
    val serviceReference: String,
    val events: List<EpgEvent>,
) {
    val currentEvent: EpgEvent? get() = events.firstOrNull { it.current } ?: events.firstOrNull()
}

data class BouquetEntry(
    val name: String,
    val reference: String,
    /** Enigma2 channel number (bouquet start + position), or 0 when OpenWebif did not say. */
    val number: Int = 0,
    /** Picon path on the receiver, e.g. `/picon/1_0_1_….png`, or empty. */
    val picon: String = "",
)

data class BouquetChannelList(
    val bouquetName: String,
    val bouquetReference: String = "",
    val channels: List<BouquetEntry>,
)

data class VolumeStatus(
    val level: Int,
    val muted: Boolean,
)

data class PowerStatus(
    val inStandby: Boolean,
)

/**
 * OpenWebif `/api/powerstate?newstate=N` values (oe-alliance and E2OpenPlugins `control.py`).
 * The reply carries `instandby` as it was *before* the command, so callers poll the state afterwards.
 */
enum class PowerAction(val newState: Int) {
    TOGGLE_STANDBY(0),
    DEEP_STANDBY(1),
    REBOOT(2),
    RESTART_GUI(3),
    WAKE_UP(4),
    STANDBY(5),
    ;

    /** Reboot, GUI restart and deep standby take the receiver off the network for a while. */
    val dropsConnection: Boolean get() = this == DEEP_STANDBY || this == REBOOT || this == RESTART_GUI
}

enum class SleepTimerAction(val wire: String) {
    STANDBY("standby"),
    SHUTDOWN("shutdown"),
}

data class SleepTimerStatus(
    val enabled: Boolean,
    val minutes: Int,
    val action: SleepTimerAction,
    val message: String = "",
)

data class ReceiverDetails(
    val model: String = "",
    val brand: String = "",
    val imageDistro: String = "",
    val imageVersion: String = "",
    val enigmaVersion: String = "",
    val webifVersion: String = "",
    val kernelVersion: String = "",
    val chipset: String = "",
    val uptime: String = "",
    val tuners: List<String> = emptyList(),
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState

    /** No receiver saved yet. */
    data object NotConfigured : ConnectionState
    data class Connected(val capabilities: ReceiverCapabilities) : ConnectionState
    data class Reconnecting(val reason: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

data class PlayerState(
    val playbackState: String = "IDLE",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String = "",
    val audioCodec: String = "",
    /** Channels of the audio that plays (2, 6 …); 0 when not known. */
    val audioChannels: Int = 0,
    val bufferMs: Long = 0,
    val liveOffsetMs: Long = -1,
    val error: String? = null,
    /** "Nederlands · AC3 5.1"; empty before the first audio track. */
    val audioLabel: UiText = UiText.Empty,
    val audioTrackCount: Int = 0,
    /** Language of the subtitles being shown, or empty when none are on. */
    val subtitleLabel: UiText = UiText.Empty,
    val subtitleTrackCount: Int = 0,
    /** Width of one video pixel relative to its height (anamorphic SD is about 1.42). */
    val pixelRatio: Float = 1f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val filePlayback: Boolean = false,
    /** Song title from the stream (ICY / HLS), empty when the stream does not send one. */
    val streamTitle: String = "",
    /** Artist from the stream, empty when the stream does not send one. */
    val streamArtist: String = "",
    /** Live TV is paused; the stream keeps buffering so resume continues from this moment. */
    val timeshiftPaused: Boolean = false,
)

data class DebugInfo(
    val host: String = "",
    val serviceName: String = "",
    val serviceReference: String = "",
    val video: String = "",
    val audio: String = "",
    val media3State: String = "",
    val latency: String = "",
    val osdState: String = "",
    val osdRequestLatencyMs: Long = 0,
    val osdFps: Float = 0f,
    val lastKey: String = "",
    val network: String = "",
)

data class ConnectionTestResult(
    val success: Boolean,
    val message: UiText,
    val capabilities: ReceiverCapabilities? = null,
    val service: EnigmaService? = null,
)

/** IPTV page source: the receiver's XStreamity playlists, or a playlist entered in the app. */
@Serializable
enum class IptvSourceMode { XSTREAMITY, PLAYLIST }

@Serializable
enum class IptvPlaylistKind { M3U, XTREAM }
