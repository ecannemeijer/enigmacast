package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvMiniTest {
    @Test
    fun onlyALiveChannelContinuesInTheMiniPlayer() {
        assertTrue(IptvMini.continues(XtreamSection.LIVE, episode = false, catchup = false))
        assertFalse(IptvMini.continues(XtreamSection.LIVE, episode = false, catchup = true))
        assertFalse(IptvMini.continues(XtreamSection.MOVIES, episode = false, catchup = false))
        assertFalse(IptvMini.continues(XtreamSection.SERIES, episode = true, catchup = false))
        assertFalse(IptvMini.continues(null, episode = false, catchup = false))
    }

    @Test
    fun theMiniPlayerOpensWhatItShows() {
        assertEquals(IptvMini.Target.IPTV, IptvMini.target(iptvLive = true, enigma2Shown = true, enigma2State = "READY"))
        assertEquals(IptvMini.Target.ENIGMA2, IptvMini.target(iptvLive = false, enigma2Shown = true, enigma2State = "READY"))
        assertEquals(IptvMini.Target.ENIGMA2, IptvMini.target(iptvLive = false, enigma2Shown = true, enigma2State = "BUFFERING"))
        // Nothing open (stopped, ended) or no Enigma2 picture on Home: not focusable.
        assertEquals(IptvMini.Target.NONE, IptvMini.target(iptvLive = false, enigma2Shown = true, enigma2State = "IDLE"))
        assertEquals(IptvMini.Target.NONE, IptvMini.target(iptvLive = false, enigma2Shown = true, enigma2State = "ENDED"))
        assertEquals(IptvMini.Target.NONE, IptvMini.target(iptvLive = false, enigma2Shown = false, enigma2State = "READY"))
    }

    @Test
    fun theTitleOnAir() {
        val list = listOf(XtreamEpg("News", 100, 200), XtreamEpg("  ", 200, 300), XtreamEpg("Film", 300, 400))
        assertEquals("News", IptvMini.nowTitle(list, 150))
        assertNull(IptvMini.nowTitle(list, 250))
        assertEquals("Film", IptvMini.nowTitle(list, 300))
        assertNull(IptvMini.nowTitle(list, 500))
        assertNull(IptvMini.nowTitle(null, 150))
    }

    @Test
    fun theReceiverNamesTheEnigma2Badge() {
        assertEquals("Vu+ Uno 4K", IptvMini.receiverBadge(" Vu+ Uno 4K "))
        assertEquals("Enigma2", IptvMini.receiverBadge(null))
        assertEquals("Enigma2", IptvMini.receiverBadge("  "))
        assertEquals("Living room rec…", IptvMini.receiverBadge("Living room receiver"))
    }
}
