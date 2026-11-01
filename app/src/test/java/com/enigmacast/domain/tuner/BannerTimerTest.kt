package com.enigmacast.domain.tuner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerTimerTest {
    @Test
    fun anyKeyDownOnTheBannerRestartsTheTimer() {
        assertTrue(BannerTimer.restartsOnKey(banner = true, quickBar = false, keyDown = true))
    }

    @Test
    fun noRestartWithoutBannerInQuickBarOrOnKeyUp() {
        assertFalse(BannerTimer.restartsOnKey(banner = false, quickBar = false, keyDown = true))
        assertFalse(BannerTimer.restartsOnKey(banner = true, quickBar = true, keyDown = true))
        assertFalse(BannerTimer.restartsOnKey(banner = true, quickBar = false, keyDown = false))
    }
}
