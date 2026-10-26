package com.enigmacast.ui

import com.enigmacast.domain.hotel.HotelConfig
import com.enigmacast.domain.hotel.HotelRoutes

enum class AppScreen {
    Loading,
    Connection,
    Setup,
    Dashboard,
    Remote,
    Settings,
    SettingsImport,
    Xtream,
    Torrent,
    Diagnostics,
    SignalMeter,
    Oscam,
    /** Hotel mode: the guest's menu (live TV, films/series when allowed, Settings behind the hotel PIN). */
    Hotel,
}

/** Hotel mode: a screen guests may not reach becomes the hotel menu. */
fun AppScreen.forHotel(config: HotelConfig): AppScreen {
    val kind = when (this) {
        AppScreen.Loading -> HotelRoutes.Screen.LOADING
        AppScreen.Hotel -> HotelRoutes.Screen.HOTEL
        AppScreen.Remote -> HotelRoutes.Screen.SATELLITE
        AppScreen.Xtream -> HotelRoutes.Screen.IPTV
        else -> HotelRoutes.Screen.OTHER
    }
    return if (HotelRoutes.allowed(kind, config)) this else AppScreen.Hotel
}
