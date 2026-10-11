package com.enigmacast.domain.osdinfo

/**
 * One reading of OpenWebif `/api/tunersignal` (or `/api/signal`). It describes the frontend
 * of the receiver's own current service, not necessarily the tuner our stream uses.
 * Fields the image did not fill in are null.
 */
data class SignalReading(
    val snrPercent: Int? = null,
    val snrDb: Float? = null,
    val agcPercent: Int? = null,
    val ber: Int? = null,
    /** 0 = tuner A. */
    val tunerNumber: Int? = null,
    val tunerType: String? = null,
    val inStandby: Boolean? = null,
) {
    val hasValues: Boolean get() = snrPercent != null || snrDb != null || agcPercent != null
}

/** Satellite polarisation as Enigma2 numbers it (0 = horizontal … 3 = circular right). */
enum class Polarisation(val short: String) { HORIZONTAL("H"), VERTICAL("V"), CIRCULAR_LEFT("L"), CIRCULAR_RIGHT("R") }

/** Tuning data of a transponder; every field is optional because images report different subsets. */
data class Transponder(
    val frequencyKhz: Int? = null,
    val symbolRate: Int? = null,
    val polarisation: Polarisation? = null,
    val fec: String? = null,
    /** "DVB-S2", "DVB-S", "DVB-C", "DVB-T2" … */
    val system: String? = null,
    val modulation: String? = null,
    val orbital: OrbitalPosition? = null,
) {
    /** "11494 H 22000 2/3 · DVB-S2 8PSK": language-neutral, as on an Enigma2 second infobar. */
    val technicalLine: String
        get() {
            val tuning = listOfNotNull(
                frequencyKhz?.let { ((it + 500) / 1000).toString() },
                polarisation?.short,
                symbolRate?.let { ((it + 500) / 1000).toString() },
                fec,
            ).joinToString(" ")
            val modulationPart = listOfNotNull(system, modulation).joinToString(" ")
            return listOf(tuning, modulationPart).filter { it.isNotBlank() }.joinToString(" · ")
        }
}

/**
 * A stream client in OpenWebif `/api/about` (`info.streams`). openATV adds the tuner and the
 * transponder of each client; OpenPLi gives only reference and address.
 */
data class StreamClientInfo(
    val reference: String,
    val address: String,
    val tunerNumber: Int? = null,
    val transponder: Transponder? = null,
)

/** The receiver's own current service from OpenWebif `/api/getcurrent` (`info`). */
data class ReceiverCurrent(
    val reference: String = "",
    val name: String = "",
    val namespace: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Enigma2 aspect code (3, 4, 7, 8, 11, 12, 15, 16 are 16:9). */
    val aspect: Int? = null,
    val widescreen: Boolean? = null,
    val teletext: Boolean? = null,
    val crypted: Boolean? = null,
    val dolby: Boolean? = null,
    val audioDescription: String = "",
    val tunerNumber: Int? = null,
    val tunerType: String? = null,
    val orbitalText: String? = null,
    val signal: SignalReading? = null,
)

/** Everything fetched from the receiver for the OSD; null parts were not fetched or not supported. */
data class ReceiverOsdData(
    val signal: SignalReading? = null,
    /** The signal endpoint answered 404 on both paths. */
    val signalUnsupported: Boolean = false,
    val current: ReceiverCurrent? = null,
    val streams: List<StreamClientInfo>? = null,
    /** Whether `/api/statusinfo`/`getcurrent` said the receiver is in standby. */
    val inStandby: Boolean? = null,
    /**
     * [signal] was read from the tuner of this stream (the frontend files), not from the
     * receiver's HDMI service. It stays valid while the box is in standby.
     */
    val streamTunerSignal: Boolean = false,
)
