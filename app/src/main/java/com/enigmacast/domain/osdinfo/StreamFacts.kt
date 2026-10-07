package com.enigmacast.domain.osdinfo

/** Whether the packets of the stream's audio/video are still scrambled (TS header bits). */
enum class Scrambling { UNKNOWN, CLEAR, SCRAMBLED }

/**
 * What the MPEG-TS this device receives says about its own service: CA descriptors in the
 * PMT (CAIDs), teletext / DVB subtitle / AC-3 descriptors, and the scrambling bits of the
 * audio/video packets. This is per stream, so it is right also when the stream uses
 * another tuner than the receiver's own picture.
 */
data class TsStreamFacts(
    val pmtSeen: Boolean = false,
    val caids: List<Int> = emptyList(),
    val teletext: Boolean = false,
    val dvbSubtitles: Boolean = false,
    val ac3: Boolean = false,
    val scrambling: Scrambling = Scrambling.UNKNOWN,
)

/** The decoded picture and sound as the player sees them. */
data class PlayerFacts(
    val width: Int = 0,
    val height: Int = 0,
    val pixelRatio: Float = 1f,
    val audioMime: String = "",
    val audioChannels: Int = 0,
    val subtitleTracks: Int = 0,
)
