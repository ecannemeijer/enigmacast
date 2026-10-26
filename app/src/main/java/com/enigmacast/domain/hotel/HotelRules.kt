package com.enigmacast.domain.hotel

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.LastLiveSource
import com.enigmacast.domain.player.LiveSubtitles
import com.enigmacast.domain.player.QuickOption
import com.enigmacast.domain.remote.PlayerAction
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamSection
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The hotel configuration in memory, for code that is not injected (filters, the player's tiles,
 * the screen guard). [com.enigmacast.data.hotel.HotelStore] keeps it in step with its file.
 */
object HotelRuntime {
    private val _state = MutableStateFlow(HotelConfig())
    val state: StateFlow<HotelConfig> = _state.asStateFlow()
    val config: HotelConfig get() = _state.value
    val active: Boolean get() = _state.value.enabled

    private val _cleanups = MutableStateFlow(0)
    /** Counts finished cleanups, so screens holding the guest's recent lists read them again. */
    val cleanups: StateFlow<Int> = _cleanups.asStateFlow()

    fun set(config: HotelConfig) {
        _state.value = config
    }

    fun cleaned() {
        _cleanups.value = _cleanups.value + 1
    }
}

/** The guest's left menu. */
enum class HotelMenuItem { SAT_TV, IPTV_LIVE, IPTV_MOVIES, IPTV_SERIES, SETTINGS }

object HotelMenu {
    /** Satellite or IPTV live (plus films and series when allowed); Settings always last. */
    fun items(config: HotelConfig): List<HotelMenuItem> = buildList {
        if (config.iptv) {
            add(HotelMenuItem.IPTV_LIVE)
            if (config.iptvVod) {
                add(HotelMenuItem.IPTV_MOVIES)
                add(HotelMenuItem.IPTV_SERIES)
            }
        } else {
            add(HotelMenuItem.SAT_TV)
        }
        add(HotelMenuItem.SETTINGS)
    }
}

/** What guests may see: bouquets, IPTV categories and sections. Outside hotel mode everything passes. */
object HotelFilter {
    fun categoryKey(source: String, section: XtreamSection) = "$source|${section.name}"

    fun bouquetAllowed(reference: String, config: HotelConfig): Boolean {
        if (!config.enabled) return true
        val allowed = config.allowedBouquets ?: return true
        return reference in allowed
    }

    fun bouquets(list: List<BouquetEntry>, config: HotelConfig): List<BouquetEntry> {
        if (!config.enabled || config.allowedBouquets == null) return list
        return list.filter { bouquetAllowed(it.reference, config) }
    }

    /** True when hotel mode hides some bouquets (filters and search caches depend on it). */
    fun restrictsBouquets(config: HotelConfig) = config.enabled && config.allowedBouquets != null

    fun sectionAllowed(section: XtreamSection, config: HotelConfig): Boolean = when {
        !config.enabled -> true
        !config.iptv -> false
        section == XtreamSection.LIVE -> true
        else -> config.iptvVod
    }

    fun categories(list: List<XtreamCategory>, source: String, section: XtreamSection, config: HotelConfig): List<XtreamCategory> {
        if (!config.enabled) return list
        if (!sectionAllowed(section, config)) return emptyList()
        val allowed = config.allowedCategories[categoryKey(source, section)] ?: return list
        return list.filter { it.id in allowed }
    }

    /** Playlist categories carry their provider; [sourceOf] maps it to the source key. */
    fun categoriesBySource(
        list: List<XtreamCategory>,
        section: XtreamSection,
        config: HotelConfig,
        sourceOf: (XtreamCategory) -> String,
    ): List<XtreamCategory> {
        if (!config.enabled) return list
        if (!sectionAllowed(section, config)) return emptyList()
        return list.filter { category ->
            val allowed = config.allowedCategories[categoryKey(sourceOf(category), section)]
            allowed == null || category.id in allowed
        }
    }

    /** Stores the owner's pick as a whitelist; every id of [all] in [shown] stays visible. */
    fun withCategories(config: HotelConfig, source: String, section: XtreamSection, shown: Set<String>): HotelConfig =
        config.copy(allowedCategories = config.allowedCategories + (categoryKey(source, section) to shown))

    fun shownCategories(all: List<XtreamCategory>, source: String, section: XtreamSection, config: HotelConfig): Set<String> {
        val allowed = config.allowedCategories[categoryKey(source, section)] ?: return all.map { it.id }.toSet()
        return all.map { it.id }.filter { it in allowed }.toSet()
    }
}

/** Player features a guest never reaches. */
object HotelRestrictions {
    /** Recording, stream/provider info, equivalent channels (other bouquets), sleep (closes the app), picture-in-picture. */
    private val HIDDEN_TILES = setOf(QuickOption.RECORD, QuickOption.STREAM_INFO, QuickOption.EQUIVALENT, QuickOption.SLEEP, QuickOption.PIP)
    private val BLOCKED_ACTIONS = setOf(PlayerAction.RECORD, PlayerAction.SWITCH_RECEIVER)

    fun tiles(tiles: List<QuickOption>, config: HotelConfig): List<QuickOption> {
        if (!config.enabled) return tiles
        return tiles.filter { it !in HIDDEN_TILES && !(it == QuickOption.SEARCH && config.iptv) }
    }

    fun actionAllowed(action: PlayerAction, config: HotelConfig): Boolean =
        !config.enabled || action !in BLOCKED_ACTIONS
}

