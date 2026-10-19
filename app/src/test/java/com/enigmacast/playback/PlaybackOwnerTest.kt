package com.enigmacast.playback

import com.enigmacast.playback.PlaybackOwner.Owner.ENIGMA2
import com.enigmacast.playback.PlaybackOwner.Owner.IPTV
import com.enigmacast.ui.theme.FocusEdges
import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOwnerTest {
    @Test fun startingOneStopsTheOther() {
        val owner = PlaybackOwner()
        var enigmaStops = 0
        var iptvStops = 0
        owner.register(ENIGMA2) { enigmaStops++ }
        owner.register(IPTV) { iptvStops++ }
        owner.claim(ENIGMA2)
        assertEquals(0, enigmaStops); assertEquals(1, iptvStops)
        owner.claim(IPTV)
        assertEquals(1, enigmaStops)
        owner.claim(IPTV) // zapping inside IPTV stops nothing
        assertEquals(1, enigmaStops)
        owner.claim(ENIGMA2)
        assertEquals(2, iptvStops)
        assertEquals(ENIGMA2, owner.current)
    }

    @Test fun claimStopsOtherBeforeReturning() {
        val owner = PlaybackOwner()
        val events = mutableListOf<String>()
        owner.register(ENIGMA2) { events += "e2 stopped" }
        owner.register(IPTV) { events += "iptv stopped" }
        owner.claim(IPTV)
        events += "iptv prepare"
        assertEquals(listOf("e2 stopped", "iptv prepare"), events)
        assertEquals(IPTV, owner.current)
    }

    @Test fun claimAfterReleaseStopsOthersAgain() {
        val owner = PlaybackOwner()
        var stops = 0
        owner.register(ENIGMA2) { stops++ }
        owner.claim(IPTV); owner.release(IPTV); owner.claim(IPTV)
        assertEquals(2, stops)
    }

    @Test fun backgroundEnigma2RefusedWhileIptvInFront() {
        assertFalse(PlaybackRules.mayStart(ENIGMA2, IPTV, IPTV))
        assertTrue(PlaybackRules.mayStart(ENIGMA2, IPTV, null))
        assertTrue(PlaybackRules.mayStart(ENIGMA2, null, IPTV))
        assertTrue(PlaybackRules.mayStart(ENIGMA2, ENIGMA2, IPTV))
    }

    @Test fun otherPlaysNamesAnotherOwnerOnly() {
        val owner = PlaybackOwner()
        assertFalse(owner.otherPlays(ENIGMA2))
        owner.claim(IPTV)
        assertTrue(owner.otherPlays(ENIGMA2))
        assertFalse(owner.otherPlays(IPTV))
        owner.release(IPTV)
        assertFalse(owner.otherPlays(ENIGMA2))
    }

    @Test fun pageEdgesBlockOnlyUpDown() {
        assertTrue(FocusEdges.blocks(FocusDirection.Up))
        assertTrue(FocusEdges.blocks(FocusDirection.Down))
        assertFalse(FocusEdges.blocks(FocusDirection.Left))
        assertFalse(FocusEdges.blocks(FocusDirection.Right))
    }
}
