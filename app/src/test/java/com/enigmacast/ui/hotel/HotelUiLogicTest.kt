package com.enigmacast.ui.hotel

import com.enigmacast.domain.hotel.HotelConfig
import com.enigmacast.domain.hotel.HotelSource
import com.enigmacast.ui.AppScreen
import com.enigmacast.ui.forHotel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotelUiLogicTest {
    @Test fun volumeSteps() {
        assertEquals(20, HotelVolume.step(-1, 1))
        assertEquals(-1, HotelVolume.step(-1, -1))
        assertEquals(-1, HotelVolume.step(0, -1))
        assertEquals(100, HotelVolume.step(100, 1))
        assertEquals(25, HotelVolume.step(20, 1))
    }

    @Test fun bouquetToggleFromAll() {
        val all = listOf("a", "b", "c")
        assertEquals(setOf("a", "c"), HotelBouquets.toggle(null, all, "b"))
        // Back to every bouquet = no whitelist (new bouquets show too).
        assertNull(HotelBouquets.toggle(setOf("a", "c"), all, "b"))
        assertEquals(setOf("a"), HotelBouquets.toggle(emptySet(), all, "a"))
        assertEquals(3, HotelBouquets.shown(null, all))
        assertEquals(1, HotelBouquets.shown(setOf("a", "zz"), all))
    }

    @Test fun screensFollowHotelMode() {
        val sat = HotelConfig(enabled = true, source = HotelSource.SATELLITE)
        val iptv = sat.copy(source = HotelSource.IPTV)
        assertEquals(AppScreen.Hotel, AppScreen.Dashboard.forHotel(sat))
        assertEquals(AppScreen.Hotel, AppScreen.Settings.forHotel(sat))
        assertEquals(AppScreen.Hotel, AppScreen.Torrent.forHotel(iptv))
        assertEquals(AppScreen.Remote, AppScreen.Remote.forHotel(sat))
        assertEquals(AppScreen.Hotel, AppScreen.Remote.forHotel(iptv))
        assertEquals(AppScreen.Xtream, AppScreen.Xtream.forHotel(iptv))
        assertEquals(AppScreen.Dashboard, AppScreen.Dashboard.forHotel(HotelConfig()))
    }
}
