package com.enigmacast.domain.hotel

import com.enigmacast.util.EnigmaLog

/**
 * One line per change of what guests see, e.g. `hotel: bron=SAT profiel=1a2b3c4d whitelist=3 → zichtbaar=3`.
 * Only counts and a shortened profile id: no names, URLs or credentials.
 */
object HotelVisibilityLog {
    private val last = HashMap<String, String>()

    fun line(source: String, profile: String, whitelist: Int, visible: Int, total: Int, fellBack: Boolean, what: String = ""): String {
        val shortProfile = profile.take(8).ifBlank { "-" }
        val scope = if (what.isBlank()) "" else " $what"
        val tail = if (fellBack) " (whitelist komt met niets overeen: alle $total zichtbaar)" else " (van $total)"
        return "hotel: bron=$source$scope profiel=$shortProfile whitelist=$whitelist → zichtbaar=$visible$tail"
    }

    fun bouquets(config: HotelConfig, visible: HotelVisible<*>) {
        if (!config.enabled) return
        log("SAT", line("SAT", HotelRuntime.profileUuid, visible.whitelist, visible.items.size, visible.total, visible.fellBack))
    }

    fun categories(config: HotelConfig, section: String, visible: HotelVisible<*>) {
        if (!config.enabled) return
        log("IPTV|$section", line("IPTV", HotelRuntime.profileUuid, visible.whitelist, visible.items.size, visible.total, visible.fellBack, section.lowercase()))
    }

    /** Free-form hotel line (entry, empty lists); always logged. */
    fun note(message: String) {
        EnigmaLog.service("hotel: $message")
    }

    @Synchronized
    private fun log(key: String, message: String) {
        if (last[key] == message) return
        last[key] = message
        EnigmaLog.service(message)
    }
}
