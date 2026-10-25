package com.enigmacast.domain.xtream

import android.view.KeyEvent
import com.enigmacast.domain.remote.KeyBindings
import com.enigmacast.domain.remote.KeyMode
import org.junit.Assert.assertEquals
import org.junit.Test

class IptvPlayerKeysTest {
    private val b = KeyBindings.defaults(KeyMode.OWN)
    private fun a(code: Int, banner: Boolean = false, zap: Boolean = false, vod: Boolean = false) = IptvPlayerKeys.action(code, b, banner, zap, vod)

    @Test fun upIsNextDownIsPrevious() {
        assertEquals(IptvKeyAction.Zap(1), a(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(IptvKeyAction.Zap(-1), a(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test fun afterZapUpDownStillZap() {
        assertEquals(IptvKeyAction.Zap(1), a(KeyEvent.KEYCODE_DPAD_UP, banner = true, zap = true))
        assertEquals(IptvKeyAction.Zap(-1), a(KeyEvent.KEYCODE_DPAD_DOWN, banner = true, zap = true))
    }

    @Test fun okShowsBannerThenDownOpensOptions() {
        assertEquals(IptvKeyAction.Banner, a(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(IptvKeyAction.Options, a(KeyEvent.KEYCODE_DPAD_DOWN, banner = true))
        assertEquals(IptvKeyAction.ChannelList, a(KeyEvent.KEYCODE_DPAD_CENTER, banner = true))
        assertEquals(IptvKeyAction.Pause, a(KeyEvent.KEYCODE_DPAD_CENTER, banner = true, vod = true))
        assertEquals(IptvKeyAction.ChannelList, a(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test fun rightGoesBackToThePreviousLiveChannel() {
        assertEquals(IptvKeyAction.PreviousChannel, a(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(IptvKeyAction.PreviousChannel, a(KeyEvent.KEYCODE_DPAD_RIGHT, banner = true, zap = true))
        assertEquals(IptvKeyAction.None, a(KeyEvent.KEYCODE_DPAD_RIGHT, vod = true))
    }

    @Test fun namesMatch() {
        assertEquals(IptvNames.normalize("NPO 1"), IptvNames.normalize("NL: NPO 1 HD"))
        assertEquals(IptvNames.normalize("NPO 1"), IptvNames.normalize("NL - NPO 1 HD"))
        assertEquals(IptvNames.normalize("NPO 1"), IptvNames.normalize("NL \u2013 NPO 1 HD"))
        assertEquals("rtl7", IptvNames.normalize("RTL-7 FHD"))
        assertEquals(IptvNames.normalize("Das Erste"), IptvNames.normalize("DE | Das Erste (Backup)"))
    }

    @Test fun satelliteGuideWinsWhenXtreamHasNoCurrentProgramme() {
        val now = 1_500L
        val past = listOf(XtreamEpg("Earlier", 100, 200))
        val live = listOf(XtreamEpg("NOS Journaal", 1000, 2000))
        assertEquals(live, chooseGuide(past, live, now))
        assertEquals(live, chooseGuide(live, past, now))
        assertEquals(past, chooseGuide(past, emptyList(), now))
    }
}
