package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BouquetPreviewTest {
    @Test fun closingWithoutAChoiceGivesTheFirstListBack() {
        val preview = BouquetPreview<String>()
        preview.begin("favourites")
        preview.begin("sport")
        assertTrue(preview.active)
        assertEquals("favourites", preview.restore())
        assertFalse(preview.active)
        assertNull(preview.restore())
    }

    @Test fun channelFromTheShownBouquetCommitsIt() {
        val preview = BouquetPreview<String>()
        preview.begin("favourites")
        assertNull(preview.onStart(listGiven = false, shownHolds = true))
        assertFalse(preview.active)
    }

    @Test fun otherChannelBringsThePlayingListBack() {
        val preview = BouquetPreview<String>()
        preview.begin("favourites")
        assertEquals("favourites", preview.onStart(listGiven = false, shownHolds = false))
        preview.begin("favourites")
        assertNull(preview.onStart(listGiven = true, shownHolds = false))
        assertFalse(preview.active)
    }
}
