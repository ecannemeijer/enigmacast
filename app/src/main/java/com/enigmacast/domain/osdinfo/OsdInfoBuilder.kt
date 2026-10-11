package com.enigmacast.domain.osdinfo

import com.enigmacast.domain.service.BouquetChannels

/** What [OsdInfoBuilder] combines for the channel that plays. */
data class OsdInfoInput(
    val reference: String,
    /** The channel is an internet / IPTV URL (see `ServiceStreams`). */
    val internet: Boolean = false,
    /** Follow-receiver mode: the receiver's own current service is what this device shows. */
    val followsReceiver: Boolean = false,
    val receiver: ReceiverOsdData = ReceiverOsdData(),
    val stream: TsStreamFacts = TsStreamFacts(),
    val player: PlayerFacts = PlayerFacts(),
)

/**
 * Builds [OsdInfo] and decides what can be trusted. OpenWebif's `getcurrent` and
 * `tunersignal` describe the receiver's own current service. With an own tuner (several users)
 * our stream may run on another tuner, so those values are only used when they demonstrably
 * belong to our channel: same service, or the same tuner/transponder as our stream client.
 * Per-stream data (the TS itself, our stream client in `/api/about`, the service reference)
 * always wins.
 */
object OsdInfoBuilder {
    fun build(input: OsdInfoInput): OsdInfo {
        val reference = input.reference
        val player = input.player
        val delivery = if (input.internet) Delivery.IPTV else ServiceOrigin.delivery(reference)
        if (delivery == Delivery.IPTV) {
            return OsdInfo(
                delivery = Delivery.IPTV,
                signal = SignalInfo.Missing(Unavailable.NOT_APPLICABLE),
                video = playerVideo(player),
                audio = playerAudio(player),
                subtitles = if (player.subtitleTracks > 0) true else null,
                crypt = CryptInfo(CryptState.NOT_APPLICABLE),
            )
        }
        val receiver = input.receiver
        val current = receiver.current
        val standby = !input.followsReceiver && (receiver.inStandby == true || receiver.signal?.inStandby == true)
        val liveReference = current?.reference.orEmpty()
        val liveIsOurs = input.followsReceiver ||
            (!standby && liveReference.isNotBlank() && sameService(liveReference, reference))
        val ourTransponder = ServiceOrigin.transponderKey(reference)
        val liveOnOurTransponder = liveIsOurs ||
            (!standby && liveReference.isNotBlank() && ourTransponder != null && ServiceOrigin.transponderKey(liveReference) == ourTransponder)
        val client = receiver.streams.orEmpty().let { clients ->
            clients.firstOrNull { sameService(it.reference, reference) && it.tunerNumber != null }
                ?: clients.firstOrNull { sameService(it.reference, reference) }
        }
        val live = current.takeIf { liveIsOurs }

        val tuner = when {
            client?.tunerNumber != null -> TunerInfo(
                letter = letter(client.tunerNumber),
                type = client.transponder?.system ?: live?.tunerType,
                source = InfoSource.RECEIVER_STREAM_CLIENT,
            )
            live != null && (live.tunerNumber ?: receiver.signal?.tunerNumber) != null -> TunerInfo(
                letter = letter(live.tunerNumber ?: receiver.signal?.tunerNumber),
                type = live.tunerType,
                source = InfoSource.RECEIVER_LIVE,
            )
            else -> null
        }

        val orbital = ServiceOrigin.orbital(reference)
            ?: client?.transponder?.orbital
            ?: live?.let { ServiceOrigin.orbitalFromNamespace(it.namespace) ?: OrbitalPosition.parse(it.orbitalText) }

        return OsdInfo(
            delivery = delivery,
            orbital = orbital.takeIf { delivery == Delivery.SATELLITE || delivery == Delivery.UNKNOWN },
            signal = signal(receiver, standby, live != null, liveOnOurTransponder, client),
            tuner = tuner,
            transponder = client?.transponder?.let { tp -> if (tp.orbital == null && orbital != null) tp.copy(orbital = orbital) else tp },
            video = playerVideo(player) ?: live?.let(::receiverVideo),
            audio = playerAudio(player) ?: live?.let { AudioInfo(it.audioDescription, 0, it.dolby == true) }?.takeIf { it.codec.isNotBlank() || it.dolby },
            teletext = when {
                input.stream.pmtSeen -> input.stream.teletext
                live != null -> live.teletext
                else -> null
            },
            subtitles = when {
                player.subtitleTracks > 0 || input.stream.dvbSubtitles -> true
                input.stream.pmtSeen -> false
                else -> null
            },
            crypt = crypt(input.stream, live),
        )
    }

    private fun signal(
        receiver: ReceiverOsdData,
        standby: Boolean,
        liveIsOurs: Boolean,
        liveOnOurTransponder: Boolean,
        client: StreamClientInfo?,
    ): SignalInfo {
        val reading = receiver.signal?.takeIf { it.hasValues } ?: receiver.current?.signal?.takeIf { it.hasValues }
        val readingTuner = receiver.signal?.tunerNumber ?: receiver.current?.tunerNumber
        val ours = belongsToStream(reading, readingTuner, client, receiver.streamTunerSignal)
        return when {
            reading != null && ours -> SignalInfo.Measured(reading)
            reading != null && client?.tunerNumber != null && readingTuner != null && readingTuner != client.tunerNumber ->
                SignalInfo.Missing(Unavailable.OTHER_TUNER)
            standby -> SignalInfo.Missing(Unavailable.STANDBY)
            reading == null -> when {
                receiver.signalUnsupported -> SignalInfo.Missing(Unavailable.UNSUPPORTED)
                receiver.signal == null && receiver.current == null -> SignalInfo.Missing(Unavailable.LOADING)
                liveIsOurs -> SignalInfo.Missing(Unavailable.UNSUPPORTED)
                else -> SignalInfo.Missing(Unavailable.OTHER_TUNER)
            }
            // Our stream client's tuner is known (openATV): the reading must be from that tuner.
            client?.tunerNumber != null ->
                if (readingTuner == client.tunerNumber) SignalInfo.Measured(reading) else SignalInfo.Missing(Unavailable.OTHER_TUNER)
            // Same service or same transponder: Enigma2 shares the tuner that is already tuned there.
            liveOnOurTransponder -> SignalInfo.Measured(reading)
            else -> SignalInfo.Missing(Unavailable.OTHER_TUNER)
        }
    }

