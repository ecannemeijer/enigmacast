package com.enigmacast.domain.tuner

import com.enigmacast.domain.osdinfo.OrbitalPosition

/** One reference transponder the signal meter tunes, instead of a channel from the bouquets. */
data class ReferenceTransponder(
    val orbitalTenths: Int,
    val frequencyMhz: Int,
    val horizontal: Boolean,
    val symbolRateKsym: Int,
) {
    /** "11836 H 27500". */
    val caption: String
        get() = "$frequencyMhz ${if (horizontal) "H" else "V"} $symbolRateKsym"
}

/** A satellite row in the signal meter, with the transponder to lock. */
data class MeterSatellite(
    val label: String,
    val transponders: List<ReferenceTransponder>,
)

/**
 * Reference transponders for the signal meter. The receiver is tuned to one of these,
 * not to whatever channel happens to be first in a bouquet.
 */
object SignalReferences {
    fun satellites(): List<MeterSatellite> = SATELLITES.map { satellite ->
        val position = OrbitalPosition(satellite.orbitalTenths)
        MeterSatellite(
            label = "${position.label} · ${satellite.name}",
            transponders = satellite.transponders,
        )
    }

    private data class Satellite(
        val orbitalTenths: Int,
        val name: String,
        val transponders: List<ReferenceTransponder>,
    )

    private fun satellite(
        orbitalTenths: Int,
        name: String,
        vararg transponders: ReferenceTransponder,
    ) = Satellite(orbitalTenths, name, transponders.toList())

    private fun tp(orbitalTenths: Int, mhz: Int, horizontal: Boolean, symbolRateKsym: Int) =
        ReferenceTransponder(orbitalTenths, mhz, horizontal, symbolRateKsym)

    private val SATELLITES = listOf(
        satellite(
            3600 - 50,
            "Eutelsat 5 West B",
            tp(3600 - 50, 11554, horizontal = false, 29900),
            tp(3600 - 50, 12543, horizontal = true, 29900),
        ),
        satellite(3600 - 8, "Thor 5 / 6 / 7", tp(3600 - 8, 11247, horizontal = false, 24500)),
        satellite(48, "Astra 4A / SES-5", tp(48, 11747, horizontal = false, 27500)),
        satellite(90, "Eutelsat 9B", tp(90, 11900, horizontal = true, 27500)),
        satellite(130, "Hotbird 13F / 13G", tp(130, 11373, horizontal = true, 27500)),
        satellite(160, "Eutelsat 16A", tp(160, 11554, horizontal = true, 30000)),
        satellite(192, "Astra 1M / 1N / 1P", tp(192, 11836, horizontal = true, 27500)),
        satellite(235, "Astra 3B / 3C", tp(235, 11739, horizontal = false, 29900)),
        satellite(282, "Astra 2E / 2F / 2G", tp(282, 10773, horizontal = true, 22000)),
        satellite(315, "Astra 5B", tp(315, 12051, horizontal = false, 30000)),
        satellite(390, "Hellas Sat 3 / 4", tp(390, 12441, horizontal = false, 23666)),
        satellite(
            420,
            "Türksat 3A / 4A / 5B",
            tp(420, 11833, horizontal = false, 6250),
            tp(420, 11833, horizontal = false, 6500),
        ),
    )
}