/** The Wi-Fi QR code text ("WIFI:T:WPA;S:<ssid>;P:<password>;;") that phones join with. */
object WifiQr {
    fun escape(value: String): String = buildString {
        value.forEach { c ->
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }

    fun payload(ssid: String, password: String): String =
        if (password.isEmpty()) "WIFI:T:nopass;S:${escape(ssid)};;"
        else "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"
}

/** The HOME alias is on only while hotel mode is on, the owner asked for it and a hotel PIN exists. */
object LauncherToggle {
    fun aliasEnabled(config: HotelConfig, pinSet: Boolean): Boolean = config.enabled && config.launcher && pinSet
}

/** The daily cleanup for the next guest. */
object HotelSchedule {
    fun epochDay(nowMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()

    fun minuteOfDay(nowMs: Long, zone: ZoneId): Int = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    /** The set time has passed today and today's cleanup has not run yet (also catches a TV that was off at that time). */
    fun due(config: HotelConfig, nowMs: Long, zone: ZoneId): Boolean =
        config.enabled && config.cleanupDaily &&
            minuteOfDay(nowMs, zone) >= config.cleanupMinute &&
            config.lastCleanupDay != epochDay(nowMs, zone)

    /**
     * Turning the daily cleanup on (or moving its time) after today's time already passed does not
     * wipe the room at once: today counts as done.
     */
    fun armed(config: HotelConfig, nowMs: Long, zone: ZoneId): HotelConfig =
        if (minuteOfDay(nowMs, zone) >= config.cleanupMinute) config.copy(lastCleanupDay = epochDay(nowMs, zone)) else config

    fun label(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** Quarter-hour steps around the clock. */
    fun step(minute: Int, delta: Int): Int = (minute + delta * 15).mod(24 * 60)
}

/**
 * The cleanup for the next guest, applied to the hotel profile's own scoped settings only (the
 * profile is active in hotel mode). Clears recent channels and the live subtitle choice and
 * puts the start channel back. Audio boost and favourites stay.
 */
object HotelCleanupPlan {
    /** Settings-side part of the cleanup. */
    fun settings(settings: AppSettings, config: HotelConfig): AppSettings {
        var next = settings.copy(
            recentByReceiver = settings.recentByReceiver.mapValues { emptyList() },
            recentChannels = emptyList(),
            liveSubtitles = LiveSubtitles(),
        )
        next = withStartChannel(next, config)
        return next
    }

    /** The start channel (satellite or IPTV) as the remembered channel, so live TV starts there. */
    fun withStartChannel(settings: AppSettings, config: HotelConfig): AppSettings {
        var next = settings
        val sat = config.startSat
        if (!config.iptv && sat != null && sat.reference.isNotBlank()) {
            val receiver = config.startReceiverId.ifBlank { settings.activeReceiver()?.id.orEmpty() }
            if (receiver.isNotBlank()) {
                next = next.copy(lastChannels = next.lastChannels + (receiver to sat), lastLiveSource = LastLiveSource.ENIGMA2)
            }
        }
        val iptv = config.startIptv
        if (config.iptv && iptv != null && iptv.source.isNotBlank() && (iptv.streamId.isNotBlank() || iptv.url.isNotBlank())) {
            next = next.copy(lastIptvChannels = next.lastIptvChannels + (iptv.source to iptv), lastLiveSource = LastLiveSource.IPTV)
        }
        return next
    }

    /**
     * A remembered satellite channel in a bouquet guests may not see is dropped, so live TV
     * starts in the first allowed bouquet instead.
     */
    fun dropHiddenLastChannel(settings: AppSettings, config: HotelConfig): AppSettings {
        if (!HotelFilter.restrictsBouquets(config)) return settings
        val kept = settings.lastChannels.filterValues { it.bouquetReference.isBlank() || HotelFilter.bouquetAllowed(it.bouquetReference, config) }
        val legacy = settings.lastChannel.takeIf { it.bouquetReference.isBlank() || HotelFilter.bouquetAllowed(it.bouquetReference, config) } ?: LastChannel()
        if (kept == settings.lastChannels && legacy == settings.lastChannel) return settings
        return settings.copy(lastChannels = kept, lastChannel = legacy)
    }

    /** Only rows of the hotel profile are touched; other profiles (and their synced data) never. */
    fun <T> rowsOfHotel(rows: List<T>, hotelUuid: String, uuidOf: (T) -> String): List<T> =
        if (hotelUuid.isBlank()) emptyList() else rows.filter { uuidOf(it) == hotelUuid }

    /** What the cleanup keeps, for the explanation on screen and the tests. */
    const val KEEPS_AUDIO_BOOST = true
    const val KEEPS_FAVOURITES = true
}

/**
 * Screens a guest may be on. Everything else (Home, Settings, setup, tools, torrents …) is
 * replaced by the hotel menu, so no code path can open them while hotel mode is on.
 */
object HotelRoutes {
    enum class Screen { LOADING, HOTEL, SATELLITE, IPTV, OTHER }

    fun allowed(screen: Screen, config: HotelConfig): Boolean = when {
        !config.enabled -> true
        screen == Screen.LOADING || screen == Screen.HOTEL -> true
        screen == Screen.SATELLITE -> !config.iptv
        screen == Screen.IPTV -> config.iptv
        else -> false
    }
}
