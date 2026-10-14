package com.enigmacast.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOwnerTest {
    @Test fun startingOneStopsTheOther() {
        val owner = PlaybackOwner()
        var enigmaStops = 0
        var iptvStops = 0
        owner.register(PlaybackOwner.Owner.ENIGMA2) { enigmaStops++ }
        owner.register(PlaybackOwner.Owner.IPTV) { iptvStops++ }
        owner.claim(PlaybackOwner.Owner.ENIGMA2)
        assertEquals(0, enigmaStops); assertEquals(1, iptvStops)
        owner.claim(PlaybackOwner.Owner.IPTV)
        assertEquals(1, enigmaStops)
        owner.claim(PlaybackOwner.Owner.IPTV) // zapping inside IPTV stops nothing
        assertEquals(1, enigmaStops)
        owner.claim(PlaybackOwner.Owner.ENIGMA2)
        assertEquals(2, iptvStops)
        assertEquals(PlaybackOwner.Owner.ENIGMA2, owner.current)
    }
}
