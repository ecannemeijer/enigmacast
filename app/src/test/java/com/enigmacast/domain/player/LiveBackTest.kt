package com.enigmacast.domain.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBackTest {
    @Test fun backWithTheOsdUpOnlyClosesTheOsd() {
        assertEquals(LiveBackStep.HIDE_OSD, LiveBack.onPicture(osdVisible = true, insetPlaying = false))
    }

    @Test fun theOsdClosesBeforeTheInset() {
        assertEquals(LiveBackStep.HIDE_OSD, LiveBack.onPicture(osdVisible = true, insetPlaying = true))
        assertEquals(LiveBackStep.STOP_INSET, LiveBack.onPicture(osdVisible = false, insetPlaying = true))
    }

    @Test fun backOnTheBarePictureOpensTheGuide() {
        assertEquals(LiveBackStep.GUIDE, LiveBack.onPicture(osdVisible = false, insetPlaying = false))
    }

    @Test fun twoBacksFromTheOsdReachTheGuide() {
        var osd = true
        val first = LiveBack.onPicture(osd, insetPlaying = false)
        if (first == LiveBackStep.HIDE_OSD) osd = false
        assertEquals(LiveBackStep.HIDE_OSD, first)
        assertEquals(LiveBackStep.GUIDE, LiveBack.onPicture(osd, insetPlaying = false))
    }
}
