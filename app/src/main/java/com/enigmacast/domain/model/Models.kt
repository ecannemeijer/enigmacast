package com.enigmacast.domain.model

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
)

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
)

@Serializable
data class OsdSettings(
    val format: String = "png",
    val fastPolling: Boolean = true,
    val idlePollMs: Long = 2_000,
    val hideTimeoutMs: Long = 4_000,
    val unchangedToSlowMs: Long = 750,
    val slowPollMs: Long = 400,
    val debugBorders: Boolean = false,
)

@Serializable
data class RemoteSettings(
    val keyRepeatIntervalMs: Long = 120,
    val longPressMs: Long = 500,
)

@Serializable
data class DebugSettings(
    val overlay: Boolean = false,
    val verboseLogging: Boolean = false,
)

/**
 * How the app gets its picture. [OWN_TUNER] is the default, also for installs that saved
 * settings before this option existed (the field is missing there, so the default applies).
 */
/**
 * Look of the OSD the app draws itself (channel banner, channel list, notices in
 * own-tuner mode). Only the current look exists for now; the "OSD skins" menu will list
 * these entries and store the choice in [AppSettings.osdSkin].
 */
@Serializable
enum class OsdSkin(val label: String) {
    STANDARD("Standaard"),
}

@Serializable
enum class StreamMode {
    /** Each device streams its own channel from the stream port; the receiver can stay in standby. */
    OWN_TUNER,

    /** Zap the receiver and stream what it shows, with its OSD (the behaviour before 1.0.0-beta.4). */
    FOLLOW_RECEIVER,
}

/** The channel this device last watched in [StreamMode.OWN_TUNER]. */
@Serializable
data class LastChannel(
    val reference: String = "",
    val name: String = "",
    val bouquetReference: String = "",
)

@Serializable
data class AppSettings(
    val receivers: List<ReceiverConfig> = emptyList(),
    val activeReceiverId: String? = null,
    val playback: PlaybackSettings = PlaybackSettings(),
    val osd: OsdSettings = OsdSettings(),
    val remote: RemoteSettings = RemoteSettings(),
    val debug: DebugSettings = DebugSettings(),
    val streamMode: StreamMode = StreamMode.OWN_TUNER,
    val lastChannel: LastChannel = LastChannel(),
    val osdSkin: OsdSkin = OsdSkin.STANDARD,
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
    /** Picon path on the receiver, e.g. `/picon/1_0_19_….png`, or empty. */
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
    val bufferMs: Long = 0,
    val liveOffsetMs: Long = -1,
    val error: String? = null,
    /** "Nederlands · AC3 5.1"; empty before the first audio track. */
    val audioLabel: String = "",
    val audioTrackCount: Int = 0,
    /** Language of the subtitles being shown, or empty when none are on. */
    val subtitleLabel: String = "",
    val subtitleTrackCount: Int = 0,
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
    val message: String,
    val capabilities: ReceiverCapabilities? = null,
    val service: EnigmaService? = null,
)
