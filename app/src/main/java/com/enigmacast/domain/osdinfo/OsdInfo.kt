package com.enigmacast.domain.osdinfo

/** Why a value is not shown. */
enum class Unavailable {
    /** Not fetched yet. */
    LOADING,

    /** The receiver's image has no such endpoint. */
    UNSUPPORTED,

    /** The receiver is in standby: OpenWebif measures only the receiver's own picture. */
    STANDBY,

    /** The receiver shows another transponder; its signal values are not ours. */
    OTHER_TUNER,

    /** IPTV and the like. */
    NOT_APPLICABLE,
}

/** Signal values with where they come from, or why there are none. */
sealed interface SignalInfo {
    data class Measured(val reading: SignalReading) : SignalInfo
    data class Missing(val reason: Unavailable) : SignalInfo
}

enum class Definition(val label: String) { SD("SD"), HD("HD"), UHD("UHD") }

data class VideoInfo(
    val width: Int,
    val height: Int,
    val definition: Definition?,
    /** "16:9", "4:3" or null. */
    val aspect: String?,
)

data class AudioInfo(
    /** "AC3", "E-AC3", "AAC", "MPEG" … or empty. */
    val codec: String,
    val channels: Int,
    val dolby: Boolean,
)

enum class CryptState {
    /** No CA system, packets clear. */
    FREE,

    /** CA system present and the packets arrive descrambled. */
    DECRYPTED,

    /** Packets still scrambled: the receiver could not decrypt this channel for us. */
    NOT_DECRYPTED,

    /** Encrypted, but whether it is decrypted is not known (yet). */
    ENCRYPTED,

    /** Nothing known. */
    UNKNOWN,

    /** IPTV: no DVB encryption. */
    NOT_APPLICABLE,
}

/** Where a value came from; shown in the technical panel so wrong guesses stand out. */
enum class InfoSource { STREAM, RECEIVER_STREAM_CLIENT, RECEIVER_LIVE, SERVICE_REFERENCE, PLAYER }

data class CryptInfo(
    val state: CryptState,
    val systems: List<String> = emptyList(),
    val caids: List<Int> = emptyList(),
    val source: InfoSource? = null,
)

data class TunerInfo(
    /** "A", "B" … */
    val letter: String?,
    /** "DVB-S2" … */
    val type: String?,
    val source: InfoSource,
)

/**
 * Everything the OSD can show about the channel that plays, independent of how a skin draws
 * it. Unknown values are null or an explicit [Unavailable] / [CryptState.UNKNOWN], never a guess.
 */
data class OsdInfo(
    val delivery: Delivery = Delivery.UNKNOWN,
    val orbital: OrbitalPosition? = null,
    val signal: SignalInfo = SignalInfo.Missing(Unavailable.LOADING),
    val tuner: TunerInfo? = null,
    val transponder: Transponder? = null,
    val video: VideoInfo? = null,
    val audio: AudioInfo? = null,
    val teletext: Boolean? = null,
    val subtitles: Boolean? = null,
    val crypt: CryptInfo = CryptInfo(CryptState.UNKNOWN),
) {
    val isIptv: Boolean get() = delivery == Delivery.IPTV

    companion object {
        val Empty = OsdInfo()
    }
}
