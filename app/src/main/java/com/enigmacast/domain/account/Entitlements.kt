package com.enigmacast.domain.account

/** What an EnigmaCast account can use. Free has one receiver and no Movies, Series, Soccer or EPG. */
enum class Plan { FREE, PREMIUM }

/** Everything that needs Premium. Anything not listed here is free. */
enum class PremiumFeature {
    /** More than one receiver: adding a second box, Switch receiver, tuner failover to another box. */
    MULTIPLE_RECEIVERS,
    MOVIES,
    SERIES,
    SOCCER,

    /** TV Guide, the EPG grid and the programme list next to the channels in the player. */
    EPG,

    /** Settings import: channel lists from GitHub and IPTV bouquets loaded on the receiver. */
    SETTINGS_IMPORT,

    /** IPTV: the Xtream Codes playlists of the receiver's XStreamity plugin, played in the app. */
    XTREAM,

    /** Torrent: TMDB catalogue with streams from a Stremio addon, played through the torrent engine. */
    TORRENT,
}

/**
 * The account as last confirmed by the server (cached on the device).
 * [verifiedAtMs] is when the server last answered for this account.
 */
data class AccountSnapshot(
    val email: String,
    val plan: Plan,
    val serverPremium: Boolean,
    val premiumUntilMs: Long?,
    val isTrial: Boolean,
    val verifiedAtMs: Long,
    val maxDevices: Int = 10,
    val devicesUsed: Int = 1,
)

/**
 * The single place that decides what is unlocked. The server computes is_premium; the app
 * trusts its cached answer for [OFFLINE_GRACE_MS] without contact, and never past premium_until.
 */
object Entitlements {
    const val OFFLINE_GRACE_MS: Long = 14L * 24 * 60 * 60 * 1000
    const val REFRESH_INTERVAL_MS: Long = 6L * 60 * 60 * 1000
    const val FREE_RECEIVER_LIMIT = 1

    fun isPremium(account: AccountSnapshot?, nowMs: Long): Boolean {
        if (account == null) return false
        if (account.plan != Plan.PREMIUM || !account.serverPremium) return false
        val until = account.premiumUntilMs
        if (until != null && nowMs >= until) return false
        // Clock set back, or the cache is from the future: count from now.
        val age = (nowMs - account.verifiedAtMs).coerceAtLeast(0)
        return age <= OFFLINE_GRACE_MS
    }

    fun allows(account: AccountSnapshot?, feature: PremiumFeature, nowMs: Long): Boolean = when (feature) {
        PremiumFeature.MULTIPLE_RECEIVERS,
        PremiumFeature.MOVIES,
        PremiumFeature.SERIES,
        PremiumFeature.SOCCER,
        PremiumFeature.EPG,
        PremiumFeature.SETTINGS_IMPORT,
        PremiumFeature.XTREAM,
        PremiumFeature.TORRENT,
        -> isPremium(account, nowMs)
    }

    fun maxReceivers(account: AccountSnapshot?, nowMs: Long): Int =
        if (isPremium(account, nowMs)) Int.MAX_VALUE else FREE_RECEIVER_LIMIT

    /** Adding a receiver when [count] are saved already. */
    fun canAddReceiver(account: AccountSnapshot?, count: Int, nowMs: Long): Boolean = count < maxReceivers(account, nowMs)

    /** Moving from the active box to another one. */
    fun canSwitchReceiver(account: AccountSnapshot?, nowMs: Long): Boolean =
        allows(account, PremiumFeature.MULTIPLE_RECEIVERS, nowMs)

    /** Time to ask the server again (start-up, and then every few hours). */
    fun needsRefresh(account: AccountSnapshot?, nowMs: Long): Boolean =
        account != null && nowMs - account.verifiedAtMs >= REFRESH_INTERVAL_MS

    /** The account is premium, but only on the strength of the offline cache now. */
    fun inOfflineGrace(account: AccountSnapshot?, nowMs: Long): Boolean =
        isPremium(account, nowMs) && nowMs - account!!.verifiedAtMs > REFRESH_INTERVAL_MS
}
