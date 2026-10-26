package com.enigmacast.domain.hotel

import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.LastIptvChannel
import kotlinx.serialization.Serializable

/** What a guest watches in hotel mode. */
@Serializable
enum class HotelSource { SATELLITE, IPTV }

/** The welcome screen shown at every start before live TV. The Wi-Fi password is not in here (SecretStore). */
@Serializable
data class HotelWelcome(
    val enabled: Boolean = false,
    val seconds: Int = 10,
    val name: String = "",
    val ssid: String = "",
    val showQr: Boolean = true,
    val text: String = "",
) {
    companion object {
        val SECONDS = listOf(5, 10, 15, 20, 30, 60)
    }
}

/**
 * Hotel mode ("Hotelstand"): the TV in a B&B room. Device-local: it lives in this TV's own
 * preferences, is never synced and never exported, so another TV of the owner stays normal.
 *
 * [allowedBouquets] and [allowedCategories] are whitelists: null / a missing key means
 * "not configured, everything visible"; a present (even empty) set shows only those ids.
 * A whitelist keeps a category the provider adds later hidden from guests until the owner allows it.
 */
@Serializable
data class HotelConfig(
    val enabled: Boolean = false,
    val source: HotelSource = HotelSource.SATELLITE,
    /** IPTV films and series (the provider's VOD only, never torrents). */
    val iptvVod: Boolean = false,
    /** The linked profile; blank = the profile that was active when hotel mode was turned on. */
    val profileUuid: String = "",
    val allowedBouquets: Set<String>? = null,
    /** "<sourceKey>|<SECTION>" → allowed category ids. */
    val allowedCategories: Map<String, Set<String>> = emptyMap(),
    val welcome: HotelWelcome = HotelWelcome(),
    val cleanupDaily: Boolean = false,
    /** Minute of the day for the daily cleanup (11:00 = 660). */
    val cleanupMinute: Int = 11 * 60,
    /** Epoch day of the last cleanup (automatic or by hand); -1 = never. */
    val lastCleanupDay: Long = -1,
    val startReceiverId: String = "",
    val startSat: LastChannel? = null,
    val startIptv: LastIptvChannel? = null,
    /** Percent of the TV's media volume at every start and after a cleanup; -1 leaves it alone. */
    val startVolume: Int = -1,
    /** The owner chose to make EnigmaCast this TV's launcher (HOME) while hotel mode is on. */
    val launcher: Boolean = false,
) {
    val active: Boolean get() = enabled
    val iptv: Boolean get() = source == HotelSource.IPTV
    val vodAllowed: Boolean get() = iptv && iptvVod
}
