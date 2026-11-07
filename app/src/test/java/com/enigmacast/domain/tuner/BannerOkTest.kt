package com.enigmacast.domain.tuner

import com.enigmacast.domain.tuner.BannerOk.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerOkTest {
    @Test
    fun okOnTheZapBannerOpensTheInfoBannerInsteadOfPausing() {
        assertEquals(Action.OPTIONS_BANNER, BannerOk.decide(tunerTrouble = false, radio = false, banner = true, fromZap = true))
    }

    @Test
    fun okOnTheInfoBannerAlsoOpensTheMiniOsdRadioPauses() {
        assertEquals(Action.OPTIONS_BANNER, BannerOk.decide(false, radio = false, banner = true, fromZap = false))
        assertEquals(Action.PAUSE_OR_LIST, BannerOk.decide(false, radio = true, banner = true, fromZap = true))
        assertEquals(Action.PAUSE_OR_LIST, BannerOk.decide(false, radio = true, banner = false, fromZap = false))
    }

    @Test
    fun noBannerShowsItAndTunerTroubleRetries() {
        assertEquals(Action.SHOW_BANNER, BannerOk.decide(false, radio = false, banner = false, fromZap = false))
        assertEquals(Action.RETRY, BannerOk.decide(true, radio = false, banner = true, fromZap = true))
    }
}