    /**
     * True when [reading] is the tuner of this stream. A matching tuner number is enough,
     * including while the box is in standby. Without a tuner number, only a reading taken
     * from the single locked frontend counts.
     */
    private fun belongsToStream(
        reading: SignalReading?,
        readingTuner: Int?,
        client: StreamClientInfo?,
        streamTunerSignal: Boolean,
    ): Boolean {
        if (reading == null || !reading.hasValues) return false
        if (client?.tunerNumber != null && readingTuner != null) return readingTuner == client.tunerNumber
        return streamTunerSignal && client?.tunerNumber == null
    }

    private fun crypt(stream: TsStreamFacts, live: ReceiverCurrent?): CryptInfo {
        val systems = CaSystems.names(stream.caids)
        return when {
            stream.scrambling == Scrambling.SCRAMBLED ->
                CryptInfo(CryptState.NOT_DECRYPTED, systems, stream.caids, InfoSource.STREAM)
            stream.pmtSeen && stream.caids.isNotEmpty() && stream.scrambling == Scrambling.CLEAR ->
                CryptInfo(CryptState.DECRYPTED, systems, stream.caids, InfoSource.STREAM)
            // Clear packets, no CA descriptor in our PMT, but the receiver says the service is
            // encrypted: the image left the descriptors out; it is decrypted all the same.
            stream.pmtSeen && stream.scrambling == Scrambling.CLEAR && live?.crypted == true ->
                CryptInfo(CryptState.DECRYPTED, emptyList(), emptyList(), InfoSource.RECEIVER_LIVE)
            stream.pmtSeen && stream.scrambling == Scrambling.CLEAR ->
                CryptInfo(CryptState.FREE, source = InfoSource.STREAM)
            stream.pmtSeen && stream.caids.isNotEmpty() ->
                CryptInfo(CryptState.ENCRYPTED, systems, stream.caids, InfoSource.STREAM)
            live?.crypted == true -> CryptInfo(CryptState.ENCRYPTED, source = InfoSource.RECEIVER_LIVE)
            live?.crypted == false -> CryptInfo(CryptState.FREE, source = InfoSource.RECEIVER_LIVE)
            else -> CryptInfo(CryptState.UNKNOWN)
        }
    }

    private fun playerVideo(player: PlayerFacts): VideoInfo? {
        if (player.width <= 0 || player.height <= 0) return null
        val ratio = player.width * player.pixelRatio.takeIf { it > 0f }.let { it ?: 1f } / player.height
        return VideoInfo(player.width, player.height, definition(player.width, player.height), aspectOf(ratio))
    }

    private fun receiverVideo(current: ReceiverCurrent): VideoInfo? {
        val width = current.width?.takeIf { it > 0 } ?: return null
        val height = current.height?.takeIf { it > 0 } ?: return null
        val aspect = when {
            current.aspect != null -> if (current.aspect in WIDE_ASPECTS) "16:9" else if (current.aspect in NARROW_ASPECTS) "4:3" else null
            current.widescreen != null -> if (current.widescreen) "16:9" else "4:3"
            else -> null
        }
        return VideoInfo(width, height, definition(width, height), aspect)
    }

    private fun playerAudio(player: PlayerFacts): AudioInfo? {
        val mime = player.audioMime.lowercase()
        if (mime.isBlank()) return null
        val codec = when {
            mime == "audio/ac3" -> "AC3"
            mime.startsWith("audio/eac3") -> "E-AC3"
            mime == "audio/ac4" -> "AC4"
            mime == "audio/mp4a-latm" || mime == "audio/aac" -> "AAC"
            mime.startsWith("audio/mpeg") -> "MPEG"
            mime.startsWith("audio/vnd.dts") -> "DTS"
            else -> mime.substringAfter('/').uppercase()
        }
        return AudioInfo(codec, player.audioChannels, dolby = codec in DOLBY)
    }

    fun definition(width: Int, height: Int): Definition = when {
        height >= 2000 || width >= 3500 -> Definition.UHD
        height >= 700 || width >= 1200 -> Definition.HD
        else -> Definition.SD
    }

    private fun aspectOf(ratio: Float): String? = when {
        ratio >= 1.55f -> "16:9"
        ratio >= 1.2f -> "4:3"
        else -> null
    }

    /** Same service, ignoring hex case (OpenWebif and bouquets do not always agree). */
    private fun sameService(left: String, right: String): Boolean =
        BouquetChannels.sameService(left.trim().uppercase(), right.trim().uppercase())

    fun letter(tunerNumber: Int?): String? = tunerNumber?.takeIf { it in 0..25 }?.let { ('A' + it).toString() }

    private val DOLBY = setOf("AC3", "E-AC3", "AC4")
    private val WIDE_ASPECTS = setOf(3, 4, 7, 8, 0xB, 0xC, 0xF, 0x10)
    private val NARROW_ASPECTS = setOf(1, 2, 5, 6, 9, 0xA, 0xD, 0xE)
}
