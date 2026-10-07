package com.enigmacast.domain.osdinfo

/** CA system names for CAIDs (the 16-bit `CA_system_ID` in a CA descriptor), as softcams show them. */
object CaSystems {
    private data class Range(val from: Int, val to: Int, val name: String)

    // Narrow ranges first: the first match wins.
    private val RANGES = listOf(
        Range(0x1702, 0x1702, "Betacrypt"),
        Range(0x1722, 0x1722, "Betacrypt"),
        Range(0x1762, 0x1762, "Betacrypt"),
        Range(0x4AE0, 0x4AE1, "DRE-Crypt"),
        Range(0x4AEA, 0x4AEA, "Cryptoguard"),
        Range(0x4AFC, 0x4AFC, "Panaccess"),
        Range(0x5581, 0x5581, "Bulcrypt"),
        Range(0x0100, 0x01FF, "Seca"),
        Range(0x0500, 0x05FF, "Viaccess"),
        Range(0x0600, 0x06FF, "Irdeto"),
        Range(0x0700, 0x07FF, "DigiCipher"),
        Range(0x0900, 0x09FF, "Videoguard"),
        Range(0x0B00, 0x0BFF, "Conax"),
        Range(0x0D00, 0x0DFF, "Cryptoworks"),
        Range(0x0E00, 0x0EFF, "PowerVu"),
        Range(0x1000, 0x10FF, "Tandberg"),
        Range(0x1700, 0x17FF, "Betacrypt"),
        Range(0x1800, 0x18FF, "Nagravision"),
        Range(0x2600, 0x26FF, "BISS"),
        Range(0x4A60, 0x4A6F, "SkyCrypt"),
        Range(0x4B00, 0x4BFF, "Tongfang"),
        Range(0x5600, 0x56FF, "Verimatrix"),
    )

    /** "Nagravision" for 0x1802; null for an unknown CAID. */
    fun name(caid: Int): String? = RANGES.firstOrNull { caid in it.from..it.to }?.name

    /** Distinct system names in the order the CAIDs came; unknown CAIDs are left out. */
    fun names(caids: Collection<Int>): List<String> = caids.mapNotNull(::name).distinct()

    /** "1802" style hex for the technical panel. */
    fun hex(caid: Int): String = "%04X".format(caid)
}
