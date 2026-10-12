package com.enigmacast.domain.osdinfo

/**
 * Signal files under `/proc/stb/frontend/N/` on the receiver. OpenWebif's `tunersignal` only
 * reads the HDMI service, which is empty while own-tuner mode keeps the box in standby.
 * These files belong to the tuner that is actually locked for the stream.
 */
object FrontendSignal {
    fun parse(snrRaw: String?, agcRaw: String?, dbRaw: String?, tunerNumber: Int): SignalReading? {
        val snr = percent(snrRaw)
        val agc = percent(agcRaw)
        val db = decibels(dbRaw)
        if (snr == null && agc == null && db == null) return null
        return SignalReading(snrPercent = snr, snrDb = db, agcPercent = agc, tunerNumber = tunerNumber)
    }

    /** 0–100 stays a percent. Larger driver values use the same 0–65535 scale as OpenWebif. */
    fun percent(raw: String?): Int? {
        val number = integer(raw) ?: return null
        return when {
            number in 0..100 -> number.toInt()
            number in 101..65535 -> (number * 100 / 65535).toInt()
            else -> null
        }
    }

    /** "12.50", "12.50 dB", or hundredths of a dB such as 1250. */
    fun decibels(raw: String?): Float? {
        val text = raw?.trim()?.replace(',', '.')?.replace("db", "", ignoreCase = true)?.trim().orEmpty()
        if (text.isEmpty() || text.equals("N/A", true)) return null
        val number = text.toFloatOrNull() ?: return null
        val db = if (!text.contains('.') && number > 60f) number / 100f else number
        return db.takeIf { it in -10f..60f }
    }

    /**
     * A frontend is in use when its lock file says so. Without that file, a non-zero
     * strength or quality is the lock.
     */
    fun locked(lockRaw: String?, reading: SignalReading?): Boolean {
        when (lockRaw?.trim()?.lowercase()) {
            "1", "on", "locked", "true", "yes" -> return true
            "0", "off", "unlocked", "false", "no" -> return false
        }
        val snr = reading?.snrPercent ?: 0
        val agc = reading?.agcPercent ?: 0
        val db = reading?.snrDb ?: 0f
        return snr > 0 || agc > 0 || db > 0.05f
    }

    private fun integer(raw: String?): Long? {
        val text = raw?.trim()?.substringBefore('%')?.trim().orEmpty()
        if (text.isEmpty() || text.equals("N/A", true)) return null
        return text.toLongOrNull()
    }
}
